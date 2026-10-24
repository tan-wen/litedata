package com.litedata.scheduling;

import com.litedata.config.PlatformProperties;
import com.litedata.service.QueryPage;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Read-only, bounded projection of DS APIs. Never return engine parameters or raw errors. */
@Component
public class DolphinMonitorClient {
    public record Workflow(long id,long definitionCode,String name,String state,String startedAt,String finishedAt,int runTimes) {}
    public record Node(long id,String name,String state,String startedAt,String finishedAt,int retryTimes) {}
    public record Page(long total,List<Workflow> items) {}
    private final RestClient client;
    private final PlatformProperties.DolphinScheduler config;
    private final JsonMapper json=JsonMapper.builder().build();
    public DolphinMonitorClient(PlatformProperties properties) {
        config=properties.dolphinscheduler();
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        client=RestClient.builder().baseUrl(config.url()).requestFactory(factory).defaultHeader("token",config.token()).build();
    }
    public Page instances(long code,QueryPage page,String state) {
        Object value=get("/projects/"+config.projectCode()+"/workflow-instances",Map.of(
                "workflowDefinitionCode",code,"pageNo",page.pageNum(),"pageSize",page.pageSize(),"stateType",state));
        var data=map(value);
        if(!(data.get("totalList") instanceof List<?> rows) || !(data.get("total") instanceof Number total) || total.longValue()<0)
            throw invalid();
        return new Page(total.longValue(),rows.stream().map(this::workflow).toList());
    }
    public Workflow instance(long id) { return workflow(get("/projects/"+config.projectCode()+"/workflow-instances/"+id,Map.of())); }
    public List<Node> nodes(long id) {
        var data=map(get("/projects/"+config.projectCode()+"/workflow-instances/"+id+"/tasks",Map.of()));
        if(!(data.get("taskList") instanceof List<?> rows)) throw invalid();
        return rows.stream().map(value->{
            var row=map(value);
            if(number(row,"workflowInstanceId")!=id) throw invalid();
            return new Node(number(row,"id"),text(row,"name"),text(row,"state"),text(row,"startTime"),text(row,"endTime"),integer(row,"retryTimes"));
        }).toList();
    }
    public String log(long taskId,int offset,int limit) {
        Object value=get("/log/"+config.projectCode()+"/detail",Map.of("taskInstanceId",taskId,"skipLineNum",offset,"limit",limit));
        if(value==null) return "";
        if(!(value instanceof String log)) throw invalid();
        // DS prefixes the first page with a file/host header, which isn't part of its line offsets.
        log=log.replace("\r\n","\n").replace('\r','\n');
        if(offset==0 && log.startsWith("[LOG-PATH]")) {
            int newline=log.indexOf('\n'); log=newline<0?"":log.substring(newline+1);
        }
        return log;
    }
    private Object get(String path,Map<String,?> params) {
        if(config.projectCode()<=0 || config.token()==null || config.token().isBlank())
            throw new IllegalStateException("尚未配置调度引擎，平台运行记录仍可查看");
        try {
            return client.get().uri(builder->{
                builder.path(path); params.forEach((key,value)->{ if(!"".equals(value)) builder.queryParam(key,value); });
                return builder.build();
            }).exchange((request,response)->{
                if(!response.getStatusCode().is2xxSuccessful()) throw unavailable();
                byte[] body=response.getBody().readNBytes(2*1024*1024+1);
                if(body.length>2*1024*1024) throw new IllegalStateException("引擎返回内容过大，请减少日志行数后重试");
                Map<?,?> result;
                try { result=json.readValue(body,Map.class); }
                catch(RuntimeException exception) { throw invalid(); }
                if(result==null) throw invalid();
                if(!(result.get("code") instanceof Number code)) throw invalid();
                if(code.longValue()!=0) throw unavailable();
                return result.get("data");
            });
        } catch(IllegalStateException exception) { throw exception; }
        catch(Exception exception) { throw unavailable(); }
    }
    private Workflow workflow(Object value) {
        var row=map(value);
        return new Workflow(number(row,"id"),number(row,"workflowDefinitionCode"),text(row,"name"),text(row,"state"),
                text(row,"startTime"),text(row,"endTime"),integer(row,"runTimes"));
    }
    private Map<?,?> map(Object value) { if(value instanceof Map<?,?> row) return row; throw invalid(); }
    private long number(Map<?,?> row,String key) { if(row.get(key) instanceof Number value && value.longValue()>0) return value.longValue(); throw invalid(); }
    private int integer(Map<?,?> row,String key) { return row.get(key) instanceof Number value?Math.max(0,value.intValue()):0; }
    private String text(Map<?,?> row,String key) { return row.get(key) instanceof String value?value:""; }
    private IllegalStateException invalid() { return new IllegalStateException("调度引擎返回格式不兼容，请检查引擎版本"); }
    private IllegalStateException unavailable() { return new IllegalStateException("调度引擎或节点日志暂不可用，请稍后重试；平台运行记录仍可查看"); }
}
