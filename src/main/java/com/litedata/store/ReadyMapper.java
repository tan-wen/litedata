package com.litedata.store;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReadyMapper {
    @Select("SELECT r.id FROM pipeline_run r JOIN quality_task q ON q.id=r.quality_task_id WHERE r.status='SUCCESS' AND r.quality_status='WAITING' AND q.active_run_id IS NULL ORDER BY r.id LIMIT 20")
    List<Long> waitingQuality();

    @Select("SELECT COUNT(*) FROM pipeline_run WHERE pipeline_id=#{id} AND quality_status IN ('WAITING','RUNNING')")
    int pendingPipeline(long id);

    @Select("SELECT COUNT(*) FROM pipeline_run WHERE quality_task_id=#{id} AND (status='RUNNING' OR quality_status IN ('WAITING','RUNNING'))")
    int pendingQuality(long id);

    @Select("SELECT COUNT(*) FROM pipeline WHERE archived=FALSE AND quality_task_id=#{id}")
    int qualityBindings(long id);

    @Update("UPDATE pipeline_run SET quality_run_id=#{qualityRun},quality_status='RUNNING' WHERE id=#{run} AND quality_status='WAITING'")
    int scanStarted(@Param("run") long run, @Param("qualityRun") long qualityRun);

    @Update("UPDATE pipeline_run SET quality_status=#{status} WHERE id=#{run} AND quality_status IN ('WAITING','RUNNING')")
    int scanFinished(@Param("run") long run, @Param("status") String status);

    @Insert("INSERT INTO data_ready_event(pipeline_id,pipeline_run_id,quality_run_id) VALUES(#{pipeline},#{run},#{qualityRun}) ON DUPLICATE KEY UPDATE id=id")
    void event(@Param("pipeline") long pipeline, @Param("run") long run, @Param("qualityRun") Long qualityRun);

    @Select("SELECT id FROM data_ready_event WHERE pipeline_run_id=#{run}")
    long eventId(long run);

    @Select("SELECT id FROM data_ready_event WHERE pipeline_run_id=#{run}")
    Long existingEvent(long run);

    @Select("SELECT COUNT(*) FROM quality_run WHERE id=#{quality} AND pipeline_run_id=#{run} AND status IN ('PASSED','WARNING')")
    int matchingQuality(@Param("quality") long quality, @Param("run") long run);

    record Binding(String targetTable, String watermarkColumn) {}
    @Select("SELECT target_table,watermark_column FROM pipeline WHERE archived=FALSE AND quality_task_id=#{id}")
    List<Binding> bindings(long id);

    @Insert("INSERT IGNORE INTO data_ready_delivery(event_id,workflow_id,trigger_generation) SELECT #{event},id,trigger_generation FROM processing_workflow WHERE archived=FALSE AND trigger_mode='EVENT' AND trigger_pipeline_id=#{pipeline} AND status='EVENT_ENABLED'")
    void subscribers(@Param("event") long event, @Param("pipeline") long pipeline);

    @Select("SELECT d.id FROM data_ready_delivery d WHERE d.state='PENDING' AND NOT EXISTS (SELECT 1 FROM data_ready_delivery earlier WHERE earlier.workflow_id=d.workflow_id AND earlier.state='PENDING' AND earlier.id<d.id) ORDER BY d.id LIMIT 20")
    List<Long> pending();

    record Delivery(long id, long eventId, long workflowId, int triggerGeneration, String state,
                    Long definitionCode, String message, LocalDateTime createdAt, LocalDateTime submittedAt,
                    long pipelineId, long pipelineRunId, Long qualityRunId, String pipelineName) {}

    String DELIVERY = "SELECT d.*,e.pipeline_id,e.pipeline_run_id,e.quality_run_id,p.name pipeline_name FROM data_ready_delivery d JOIN data_ready_event e ON e.id=d.event_id JOIN pipeline p ON p.id=e.pipeline_id ";

    @Select(DELIVERY + "WHERE d.id=#{id} FOR UPDATE")
    Delivery lockDelivery(long id);

    @Select("SELECT COUNT(*) FROM data_ready_delivery WHERE workflow_id=#{id}")
    long count(long id);

    @Select(DELIVERY + "WHERE d.workflow_id=#{id} ORDER BY d.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Delivery> page(@Param("id") long id, @Param("limit") int limit, @Param("offset") long offset);

    @Update("UPDATE data_ready_delivery SET state=#{state},message=#{message},definition_code=COALESCE(#{code},definition_code),submitted_at=CASE WHEN #{state}='SUBMITTED' THEN CURRENT_TIMESTAMP(6) ELSE submitted_at END WHERE id=#{id}")
    void state(@Param("id") long id, @Param("state") String state, @Param("message") String message, @Param("code") Long code);

    @Update("UPDATE data_ready_delivery SET state='PENDING',trigger_generation=#{generation},message='用户请求重试未提交事件',definition_code=NULL WHERE id=#{id} AND state='FAILED'")
    void retry(@Param("id") long id, @Param("generation") int generation);

    @Update("UPDATE data_ready_delivery SET state='UNKNOWN',message='平台重启，提交结果待核验；请先在引擎核对，勿重复提交' WHERE state='SUBMITTING'")
    void recover();

    @Select("SELECT COUNT(*) FROM data_ready_delivery WHERE workflow_id=#{id} AND state='SUBMITTING'")
    int submitting(long id);

    @Insert("INSERT IGNORE INTO quality_alert(quality_run_id,severity,message) VALUES(#{run},#{severity},#{message})")
    void alert(@Param("run") long run, @Param("severity") String severity, @Param("message") String message);

    record Alert(long id, long qualityRunId, long taskId, String taskName, String severity, String message) {}
    @Select("SELECT COUNT(*) FROM quality_alert")
    long alertCount();

    @Select("SELECT a.id,a.quality_run_id,r.task_id,t.name task_name,a.severity,a.message FROM quality_alert a JOIN quality_run r ON r.id=a.quality_run_id JOIN quality_task t ON t.id=r.task_id ORDER BY a.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Alert> alerts(@Param("limit") int limit, @Param("offset") long offset);
}
