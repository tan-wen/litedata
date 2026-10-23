package com.litedata.transform;

import com.litedata.config.PlatformProperties;
import java.net.http.HttpClient;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/** Small, deliberately stateless adapter for DolphinScheduler workflow APIs. */
@Component
public class DolphinWorkflowClient {
    private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final PlatformProperties.DolphinScheduler config;
    private final RestClient client;
    private final JsonMapper json=JsonMapper.builder().build();

    public record Node(String key,String name,String script,List<String> upstreamKeys) {}

    public DolphinWorkflowClient(PlatformProperties properties) {
        config=properties.dolphinscheduler();
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        client=RestClient.builder().baseUrl(config.url()).requestFactory(factory).defaultHeader("token",config.token()).build();
    }

    public boolean configured() { return config.projectCode()>0 && config.token()!=null && !config.token().isBlank(); }

    /** Creates a draft definition. The caller explicitly publishes it after persistence succeeds. */
    public long create(String name,String description,List<Node> nodes) {
        requireConfigured();
        if(name==null || name.isBlank() || nodes==null || nodes.isEmpty()) throw new IllegalArgumentException("工作流名称和节点不能为空");
        List<Long> codes=taskCodes(nodes.size());
        Map<String,Long> byKey=new LinkedHashMap<>();
        List<Map<String,Object>> tasks=new ArrayList<>();
        for(int i=0;i<nodes.size();i++) {
            Node node=nodes.get(i);
            if(node==null || node.key()==null || node.key().isBlank() || node.name()==null || node.name().isBlank() || node.script()==null || node.script().isBlank())
                throw new IllegalArgumentException("工作流节点不完整");
            if(byKey.putIfAbsent(node.key(),codes.get(i))!=null) throw new IllegalArgumentException("工作流节点键重复");
            tasks.add(task(codes.get(i),node));
        }
        List<Map<String,Object>> relations=new ArrayList<>();
        for(Node node:nodes) {
            List<String> upstream=node.upstreamKeys()==null ? List.of() : node.upstreamKeys();
            if(upstream.isEmpty()) relations.add(relation(0,0,byKey.get(node.key())));
            else for(String key:upstream) {
                Long pre=byKey.get(key);
                if(pre==null) throw new IllegalArgumentException("工作流上游节点不存在");
                if(pre.equals(byKey.get(node.key()))) throw new IllegalArgumentException("工作流节点不能依赖自身");
                relations.add(relation(pre,1,byKey.get(node.key())));
            }
        }
        Object result=post("创建工作流","/workflow-definition",Map.of(
                "name",name,"description",description==null ? "" : description,"tenantCode",config.tenantCode(),
                "globalParams","[]","locations","[]","timeout",Integer.toString(nodes.size()*50+5),"executionType","SERIAL_WAIT",
                "taskDefinitionJson",write(tasks),"taskRelationJson",write(relations)));
        return workflowCode(result);
    }

    public void publish(long code) { requireConfigured(); post("发布工作流","/workflow-definition/"+code+"/release",Map.of("releaseState","ONLINE")); }

    public void start(long code) {
        requireConfigured();
        post("启动工作流","/executors/start-workflow-instance",Map.of("workflowDefinitionCode",Long.toString(code),"scheduleTime","",
                "failureStrategy","END","warningType","NONE","workerGroup","default","tenantCode",config.tenantCode(),
                "execType","START_PROCESS","taskDependType","TASK_POST","runMode","RUN_MODE_SERIAL"));
    }

    public int createSchedule(long workflowCode,String cron) {
        requireConfigured();
        if(cron==null || cron.isBlank()) throw new IllegalArgumentException("调度表达式不能为空");
        String schedule=write(Map.of("startTime",LocalDateTime.now(SHANGHAI).format(TIME),"endTime","2099-12-31 23:59:59",
                "crontab",cron,"timezoneId","Asia/Shanghai"));
        return scheduleId(post("创建调度","/schedules",Map.of("workflowDefinitionCode",Long.toString(workflowCode),"schedule",schedule,
                "failureStrategy","END","warningType","NONE","workerGroup","default","tenantCode",config.tenantCode())));
    }

    public void online(int scheduleId) { requireConfigured(); post("上线调度","/schedules/"+scheduleId+"/online",Map.of()); }
    public void offline(int scheduleId) { requireConfigured(); post("下线调度","/schedules/"+scheduleId+"/offline",Map.of()); }

    private List<Long> taskCodes(int count) {
        Object data=get("获取任务编号","/task-definition/gen-task-codes?genNum="+count);
        if(!(data instanceof List<?> list) || list.size()!=count) throw invalid("获取任务编号");
        List<Long> result=new ArrayList<>();
        for(Object item:list) {
            if(!(item instanceof Number number) || number.longValue()<=0) throw invalid("获取任务编号");
            result.add(number.longValue());
        }
        if(new HashSet<>(result).size()!=count) throw invalid("获取任务编号");
        return result;
    }

    private Map<String,Object> task(long code,Node node) {
        Map<String,Object> task=new LinkedHashMap<>();
        task.put("code",code); task.put("name",node.name()); task.put("version",1); task.put("description",""); task.put("taskType","SHELL");
        task.put("taskParams",Map.of("resourceList",List.of(),"localParams",List.of(),"rawScript",node.script()));
        task.put("flag","YES"); task.put("taskPriority","MEDIUM"); task.put("workerGroup","default"); task.put("environmentCode",-1);
        task.put("failRetryTimes",2); task.put("failRetryInterval",1); task.put("timeoutFlag","OPEN"); task.put("timeoutNotifyStrategy","FAILED"); task.put("timeout",50);
        task.put("delayTime",0); task.put("taskExecuteType","BATCH"); task.put("cpuQuota",-1); task.put("memoryMax",-1);
        return task;
    }
    private Map<String,Object> relation(long pre,int preVersion,long post) { return Map.of("preTaskCode",pre,"preTaskVersion",preVersion,"postTaskCode",post,"postTaskVersion",1,"conditionType","NONE","conditionParams",Map.of()); }
    private String path(String suffix) { return "/projects/"+config.projectCode()+suffix; }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(RuntimeException exception) { throw invalid("序列化工作流"); } }
    private Object get(String operation,String suffix) {
        try { return data(operation,client.get().uri(path(suffix)).retrieve().body(Map.class)); }
        catch(RestClientResponseException exception) { throw http(operation,exception.getStatusCode().value()); }
        catch(ResourceAccessException exception) { throw unavailable(operation); }
        catch(org.springframework.web.client.RestClientException exception) { throw invalid(operation); }
    }
    private Object post(String operation,String suffix,Map<String,String> values) {
        var form=new LinkedMultiValueMap<String,String>(); values.forEach(form::add);
        try { return data(operation,client.post().uri(path(suffix)).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class)); }
        catch(RestClientResponseException exception) { throw http(operation,exception.getStatusCode().value()); }
        catch(ResourceAccessException exception) { throw unavailable(operation); }
        catch(org.springframework.web.client.RestClientException exception) { throw invalid(operation); }
    }
    private Object data(String operation,Map<?,?> response) {
        if(response==null || !(response.get("code") instanceof Number code)) throw invalid(operation);
        if(code.longValue()!=0) throw failure(operation,"业务码 "+code.longValue());
        return response.get("data");
    }
    private long workflowCode(Object data) { if(data instanceof Map<?,?> map && map.get("code") instanceof Number code && code.longValue()>0) return code.longValue(); throw invalid("创建工作流"); }
    private int scheduleId(Object data) { if(data instanceof Number number && number.intValue()>0) return number.intValue(); if(data instanceof Map<?,?> map && map.get("id") instanceof Number number && number.intValue()>0) return number.intValue(); throw invalid("创建调度"); }
    private void requireConfigured() { if(!configured()) throw new IllegalStateException("DolphinScheduler 尚未配置"); }
    private IllegalStateException http(String operation,int status) { return failure(operation,"HTTP "+status); }
    private IllegalStateException invalid(String operation) { return failure(operation,"返回格式无效"); }
    private IllegalStateException unavailable(String operation) { return failure(operation,"连接或读取超时"); }
    private IllegalStateException failure(String operation,String reason) { return new IllegalStateException("DolphinScheduler "+operation+"失败（"+reason+"），请检查引擎状态、项目编号和访问配置"); }
}
