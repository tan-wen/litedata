package com.litedata.metric;

import java.util.List;

/** Configuration evidence captured with a metric version; no business data or credentials. */
public final class MetricLineage {
    private MetricLineage() {}
    public record Output(String name, String label, String key) {}
    public record Node(String key, String label, String detail, String kind, String href, String status, int level) {}
    public record Edge(String from, String to, String fromLabel, String toLabel, String role,
                       String expression, String task, String href) {}
    public record Graph(List<Node> nodes, List<Edge> edges, List<Output> outputs, List<String> warnings,
                        boolean snapshot, boolean truncated) {}
}
