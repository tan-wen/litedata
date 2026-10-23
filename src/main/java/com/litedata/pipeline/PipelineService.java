package com.litedata.pipeline;

import com.litedata.config.PlatformProperties;
import com.litedata.data.*;
import com.litedata.model.*;
import com.litedata.store.PlatformMapper;
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
    public PipelineService(PlatformMapper mapper, SourceService sources, PlatformProperties properties) {
        this.mapper=mapper; this.sources=sources; this.properties=properties;
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
        if(existing.getSourceId()!=sourceId || !Objects.equals(existing.getSourceTable(),table) ||
                !Objects.equals(existing.getWatermarkColumn(),column) || !Objects.equals(existing.getTargetTable(),target) ||
                !Objects.equals(existing.getCronExpression(),cron))
            throw new IllegalArgumentException("同步边界及调度配置创建后固定，调整时请新建管道以避免复用旧水位");
        var pipeline=definition(name,sourceId,table,column,target,transform,cron);
        pipeline.setId(id);
        mapper.updatePipeline(pipeline);
    }
    private Pipeline definition(String name,long sourceId,String table,String column,String target,String transform,String cron) {
        sources.require(sourceId);
        if(name==null || name.isBlank() || name.length()>100) throw new IllegalArgumentException("任务名称不能为空，最长100字符");
        // The first release deliberately has a single hourly policy, in Quartz syntax.
        if(!"0 0 * * * ?".equals(cron)) throw new IllegalArgumentException("首版固定每小时整点运行：0 0 * * * ?");
        transform=transform==null?"":transform.trim();
        if(!transform.isEmpty()) {
            if(transform.length()>20000 ||
               !transform.stripLeading().toUpperCase(Locale.ROOT).startsWith("INSERT INTO ") || transform.contains(";"))
                throw new IllegalArgumentException("加工 SQL 必须为单条 INSERT INTO ... SELECT，写入幂等目标表");
            try {
                var parsed=net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(transform);
                if(!(parsed instanceof net.sf.jsqlparser.statement.insert.Insert insert) || insert.getSelect()==null)
                    throw new IllegalArgumentException("加工 SQL 必须为 INSERT INTO ... SELECT");
                for(String referenced:new net.sf.jsqlparser.util.TablesNamesFinder<Void>().getTables(parsed)) {
                    if(!referenced.replace("`", "").matches("(?i)(warehouse\\.)?[A-Za-z_][A-Za-z0-9_]{0,63}"))
                        throw new IllegalArgumentException("加工 SQL 仅允许访问 warehouse 库");
                }
            } catch(IllegalArgumentException exception) { throw exception; }
            catch(Exception exception) { throw new IllegalArgumentException("加工 SQL 解析失败"); }
        }
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
        DataSource source=sources.require(pipeline.getSourceId());
        LocalDateTime upper;
        try(Connection connection=sources.connect(source); Statement statement=connection.createStatement()) {
            statement.setQueryTimeout(10);
            // Verify timestamp watermarks at the source and fail before emitting a runnable specification.
            try(ResultSet columns=connection.getMetaData().getColumns(connection.getCatalog(),null,pipeline.getSourceTable(),pipeline.getWatermarkColumn())) {
                if(!columns.next() || !Set.of(Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE).contains(columns.getInt("DATA_TYPE")))
                    throw new IllegalArgumentException("增量水位字段须为 DATETIME/TIMESTAMP，并建立索引");
                if(columns.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls)
                    throw new IllegalArgumentException("增量水位字段必须为 NOT NULL");
            }
            boolean indexed=false;
            try(ResultSet indexes=connection.getMetaData().getIndexInfo(connection.getCatalog(),null,pipeline.getSourceTable(),false,false)) {
                while(indexes.next()) if(indexes.getShort("ORDINAL_POSITION")==1 && pipeline.getWatermarkColumn().equals(indexes.getString("COLUMN_NAME"))) indexed=true;
            }
            if(!indexed) throw new IllegalArgumentException("请为水位字段创建以该字段开头的索引，避免每小时扫描全表");
            try(ResultSet result=statement.executeQuery("SELECT CURRENT_TIMESTAMP(6)")) { result.next(); upper=result.getObject(1,LocalDateTime.class); }
        } catch(SQLException e) { throw new IllegalArgumentException("无法读取源库水位，请先检查连接和字段"); }
        if(!upper.isAfter(pipeline.getWatermark())) throw new IllegalArgumentException("源库时间未超过已完成水位");
        // A five-minute replay window handles bounded lateness; target UNIQUE KEY makes replays idempotent.
        LocalDateTime lower=pipeline.getWatermark().minusMinutes(5);
        LocalDateTime epoch=LocalDateTime.of(1970,1,1,0,0);
        if(lower.isBefore(epoch)) lower=epoch;
        PipelineRun run=new PipelineRun(); run.setPipelineId(id); run.setLowerBound(lower); run.setUpperBound(upper);
        mapper.insertRun(run); mapper.activeRun(id,run.getId());
        return Map.of("runId",run.getId(),"source",Map.of("jdbcUrl",source.getJdbcUrl(),"username",source.getUsername(),"password",sources.password(source)),
                "sourceTable",pipeline.getSourceTable(),"watermarkColumn",pipeline.getWatermarkColumn(),"targetTable",pipeline.getTargetTable(),
                "lowerBound",TIME.format(lower),"upperBound",TIME.format(upper),"transformSql",pipeline.getTransformSql(),
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
        String safeMessage=success?"同步与加工完成":"同步或加工失败，请查看执行节点受限日志";
        mapper.finishRun(runId,success?"SUCCESS":"FAILED",safeMessage);
        if(success) mapper.advanceWatermark(pipeline.getId(),run.getUpperBound());
        else mapper.failPipeline(pipeline.getId(),safeMessage);
    }
}

