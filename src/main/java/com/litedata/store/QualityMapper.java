package com.litedata.store;

import com.litedata.quality.*;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface QualityMapper {
    String FILTER=" WHERE archived=FALSE AND (#{keyword}='' OR LOCATE(#{keyword},CONCAT(name,' ',table_name,' ',owner))>0) ";
    @Select("SELECT COUNT(*) FROM quality_task"+FILTER) long count(String keyword);
    @Select("SELECT quality_task.*,(SELECT COUNT(*) FROM quality_rule r WHERE r.task_id=quality_task.id) rule_count FROM quality_task"+FILTER+" ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<QualityTask> page(@Param("keyword") String keyword,@Param("limit") int limit,@Param("offset") long offset);
    @Select("SELECT * FROM quality_task WHERE id=#{id}") QualityTask task(long id);
    @Select("SELECT * FROM quality_task WHERE id=#{id} FOR UPDATE") QualityTask lock(long id);
    @Insert("INSERT INTO quality_task(name,source_id,table_name,owner,description,cron_expression,time_column,window_hours,timeout_seconds) VALUES(#{name},#{sourceId},#{tableName},#{owner},#{description},#{cronExpression},#{timeColumn},#{windowHours},#{timeoutSeconds})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insert(QualityTask task);
    @Update("UPDATE quality_task SET name=#{name},source_id=#{sourceId},table_name=#{tableName},owner=#{owner},description=#{description},cron_expression=#{cronExpression},time_column=#{timeColumn},window_hours=#{windowHours},timeout_seconds=#{timeoutSeconds},version=version+1,enabled=FALSE,next_run_at=NULL WHERE id=#{id}")
    void update(QualityTask task);
    @Select("SELECT * FROM quality_rule WHERE task_id=#{id} ORDER BY id") List<QualityRule> rules(long id);
    @Delete("DELETE FROM quality_rule WHERE task_id=#{id}") void deleteRules(long id);
    @Insert("INSERT INTO quality_rule(task_id,name,type,`columns`,parameter1,parameter2,reference_table,reference_column,tolerance_count,tolerance_percent,severity) VALUES(#{taskId},#{name},#{type},#{columns},#{parameter1},#{parameter2},#{referenceTable},#{referenceColumn},#{toleranceCount},#{tolerancePercent},#{severity})")
    void rule(QualityRule rule);
    @Update("UPDATE quality_task SET enabled=#{enabled},next_run_at=#{next},version=version+1 WHERE id=#{id}")
    void schedule(@Param("id") long id,@Param("enabled") boolean enabled,@Param("next") LocalDateTime next);
    @Update("UPDATE quality_task SET archived=TRUE,enabled=FALSE,next_run_at=NULL,version=version+1 WHERE id=#{id}") void archive(long id);
    @Update("UPDATE quality_task SET active_run_id=#{runId},next_run_at=#{next} WHERE id=#{id}")
    void claim(@Param("id") long id,@Param("runId") long runId,@Param("next") LocalDateTime next);
    @Update("UPDATE quality_task SET active_run_id=NULL WHERE id=#{id} AND active_run_id=#{runId}")
    void release(@Param("id") long id,@Param("runId") long runId);
    @Select("SELECT id FROM quality_task WHERE archived=FALSE AND enabled=TRUE AND active_run_id IS NULL AND next_run_at<=#{now} ORDER BY next_run_at,id LIMIT 20") List<Long> due(LocalDateTime now);
    @Insert("INSERT INTO quality_run(task_id,task_version,status,actor,config_snapshot,started_at) VALUES(#{taskId},#{taskVersion},'RUNNING',#{actor},#{configSnapshot},#{startedAt})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insertRun(QualityRun run);
    @Select("SELECT * FROM quality_run WHERE id=#{id}") QualityRun run(long id);
    @Select("SELECT COUNT(*) FROM quality_run WHERE task_id=#{id}") long runCount(long id);
    @Select("SELECT * FROM quality_run WHERE task_id=#{id} ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<QualityRun> runs(@Param("id") long id,@Param("limit") int limit,@Param("offset") long offset);
    @Update("UPDATE quality_run SET status=#{status},message=#{message},finished_at=#{finishedAt} WHERE id=#{id} AND status='RUNNING'") void finish(QualityRun run);
    @Insert("INSERT INTO quality_result(run_id,name,type,severity,total_count,anomaly_count,anomaly_percent,status,message,metric_value) VALUES(#{runId},#{name},#{type},#{severity},#{totalCount},#{anomalyCount},#{anomalyPercent},#{status},#{message},#{metricValue})")
    void result(QualityResult result);
    @Select("SELECT * FROM quality_result WHERE run_id=#{id} ORDER BY id") List<QualityResult> results(long id);
    @Select("SELECT x.metric_value FROM quality_result x JOIN quality_run r ON x.run_id=r.id WHERE r.task_id=#{taskId} AND r.task_version=#{version} AND r.id<#{runId} AND r.status IN ('PASSED','WARNING','FAILED') AND x.type='ROW_COUNT_CHANGE' AND BINARY x.name=BINARY #{name} AND x.status<>'ERROR' ORDER BY r.id DESC LIMIT 1")
    java.math.BigDecimal baseline(@Param("taskId") long taskId,@Param("version") int version,@Param("runId") long runId,@Param("name") String name);
    @Select("SELECT * FROM quality_run WHERE status='RUNNING'") List<QualityRun> unfinished();
}
