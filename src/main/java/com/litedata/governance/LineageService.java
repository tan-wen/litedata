package com.litedata.governance;

import com.litedata.model.DataSource;
import com.litedata.store.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LineageService {
    public record Node(String key,String table,String source,String layer,Long metadataId,boolean present,int level) {}
    public record Edge(String from,String to,String task,String kind,String href,String fromLabel,String toLabel) {}
    public record Graph(List<Node> nodes,List<Edge> edges,List<String> warnings,boolean truncated) {}
    private record Ref(String scope,String catalog,String table,String source) {
        String key() { return scope+"/"+catalog+"/"+table; }
    }
    private final MetadataMapper metadata;
    private final LineageMapper mapper;
    private final PlatformMapper platform;
    public LineageService(MetadataMapper metadata,LineageMapper mapper,PlatformMapper platform) {
        this.metadata=metadata; this.mapper=mapper; this.platform=platform;
    }
    @Transactional(readOnly=true)
    public Graph graph(long id,String direction,int depth) {
        if(!List.of("both","upstream","downstream").contains(direction) || depth<1 || depth>6)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"血缘方向无效或层数超出1至6");
        var root=metadata.table(id);
        if(root==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"元数据不存在");
        var refs=new LinkedHashMap<String,Ref>(); var edges=new LinkedHashSet<Edge>(); var warnings=new ArrayList<String>();
        var focus=ref(root.getScopeKey(),root.getCatalogName(),root.getTableName(),root.getSourceName());
        refs.put(focus.key(),focus);
        var sources=new HashMap<Long,DataSource>();
        for(var source:platform.sources()) sources.put(source.getId(),source);
        var pipelines=mapper.pipelines(); var tasks=mapper.tasks();
        if(pipelines.size()>5000 || tasks.size()>5000)
            throw new IllegalArgumentException("当前血缘索引支持最多5000条同步管道和5000条SQL任务，请缩小平台任务规模后查看");
        for(int index=0;index<pipelines.size();index++) {
            var pipeline=pipelines.get(index);
            String pipelineHref="/pipelines?pageNum="+((pipelines.size()-1-index)/20+1)+"#pipeline-"+pipeline.getId();
            var source=sources.get(pipeline.getSourceId());
            if(source!=null) {
                String catalog=source.getNamespace();
                var from=ref("source:"+source.getId(),catalog,pipeline.getSourceTable(),source.getName());
                var to=doris(pipeline.getTargetTable());
                add(refs,edges,from,to,pipeline.getName(),"同步",pipelineHref);
            }
            if(pipeline.getTransformSql()!=null && !pipeline.getTransformSql().isBlank())
                warnings.add("管道“"+pipeline.getName()+"”的旧加工 SQL 待迁移，未作为有效加工血缘展开");
        }
        for(var task:tasks) parse(refs,edges,warnings,task.getSqlText(),task.getName(),"数据加工","/transforms/"+task.getId());
        var levels=new LinkedHashMap<String,Integer>(); levels.put(focus.key(),0);
        boolean truncated=false;
        if(!direction.equals("downstream")) truncated=walk(focus.key(),edges,levels,depth,true);
        if(!direction.equals("upstream")) truncated=walk(focus.key(),edges,levels,depth,false) || truncated;
        var selected=edges.stream().filter(e->levels.containsKey(e.from()) && levels.containsKey(e.to())).toList();
        if(selected.size()>300) { selected=selected.subList(0,300); truncated=true; }
        var nodes=new ArrayList<Node>();
        for(var entry:levels.entrySet()) {
            var r=refs.get(entry.getKey()); var asset=metadata.find(r.scope(),r.catalog(),r.table());
            String layer="";
            if(asset!=null) { var detail=metadata.table(asset.getId()); layer=Objects.toString(detail.getLayer(),""); }
            nodes.add(new Node(r.key(),r.catalog()+"."+r.table(),r.source(),layer,asset==null?null:asset.getId(),asset!=null && asset.isPresent(),entry.getValue()));
        }
        nodes.sort(Comparator.comparingInt(Node::level).thenComparing(Node::key));
        return new Graph(List.copyOf(nodes),List.copyOf(selected),List.copyOf(warnings),truncated);
    }
    private static boolean walk(String root,Set<Edge> edges,Map<String,Integer> levels,int depth,boolean upstream) {
        Set<String> visited=new HashSet<>(); visited.add(root); List<String> frontier=List.of(root); boolean truncated=false;
        for(int distance=1;distance<=depth && !frontier.isEmpty();distance++) {
            var next=new LinkedHashSet<String>();
            for(String key:frontier) for(var edge:edges) {
                if(!key.equals(upstream?edge.to():edge.from())) continue;
                String neighbor=upstream?edge.from():edge.to();
                if(!visited.add(neighbor)) continue;
                if(!levels.containsKey(neighbor) && levels.size()>=120) { truncated=true; continue; }
                levels.putIfAbsent(neighbor,upstream?-distance:distance); next.add(neighbor);
            }
            frontier=new ArrayList<>(next);
        }
        return truncated;
    }
    private static void parse(Map<String,Ref> refs,Set<Edge> edges,List<String> warnings,String sql,String name,String kind,String href) {
        try {
            var parsed=LineageSql.parse(sql);
            for(String from:parsed.sources()) add(refs,edges,doris(from),doris(parsed.target()),name,kind,href);
        } catch(IllegalArgumentException exception) {
            if(warnings.size()<50) warnings.add(kind+"「"+name+"」解析失败，其 SQL 血缘未展示，请核对任务定义");
            else if(warnings.size()==50) warnings.add("更多解析失败任务已省略，请逐项核对任务定义");
        }
    }
    private static void add(Map<String,Ref> refs,Set<Edge> edges,Ref from,Ref to,String task,String kind,String href) {
        refs.putIfAbsent(from.key(),from); refs.putIfAbsent(to.key(),to);
        edges.add(new Edge(from.key(),to.key(),task,kind,href,
                from.source()+" / "+from.catalog()+"."+from.table(),to.source()+" / "+to.catalog()+"."+to.table()));
    }
    private static Ref doris(String table) { return ref("doris","warehouse",table,"Doris 数仓"); }
    private static Ref ref(String scope,String catalog,String table,String source) {
        return new Ref(scope,catalog,scope.equals("doris")?table.toLowerCase(Locale.ROOT):table,Objects.toString(source,scope));
    }
}
