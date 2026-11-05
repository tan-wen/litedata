package com.litedata.pipeline;

import com.litedata.model.SqlTask;
import com.litedata.workflow.*;
import com.litedata.store.*;
import com.litedata.transform.*;

import java.util.*;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.insert.Insert;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit migration: legacy configuration stays read-only until all affected schedules are paused.
 */
@Service
public class PipelineTransformMigration {
    private final PlatformMapper platform;
    private final ModelMapper models;
    private final SqlTaskMapper mapper;
    private final TransformService tasks;
    private final TransformSqlPolicy policy;
    private final PipelineService pipelines;
    private final JdbcTemplate jdbc;
    private final WorkflowMapper workflows;
    private final WorkflowGraph graphs;

    public PipelineTransformMigration(PlatformMapper platform, ModelMapper models, SqlTaskMapper mapper,
                                      TransformService tasks, TransformSqlPolicy policy, PipelineService pipelines, JdbcTemplate jdbc, WorkflowMapper workflows, WorkflowGraph graphs) {
        this.platform = platform;
        this.models = models;
        this.mapper = mapper;
        this.tasks = tasks;
        this.policy = policy;
        this.pipelines = pipelines;
        this.jdbc = jdbc;
        this.workflows = workflows;
        this.graphs = graphs;
    }

    public Long migratedTaskId(long id) {
        return jdbc.query("SELECT task_id FROM pipeline_transform_archive WHERE pipeline_id=?",
            (row, index) -> row.getLong(1), id).stream().findFirst().orElse(null);
    }

    @Transactional
    public long migrate(long id, String actor) {
        mapper.lockGraph();
        var pipeline = platform.lockPipeline(id);
        if (pipeline == null) throw new IllegalArgumentException("同步管道不存在");
        Long migrated = migratedTaskId(id);
        if (migrated != null) return migrated;
        String originalSql = Objects.toString(pipeline.getTransformSql(), "");
        String sql = originalSql.trim();
        if (sql.isEmpty()) throw new IllegalArgumentException("该管道没有待迁移的加工 SQL");
        if (pipeline.getActiveRunId() != null) throw new IllegalArgumentException("同步管道正在运行，请等待完成后迁移");
        if (Set.of("SCHEDULED", "SCHEDULE_PENDING").contains(pipeline.getStatus()) ||
            pipeline.getScheduleId() != null && !"PAUSED".equals(pipeline.getStatus()))
            throw new IllegalArgumentException("请先暂停同步管道定时调度后迁移");
        pipelines.requireOdsTarget(pipeline.getTargetTable());

        var dependents = jdbc.queryForList("SELECT d.task_id FROM sql_pipeline_dependency d JOIN sql_task t ON t.id=d.task_id WHERE d.pipeline_id=? AND t.archived=FALSE ORDER BY d.task_id", Long.class, id);
        var ownerMap = new LinkedHashMap<Long, ProcessingWorkflow>();
        for (var owner : workflows.forPipeline(id)) ownerMap.put(owner.getId(), owner);
        // Pre-canvas consumers may have only legacy dependency rows. They still determine the single owner.
        for (long dependent : dependents) {
            var owner = workflows.workflow(tasks.require(dependent).getWorkflowId());
            if (owner != null) ownerMap.put(owner.getId(), owner);
        }
        var owners = new ArrayList<>(ownerMap.values());
        if (owners.size() > 1)
            throw new IllegalArgumentException("此管道被多个加工流程引用，请先仅保留迁移目标流程中的引用；SQL 不能跨流程复用");
        if (owners.stream().anyMatch(w -> Set.of("SCHEDULED", "SCHEDULE_PENDING").contains(w.getStatus())))
            throw new IllegalArgumentException("请先暂停引用此管道的工作流调度后迁移");
        String target = target(sql);
        var model = models.modelByTable(target);
        if (model == null) throw new IllegalArgumentException("请先登记旧 SQL 的目标模型：" + target);
        if (!Set.of("DIM", "DWD", "DWS", "ADS").contains(model.getLayer()) || !"UNIQUE".equals(model.getModelType()))
            throw new IllegalArgumentException("旧 SQL 目标须为 DIM、DWD、DWS 或 ADS 的 UNIQUE 模型");
        sql = policy.validate(sql, target).sql();
        for (long dependent : dependents) tasks.ensureEditable(dependent);

        var existingIds = jdbc.queryForList("SELECT id FROM sql_task WHERE model_id=? AND archived=FALSE", Long.class, model.getId());
        SqlTask task;
        Long taskId = null;
        if (!existingIds.isEmpty()) {
            taskId = existingIds.getFirst();
            // Never overwrite an active writer, even when the target model matches.
            task = tasks.require(taskId);
            if (!owners.isEmpty() && !Objects.equals(task.getWorkflowId(), owners.getFirst().getId()))
                throw new IllegalArgumentException("旧 SQL 的目标模型已被其他流程占用，请人工处理后再迁移");
            if (!sameStatement(sql, task.getSqlText()))
                throw new IllegalArgumentException("旧 SQL 目标已有不同的加工任务，请先人工合并：" + target);
            tasks.ensureEditable(taskId);
        } else {
            task = new SqlTask();
            String name = "迁移加工-" + id + "-" + pipeline.getName();
            task.setName(name.substring(0, Math.min(100, name.length())));
            task.setModelId(model.getId());
            task.setSqlText(sql);
            if (!owners.isEmpty()) task.setWorkflowId(owners.getFirst().getId());
        }
        var upstream = new ArrayList<>(task.getUpstreamPipelineIds() == null ? List.<Long>of() : task.getUpstreamPipelineIds());
        upstream.add(id);
        task.setUpstreamPipelineIds(upstream);
        taskId = tasks.save(taskId, task, actor);

        for (long dependent : dependents) {
            if (dependent == taskId) continue;
            var consumer = tasks.require(dependent);
            consumer.setUpstreamPipelineIds(consumer.getUpstreamPipelineIds().stream().filter(p -> p != id).toList());
            var dependencies = new ArrayList<>(consumer.getUpstreamTaskIds());
            dependencies.add(taskId);
            consumer.setUpstreamTaskIds(dependencies);
            tasks.save(dependent, consumer, actor); // Includes cycle checks; any conflict rolls back the entire migration.
        }
        if (owners.isEmpty()) {
            var workflow = workflows.workflow(tasks.require(taskId).getWorkflowId());
            if (!workflows.nodeKeys(workflow.getId()).contains("pipeline-" + id))
                workflows.node(workflow.getId(), "pipeline-" + id, null, id);
            workflows.edge(workflow.getId(), "pipeline-" + id, "sql-" + taskId);
            workflows.graph(workflow.getId(), null, pipeline.getCronExpression());
        } else for (var workflow : owners) {
            workflows.retireLegacy(workflow.getId());
            String taskKey = "sql-" + taskId, pipeKey = "pipeline-" + id;
            if (!workflows.nodeKeys(workflow.getId()).contains(taskKey))
                workflows.node(workflow.getId(), taskKey, taskId, null);
            if (!workflows.nodeKeys(workflow.getId()).contains(pipeKey))
                workflows.node(workflow.getId(), pipeKey, null, id);
            var edges = workflows.edges(workflow.getId());
            workflows.deleteEdges(workflow.getId());
            var newEdges = new LinkedHashSet<WorkflowService.Edge>();
            for (var edge : edges)
                newEdges.add(edge.from().equals(pipeKey) && !edge.to().equals(taskKey) ? new WorkflowService.Edge(taskKey, edge.to()) : edge);
            newEdges.add(new WorkflowService.Edge(pipeKey, taskKey));
            for (long dependent : dependents)
                if (dependent != taskId) newEdges.add(new WorkflowService.Edge(taskKey, "sql-" + dependent));
            for (var edge : newEdges) workflows.edge(workflow.getId(), edge.from(), edge.to());
            graphs.graph(workflow.getId());
            workflows.graph(workflow.getId(), workflow.getPositionsJson(), workflow.getCronExpression());
        }
        jdbc.update("INSERT INTO pipeline_transform_archive(pipeline_id,sql_text,task_id) VALUES(?,?,?)", id, originalSql, taskId);
        if (pipeline.getDefinitionCode() != null)
            platform.rememberWorkflow("pipeline", id, pipeline.getDefinitionCode());
        jdbc.update("UPDATE pipeline SET transform_sql='',definition_code=NULL,schedule_id=NULL WHERE id=?", id);
        platform.audit(actor, "PIPELINE_TRANSFORM_MIGRATE", Long.toString(id), "SUCCESS");
        return taskId;
    }

    private static String target(String sql) {
        try {
            if (CCJSqlParserUtil.parse(sql) instanceof Insert insert) {
                String name = insert.getTable().getFullyQualifiedName().replace("`", "").toLowerCase(Locale.ROOT);
                if (name.matches("(warehouse\\.)?[a-z_][a-z0-9_]{0,63}"))
                    return name.startsWith("warehouse.") ? name.substring(10) : name;
            }
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException("旧加工 SQL 无法识别，请按单条 warehouse INSERT INTO ... SELECT 核对");
    }

    private static boolean sameStatement(String left, String right) {
        try {
            // Normalize syntax spacing/keyword case only. Literals and identifiers
            // remain intact; a different expression still requires manual merging.
            return CCJSqlParserUtil.parse(left).toString().equals(CCJSqlParserUtil.parse(right).toString());
        } catch (Exception ignored) {
            return false;
        }
    }
}
