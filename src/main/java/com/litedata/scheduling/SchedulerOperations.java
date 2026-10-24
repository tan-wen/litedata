package com.litedata.scheduling;

import com.litedata.store.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SchedulerOperations {
    public record Resource(String type,long id,String name,String backPath,List<Long> codes) {
        public String path() { return "/scheduling/"+type+"/"+id; }
    }
    public record LogPage(String content,int offset,int nextOffset,int lineCount,boolean hasMore,boolean running) {}
    private final PlatformMapper platform;
    private final SqlTaskMapper tasks;
    private final DolphinMonitorClient engine;
    public SchedulerOperations(PlatformMapper platform,SqlTaskMapper tasks,DolphinMonitorClient engine) {
        this.platform=platform; this.tasks=tasks; this.engine=engine;
    }
    public Resource resource(String type,long id) {
        String name,back; Long current;
        if(type.equals("pipeline")) {
            var item=platform.pipeline(id); if(item==null) throw missing();
            name=item.getName(); current=item.getDefinitionCode(); back="/pipelines";
        } else if(type.equals("transform")) {
            var item=tasks.task(id); if(item==null) throw missing();
            name=item.getName(); current=item.getDefinitionCode(); back="/transforms/"+id;
        } else throw missing();
        var codes=new LinkedHashSet<Long>(); if(current!=null) codes.add(current);
        codes.addAll(platform.workflowCodes(type,id));
        return new Resource(type,id,name,back,List.copyOf(codes));
    }
    public void checkCode(Resource resource,long code) { if(!resource.codes().contains(code)) throw missing(); }
    public DolphinMonitorClient.Workflow instance(Resource resource,long id) {
        if(id<=0 || resource.codes().isEmpty()) throw missing();
        var instance=engine.instance(id); checkCode(resource,instance.definitionCode());
        return instance;
    }
    public LogPage log(Resource resource,long instanceId,long taskId,int offset,int limit,String actor) {
        if(offset<0 || offset>1_000_000 || limit<1 || limit>1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"日志偏移须在0到1000000之间，每次读取1到1000行");
        instance(resource,instanceId);
        var node=engine.nodes(instanceId).stream().filter(n->n.id()==taskId).findFirst().orElseThrow(SchedulerOperations::missing);
        String raw=engine.log(taskId,offset,limit);
        int lines=raw.isEmpty()?0:(int)raw.chars().filter(c->c=='\n').count()+(raw.endsWith("\n")?0:1);
        if(lines>limit) throw new IllegalStateException("引擎日志分页格式不兼容");
        platform.audit(actor,"SCHEDULER_LOG_READ",resource.type()+":"+resource.id()+":"+instanceId+":"+taskId,"SUCCESS");
        return new LogPage(LogRedactor.redact(raw),offset,offset+lines,lines,lines==limit,isRunning(node.state()));
    }
    public static boolean isRunning(String state) { return !Set.of("SUCCESS","FAILURE","STOP","PAUSE","KILL","FORCED_SUCCESS").contains(state); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"任务或运行实例不存在"); }
}
