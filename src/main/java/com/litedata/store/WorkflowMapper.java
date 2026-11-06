package com.litedata.store;

import com.litedata.workflow.ProcessingWorkflow;

import java.util.*;

import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowMapper {
    String FROM = " FROM processing_workflow w WHERE w.archived=FALSE ";
    String FILTER = " AND (LOWER(w.name) LIKE CONCAT('%',#{keyword},'%') OR LOWER(w.description) LIKE CONCAT('%',#{keyword},'%')) ";

    @Select("SELECT COUNT(*)" + FROM + FILTER)
    long count(String keyword);

    @Select("SELECT w.*,(SELECT MAX(recent.started_at) FROM ("
        + "SELECT MAX(r.started_at) started_at FROM sql_task_run r WHERE r.workflow_id=w.id UNION ALL "
        + "SELECT MAX(r.started_at) FROM workflow_definition_snapshot s JOIN pipeline_run r ON r.definition_code=s.definition_code WHERE s.workflow_id=w.id"
        + ") recent) last_run_at"
        + FROM + FILTER + " ORDER BY w.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ProcessingWorkflow> page(@Param("keyword") String keyword, @Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT * FROM processing_workflow WHERE id=#{id} AND archived=FALSE")
    ProcessingWorkflow workflow(long id);

    @Select("SELECT * FROM processing_workflow WHERE id=#{id}")
    ProcessingWorkflow workflowIncludingArchived(long id);

    @Select("SELECT w.* FROM processing_workflow w JOIN processing_workflow_node n ON n.workflow_id=w.id WHERE w.archived=FALSE AND n.task_id=#{id} ORDER BY w.id")
    List<ProcessingWorkflow> forTask(long id);

    @Select("SELECT w.* FROM processing_workflow w JOIN processing_workflow_node n ON n.workflow_id=w.id WHERE w.archived=FALSE AND n.pipeline_id=#{id}")
    List<ProcessingWorkflow> forPipeline(long id);

    @Select("SELECT DISTINCT w.* FROM processing_workflow w JOIN processing_workflow_node n ON n.workflow_id=w.id WHERE w.archived=FALSE AND w.status IN ('SCHEDULED','SCHEDULE_PENDING','EVENT_ENABLED') AND n.task_id=#{id}")
    List<ProcessingWorkflow> scheduledForTask(long id);

    @Select("SELECT COUNT(*) FROM workflow_legacy_binding WHERE root_task_id=#{root} AND (graph_hash IS NULL OR graph_hash<>#{hash})")
    int retiredLegacy(@Param("root") long root, @Param("hash") String hash);

    @Update("UPDATE workflow_legacy_binding SET graph_hash=NULL WHERE workflow_id=#{id}")
    void retireLegacy(long id);

    record LegacyBinding(long rootTaskId, String graphHash) {
    }

    @Select("SELECT root_task_id,graph_hash FROM workflow_legacy_binding WHERE workflow_id=#{id}")
    LegacyBinding legacyBinding(long id);

    @Insert("INSERT INTO processing_workflow(name,description,trigger_mode,trigger_pipeline_id) VALUES(#{name},#{description},#{triggerMode},#{triggerPipelineId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ProcessingWorkflow workflow);

    @Update("UPDATE processing_workflow SET name=#{name},description=#{description},trigger_mode=#{triggerMode},trigger_pipeline_id=#{triggerPipelineId},trigger_generation=trigger_generation+1,version=version+1 WHERE id=#{id}")
    void metadata(ProcessingWorkflow workflow);

    @Update("UPDATE processing_workflow SET positions_json=#{positions},cron_expression=#{cron},version=version+1 WHERE id=#{id}")
    void graph(@Param("id") long id, @Param("positions") String positions, @Param("cron") String cron);

    @Update("UPDATE processing_workflow SET definition_code=#{code},definition_hash=#{hash},schedule_id=NULL,status='DRAFT' WHERE id=#{id}")
    void definition(@Param("id") long id, @Param("code") long code, @Param("hash") String hash);

    @Update("UPDATE processing_workflow SET schedule_id=#{scheduleId},status=#{status} WHERE id=#{id}")
    void schedule(@Param("id") long id, @Param("scheduleId") Integer scheduleId, @Param("status") String status);

    @Update("UPDATE processing_workflow SET status='EVENT_ENABLED',trigger_generation=trigger_generation+1 WHERE id=#{id}")
    void enableEvents(long id);

    @Update("UPDATE processing_workflow SET archived=TRUE,version=version+1 WHERE id=#{id}")
    void archive(long id);

    @Update("UPDATE processing_workflow SET review_required=FALSE,ownership_notice='' WHERE id=#{id}")
    void acknowledgeOwnership(long id);

    @Select("SELECT * FROM processing_workflow WHERE archived=FALSE AND review_required=TRUE AND schedule_id IS NOT NULL AND status<>'PAUSED'")
    List<ProcessingWorkflow> pendingOwnershipPause();

    @Select("SELECT node_key FROM processing_workflow_node WHERE workflow_id=#{id} ORDER BY node_key")
    List<String> nodeKeys(long id);

    @Select("SELECT from_key `from`,to_key `to` FROM processing_workflow_edge WHERE workflow_id=#{id} ORDER BY from_key,to_key")
    List<com.litedata.workflow.WorkflowService.Edge> edges(long id);

    record ModelFieldChoice(long modelId, String name) {
    }

    @Select("SELECT f.model_id AS modelId,f.name FROM model_field f JOIN data_model m ON m.id=f.model_id "
        + "WHERE m.archived=FALSE AND m.layer<>'ODS' AND m.model_type='UNIQUE' ORDER BY f.model_id,f.ordinal_position")
    List<ModelFieldChoice> sqlModelFields();

    @Delete("DELETE FROM processing_workflow_edge WHERE workflow_id=#{id}")
    void deleteEdges(long id);

    @Delete("DELETE FROM processing_workflow_node WHERE workflow_id=#{id}")
    void deleteNodes(long id);

    @Insert("INSERT INTO processing_workflow_node(workflow_id,node_key,task_id,pipeline_id) VALUES(#{id},#{key},#{task},#{pipeline})")
    void node(@Param("id") long id, @Param("key") String key, @Param("task") Long task, @Param("pipeline") Long pipeline);

    @Insert("INSERT INTO processing_workflow_edge(workflow_id,from_key,to_key) VALUES(#{id},#{from},#{to})")
    void edge(@Param("id") long id, @Param("from") String from, @Param("to") String to);

    String CATALOG = " FROM (SELECT CONCAT('pipeline-',p.id) node_key,p.id ref_id,p.name,'同步' kind,p.target_table table_name FROM pipeline p WHERE p.archived=FALSE) n "
        + "WHERE LOWER(CONCAT(n.name,' ',n.table_name)) LIKE CONCAT('%',#{keyword},'%') ";

    record CatalogItem(String key, long refId, String name, String kind, String tableName) {
    }

    @Select("SELECT COUNT(*)" + CATALOG)
    long catalogCount(String keyword);

    @Select("SELECT node_key `key`,ref_id,name,kind,table_name" + CATALOG + " ORDER BY kind,node_key LIMIT #{limit} OFFSET #{offset}")
    List<CatalogItem> catalog(@Param("keyword") String keyword, @Param("limit") int limit, @Param("offset") long offset);

    @Insert("INSERT IGNORE INTO workflow_definition_snapshot(definition_code,workflow_id,nodes_json) VALUES(#{code},#{workflow},#{nodes})")
    void snapshot(@Param("code") long code, @Param("workflow") long workflow, @Param("nodes") String nodes);

    @Select("SELECT nodes_json FROM workflow_definition_snapshot WHERE definition_code=#{code} AND workflow_id=#{workflow}")
    String snapshotJson(@Param("code") long code, @Param("workflow") long workflow);

    @Select("SELECT definition_code FROM workflow_definition_snapshot WHERE workflow_id=#{id} ORDER BY definition_code DESC")
    List<Long> snapshotCodes(long id);

    @Select("SELECT nodes_json FROM processing_workflow_snapshot WHERE definition_code=#{code} AND root_task_id=#{root}")
    String legacySnapshotJson(@Param("code") long code, @Param("root") long root);
}
