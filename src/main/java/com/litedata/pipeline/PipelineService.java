package com.litedata.pipeline;

import com.litedata.config.PlatformProperties;
import com.litedata.data.*;
import com.litedata.model.*;
import com.litedata.scheduling.SchedulePreview;
import com.litedata.store.PlatformMapper;
import com.litedata.store.ModelMapper;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PipelineService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final PlatformMapper mapper; private final SourceService sources; private final PlatformProperties properties;
    private final ModelMapper models;
    public PipelineService(PlatformMapper mapper, SourceService sources, PlatformProperties properties, ModelMapper models) {
        this.mapper=mapper; this.sources=sources; this.properties=properties; this.models=models;
    }
    public Pipeline require(long id) {
        Pipeline pipeline=mapper.pipeline(id);
        if(pipeline==null) throw new IllegalArgumentException("同步任务不存在");
        return pipeline;
    }
    public void create(String name,long sourceId,String table,String column,String target,String transform,String cron) {
        mapper.insertPipeline(definition(name,sourceId,table,column,target,transform,cron));
    }
    @Transactional
    public void update(long id,String name,long sourceId,String table,String column,String target,String transform,String cron) {
        var existing=mapper.lockPipeline(id);
        if(existing==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"同步任务不存在");
        if(existing.getActiveRunId()!=null) throw new IllegalArgumentException("任务已有执行中的实例，请等待完成后编辑");
        if(existing.getTransformSql()!=null && !existing.getTransformSql().isBlank())
            throw new IllegalArgumentException("请先将旧加工 SQL 迁移到数据加工，再编辑同步管道");
        if(existing.getSourceId()!=sourceId || !Objects.equals(existing.getSourceTable(),table) ||
                !Objects.equals(existing.getWatermarkColumn(),column) || !Objects.equals(existing.getTargetTable(),target))
            throw new IllegalArgumentException("同步边界创建后固定，调整时请新建管道以避免复用旧水位");
        var pipeline=definition(name,sourceId,table,column,target,transform,cron);
        pipeline.setId(id);
        if(!Objects.equals(existing.getCronExpression(),pipeline.getCronExpression())) {
            if(existing.getScheduleId()!=null && !"PAUSED".equals(existing.getStatus()))
                throw new IllegalArgumentException("定时调度已启用，请先暂停后再修改执行周期");
            mapper.updatePipelineFrequency(pipeline);
        } else mapper.updatePipeline(pipeline);
    }
    private Pipeline definition(String name,long sourceId,String table,String column,String target,String transform,String cron) {
        sources.require(sourceId);
        requireOdsTarget(JdbcConnections.identifier(target));
        if(name==null || name.isBlank() || name.length()>100) throw new IllegalArgumentException("任务名称不能为空，最长100字符");
        cron=SchedulePreview.validate(cron);
        transform=transform==null?"":transform.trim();
        if(!transform.isEmpty()) throw new IllegalArgumentException("同步管道仅负责 ODS 贴源同步，加工 SQL 请在数据加工中配置");
        Pipeline pipeline=new Pipeline(); pipeline.setName(name.trim()); pipeline.setSourceId(sourceId);
        pipeline.setSourceTable(JdbcConnections.identifier(table)); pipeline.setWatermarkColumn(JdbcConnections.identifier(column));
        pipeline.setTargetTable(JdbcConnections.identifier(target)); pipeline.setTransformSql(transform); pipeline.setCronExpression(cron);
        return pipeline;
    }
    @Transactional
    public Map<String,Object> claim(long id) {
        Pipeline pipeline=mapper.lockPipeline(id);
        if(pipeline==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"任务不存在");
        if(pipeline.getActiveRunId()!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"已有实例运行；确认执行节点停止后再恢复");
        // Recheck registered models at execution time, including edits made after pipeline creation.
        validateExecutable(pipeline);
        DataSource source=sources.require(pipeline.getSourceId());
        SourceTable sourceTable;
        try(Connection connection=sources.connect(source)) {
            sourceTable=SourceTable.inspect(connection,source.getDatabaseType(),pipeline.getSourceTable(),pipeline.getWatermarkColumn());
        } catch(SQLException e) { throw new IllegalArgumentException("无法读取源库水位，请先检查连接、模式和字段"); }
        LocalDateTime upper=sourceTable.upperBound();
        if(!upper.isAfter(pipeline.getWatermark())) throw new IllegalArgumentException("源库时间未超过已完成水位");
        // A five-minute replay window handles bounded lateness; target UNIQUE KEY makes replays idempotent.
        LocalDateTime lower=pipeline.getWatermark().minusMinutes(5);
        LocalDateTime epoch=LocalDateTime.of(1970,1,1,0,0);
        if(lower.isBefore(epoch)) lower=epoch;
        PipelineRun run=new PipelineRun(); run.setPipelineId(id); run.setLowerBound(lower); run.setUpperBound(upper);
        mapper.insertRun(run); mapper.activeRun(id,run.getId());
        return Map.of("runId",run.getId(),"source",Map.of("jdbcUrl",source.getJdbcUrl(),"username",source.getUsername(),"password",sources.password(source),
                        "databaseType",source.getDatabaseType().name(),"schema",Objects.toString(sourceTable.schema(),""),"columns",sourceTable.columns(),"timestampColumns",sourceTable.timestampColumns()),
                "sourceTable",pipeline.getSourceTable(),"watermarkColumn",pipeline.getWatermarkColumn(),"targetTable",pipeline.getTargetTable(),
                "lowerBound",TIME.format(lower),"upperBound",TIME.format(upper),
                "doris",Map.of("jdbcUrl",properties.doris().jdbcUrl(),"username",properties.doris().username(),"password",properties.doris().password()));
    }
    @Transactional
    public void finish(long runId,boolean success,String message) {
        PipelineRun run=mapper.run(runId);
        if(run==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"实例不存在");
        Pipeline pipeline=mapper.lockPipeline(run.getPipelineId());
        run=mapper.lockRun(runId);
        if(!"RUNNING".equals(run.getStatus())) return; // A lost HTTP acknowledgement may be retried safely.
        if(!Objects.equals(pipeline.getActiveRunId(),runId)) throw new ResponseStatusException(HttpStatus.CONFLICT,"实例已失去执行权");
        String safeMessage=success?"ODS 同步完成":"ODS 同步失败，请查看执行节点受限日志";
        mapper.finishRun(runId,success?"SUCCESS":"FAILED",safeMessage);
        if(success) mapper.advanceWatermark(pipeline.getId(),run.getUpperBound());
        else mapper.failPipeline(pipeline.getId(),safeMessage);
    }

    public void validateExecutable(Pipeline pipeline) {
        requireOdsTarget(pipeline.getTargetTable());
        if(pipeline.getTransformSql()!=null && !pipeline.getTransformSql().isBlank())
            throw new IllegalArgumentException("旧加工 SQL 尚未迁移，请先在同步管道编辑页迁移到数据加工");
    }

    public void requireOdsTarget(String table) {
        var model = models.modelByTable(table.toLowerCase(Locale.ROOT));
        if(model==null || !"ODS".equals(model.getLayer()))
            throw new IllegalArgumentException("同步目标须为已登记的 ODS 模型，请先在数据建模中配置："+table);
        if (!"UNIQUE".equals(model.getModelType()))
            throw new IllegalArgumentException("ODS 同步回放及重试的写入目标须为 UNIQUE：" + table);
    }
}

