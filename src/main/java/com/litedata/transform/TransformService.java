package com.litedata.transform;

import com.litedata.model.*;
import com.litedata.modeling.*;
import com.litedata.store.*;
import com.litedata.pipeline.PipelineService;
import com.litedata.workflow.WorkflowGraph;
import com.litedata.workflow.ProcessingWorkflow;
import com.litedata.service.QueryPage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TransformService {
    public record PlannedNode(String key, String name, String kind, long refId, int version, List<String> upstreamKeys,
                              String tableName) {
    }

    public record Graph(List<PlannedNode> nodes, String hash) {
    }

    private final SqlTaskMapper mapper;
    private final PlatformMapper platform;
    private final ModelingService models;
    private final TransformSqlPolicy policy;
    private final DorisModelGateway gateway;
    private final PipelineService pipelines;
    private final WorkflowMapper workflows;
    private final WorkflowGraph workflowGraphs;

    public TransformService(SqlTaskMapper mapper, PlatformMapper platform, ModelingService models, TransformSqlPolicy policy, DorisModelGateway gateway, PipelineService pipelines, WorkflowMapper workflows, WorkflowGraph workflowGraphs) {
        this.mapper = mapper;
        this.platform = platform;
        this.models = models;
        this.policy = policy;
        this.gateway = gateway;
        this.pipelines = pipelines;
        this.workflows = workflows;
        this.workflowGraphs = workflowGraphs;
    }

    public List<SqlTask> list() {
        return mapper.tasks();
    }

    public long count() {
        return mapper.countTasks();
    }

    public List<SqlTask> list(QueryPage page) {
        return mapper.taskPage(page.pageSize(), page.offset());
    }

    public SqlTask require(long id) {
        var task = required(mapper.task(id));
        task.setUpstreamTaskIds(mapper.upstreamTasks(id));
        task.setUpstreamPipelineIds(mapper.upstreamPipelines(id));
        return task;
    }

    @Transactional
    public long save(Long id, SqlTask input, String actor) {
        mapper.lockGraph();
        input = prepareDefinition(input);
        if (id == null) {
            input.setId(null);
            if (input.getWorkflowId() == null) {
                var owner = new ProcessingWorkflow();
                String name = input.getName();
                if (name.length() > 80) name = name.substring(0, 80);
                owner.setName(name + "-" + UUID.randomUUID().toString().substring(0, 8));
                workflows.insert(owner);
                input.setWorkflowId(owner.getId());
            }
            ensureOwnerEditable(input.getWorkflowId());
            if (workflows.nodeKeys(input.getWorkflowId()).size() >= 100)
                throw new IllegalArgumentException("工作流最多100个节点");
            mapper.insert(input);
            id = input.getId();
            workflows.node(input.getWorkflowId(), "sql-" + id, id, null);
        } else {
            var current = required(mapper.lockTask(id));
            checkVersion(current, input.getVersion());
            if (input.getWorkflowId() != null && !Objects.equals(input.getWorkflowId(), current.getWorkflowId()))
                throw new IllegalArgumentException("SQL 任务归属不可变更");
            input.setWorkflowId(current.getWorkflowId());
            ensureEditable(id);
            input.setId(id);
            mapper.update(input);
            mapper.deleteTaskDependencies(id);
            mapper.deletePipelineDependencies(id);
        }
        for (long upstream : input.getUpstreamTaskIds()) {
            if (!Objects.equals(require(upstream).getWorkflowId(), input.getWorkflowId()))
                throw new IllegalArgumentException("SQL 任务不能跨流程引用");
        }
        for (long upstream : input.getUpstreamTaskIds()) mapper.taskDependency(id, upstream);
        for (long pipeline : input.getUpstreamPipelineIds()) mapper.pipelineDependency(id, pipeline);
        graph(id); // Cycle detection is in the same transaction as edge replacement.
        var owner = workflows.workflow(input.getWorkflowId());
        workflows.retireLegacy(owner.getId());
        workflows.graph(owner.getId(), owner.getPositionsJson(), owner.getCronExpression());
        platform.audit(actor, "SQL_TASK_SAVE", Long.toString(id), "SUCCESS");
        return id;
    }

    /**
     * Validates a definition without persisting it, for atomic workflow draft saves.
     */
    public SqlTask prepareDefinition(SqlTask input) {
        // Callers inside a transaction may pass a MyBatis-cached task. Graph validation
        // reloads its dependency lists, so keep the requested edits in a separate object.
        var definition = new SqlTask();
        org.springframework.beans.BeanUtils.copyProperties(input, definition);
        input = definition;
        if (input.getName() == null || input.getName().isBlank() || input.getName().trim().length() > 100)
            throw new IllegalArgumentException("任务名称不能为空，最长100字符");
        if (input.getModelId() == null) throw new IllegalArgumentException("请选择目标模型");
        var model = models.require(input.getModelId());
        if ("ODS".equals(model.getLayer()))
            throw new IllegalArgumentException("数据加工目标请选择 DIM、DWD、DWS 或 ADS 模型；ODS 使用同步管道接入");
        requireReplaySafe(model);
        var validated = policy.validate(input.getSqlText(), model.getTableName());
        input.setName(input.getName().trim());
        input.setSqlText(validated.sql());
        input.setUpstreamTaskIds(ids(input.getUpstreamTaskIds()));
        input.setUpstreamPipelineIds(ids(input.getUpstreamPipelineIds()));
        if (!input.getUpstreamPipelineIds().isEmpty())
            throw new IllegalArgumentException("同步与加工不允许直接依赖，请在加工流程配置数据就绪订阅");
        for (long pipeline : input.getUpstreamPipelineIds())
            if (platform.pipeline(pipeline) == null) throw new IllegalArgumentException("上游同步管道不存在");
        return input;
    }

    @Transactional
    public void archive(long id, int version, String actor) {
        mapper.lockGraph();
        var task = required(mapper.lockTask(id));
        checkVersion(task, version);
        ensureEditable(id);
        if (!workflows.forTask(id).isEmpty()) throw new IllegalArgumentException("任务仍被工作流引用，请先从工作流移除");
        if (mapper.dependents(id) > 0) throw new IllegalArgumentException("该任务仍被下游引用，请先移除依赖");
        mapper.archive(id);
        platform.audit(actor, "SQL_TASK_ARCHIVE", Long.toString(id), "SUCCESS");
    }

    public Graph graph(long rootId) {
        var nodes = new LinkedHashMap<String, PlannedNode>();
        var visiting = new HashSet<Long>();
        var fingerprint = new StringBuilder();
        visit(rootId, nodes, visiting, fingerprint);
        if (nodes.size() > 100) throw new IllegalArgumentException("单条加工链最多100个节点");
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fingerprint.toString().getBytes(StandardCharsets.UTF_8)));
            return new Graph(List.copyOf(nodes.values()), hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void visit(long id, LinkedHashMap<String, PlannedNode> nodes, Set<Long> visiting, StringBuilder fingerprint) {
        if (nodes.containsKey("sql-" + id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("上游依赖存在循环，请调整依赖关系");
        if (visiting.size() > 100) throw new IllegalArgumentException("依赖链过深，最多100个节点");
        var task = require(id);
        var upstreamKeys = new ArrayList<String>();
        for (long pipelineId : task.getUpstreamPipelineIds()) {
            var pipeline = platform.pipeline(pipelineId);
            if (pipeline == null) throw new IllegalArgumentException("上游同步管道不存在");
            String key = "pipeline-" + pipelineId;
            if (!nodes.containsKey(key)) {
                if (nodes.size() >= 100) throw new IllegalArgumentException("单条加工链最多100个节点");
                nodes.put(key, new PlannedNode(key, pipeline.getName(), "同步", pipelineId, 0, List.of(), pipeline.getTargetTable()));
                fingerprint.append("ODS_SYNC_V2:").append(pipelineId).append(':').append(pipeline.getSourceId()).append(':')
                    .append(pipeline.getSourceTable()).append(':').append(pipeline.getTargetTable()).append(':')
                    .append(pipeline.getWatermarkColumn()).append('\n');
            }
            upstreamKeys.add(key);
        }
        for (long upstream : task.getUpstreamTaskIds()) {
            visit(upstream, nodes, visiting, fingerprint);
            upstreamKeys.add("sql-" + upstream);
        }
        var preview = models.preview(task.getModelId());
        policy.validate(task.getSqlText(), preview.model().getTableName());
        fingerprint.append("T:").append(task.getId()).append(':').append(task.getVersion()).append(':').append(preview.hash())
            .append(':').append(task.getSqlText()).append(':').append(String.join(",", upstreamKeys)).append('\n');
        if (nodes.size() >= 100) throw new IllegalArgumentException("单条加工链最多100个节点");
        nodes.put("sql-" + id, new PlannedNode("sql-" + id, task.getName(), "SQL", id, task.getVersion(), List.copyOf(upstreamKeys), task.getTargetTable()));
        visiting.remove(id);
    }

    public void ensureEditable(long taskId) {
        ensureOwnerEditable(required(mapper.task(taskId)).getWorkflowId());
        if (required(mapper.task(taskId)).getActiveRunId() != null)
            throw new IllegalArgumentException("任务正在运行，请等待完成");
        for (var node : graph(taskId).nodes()) {
            boolean active = node.kind().equals("SQL") ? required(mapper.task(node.refId())).getActiveRunId() != null
                : platform.pipeline(node.refId()).getActiveRunId() != null;
            if (active) throw new IllegalArgumentException("依赖链正在运行，请等待完成后编辑或归档");
        }
        var scheduled = workflows.scheduledForTask(taskId);
        if (!scheduled.isEmpty())
            throw new IllegalArgumentException("任务被已启用调度的工作流引用，请先暂停：" + scheduled.getFirst().getName());
    }

    private void ensureOwnerEditable(long id) {
        var owner = workflows.workflow(id);
        if (owner == null) throw new IllegalArgumentException("所属加工流程不存在或已删除");
        if (owner.isReviewRequired())
            throw new IllegalArgumentException("请先在画布核对迁移后的输入表和依赖，确认并保存");
        if (Set.of("SCHEDULED", "SCHEDULE_PENDING", "EVENT_ENABLED").contains(owner.getStatus()))
            throw new IllegalArgumentException("请先暂停所属加工流程的定时调度");
        for (String key : workflows.nodeKeys(id)) {
            boolean active = key.startsWith("sql-") ? required(mapper.task(WorkflowGraph.parse(key))).getActiveRunId() != null
                : platform.pipeline(WorkflowGraph.parse(key)).getActiveRunId() != null;
            if (active) throw new IllegalArgumentException("所属加工流程存在运行中的节点，请等待完成");
        }
    }

    public void preflight(Graph graph) {
        for (var node : graph.nodes()) {
            if (node.kind().equals("SQL")) {
                var task = require(node.refId());
                if (task.getActiveRunId() != null)
                    throw new IllegalArgumentException("加工链已有运行中的任务：" + task.getName());
                verifiedModel(task.getModelId());
            } else {
                var pipeline = platform.pipeline(node.refId());
                pipelines.validateExecutable(pipeline);
                if (pipeline.getActiveRunId() != null)
                    throw new IllegalArgumentException("上游同步管道正在运行，请等待完成");
            }
        }
    }

    private DataModel verifiedModel(long modelId) {
        var model = models.preview(modelId).model();
        requireReplaySafe(model);
        try {
            if (!SchemaComparison.compare(model, gateway.inspect(model.getTableName())).matches())
                throw new IllegalArgumentException("目标模型尚未建表或结构不一致，请先核对：" + model.getTableName());
        } catch (java.sql.SQLException e) {
            throw new IllegalArgumentException("无法读取 Doris 目标表结构，请检查连接和权限");
        }
        return model;
    }

    private static void requireReplaySafe(DataModel model) {
        if ("ODS".equals(model.getLayer()))
            throw new IllegalArgumentException("数据加工目标请选择 DIM、DWD、DWS 或 ADS 模型；ODS 使用同步管道接入");
        if (!"UNIQUE".equals(model.getModelType()))
            throw new IllegalArgumentException("当前数据加工使用可重试的 INSERT SELECT，目标须为 UNIQUE；DUPLICATE / AGGREGATE 请使用独立的写入与重算策略");
    }

    @Transactional
    public Map<String, Object> claim(long id, int version, long rootId, String hash) {
        return claim(id, version, rootId, null, hash);
    }

    @Transactional
    public Map<String, Object> claim(long id, int version, Long rootId, Long workflowId, String hash) {
        mapper.lockGraph();
        if ((rootId == null) == (workflowId == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定工作流或旧版链路编号");
        if (rootId != null && workflows.retiredLegacy(rootId, hash) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "旧版链路已更新，请从工作流重新发布");
        var graph = workflowId == null ? graph(rootId) : workflowGraphs.graph(workflowId);
        long ownerId = workflowId == null ? required(mapper.task(rootId)).getWorkflowId() : workflowId;
        var owner = workflows.workflow(ownerId);
        if (owner == null || owner.isReviewRequired())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "加工流程已删除或迁移尚未核对");
        for (var node : graph.nodes())
            if (node.kind().equals("SQL") && !Objects.equals(required(mapper.task(node.refId())).getWorkflowId(), ownerId))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "SQL 任务不能跨流程运行，请重新发布");
        if (!graph.hash().equals(hash) || graph.nodes().stream().noneMatch(n -> n.kind().equals("SQL") && n.refId() == id))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "加工链配置已变化，请重新发布");
        var task = required(mapper.lockTask(id));
        if (task.getVersion() != version)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务版本已变化，请重新发布");
        if (task.getActiveRunId() != null) throw new ResponseStatusException(HttpStatus.LOCKED, "任务正在运行");
        var model = verifiedModel(task.getModelId());
        var validated = policy.validate(task.getSqlText(), model.getTableName());
        var run = new SqlTaskRun();
        run.setTaskId(id);
        run.setRootTaskId(rootId);
        run.setWorkflowId(ownerId);
        run.setTaskVersion(version);
        run.setTargetTable(model.getTableName());
        run.setSqlText(validated.sql());
        mapper.insertRun(run);
        mapper.activeRun(id, run.getId());
        return Map.of("runId", run.getId(), "sql", validated.sql(), "targetTable", model.getTableName(), "taskVersion", version);
    }

    @Transactional
    public void finish(long runId, boolean success) {
        var run = mapper.run(runId);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "加工运行记录不存在");
        var task = required(mapper.lockTask(run.getTaskId()));
        run = mapper.lockRun(runId);
        if (!"RUNNING".equals(run.getStatus())) return;
        if (!Objects.equals(task.getActiveRunId(), runId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本次运行已失去执行权");
        mapper.finishRun(runId, success ? "SUCCESS" : "FAILED", success ? "Doris 数据加工完成" : "数据加工失败，请查看调度节点受限日志");
        mapper.activeRun(task.getId(), null);
        platform.audit("worker", "SQL_TASK_FINISH", Long.toString(runId), success ? "SUCCESS" : "FAILED");
    }

    public List<SqlTaskRun> runs(long id) {
        var result = mapper.runs(id);
        for (var run : result) {
            if (run.getStartedAt() != null) run.setStartedAt(beijing(run.getStartedAt()));
            if (run.getFinishedAt() != null) run.setFinishedAt(beijing(run.getFinishedAt()));
        }
        return result;
    }

    private static LocalDateTime beijing(LocalDateTime time) {
        return time.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
    }

    private static List<Long> ids(List<Long> ids) {
        if (ids == null) return List.of();
        if (ids.size() > 100 || ids.stream().anyMatch(i -> i == null || i <= 0))
            throw new IllegalArgumentException("上游任务选择无效");
        return ids.stream().distinct().sorted().toList();
    }

    private static SqlTask required(SqlTask task) {
        if (task == null) throw new IllegalArgumentException("数据加工不存在或已归档");
        return task;
    }

    public static void checkVersion(SqlTask task, int version) {
        if (task.getVersion() != version) throw new IllegalArgumentException("任务已被修改，请刷新后重试");
    }
}
