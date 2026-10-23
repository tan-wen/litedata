package com.litedata.pipeline;

import com.litedata.config.PlatformProperties;
import com.litedata.model.Pipeline;
import com.litedata.store.PlatformMapper;
import java.net.http.HttpClient;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DolphinSchedulerClient {
    private final PlatformMapper mapper; private final PlatformProperties.DolphinScheduler config;
    private final RestClient client; private final JsonMapper json=JsonMapper.builder().build();
    public DolphinSchedulerClient(PlatformMapper mapper,PlatformProperties properties) {
        this.mapper=mapper; config=properties.dolphinscheduler();
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        client=RestClient.builder().baseUrl(config.url()).requestFactory(factory).defaultHeader("token",config.token()).build();
    }
    public boolean configured() { return config.projectCode()>0 && config.token()!=null && !config.token().isBlank(); }
    @Transactional(noRollbackFor=IllegalStateException.class)
    public void run(long id) {
        Pipeline pipeline=lock(id); long code=definition(pipeline);
        post("/executors/start-workflow-instance",Map.of("workflowDefinitionCode",Long.toString(code),"scheduleTime","",
                "failureStrategy","END","warningType","NONE","workerGroup","default","tenantCode",config.tenantCode(),
                "execType","START_PROCESS","taskDependType","TASK_POST","runMode","RUN_MODE_SERIAL"));
    }
    @Transactional(noRollbackFor=IllegalStateException.class)
    public void schedule(long id) {
        Pipeline pipeline=lock(id); long code=definition(pipeline);
        int scheduleId;
        if(pipeline.getScheduleId()!=null) scheduleId=pipeline.getScheduleId();
        else {
            String schedule=json.writeValueAsString(Map.of("startTime",LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    "endTime","2099-12-31 23:59:59","crontab",pipeline.getCronExpression(),"timezoneId","Asia/Shanghai"));
            Object result=post("/schedules",Map.of("workflowDefinitionCode",Long.toString(code),"schedule",schedule,
                    "failureStrategy","END","warningType","NONE","workerGroup","default","tenantCode",config.tenantCode()));
            scheduleId=result instanceof Number number?number.intValue():((Number)((Map<?,?>)result).get("id")).intValue();
            mapper.schedule(id,scheduleId,"SCHEDULE_PENDING");
        }
        post("/schedules/"+scheduleId+"/online",Map.of());
        mapper.schedule(id,scheduleId,"SCHEDULED");
    }
    private Pipeline lock(long id) {
        if(!configured()) throw new IllegalStateException("调度引擎尚未初始化，请先执行部署初始化脚本并配置访问令牌及项目编号");
        Pipeline pipeline=mapper.lockPipeline(id);
        if(pipeline==null) throw new IllegalArgumentException("任务不存在");
        if(pipeline.getActiveRunId()!=null) throw new IllegalArgumentException("任务已有执行中的实例");
        return pipeline;
    }
    private long definition(Pipeline pipeline) {
        Long code=pipeline.getDefinitionCode();
        if(code==null) {
            Object generated=get("/task-definition/gen-task-codes?genNum=1");
            long taskCode=((Number)((List<?>)generated).getFirst()).longValue();
            Map<String,Object> task=new LinkedHashMap<>();
            task.put("code",taskCode); task.put("name","同步与加工"); task.put("version",1); task.put("description","平台固定执行链路");
            task.put("taskType","SHELL"); task.put("taskParams",Map.of("resourceList",List.of(),"localParams",List.of(),
                    "rawScript","python3 /opt/platform/bin/run_pipeline.py --pipeline-id "+pipeline.getId()));
            task.put("flag","YES"); task.put("taskPriority","MEDIUM"); task.put("workerGroup","default"); task.put("environmentCode",-1);
            task.put("failRetryTimes",2); task.put("failRetryInterval",1); task.put("timeoutFlag","OPEN");
            task.put("timeoutNotifyStrategy","FAILED"); task.put("timeout",50); task.put("delayTime",0);
            task.put("taskExecuteType","BATCH"); task.put("cpuQuota",-1); task.put("memoryMax",-1);
            String tasks=json.writeValueAsString(List.of(task));
            String relations=json.writeValueAsString(List.of(Map.of("preTaskCode",0,"preTaskVersion",0,"postTaskCode",taskCode,
                    "postTaskVersion",1,"conditionType","NONE","conditionParams",Map.of())));
            Object created=post("/workflow-definition",Map.of("name","platform-pipeline-"+pipeline.getId(),"description",pipeline.getName(),
                    "tenantCode",config.tenantCode(),"globalParams","[]","locations","[]","timeout","55",
                    "executionType","SERIAL_WAIT","taskDefinitionJson",tasks,"taskRelationJson",relations));
            code=((Number)((Map<?,?>)created).get("code")).longValue();
            mapper.definition(pipeline.getId(),code); pipeline.setDefinitionCode(code);
        }
        post("/workflow-definition/"+code+"/release",Map.of("releaseState","ONLINE"));
        return code;
    }
    private String path(String suffix) { return "/projects/"+config.projectCode()+suffix; }
    private Object get(String suffix) {
        try { return data(client.get().uri(path(suffix)).retrieve().body(Map.class)); }
        catch(org.springframework.web.client.RestClientException exception) { throw unavailable(); }
    }
    private Object post(String suffix,Map<String,String> values) {
        var form=new LinkedMultiValueMap<String,String>(); values.forEach(form::add);
        try { return data(client.post().uri(path(suffix)).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class)); }
        catch(org.springframework.web.client.RestClientException exception) { throw unavailable(); }
    }
    private Object data(Map<?,?> response) {
        if(response==null || !(response.get("code") instanceof Number code) || code.intValue()!=0) throw unavailable();
        return response.get("data");
    }
    private IllegalStateException unavailable() {
        return new IllegalStateException("DolphinScheduler 请求失败，请检查引擎状态、项目编号、租户和令牌；任务未标记为成功");
    }
}

