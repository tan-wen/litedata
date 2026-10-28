package com.litedata.pipeline;

import com.litedata.model.SqlTask;
import com.litedata.store.*;
import com.litedata.transform.*;
import java.util.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.insert.Insert;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit migration: legacy configuration stays read-only until all affected schedules are paused. */
@Service
public class PipelineTransformMigration {
    private final PlatformMapper platform;
    private final ModelMapper models;
    private final SqlTaskMapper mapper;
    private final TransformService tasks;
    private final TransformSqlPolicy policy;
    private final PipelineService pipelines;
    private final JdbcTemplate jdbc;

    public PipelineTransformMigration(PlatformMapper platform, ModelMapper models, SqlTaskMapper mapper,
            TransformService tasks, TransformSqlPolicy policy, PipelineService pipelines, JdbcTemplate jdbc) {
        this.platform=platform; this.models=models; this.mapper=mapper; this.tasks=tasks;
        this.policy=policy; this.pipelines=pipelines; this.jdbc=jdbc;
    }

    public Long migratedTaskId(long id) {
        return jdbc.query("SELECT task_id FROM pipeline_transform_archive WHERE pipeline_id=?",
                (row,index)->row.getLong(1),id).stream().findFirst().orElse(null);
    }

    @Transactional
    public long migrate(long id, String actor) {
        mapper.lockGraph();
        var pipeline=platform.lockPipeline(id);
        if(pipeline==null) throw new IllegalArgumentException("同步管道不存在");
        Long migrated=migratedTaskId(id);
        if(migrated!=null) return migrated;
        String originalSql=Objects.toString(pipeline.getTransformSql(),"");
        String sql=originalSql.trim();
        if(sql.isEmpty()) throw new IllegalArgumentException("该管道没有待迁移的加工 SQL");
        if(pipeline.getActiveRunId()!=null) throw new IllegalArgumentException("同步管道正在运行，请等待完成后迁移");
        if(Set.of("SCHEDULED","SCHEDULE_PENDING").contains(pipeline.getStatus()) ||
                pipeline.getScheduleId()!=null && !"PAUSED".equals(pipeline.getStatus()))
            throw new IllegalArgumentException("请先暂停同步管道定时调度后迁移");
        pipelines.requireOdsTarget(pipeline.getTargetTable());

        String target=target(sql);
        var model=models.modelByTable(target);
        if(model==null) throw new IllegalArgumentException("请先登记旧 SQL 的目标模型："+target);
        if(!Set.of("DIM","DWD","DWS","ADS").contains(model.getLayer()) || !"UNIQUE".equals(model.getModelType()))
            throw new IllegalArgumentException("旧 SQL 目标须为 DIM、DWD、DWS 或 ADS 的 UNIQUE 模型");
        sql=policy.validate(sql,target).sql();
        var dependents=jdbc.queryForList("SELECT d.task_id FROM sql_pipeline_dependency d JOIN sql_task t ON t.id=d.task_id WHERE d.pipeline_id=? AND t.archived=FALSE ORDER BY d.task_id",Long.class,id);
        for(long dependent:dependents) tasks.ensureEditable(dependent);

        var existingIds=jdbc.queryForList("SELECT id FROM sql_task WHERE model_id=?",Long.class,model.getId());
        SqlTask task;
        Long taskId=null;
        if(!existingIds.isEmpty()) {
            taskId=existingIds.getFirst();
            // require rejects archived tasks too; the unique target must never be silently overwritten.
            task=tasks.require(taskId);
            if(!sameStatement(sql,task.getSqlText()))
                throw new IllegalArgumentException("旧 SQL 目标已有不同的加工任务，请先人工合并："+target);
            tasks.ensureEditable(taskId);
        } else {
            task=new SqlTask();
            String name="迁移加工-"+id+"-"+pipeline.getName();
            task.setName(name.substring(0,Math.min(100,name.length())));
            task.setModelId(model.getId()); task.setSqlText(sql);
            task.setCronExpression(TransformService.SCHEDULES.containsKey(pipeline.getCronExpression())
                    ?pipeline.getCronExpression():"0 0 * * * ?");
        }
        var upstream=new ArrayList<>(task.getUpstreamPipelineIds()==null?List.<Long>of():task.getUpstreamPipelineIds());
        upstream.add(id); task.setUpstreamPipelineIds(upstream);
        taskId=tasks.save(taskId,task,actor);

        for(long dependent:dependents) {
            if(dependent==taskId) continue;
            var consumer=tasks.require(dependent);
            consumer.setUpstreamPipelineIds(consumer.getUpstreamPipelineIds().stream().filter(p->p!=id).toList());
            var dependencies=new ArrayList<>(consumer.getUpstreamTaskIds()); dependencies.add(taskId);
            consumer.setUpstreamTaskIds(dependencies);
            tasks.save(dependent,consumer,actor); // Includes cycle checks; any conflict rolls back the entire migration.
        }
        jdbc.update("INSERT INTO pipeline_transform_archive(pipeline_id,sql_text,task_id) VALUES(?,?,?)",id,originalSql,taskId);
        if(pipeline.getDefinitionCode()!=null) platform.rememberWorkflow("pipeline",id,pipeline.getDefinitionCode());
        jdbc.update("UPDATE pipeline SET transform_sql='',definition_code=NULL,schedule_id=NULL WHERE id=?",id);
        platform.audit(actor,"PIPELINE_TRANSFORM_MIGRATE",Long.toString(id),"SUCCESS");
        return taskId;
    }

    private static String target(String sql) {
        try {
            if(CCJSqlParserUtil.parse(sql) instanceof Insert insert) {
                String name=insert.getTable().getFullyQualifiedName().replace("`", "").toLowerCase(Locale.ROOT);
                if(name.matches("(warehouse\\.)?[a-z_][a-z0-9_]{0,63}"))
                    return name.startsWith("warehouse.")?name.substring(10):name;
            }
        } catch(Exception ignored) { }
        throw new IllegalArgumentException("旧加工 SQL 无法识别，请按单条 warehouse INSERT INTO ... SELECT 核对");
    }

    private static boolean sameStatement(String left,String right) {
        try {
            // Normalize syntax spacing/keyword case only. Literals and identifiers
            // remain intact; a different expression still requires manual merging.
            return CCJSqlParserUtil.parse(left).toString().equals(CCJSqlParserUtil.parse(right).toString());
        } catch(Exception ignored) { return false; }
    }
}
