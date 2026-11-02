package com.litedata.workflow;

import com.litedata.model.SqlTask;
import com.litedata.service.QueryPage;
import com.litedata.scheduling.SchedulePreview;
import com.litedata.store.*;
import com.litedata.transform.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WorkflowService {
    public record Position(double x,double y) {}
    public record Node(String key,String name,String kind,long refId,int version,List<String> upstreamKeys,
                       String tableName,String editUrl,String engineName,String configHash,boolean standaloneScheduled) {}
    public record Edge(String from,String to) {}
    public record View(long id,String name,String description,int version,String cronExpression,String status,
                       Integer scheduleId,Long definitionCode,String graphHash,List<Node> nodes,Map<String,Position> positions) {}
    public record Save(int version,String graphHash,String cronExpression,List<Node> nodes,List<Edge> edges,Map<String,Position> positions) {}
    public record TaskSave(int workflowVersion,String graphHash,Long taskId,int version,String name,Long modelId,String sqlText) {}
    public record TaskDefinition(long id,String name,Long modelId,String sqlText,int version) {}
    public record Catalog(List<WorkflowMapper.CatalogItem> items,long total,int pageNum,int pageSize) {}
    private final WorkflowMapper mapper;
    private final SqlTaskMapper sql;
    private final PlatformMapper platform;
    private final TransformService tasks;
    private final WorkflowScheduler scheduler;
    private final WorkflowGraph graphs;
    private final JsonMapper json=JsonMapper.builder().build();
    public WorkflowService(WorkflowMapper mapper,SqlTaskMapper sql,PlatformMapper platform,TransformService tasks,WorkflowScheduler scheduler,WorkflowGraph graphs) {
        this.mapper=mapper; this.sql=sql; this.platform=platform; this.tasks=tasks; this.scheduler=scheduler; this.graphs=graphs;
    }
    public ProcessingWorkflow require(long id) {
        var workflow=mapper.workflow(id);
        if(workflow==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"工作流不存在");
        return workflow;
    }
    public List<ProcessingWorkflow> forTask(long id) { return mapper.forTask(id); }
    public long count(String keyword) { return mapper.count(query(keyword)); }
    public List<ProcessingWorkflow> list(String keyword,QueryPage page) { return mapper.page(query(keyword),page.pageSize(),page.offset()); }
    public Catalog catalog(long id,String keyword,QueryPage page) {
        require(id); return new Catalog(mapper.catalog(query(keyword),page.pageSize(),page.offset()),mapper.catalogCount(query(keyword)),page.pageNum(),page.pageSize());
    }
    @Transactional
    public long metadata(Long id,ProcessingWorkflow draft,String actor) {
        sql.lockGraph();
        if(draft.getName()==null || draft.getName().isBlank() || draft.getName().trim().length()>100)
            throw new IllegalArgumentException("工作流名称不能为空，最长100字符");
        draft.setName(draft.getName().trim()); draft.setDescription(Objects.toString(draft.getDescription(),"").trim());
        if(draft.getDescription().length()>500) throw new IllegalArgumentException("描述最长500字符");
        if(id==null) { draft.setId(null); mapper.insert(draft); id=draft.getId(); }
        else { version(require(id),draft.getVersion()); draft.setId(id); mapper.metadata(draft); }
        platform.audit(actor,"WORKFLOW_SAVE",Long.toString(id),"SUCCESS"); return id;
    }
    public View view(long id) {
        var workflow=require(id); var graph=graphs.graph(id);
        var nodes=graph.nodes().stream().map(n->graphs.node(id,n.key(),n.upstreamKeys())).toList();
        return view(workflow,graph.hash(),nodes,positions(workflow.getPositionsJson()));
    }
    public View summary(long id) { return view(require(id),"",List.of(),Map.of()); }
    private View view(ProcessingWorkflow w,String hash,List<Node> nodes,Map<String,Position> positions) {
        return new View(w.getId(),w.getName(),w.getDescription(),w.getVersion(),w.getCronExpression(),w.getStatus(),
                w.getScheduleId(),w.getDefinitionCode(),hash,nodes,positions);
    }
    public List<Node> expand(long id,String key) { require(id); return List.of(graphs.node(id,key,List.of())); }
    public TaskDefinition task(long workflowId,long taskId) {
        require(workflowId);
        if(!mapper.nodeKeys(workflowId).contains("sql-"+taskId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"任务不在此工作流中");
        var t=tasks.require(taskId); return new TaskDefinition(t.getId(),t.getName(),t.getModelId(),t.getSqlText(),t.getVersion());
    }
    @Transactional
    public View saveTask(long id,TaskSave input,String actor) {
        sql.lockGraph(); var workflow=require(id); version(workflow,input.workflowVersion()); checkHash(id,input.graphHash());
        ensureEditable(workflow);
        if(input.taskId()!=null) task(id,input.taskId());
        else if(mapper.nodeKeys(id).size()>=100) throw new IllegalArgumentException("工作流最多100个节点");
        var draft=input.taskId()==null?new SqlTask():tasks.require(input.taskId());
        draft.setName(input.name()); draft.setModelId(input.modelId()); draft.setSqlText(input.sqlText()); draft.setVersion(input.version());
        long taskId=tasks.save(input.taskId(),draft,actor);
        mapper.retireLegacy(id);
        if(input.taskId()==null) mapper.node(id,"sql-"+taskId,taskId,null);
        mapper.graph(id,workflow.getPositionsJson(),workflow.getCronExpression());
        platform.audit(actor,"WORKFLOW_SQL_SAVE",id+":"+taskId,"SUCCESS"); return view(id);
    }
    @Transactional
    public View save(long id,Save input,String actor) {
        sql.lockGraph(); var workflow=require(id); version(workflow,input.version()); checkHash(id,input.graphHash());
        String cron=SchedulePreview.validate(input.cronExpression());
        if(input.nodes()==null || input.nodes().size()>100) throw new IllegalArgumentException("工作流最多100个节点");
        var keys=new ArrayList<String>();
        for(var node:input.nodes()) {
            if(node==null) throw new IllegalArgumentException("节点无效");
            var current=graphs.node(id,node.key(),List.of());
            if(node.version()!=current.version() || !Objects.equals(node.configHash(),current.configHash())) throw conflict();
            keys.add(node.key());
        }
        var upstream=WorkflowGraph.upstream(keys,input.edges());
        boolean changed=!new TreeSet<>(keys).equals(new TreeSet<>(mapper.nodeKeys(id)))
                || !upstream.equals(WorkflowGraph.upstream(mapper.nodeKeys(id),mapper.edges(id)));
        boolean cronChanged=!workflow.getCronExpression().equals(cron);
        if(changed || cronChanged) ensureEditable(workflow);
        var positions=input.positions()==null?Map.<String,Position>of():input.positions();
        if(positions.size()>100) throw new IllegalArgumentException("布局节点过多");
        var valid=new LinkedHashMap<String,Position>();
        for(var entry:positions.entrySet()) {
            var point=entry.getValue();
            if(!upstream.containsKey(entry.getKey()) || point==null || !Double.isFinite(point.x()) || !Double.isFinite(point.y())
                    || point.x()<0 || point.y()<0 || point.x()>50000 || point.y()>50000) throw new IllegalArgumentException("节点位置无效");
            valid.put(entry.getKey(),point);
        }
        if(changed) {
            mapper.retireLegacy(id);
            mapper.deleteEdges(id); mapper.deleteNodes(id);
            for(String key:keys) mapper.node(id,key,key.startsWith("sql-")?WorkflowGraph.parse(key):null,key.startsWith("pipeline-")?WorkflowGraph.parse(key):null);
            for(var edge:input.edges()) mapper.edge(id,edge.from(),edge.to());
        }
        if(cronChanged) mapper.schedule(id,null,"DRAFT"); // The old paused schedule contains the old Cron.
        mapper.graph(id,json.writeValueAsString(valid),cron);
        platform.audit(actor,"WORKFLOW_GRAPH_SAVE",Long.toString(id),"SUCCESS"); return view(id);
    }
    private void ensureEditable(ProcessingWorkflow workflow) {
        if(Set.of("SCHEDULED","SCHEDULE_PENDING").contains(workflow.getStatus()))
            throw new IllegalArgumentException("请先暂停此工作流的定时调度，再修改节点、依赖或 Cron");
        for(String key:mapper.nodeKeys(workflow.getId())) {
            boolean active=key.startsWith("sql-")?tasks.require(WorkflowGraph.parse(key)).getActiveRunId()!=null:platform.pipeline(WorkflowGraph.parse(key)).getActiveRunId()!=null;
            if(active) throw new IllegalArgumentException("工作流存在运行中的节点，请等待完成");
        }
    }
    @Transactional(noRollbackFor=IllegalStateException.class)
    public void execute(long id,int expected,String graphHash,String action,String actor) {
        sql.lockGraph(); var workflow=require(id); version(workflow,expected);
        if(action.equals("pause")) { scheduler.pause(id,actor); return; }
        checkHash(id,graphHash);
        switch(action) {
            case "run" -> scheduler.run(id,actor);
            case "schedule" -> scheduler.schedule(id,actor);
            default -> throw new IllegalArgumentException("操作无效");
        }
    }
    private void checkHash(long id,String hash) { if(!Objects.equals(graphs.graph(id).hash(),hash)) throw conflict(); }
    @SuppressWarnings("unchecked")
    private Map<String,Position> positions(String value) {
        if(value==null || value.isBlank()) return Map.of();
        Map<String,Object> raw=json.readValue(value,Map.class); var result=new LinkedHashMap<String,Position>();
        raw.forEach((key,point)-> { var p=(Map<String,Number>)point; result.put(key,new Position(p.get("x").doubleValue(),p.get("y").doubleValue())); });
        return result;
    }
    private static String query(String value) { return Objects.toString(value,"").trim().toLowerCase(Locale.ROOT); }
    private static void version(ProcessingWorkflow value,int expected) { if(value.getVersion()!=expected) throw conflict(); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"工作流或任务配置已变化，请刷新后重新编排"); }
}
