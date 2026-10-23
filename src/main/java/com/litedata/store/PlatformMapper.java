package com.litedata.store;

import com.litedata.model.*;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PlatformMapper {
    @Select("SELECT * FROM app_user WHERE username=#{username}") AppUser findUser(String username);
    @Insert("INSERT INTO app_user(username,password_hash,role) VALUES(#{username},#{password},#{role})")
    void insertUser(@Param("username") String username, @Param("password") String password, @Param("role") String role);
    @Select("SELECT id,username,role,enabled FROM app_user ORDER BY id") List<AppUser> users();
    @Select("SELECT * FROM data_source ORDER BY id DESC") List<DataSource> sources();
    @Select("SELECT * FROM data_source WHERE id=#{id}") DataSource source(long id);
    @Insert("INSERT INTO data_source(name,jdbc_url,username,encrypted_password) VALUES(#{name},#{jdbcUrl},#{username},#{encryptedPassword})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insertSource(DataSource source);
    @Update("UPDATE data_source SET status=#{status},last_error=#{error} WHERE id=#{id}")
    void sourceStatus(@Param("id") long id,@Param("status") String status,@Param("error") String error);
    @Select("SELECT * FROM pipeline ORDER BY id DESC") List<Pipeline> pipelines();
    @Select("SELECT * FROM pipeline WHERE id=#{id}") Pipeline pipeline(long id);
    @Select("SELECT * FROM pipeline WHERE id=#{id} FOR UPDATE") Pipeline lockPipeline(long id);
    @Insert("INSERT INTO pipeline(name,source_id,source_table,watermark_column,target_table,transform_sql,cron_expression) VALUES(#{name},#{sourceId},#{sourceTable},#{watermarkColumn},#{targetTable},#{transformSql},#{cronExpression})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insertPipeline(Pipeline pipeline);
    @Update("UPDATE pipeline SET definition_code=#{code} WHERE id=#{id}")
    void definition(@Param("id") long id,@Param("code") long code);
    @Update("UPDATE pipeline SET schedule_id=#{scheduleId},status=#{status},last_error=NULL WHERE id=#{id}")
    void schedule(@Param("id") long id,@Param("scheduleId") int scheduleId,@Param("status") String status);
    @Update("UPDATE pipeline SET active_run_id=#{runId} WHERE id=#{id}")
    void activeRun(@Param("id") long id,@Param("runId") Long runId);
    @Update("UPDATE pipeline SET watermark=#{watermark},active_run_id=NULL,last_error=NULL WHERE id=#{id}")
    void advanceWatermark(@Param("id") long id,@Param("watermark") LocalDateTime watermark);
    @Update("UPDATE pipeline SET active_run_id=NULL,last_error=#{error} WHERE id=#{id}")
    void failPipeline(@Param("id") long id,@Param("error") String error);
    @Insert("INSERT INTO pipeline_run(pipeline_id,status,lower_bound,upper_bound) VALUES(#{pipelineId},'RUNNING',#{lowerBound},#{upperBound})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insertRun(PipelineRun run);
    @Select("SELECT * FROM pipeline_run WHERE id=#{id}") PipelineRun run(long id);
    @Select("SELECT * FROM pipeline_run WHERE id=#{id} FOR UPDATE") PipelineRun lockRun(long id);
    @Update("UPDATE pipeline_run SET status=#{status},message=#{message},finished_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND status='RUNNING'")
    int finishRun(@Param("id") long id,@Param("status") String status,@Param("message") String message);
    @Select("SELECT r.*,p.name pipeline_name FROM pipeline_run r JOIN pipeline p ON p.id=r.pipeline_id ORDER BY r.id DESC LIMIT 50")
    List<PipelineRun> recentRuns();
    @Select("SELECT * FROM data_service ORDER BY id DESC") List<DataService> services();
    @Select("SELECT * FROM data_service WHERE id=#{id}") DataService dataService(long id);
    @Select("SELECT * FROM data_service WHERE slug=#{slug} AND published=TRUE") DataService publishedService(String slug);
    @Insert("INSERT INTO data_service(name,slug,query_sql,max_rows) VALUES(#{name},#{slug},#{querySql},#{maxRows})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insertService(DataService service);
    @Update("UPDATE data_service SET published=#{published} WHERE id=#{id}")
    void publish(@Param("id") long id,@Param("published") boolean published);
    @Update("UPDATE api_credential SET enabled=FALSE WHERE service_id=#{id}") void revokeCredentials(long id);
    @Insert("INSERT INTO api_credential(service_id,token_hash) VALUES(#{serviceId},#{tokenHash})")
    void insertCredential(@Param("serviceId") long serviceId,@Param("tokenHash") String tokenHash);
    @Select("SELECT COUNT(*) FROM api_credential WHERE service_id=#{serviceId} AND token_hash=#{tokenHash} AND enabled=TRUE")
    int credentialValid(@Param("serviceId") long serviceId,@Param("tokenHash") String tokenHash);
    @Insert("INSERT INTO audit_event(actor,action,target,outcome) VALUES(#{actor},#{action},#{target},#{outcome})")
    void audit(@Param("actor") String actor,@Param("action") String action,@Param("target") String target,@Param("outcome") String outcome);
    @Select("SELECT (SELECT COUNT(*) FROM data_source) sources,(SELECT COUNT(*) FROM pipeline) pipelines,(SELECT COUNT(*) FROM data_service WHERE published=TRUE) services,(SELECT COUNT(*) FROM pipeline_run WHERE status='FAILED') failedRuns")
    Map<String, Object> stats();
}

