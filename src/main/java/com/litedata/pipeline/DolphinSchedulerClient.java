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
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DolphinSchedulerClient {
    private final PlatformMapper mapper; private final PlatformProperties.DolphinScheduler config;
    private final RestClient client; private final JsonMapper json=JsonMapper.builder().build();
    private final PipelineService pipelines;
    public DolphinSchedulerClient(PlatformMapper mapper,PlatformProperties properties,PipelineService pipelines) {
        this.pipelines=pipelines;
        this.mapper=mapper; config=properties.dolphinscheduler();
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        client=RestClient.builder().baseUrl(config.url()).requestFactory(factory).defaultHeader("token",config.token()).build();
    }
    public boolean configured() { return config.projectCode()>0 && config.token()!=null && !config.token().isBlank(); }
    @Transactional(noRollbackFor=IllegalStateException.class)
    public void run(long id) {
        Pipeline pipeline=lock(id); long code=definition(pipeline);
        post("启动工作流","/executors/start-workflow-instance",Map.of("workflowDefinitionCode",Long.toString(code),"scheduleTime","",
                "failureStrategy","END","warningType","NONE","workerGroup","default","tenantCode",config.tenantCode(),
                "execType","START_PROCESS","taskDependType","TASK_POST","runMode","RUN_MODE_SERIAL"));
    }
    @Transactional(noRollbackFor=IllegalStateException.class)
    public void schedule(long id) {
        Pipeline pipeline=lock(id); long code=definition(pipeline);
        int scheduleId;
        if(pipeline.getScheduleId()!=null) scheduleId=pipeline.getScheduleId();
        else {
            String schedule=json.writeValueAsString(Map.of("startTime",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    "endTime","2099-12-31 23:59:59","crontab",pipeline.getCronExpression(),"timezoneId","Asia/Shanghai"));
            Object result=post("创建调度", "/schedules",Map.of("workflowDefinitionCode",Long.toString(code),"schedule",schedule,
                    "failureStrategy","END","warningType","NONE","workerGroup","default","tenantCode",config.tenantCode()));
            scheduleId=scheduleId(result);
            mapper.schedule(id,scheduleId,"SCHEDULE_PENDING");
        }
        post("上线调度","/schedules/"+scheduleId+"/online",Map.of());
        mapper.schedule(id,scheduleId,"SCHEDULED");
    }
    @Transactional(noRollbackFor=IllegalStateException.class)
    public void pause(long id) {
        if(!configured()) throw new IllegalStateException("调度引擎尚未配置");
        Pipeline pipeline=mapper.lockPipeline(id);
        if(pipeline==null) throw new IllegalArgumentException("任务不存在");
        if(pipeline.getScheduleId()==null) throw new IllegalArgumentException("尚未启用定时调度");
        if(!"PAUSED".equals(pipeline.getStatus())) post("下线调度","/schedules/"+pipeline.getScheduleId()+"/offline",Map.of());
        mapper.schedule(id,pipeline.getScheduleId(),"PAUSED");
    }
    private Pipeline lock(long id) {
        if(!configured()) throw new IllegalStateException("调度引擎尚未初始化，请先执行部署初始化脚本并配置访问令牌及项目编号");
        Pipeline pipeline=mapper.lockPipeline(id);
        if(pipeline==null) throw new IllegalArgumentException("任务不存在");
        if(pipeline.getActiveRunId()!=null) throw new IllegalArgumentException("任务已有执行中的实例");
        pipelines.validateExecutable(pipeline);
        return pipeline;
    }
    private long definition(Pipeline pipeline) {
        Long code=pipeline.getDefinitionCode();
        if(code==null) {
            Object generated=get("获取任务编号","/task-definition/gen-task-codes?genNum=1");
            long taskCode=taskCode(generated);
            Map<String,Object> task=new LinkedHashMap<>();
            task.put("code",taskCode); task.put("name","ODS 贴源同步"); task.put("version",1); task.put("description","DataX 增量同步至 ODS");
            task.put("taskType","SHELL"); task.put("taskParams",Map.of("resourceList",List.of(),"localParams",List.of(),
                    "rawScript","python3 /opt/platform/bin/run_pipeline.py --pipeline-id "+pipeline.getId()
                            +" --workflow-instance-id ${system.workflow.instance.id} --task-instance-id ${system.task.instance.id}"));
            task.put("flag","YES"); task.put("taskPriority","MEDIUM"); task.put("workerGroup","default"); task.put("environmentCode",-1);
            task.put("failRetryTimes",2); task.put("failRetryInterval",1); task.put("timeoutFlag","OPEN");
            task.put("timeoutNotifyStrategy","FAILED"); task.put("timeout",50); task.put("delayTime",0);
            task.put("taskExecuteType","BATCH"); task.put("cpuQuota",-1); task.put("memoryMax",-1);
            String tasks=json.writeValueAsString(List.of(task));
            String relations=json.writeValueAsString(List.of(Map.of("preTaskCode",0,"preTaskVersion",0,"postTaskCode",taskCode,
                    "postTaskVersion",1,"conditionType","NONE","conditionParams",Map.of())));
            Object created=post("创建工作流","/workflow-definition",Map.of("name","platform-pipeline-"+pipeline.getId()+"-"+taskCode,"description",pipeline.getName(),
                    "tenantCode",config.tenantCode(),"globalParams","[]","locations","[]","timeout","55",
                    "executionType","SERIAL_WAIT","taskDefinitionJson",tasks,"taskRelationJson",relations));
            code=workflowCode(created);
            mapper.definition(pipeline.getId(),code); pipeline.setDefinitionCode(code);
        }
        post("发布工作流","/workflow-definition/"+code+"/release",Map.of("releaseState","ONLINE"));
        mapper.rememberWorkflow("pipeline",pipeline.getId(),code);
        return code;
    }
    private String path(String suffix) { return "/projects/"+config.projectCode()+suffix; }
    private Object get(String operation,String suffix) {
        try { return data(operation,client.get().uri(path(suffix)).retrieve().body(Map.class)); }
        catch(RestClientResponseException exception) { throw requestFailed(operation,exception.getStatusCode().value()); }
        catch(ResourceAccessException exception) { throw unavailable(operation); }
        catch(org.springframework.web.client.RestClientException exception) { throw invalidResponse(operation); }
    }
    private Object post(String operation,String suffix,Map<String,String> values) {
        var form=new LinkedMultiValueMap<String,String>(); values.forEach(form::add);
        try { return data(operation,client.post().uri(path(suffix)).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class)); }
        catch(RestClientResponseException exception) { throw requestFailed(operation,exception.getStatusCode().value()); }
        catch(ResourceAccessException exception) { throw unavailable(operation); }
        catch(org.springframework.web.client.RestClientException exception) { throw invalidResponse(operation); }
    }
    private Object data(String operation,Map<?,?> response) {
        if(response==null || !(response.get("code") instanceof Number code)) throw invalidResponse(operation);
        if(code.longValue()!=0) throw businessFailed(operation,code.longValue());
        return response.get("data");
    }
    private long taskCode(Object data) {
        if(data instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Number code) return code.longValue();
        throw invalidResponse("获取任务编号");
    }
    private long workflowCode(Object data) {
        if(data instanceof Map<?,?> map && map.get("code") instanceof Number code) return code.longValue();
        throw invalidResponse("创建工作流");
    }
    private int scheduleId(Object data) {
        if(data instanceof Number number) return number.intValue();
        if(data instanceof Map<?,?> map && map.get("id") instanceof Number number) return number.intValue();
        throw invalidResponse("创建调度");
    }
    private IllegalStateException requestFailed(String operation,int status) {
        String detail=switch(status) {
            case 401 -> "访问令牌已失效，请执行 deploy/start.ps1 或 deploy/start.sh 重新初始化并重建平台";
            case 403 -> "访问被拒绝，请检查项目编号、租户和令牌权限";
            default -> "引擎返回 HTTP "+status+"，请检查引擎状态、项目编号、租户和令牌";
        };
        return failure(operation,"HTTP "+status,detail);
    }
    private IllegalStateException businessFailed(String operation,long code) {
        String detail=code==10001
                ? "工作流参数或任务插件校验失败，请检查任务配置并查看引擎日志"
                : "请检查项目编号、租户和工作流配置";
        return failure(operation,"业务码 "+code,detail);
    }
    private IllegalStateException invalidResponse(String operation) {
        return failure(operation,"返回格式无效","请检查引擎版本、项目编号和令牌配置");
    }
    private IllegalStateException unavailable(String operation) {
        return failure(operation,"连接或读取超时","请确认引擎服务可访问后重试，并检查引擎地址配置");
    }
    private IllegalStateException failure(String operation,String kind,String detail) {
        return new IllegalStateException("DolphinScheduler "+operation+"失败（"+kind+"）："+detail+"；任务未标记为成功");
    }
}

