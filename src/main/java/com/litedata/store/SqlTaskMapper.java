package com.litedata.store;

import com.litedata.model.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SqlTaskMapper {
    String TASK_COLUMNS = "SELECT t.*,m.table_name target_table,m.name model_name,m.layer FROM sql_task t JOIN data_model m ON m.id=t.model_id ";
    @Select("SELECT id FROM sql_graph_guard WHERE id=1 FOR UPDATE") int lockGraph();
    @Select(TASK_COLUMNS + "WHERE t.archived=FALSE ORDER BY t.id DESC") List<SqlTask> tasks();
    @Select("SELECT COUNT(*) FROM sql_task t JOIN data_model m ON m.id=t.model_id WHERE t.archived=FALSE") long countTasks();
    @Select(TASK_COLUMNS + "WHERE t.archived=FALSE ORDER BY t.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<SqlTask> taskPage(@Param("limit") int limit, @Param("offset") long offset);
    @Select(TASK_COLUMNS + "WHERE t.id=#{id} AND t.archived=FALSE") SqlTask task(long id);
    @Select(TASK_COLUMNS + "WHERE t.id=#{id} AND t.archived=FALSE FOR UPDATE") SqlTask lockTask(long id);
    @Select("SELECT upstream_id FROM sql_task_dependency WHERE task_id=#{id} ORDER BY upstream_id") List<Long> upstreamTasks(long id);
    @Select("SELECT pipeline_id FROM sql_pipeline_dependency WHERE task_id=#{id} ORDER BY pipeline_id") List<Long> upstreamPipelines(long id);
    @Insert("INSERT INTO sql_task(name,model_id,sql_text,cron_expression) VALUES(#{name},#{modelId},#{sqlText},#{cronExpression})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insert(SqlTask task);
    @Update("UPDATE sql_task SET name=#{name},model_id=#{modelId},sql_text=#{sqlText},cron_expression=#{cronExpression},version=version+1 WHERE id=#{id}")
    void update(SqlTask task);
    @Delete("DELETE FROM sql_task_dependency WHERE task_id=#{id}") void deleteTaskDependencies(long id);
    @Delete("DELETE FROM sql_pipeline_dependency WHERE task_id=#{id}") void deletePipelineDependencies(long id);
    @Insert("INSERT INTO sql_task_dependency(task_id,upstream_id) VALUES(#{id},#{upstream})")
    void taskDependency(@Param("id") long id,@Param("upstream") long upstream);
    @Insert("INSERT INTO sql_pipeline_dependency(task_id,pipeline_id) VALUES(#{id},#{pipeline})")
    void pipelineDependency(@Param("id") long id,@Param("pipeline") long pipeline);
    @Update("UPDATE sql_task SET definition_code=#{code},definition_hash=#{hash},schedule_id=NULL,status='DRAFT' WHERE id=#{id}")
    void definition(@Param("id") long id,@Param("code") long code,@Param("hash") String hash);
    @Update("UPDATE sql_task SET schedule_id=#{scheduleId},status=#{status} WHERE id=#{id}")
    void schedule(@Param("id") long id,@Param("scheduleId") Integer scheduleId,@Param("status") String status);
    @Update("UPDATE sql_task SET active_run_id=#{runId} WHERE id=#{id}")
    void activeRun(@Param("id") long id,@Param("runId") Long runId);
    @Update("UPDATE sql_task SET archived=TRUE WHERE id=#{id}") void archive(long id);
    @Select("SELECT COUNT(*) FROM sql_task_dependency d JOIN sql_task t ON d.task_id=t.id WHERE d.upstream_id=#{id} AND t.archived=FALSE")
    int dependents(long id);
    @Select("SELECT COUNT(*) FROM sql_task WHERE model_id=#{modelId} AND archived=FALSE") int modelReferences(long modelId);
    @Insert("INSERT INTO sql_task_run(task_id,root_task_id,task_version,target_table,sql_text) VALUES(#{taskId},#{rootTaskId},#{taskVersion},#{targetTable},#{sqlText})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insertRun(SqlTaskRun run);
    @Select("SELECT * FROM sql_task_run WHERE id=#{id}") SqlTaskRun run(long id);
    @Select("SELECT * FROM sql_task_run WHERE id=#{id} FOR UPDATE") SqlTaskRun lockRun(long id);
    @Update("UPDATE sql_task_run SET status=#{status},message=#{message},finished_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND status='RUNNING'")
    void finishRun(@Param("id") long id,@Param("status") String status,@Param("message") String message);
    @Select("SELECT r.*,t.name task_name,root.name root_name FROM sql_task_run r JOIN sql_task t ON t.id=r.task_id JOIN sql_task root ON root.id=r.root_task_id WHERE r.root_task_id=#{id} OR r.task_id=#{id} ORDER BY r.id DESC LIMIT 100")
    List<SqlTaskRun> runs(long id);
}
