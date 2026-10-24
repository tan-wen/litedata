package com.litedata.metric;

import static com.litedata.metric.MetricLineage.*;
import java.util.*;

/**
 * A read-only presentation lens for a captured field lineage graph.
 *
 * <p>Snapshots deliberately retain complete evidence. This class only removes
 * indirect relationships from the graph rendered by the default page; callers
 * can always request the complete graph again.</p>
 */
public final class MetricLineageProjection {
    private static final int MAX_DEPTH = 12;
    private static final Set<String> INDIRECT = Set.of("SQL 筛选", "SQL 关联", "SQL 分组", "SQL HAVING",
            "分组维度", "维度关联", "时间窗口", "业务筛选");

    private MetricLineageProjection() {}

    public record Context(String role, String expression, String task, String href, List<String> inputs, int relationships) {}
    public record Projection(Graph graph, List<Context> contexts, int fullNodeCount, int fullEdgeCount,
                             int hiddenNodeCount, int hiddenEdgeCount, String mode) {}

    public static Projection project(Graph complete, String mode) {
        if (complete == null) throw new IllegalArgumentException("血缘图不能为空");
        if (!"direct".equals(mode) && !"full".equals(mode)) throw new IllegalArgumentException("血缘展示方式无效");
        List<Context> contexts = contexts(complete.edges());
        if ("full".equals(mode)) return new Projection(complete, contexts, complete.nodes().size(), complete.edges().size(), 0, 0, mode);

        Set<String> roots = new LinkedHashSet<>();
        for (Node node : complete.nodes()) if (node.level() == 0) roots.add(node.key());
        Map<String, Integer> selected = new LinkedHashMap<>();
        roots.forEach(key -> selected.put(key, 0));
        Set<String> frontier = new LinkedHashSet<>(roots);
        for (int depth = 1; depth <= MAX_DEPTH && !frontier.isEmpty(); depth++) {
            Set<String> next = new LinkedHashSet<>();
            for (Edge edge : complete.edges()) if (direct(edge) && frontier.contains(edge.to()) && !roots.contains(edge.from())
                    && selected.getOrDefault(edge.from(), 1) > -depth) {
                selected.put(edge.from(), -depth);
                next.add(edge.from());
            }
            frontier = next;
        }
        List<Node> nodes = complete.nodes().stream().filter(node -> selected.containsKey(node.key()))
                .map(node -> new Node(node.key(), node.label(), node.detail(), node.kind(), node.href(), node.status(), selected.get(node.key())))
                .sorted(Comparator.comparingInt(Node::level).thenComparing(Node::key)).toList();
        List<Edge> edges = complete.edges().stream().filter(edge -> direct(edge)
                && selected.containsKey(edge.from()) && selected.containsKey(edge.to())).toList();
        Graph graph = new Graph(nodes, edges, complete.outputs(), complete.warnings(), complete.snapshot(), complete.truncated());
        return new Projection(graph, contexts, complete.nodes().size(), complete.edges().size(),
                complete.nodes().size() - nodes.size(), complete.edges().size() - edges.size(), mode);
    }

    private static boolean direct(Edge edge) {
        if ("SQL 行集".equals(edge.role())) return edge.toLabel() != null && edge.toLabel().endsWith(".*");
        if ("同步行集".equals(edge.role()) || "行数".equals(edge.role()) || "行数聚合".equals(edge.role())) return true;
        if (edge.role() == null) return true;
        return !INDIRECT.contains(edge.role());
    }

    private static List<Context> contexts(List<Edge> edges) {
        Map<ContextKey, ContextBuilder> grouped = new LinkedHashMap<>();
        for (Edge edge : edges) if (!direct(edge)) {
            ContextKey key = new ContextKey(text(edge.role()), text(edge.expression()), text(edge.task()), text(edge.href()));
            ContextBuilder builder = grouped.computeIfAbsent(key, ignored -> new ContextBuilder());
            builder.inputs.add(text(edge.fromLabel()));
            builder.relationships++;
        }
        return grouped.entrySet().stream().map(entry -> new Context(entry.getKey().role, entry.getKey().expression,
                entry.getKey().task, entry.getKey().href, List.copyOf(entry.getValue().inputs), entry.getValue().relationships)).toList();
    }

    private static String text(String value) { return value == null ? "" : value; }
    private record ContextKey(String role, String expression, String task, String href) {}
    private static final class ContextBuilder {
        final Set<String> inputs = new LinkedHashSet<>();
        int relationships;
    }
}
