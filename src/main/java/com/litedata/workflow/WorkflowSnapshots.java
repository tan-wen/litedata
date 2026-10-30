package com.litedata.workflow;

import com.litedata.store.WorkflowMapper;
import com.litedata.transform.TransformService;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Immutable topology for the published definition, never reconstructed from today's dependencies. */
@Component
public class WorkflowSnapshots {
    private final WorkflowMapper mapper;
    private final JsonMapper json=JsonMapper.builder().build();
    public WorkflowSnapshots(WorkflowMapper mapper) { this.mapper=mapper; }
    public void remember(long root,long code,TransformService.Graph graph) {
        var nodes=graph.nodes().stream().map(n->new WorkflowService.Node(n.key(),n.name(),n.kind(),n.refId(),n.version(),n.upstreamKeys(),n.tableName(),
                n.kind().equals("SQL")?"/transforms/"+n.refId():"/pipelines#pipeline-"+n.refId(),n.kind()+"："+n.name(),"")).toList();
        mapper.snapshot(code,root,json.writeValueAsString(nodes));
    }
    public List<WorkflowService.Node> read(long root,long code) {
        String value=mapper.snapshotJson(code,root);
        return value==null?null:List.copyOf(Arrays.asList(json.readValue(value,WorkflowService.Node[].class)));
    }
}
