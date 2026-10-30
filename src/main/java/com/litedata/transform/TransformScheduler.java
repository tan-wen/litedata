package com.litedata.transform;

import com.litedata.store.PlatformMapper;
import com.litedata.store.SqlTaskMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransformScheduler {
    private final SqlTaskMapper mapper;
    private final PlatformMapper platform;
    private final TransformService tasks;
    private final DolphinWorkflowClient engine;
    public TransformScheduler(SqlTaskMapper mapper,PlatformMapper platform,TransformService tasks,DolphinWorkflowClient engine) {
        this.mapper=mapper; this.platform=platform; this.tasks=tasks; this.engine=engine;
    }
    @Transactional(noRollbackFor=IllegalStateException.class)
    public void run(long id,int version,String actor) {
        mapper.lockGraph(); var task=tasks.require(id); TransformService.checkVersion(task,version);
        var graph=tasks.graph(id); tasks.preflight(graph);
        long code=definition(id,graph);
        engine.publish(code); engine.start(code);
        platform.audit(actor,"SQL_CHAIN_SUBMIT",Long.toString(id),"ACCEPTED");
    }
    @Transactional(noRollbackFor=IllegalStateException.class)
    public void schedule(long id,int version,String actor) {
        mapper.lockGraph(); var task=tasks.require(id); TransformService.checkVersion(task,version);
        var graph=tasks.graph(id); tasks.preflight(graph);
        long code=definition(id,graph); engine.publish(code); task=tasks.require(id);
        Integer scheduleId=task.getScheduleId();
        if(scheduleId==null) {
            scheduleId=engine.createSchedule(code,task.getCronExpression());
            mapper.schedule(id,scheduleId,"SCHEDULE_PENDING");
        }
        engine.online(scheduleId); mapper.schedule(id,scheduleId,"SCHEDULED");
        platform.audit(actor,"SQL_CHAIN_SCHEDULE",Long.toString(id),"SUCCESS");
    }
    @Transactional(noRollbackFor=IllegalStateException.class)
    public void pause(long id,int version,String actor) {
        mapper.lockGraph(); var task=tasks.require(id); TransformService.checkVersion(task,version);
        if(task.getScheduleId()!=null && !"PAUSED".equals(task.getStatus())) engine.offline(task.getScheduleId());
        mapper.schedule(id,task.getScheduleId(),"PAUSED");
        platform.audit(actor,"SQL_CHAIN_PAUSE",Long.toString(id),"SUCCESS");
    }
    private long definition(long rootId,TransformService.Graph graph) {
        if(!engine.configured()) throw new IllegalStateException("调度引擎尚未配置，请先完成引擎初始化");
        var task=tasks.require(rootId);
        if(task.getDefinitionCode()!=null && graph.hash().equals(task.getDefinitionHash())) {
            remember(graph,task.getDefinitionCode());
            return task.getDefinitionCode();
        }
        if(task.getScheduleId()!=null && !"PAUSED".equals(task.getStatus())) engine.offline(task.getScheduleId());
        var nodes=graph.nodes().stream().map(node-> {
            String script=node.kind().equals("同步")
                    ? "python3 /opt/platform/bin/run_pipeline.py --pipeline-id "+node.refId()
                        +" --workflow-instance-id ${system.workflow.instance.id} --task-instance-id ${system.task.instance.id}"
                    : "python3 /opt/platform/bin/run_sql_task.py --task-id "+node.refId()+" --version "+node.version()
                        +" --root-id "+rootId+" --graph-hash "+graph.hash();
            return new DolphinWorkflowClient.Node(node.key(),node.kind()+"："+node.name(),script,node.upstreamKeys());
        }).toList();
        long code=engine.create("platform-sql-"+rootId+"-"+graph.hash().substring(0,12),task.getName(),nodes);
        mapper.definition(rootId,code,graph.hash());
        remember(graph,code);
        return code;
    }
    private void remember(TransformService.Graph graph,long code) {
        for(var node:graph.nodes()) platform.rememberWorkflow(node.kind().equals("同步")?"pipeline":"transform",node.refId(),code);
    }
}
