package com.litedata.store;

import com.litedata.model.SqlTask;
import com.litedata.workflow.ProcessingWorkflow;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowMapper {
    String FROM=" FROM processing_workflow w JOIN sql_task t ON t.id=w.root_task_id WHERE t.archived=FALSE ";
    String FILTER=" AND (LOWER(w.name) LIKE CONCAT('%',#{keyword},'%') OR LOWER(w.description) LIKE CONCAT('%',#{keyword},'%') OR LOWER(t.name) LIKE CONCAT('%',#{keyword},'%')) ";
    String COLUMNS="SELECT w.*,t.name root_name,t.cron_expression,t.status ";
    @Select("SELECT COUNT(*)"+FROM+FILTER) long count(String keyword);
    @Select(COLUMNS+FROM+FILTER+" ORDER BY w.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ProcessingWorkflow> page(@Param("keyword") String keyword,@Param("limit") int limit,@Param("offset") long offset);
    @Select(COLUMNS+FROM+" AND w.id=#{id}") ProcessingWorkflow workflow(long id);
    @Select(COLUMNS+FROM+" AND w.root_task_id=#{id}") ProcessingWorkflow forRoot(long id);
    @Insert("INSERT INTO processing_workflow(name,description,root_task_id) VALUES(#{name},#{description},#{rootTaskId})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insert(ProcessingWorkflow workflow);
    @Update("UPDATE processing_workflow SET name=#{name},description=#{description},version=version+1 WHERE id=#{id}") void metadata(ProcessingWorkflow workflow);
    @Update("UPDATE processing_workflow SET positions_json=#{positions},version=version+1 WHERE id=#{id}")
    void graph(@Param("id") long id,@Param("positions") String positions);
    @Select(SqlTaskMapper.TASK_COLUMNS+"WHERE t.archived=FALSE AND NOT EXISTS(SELECT 1 FROM processing_workflow w WHERE w.root_task_id=t.id) ORDER BY t.id DESC")
    List<SqlTask> roots();
    String CATALOG=" FROM (SELECT CONCAT('sql-',t.id) node_key,t.id ref_id,t.name,'SQL' kind,m.table_name "
            +"FROM sql_task t JOIN data_model m ON m.id=t.model_id WHERE t.archived=FALSE UNION ALL "
            +"SELECT CONCAT('pipeline-',p.id),p.id,p.name,'同步',p.target_table FROM pipeline p) n "
            +"WHERE LOWER(CONCAT(n.name,' ',n.table_name)) LIKE CONCAT('%',#{keyword},'%') ";
    record CatalogItem(String key,long refId,String name,String kind,String tableName) {}
    @Select("SELECT COUNT(*)"+CATALOG) long catalogCount(String keyword);
    @Select("SELECT node_key `key`,ref_id,name,kind,table_name"+CATALOG+" ORDER BY kind,node_key LIMIT #{limit} OFFSET #{offset}")
    List<CatalogItem> catalog(@Param("keyword") String keyword,@Param("limit") int limit,@Param("offset") long offset);
    @Insert("INSERT IGNORE INTO processing_workflow_snapshot(definition_code,root_task_id,nodes_json) VALUES(#{code},#{root},#{nodes})")
    void snapshot(@Param("code") long code,@Param("root") long root,@Param("nodes") String nodes);
    @Select("SELECT nodes_json FROM processing_workflow_snapshot WHERE definition_code=#{code} AND root_task_id=#{root}")
    String snapshotJson(@Param("code") long code,@Param("root") long root);
    @Select("SELECT definition_code FROM processing_workflow_snapshot WHERE root_task_id=#{root} ORDER BY definition_code DESC")
    List<Long> snapshotCodes(long root);
}
