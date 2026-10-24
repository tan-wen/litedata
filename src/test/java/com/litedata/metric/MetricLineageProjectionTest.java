package com.litedata.metric;

import static com.litedata.metric.MetricLineage.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MetricLineageProjectionTest {
    @Test void directViewHidesConditionsButKeepsMeasureDimensionAndFormula() {
        Node root = node("metric", "metric_value", 0), dimension = node("dimension", "enterprise_id", 0);
        Graph complete = graph(List.of(root, dimension, node("measure", "steam_tons", -1), node("formula", "derived", -1),
                node("filter", "product_code", -1), node("join", "enterprise_id", -1), node("rows", "warehouse.dwd.*", -1)), List.of(
                edge("measure", "metric", "聚合输入"), edge("formula", "metric", "复合公式"), edge("filter", "metric", "SQL 筛选"),
                edge("join", "dimension", "SQL 关联"), edge("rows", "dimension", "SQL 行集")));
        var direct = MetricLineageProjection.project(complete, "direct");
        assertThat(labels(direct.graph())).containsExactlyInAnyOrder("metric_value", "enterprise_id", "steam_tons", "derived");
        assertThat(labels(direct.graph())).doesNotContain("product_code", "warehouse.dwd.*");
        assertThat(direct.contexts()).extracting(MetricLineageProjection.Context::role).containsExactlyInAnyOrder("SQL 筛选", "SQL 关联", "SQL 行集");
        assertThat(direct.hiddenNodeCount()).isEqualTo(3);
    }

    @Test void countKeepsTheTableRowSetAndSyncPath() {
        Graph complete = graph(List.of(node("count", "metric_value", 0), node("dwdRows", "warehouse.dwd.*", -1),
                node("odsRows", "warehouse.ods.*", -2), node("sourceRows", "demo.orders.*", -3)), List.of(
                edge("dwdRows", "count", "warehouse.dwd.*", "metric_value", "行数聚合"),
                edge("odsRows", "dwdRows", "warehouse.ods.*", "warehouse.dwd.*", "SQL 行集"),
                edge("sourceRows", "odsRows", "demo.orders.*", "warehouse.ods.*", "同步行集")));
        var direct = MetricLineageProjection.project(complete, "direct").graph();
        assertThat(labels(direct)).containsExactlyInAnyOrder("metric_value", "warehouse.dwd.*", "warehouse.ods.*", "demo.orders.*");
        assertThat(direct.edges()).extracting(Edge::role).containsExactly("行数聚合", "SQL 行集", "同步行集");
    }

    @Test void directDimensionMappingAndUnknownEvidenceRemainWhileContextIsDeduplicated() {
        Graph complete = graph(List.of(node("dimension", "enterprise_id", 0), node("mapped", "enterprise_id", -1),
                node("unknown", "custom_evidence", -1), node("nullRole", "legacy_evidence", -1),
                node("group", "period", -1), node("groupDimension", "period_dimension", -1), node("join", "enterprise_code", -1), node("time", "period_start", -1),
                node("filterA", "status", -1), node("filterB", "region", -1)), List.of(
                edge("mapped", "dimension", "维度输出"), edge("unknown", "dimension", "未知证据"),
                new Edge("nullRole", "dimension", "legacy_evidence", "enterprise_id", null, null, null, null),
                edge("group", "dimension", "SQL 分组"), edge("groupDimension", "dimension", "分组维度"),
                edge("join", "dimension", "维度关联"), edge("time", "dimension", "时间窗口"),
                new Edge("filterA", "dimension", "status", "enterprise_id", "SQL 筛选", "status = '正常'", "sql-task", "/tasks/1"),
                new Edge("filterB", "dimension", "region", "enterprise_id", "SQL 筛选", "status = '正常'", "sql-task", "/tasks/1")));

        var direct = MetricLineageProjection.project(complete, "direct");
        assertThat(labels(direct.graph())).containsExactlyInAnyOrder("enterprise_id", "enterprise_id", "custom_evidence", "legacy_evidence");
        assertThat(labels(direct.graph())).doesNotContain("period", "period_dimension", "enterprise_code", "period_start", "status", "region");
        assertThat(direct.contexts()).extracting(MetricLineageProjection.Context::role)
                .containsExactlyInAnyOrder("SQL 分组", "分组维度", "维度关联", "时间窗口", "SQL 筛选");
        assertThat(direct.contexts()).filteredOn(context -> context.role().equals("SQL 筛选")).singleElement().satisfies(context -> {
            assertThat(context.inputs()).containsExactly("status", "region");
            assertThat(context.relationships()).isEqualTo(2);
        });
    }

    @Test void caseExpressionBoundariesWarningsCyclesAndSharedAncestorsArePreserved() {
        Node rootA = node("a", "a", 0), rootB = node("b", "b", 0), shared = node("shared", "shared", -1), boundary = new Node("boundary", "unknown", "missing source", "BOUNDARY", "", "未登记", -2);
        Graph complete = new Graph(List.of(rootA, rootB, shared, boundary), List.of(edge("shared", "a", "表达式", "CASE WHEN active THEN amount END"), edge("shared", "b", "表达式"),
                edge("boundary", "shared", "表达式"), edge("shared", "boundary", "表达式")), List.of(), List.of("missing mapping"), true, false);
        var direct = MetricLineageProjection.project(complete, "direct").graph();
        assertThat(labels(direct)).containsExactlyInAnyOrder("a", "b", "shared", "unknown");
        assertThat(direct.nodes()).anySatisfy(n -> { assertThat(n.kind()).isEqualTo("BOUNDARY"); assertThat(n.status()).isEqualTo("未登记"); });
        assertThat(direct.warnings()).containsExactly("missing mapping");
        assertThat(direct.nodes()).allSatisfy(n -> assertThat(n.level()).isBetween(-12, 0));
    }

    @Test void fullKeepsTheSameGraphAndContextsDoNotMutateSnapshot() {
        Graph complete = new Graph(List.of(node("root", "root", 0), node("where", "status", -1)), List.of(edge("where", "root", "status", "root", "SQL 筛选")),
                List.of(new Output("metric_value", "指标值", "root")), List.of("warning"), true, false);
        var full = MetricLineageProjection.project(complete, "full");
        assertThat(full.graph()).isSameAs(complete);
        assertThat(full.hiddenNodeCount()).isZero();
        assertThat(full.contexts()).singleElement().satisfies(context -> {
            assertThat(context.inputs()).containsExactly("status"); assertThat(context.relationships()).isOne();
        });
        assertThat(MetricLineageProjection.project(complete, "direct").graph().nodes()).hasSize(1);
        assertThat(complete.nodes()).hasSize(2);
    }

    @Test void rejectsUnknownMode() {
        assertThatIllegalArgumentException().isThrownBy(() -> MetricLineageProjection.project(graph(List.of(), List.of()), "compact"));
    }

    private static Graph graph(List<Node> nodes, List<Edge> edges) { return new Graph(nodes, edges, List.of(), List.of(), true, false); }
    private static Node node(String key, String label, int level) { return new Node(key, label, "detail", "WAREHOUSE_FIELD", "", "configured", level); }
    private static Edge edge(String from, String to, String role) { return new Edge(from, to, from, to, role, role + " expression", "task", "/task"); }
    private static Edge edge(String from, String to, String fromLabel, String toLabel, String role) {
        return new Edge(from, to, fromLabel, toLabel, role, role + " expression", "task", "/task");
    }
    private static Edge edge(String from, String to, String role, String expression) { return new Edge(from, to, from, to, role, expression, "task", "/task"); }
    private static List<String> labels(Graph graph) { return graph.nodes().stream().map(Node::label).toList(); }
}
