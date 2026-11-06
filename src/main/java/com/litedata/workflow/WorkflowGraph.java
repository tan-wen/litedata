package com.litedata.workflow;

import com.litedata.modeling.ModelingService;
import com.litedata.store.*;
import com.litedata.transform.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import org.springframework.stereotype.Component;

/**
 * Workflow-local SQL ownership, membership and dependencies. Pipelines remain reusable sources.
 */
@Component
public class WorkflowGraph {
    private final WorkflowMapper workflows;
    private final SqlTaskMapper tasks;
    private final PlatformMapper platform;
    private final ModelingService models;
    private final TransformSqlPolicy policy;

    public WorkflowGraph(WorkflowMapper workflows, SqlTaskMapper tasks, PlatformMapper platform, ModelingService models, TransformSqlPolicy policy) {
        this.workflows = workflows;
        this.tasks = tasks;
        this.platform = platform;
        this.models = models;
        this.policy = policy;
    }

    public TransformService.Graph graph(long id) {
        if (workflows.workflow(id) == null)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "加工任务不存在或已删除");
        var upstream = upstream(workflows.nodeKeys(id), workflows.edges(id));
        var ordered = new ArrayList<String>();
        var visited = new HashSet<String>();
        for (String key : upstream.keySet()) visit(key, upstream, new HashSet<>(), visited, ordered);
        var result = new ArrayList<TransformService.PlannedNode>();
        var fingerprint = new StringBuilder("WORKFLOW_V2:").append(id).append('\n');
        for (String key : ordered) {
            var node = node(id, key, List.copyOf(upstream.get(key)));
            fingerprint.append(key).append(':').append(node.configHash()).append(':').append(String.join(",", node.upstreamKeys())).append('\n');
            result.add(new TransformService.PlannedNode(key, node.name(), node.kind(), node.refId(), node.version(), node.upstreamKeys(), node.tableName()));
        }
        return new TransformService.Graph(List.copyOf(result), hash(fingerprint.toString()));
    }

    public WorkflowService.Node node(long workflowId, String key, List<String> upstream) {
        if (key == null || !key.startsWith("sql-"))
            throw new IllegalArgumentException("加工画布仅允许 SQL 节点；同步通过数据就绪事件触发加工");
        long id = parse(key);
        if (key.startsWith("sql-")) {
            var task = tasks.task(id);
            if (task == null) throw new IllegalArgumentException("数据加工不存在或已归档");
            if (!Objects.equals(task.getWorkflowId(), workflowId))
                throw new IllegalArgumentException("SQL 任务仅属于其创建流程，不能跨流程引用");
            var model = models.preview(task.getModelId());
            policy.validate(task.getSqlText(), model.model().getTableName());
            String fingerprint = task.getId() + "|" + task.getName() + "|" + task.getVersion() + "|" + task.getSqlText() + "|" + model.hash();
            return new WorkflowService.Node(key, task.getName(), "SQL", id, task.getVersion(), upstream, task.getTargetTable(),
                "/transforms/" + id + "/edit?workflowId=" + workflowId, "SQL：" + task.getName(), hash(fingerprint), false);
        }
        var pipe = platform.pipeline(id);
        if (pipe == null) throw new IllegalArgumentException("同步任务不存在");
        String fingerprint = pipe.getName() + "|" + pipe.getSourceId() + "|" + pipe.getSourceTable() + "|" + pipe.getTargetTable() + "|" + pipe.getWatermarkColumn();
        return new WorkflowService.Node(key, pipe.getName(), "同步", id, 0, upstream, pipe.getTargetTable(),
            "/pipelines/" + id + "/edit?workflowId=" + workflowId, "同步：" + pipe.getName(), hash(fingerprint),
            pipe.getScheduleId() != null && !"PAUSED".equals(pipe.getStatus()));
    }

    public static Map<String, SortedSet<String>> upstream(Collection<String> keys, List<WorkflowService.Edge> edges) {
        if (keys.size() > 100 || edges == null || edges.size() > 10000)
            throw new IllegalArgumentException("工作流最多100个节点");
        var upstream = new TreeMap<String, SortedSet<String>>();
        for (String key : keys) {
            parse(key);
            if (!key.startsWith("sql-")) throw new IllegalArgumentException("加工画布不能包含同步任务，请配置数据就绪订阅");
            if (upstream.putIfAbsent(key, new TreeSet<>()) != null) throw new IllegalArgumentException("节点重复");
        }
        for (var edge : edges) {
            if (edge == null || !upstream.containsKey(edge.from()) || !upstream.containsKey(edge.to()) || !edge.to().startsWith("sql-"))
                throw new IllegalArgumentException("连线须连接画布中的节点，同步任务只能作为上游");
            if (edge.from().equals(edge.to())) throw new IllegalArgumentException("节点不能依赖自身");
            if (!upstream.get(edge.to()).add(edge.from())) throw new IllegalArgumentException("连线重复");
        }
        var visited = new HashSet<String>();
        for (String key : upstream.keySet()) visit(key, upstream, new HashSet<>(), visited, new ArrayList<>());
        return upstream;
    }

    private static void visit(String key, Map<String, ? extends Set<String>> upstream, Set<String> visiting, Set<String> visited, List<String> ordered) {
        if (visited.contains(key)) return;
        if (!visiting.add(key)) throw new IllegalArgumentException("上游依赖存在循环");
        for (String parent : upstream.get(key)) visit(parent, upstream, visiting, visited, ordered);
        visiting.remove(key);
        visited.add(key);
        ordered.add(key);
    }

    public static long parse(String key) {
        if (key == null || !key.matches("(?:sql|pipeline)-[1-9][0-9]{0,17}"))
            throw new IllegalArgumentException("节点编号无效");
        return Long.parseLong(key.substring(key.indexOf('-') + 1));
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
