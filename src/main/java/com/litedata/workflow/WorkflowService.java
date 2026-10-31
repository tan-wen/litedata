package com.litedata.workflow;

import com.litedata.model.SqlTask;
import com.litedata.service.QueryPage;
import com.litedata.scheduling.SchedulePreview;
import com.litedata.store.*;
import com.litedata.transform.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** A named, single-output workflow over the existing task DAG. Dependencies have one source of truth. */
@Service
public class WorkflowService {
    public record Position(double x,double y) {}
    public record Node(String key,String name,String kind,long refId,int version,List<String> upstreamKeys,
                       String tableName,String editUrl,String engineName,String configHash) {}
    public record Edge(String from,String to) {}
    public record View(long id,String name,String description,int version,long rootTaskId,String rootKey,int rootVersion,
                       String cronExpression,String status,Integer scheduleId,Long definitionCode,String graphHash,
                       List<Node> nodes,Map<String,Position> positions) {}
    public record Save(int version,String graphHash,String cronExpression,List<Node> nodes,List<Edge> edges,Map<String,Position> positions) {}
    public record Catalog(List<WorkflowMapper.CatalogItem> items,long total,int pageNum,int pageSize) {}
    private final WorkflowMapper mapper;
    private final SqlTaskMapper sql;
    private final PlatformMapper platform;
    private final TransformService tasks;
    private final TransformScheduler scheduler;
    private final JsonMapper json=JsonMapper.builder().build();
    public WorkflowService(WorkflowMapper mapper,SqlTaskMapper sql,PlatformMapper platform,TransformService tasks,TransformScheduler scheduler) {
        this.mapper=mapper; this.sql=sql; this.platform=platform; this.tasks=tasks; this.scheduler=scheduler;
    }
    public ProcessingWorkflow require(long id) {
        var workflow=mapper.workflow(id);
        if(workflow==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"工作流不存在");
        return workflow;
    }
    public ProcessingWorkflow forRoot(long id) { return mapper.forRoot(id); }
    public List<SqlTask> roots() { return mapper.roots(); }
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
        if(id==null) {
            if(draft.getRootTaskId()==null) throw new IllegalArgumentException("请选择末端加工任务");
            tasks.require(draft.getRootTaskId()); tasks.graph(draft.getRootTaskId()); draft.setId(null); mapper.insert(draft);
            id=draft.getId();
        } else {
            var current=require(id); version(current,draft.getVersion());
            draft.setId(id); draft.setRootTaskId(current.getRootTaskId()); mapper.metadata(draft);
        }
        platform.audit(actor,"WORKFLOW_SAVE",Long.toString(id),"SUCCESS"); return id;
    }
    public View view(long id) {
        var workflow=require(id); var root=tasks.require(workflow.getRootTaskId()); var graph=tasks.graph(root.getId());
        return new View(id,workflow.getName(),workflow.getDescription(),workflow.getVersion(),root.getId(),"sql-"+root.getId(),root.getVersion(),
                root.getCronExpression(),root.getStatus(),root.getScheduleId(),root.getDefinitionCode(),graph.hash(),nodes(graph,id),positions(workflow.getPositionsJson()));
    }
    public View summary(long id) {
        var workflow=require(id); var root=tasks.require(workflow.getRootTaskId());
        return new View(id,workflow.getName(),workflow.getDescription(),workflow.getVersion(),root.getId(),"sql-"+root.getId(),root.getVersion(),
                root.getCronExpression(),root.getStatus(),root.getScheduleId(),root.getDefinitionCode(),"",List.of(),Map.of());
    }
    public List<Node> expand(long id,String key) {
        require(id); long ref=parse(key);
        if(key.startsWith("sql-")) return nodes(tasks.graph(ref),id);
        return List.of(pipeline(ref,id));
    }
    private List<Node> nodes(TransformService.Graph graph,long workflowId) {
        return graph.nodes().stream().map(n->n.kind().equals("SQL")
                ?new Node(n.key(),n.name(),n.kind(),n.refId(),n.version(),n.upstreamKeys(),n.tableName(),
                    "/transforms/"+n.refId()+"/edit?workflowId="+workflowId,"SQL："+n.name(),tasks.graph(n.refId()).hash())
                :pipeline(n.refId(),workflowId)).toList();
    }
    private Node pipeline(long id,long workflowId) {
        var p=platform.pipeline(id); if(p==null) throw new IllegalArgumentException("同步管道不存在");
        String fingerprint=p.getSourceId()+"|"+p.getSourceTable()+"|"+p.getTargetTable()+"|"+p.getWatermarkColumn();
        return new Node("pipeline-"+id,p.getName(),"同步",id,0,List.of(),p.getTargetTable(),
                "/pipelines/"+id+"/edit?workflowId="+workflowId,"同步："+p.getName(),hash(fingerprint));
    }
    @Transactional
    public View save(long id,Save input,String actor) {
        sql.lockGraph(); var workflow=require(id); version(workflow,input.version());
        var root=tasks.require(workflow.getRootTaskId());
        if(!Objects.equals(tasks.graph(root.getId()).hash(),input.graphHash())) throw conflict();
        String cron=SchedulePreview.validate(input.cronExpression());
        if(input.nodes()==null || input.nodes().isEmpty() || input.nodes().size()>100 || input.edges()==null || input.edges().size()>10000)
            throw new IllegalArgumentException("工作流须包含1到100个节点");
        var nodes=new LinkedHashMap<String,Node>();
        for(var node:input.nodes()) {
            if(node==null) throw new IllegalArgumentException("节点无效");
            long ref=parse(node.key());
            if(nodes.putIfAbsent(node.key(),node)!=null) throw new IllegalArgumentException("节点重复");
            if(node.key().startsWith("sql-")) {
                var current=tasks.require(ref);
                if(current.getVersion()!=node.version() || !Objects.equals(tasks.graph(ref).hash(),node.configHash())) throw conflict();
            } else if(!Objects.equals(pipeline(ref,id).configHash(),node.configHash())) throw conflict();
        }
        String rootKey="sql-"+root.getId();
        if(!nodes.containsKey(rootKey)) throw new IllegalArgumentException("不能移除末端加工任务");
        var upstream=new LinkedHashMap<String,Set<String>>(); nodes.keySet().forEach(k->upstream.put(k,new TreeSet<>()));
        for(var edge:input.edges()) {
            if(edge==null || !nodes.containsKey(edge.from()) || !nodes.containsKey(edge.to()) || !edge.to().startsWith("sql-"))
                throw new IllegalArgumentException("连线须连接画布中的节点，同步管道只能作为上游");
            if(edge.from().equals(edge.to()) || edge.from().equals(rootKey)) throw new IllegalArgumentException("末端任务不能作为上游，节点不能依赖自身");
            if(!upstream.get(edge.to()).add(edge.from())) throw new IllegalArgumentException("连线重复");
        }
        var visited=new HashSet<String>(); visit(rootKey,upstream,new HashSet<>(),visited);
        if(visited.size()!=nodes.size()) throw new IllegalArgumentException("所有节点必须直接或间接连接到末端任务，请移出孤立节点");
        var changed=new ArrayList<Long>();
        for(String key:nodes.keySet()) if(key.startsWith("sql-")) {
            long ref=parse(key); var current=tasks.require(ref);
            var actual=new TreeSet<String>(); current.getUpstreamTaskIds().forEach(v->actual.add("sql-"+v));
            current.getUpstreamPipelineIds().forEach(v->actual.add("pipeline-"+v));
            if(!actual.equals(upstream.get(key))) changed.add(ref);
        }
        boolean cronChanged=!root.getCronExpression().equals(cron);
        // Check all affected chains before applying any edge, under the shared graph lock.
        for(long ref:changed) tasks.ensureEditable(ref);
        if(cronChanged && !changed.contains(root.getId())) tasks.ensureEditable(root.getId());
        for(long ref:changed) {
            sql.deleteTaskDependencies(ref); sql.deletePipelineDependencies(ref);
            for(String key:upstream.get("sql-"+ref)) {
                if(key.startsWith("sql-")) sql.taskDependency(ref,parse(key)); else sql.pipelineDependency(ref,parse(key));
            }
            if(!cronChanged || ref!=root.getId()) sql.changed(ref);
        }
        if(cronChanged) sql.cron(root.getId(),cron);
        tasks.graph(root.getId());
        var positions=input.positions()==null?Map.<String,Position>of():input.positions();
        if(positions.size()>100) throw new IllegalArgumentException("布局节点过多");
        var valid=new LinkedHashMap<String,Position>();
        for(var entry:positions.entrySet()) {
            var point=entry.getValue();
            if(!nodes.containsKey(entry.getKey()) || point==null || !Double.isFinite(point.x()) || !Double.isFinite(point.y())
                    || point.x()<0 || point.y()<0 || point.x()>50000 || point.y()>50000) throw new IllegalArgumentException("节点位置无效");
            valid.put(entry.getKey(),point);
        }
        mapper.graph(id,json.writeValueAsString(valid));
        platform.audit(actor,"WORKFLOW_GRAPH_SAVE",Long.toString(id),"SUCCESS"); return view(id);
    }
    @Transactional(noRollbackFor=IllegalStateException.class)
    public void execute(long id,int expected,String graphHash,String action,String actor) {
        sql.lockGraph(); var workflow=require(id); version(workflow,expected); var root=tasks.require(workflow.getRootTaskId());
        // Stopping future schedules must remain possible even when a model or dependency is invalid.
        if(action.equals("pause")) { scheduler.pause(root.getId(),root.getVersion(),actor); return; }
        if(!Objects.equals(tasks.graph(root.getId()).hash(),graphHash)) throw conflict();
        switch(action) {
            case "run" -> scheduler.run(root.getId(),root.getVersion(),actor);
            case "schedule" -> scheduler.schedule(root.getId(),root.getVersion(),actor);
            default -> throw new IllegalArgumentException("操作无效");
        }
    }
    private static void visit(String key,Map<String,Set<String>> upstream,Set<String> visiting,Set<String> visited) {
        if(visited.contains(key)) return;
        if(!visiting.add(key)) throw new IllegalArgumentException("上游依赖存在循环");
        for(String parent:upstream.get(key)) visit(parent,upstream,visiting,visited);
        visiting.remove(key); visited.add(key);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Position> positions(String value) {
        if(value==null || value.isBlank()) return Map.of();
        Map<String,Object> raw=json.readValue(value,Map.class); var result=new LinkedHashMap<String,Position>();
        raw.forEach((key,point)-> { var p=(Map<String,Number>)point; result.put(key,new Position(p.get("x").doubleValue(),p.get("y").doubleValue())); });
        return result;
    }
    private static long parse(String key) {
        if(key==null || !key.matches("(?:sql|pipeline)-[1-9][0-9]{0,17}")) throw new IllegalArgumentException("节点编号无效");
        return Long.parseLong(key.substring(key.indexOf('-')+1));
    }
    private static String query(String value) { return Objects.toString(value,"").trim().toLowerCase(Locale.ROOT); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void version(ProcessingWorkflow value,int expected) { if(value.getVersion()!=expected) throw conflict(); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"工作流或任务配置已变化，请刷新后重新编排"); }
}
