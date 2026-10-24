package com.litedata.metric;

import com.litedata.governance.ColumnLineageSql;
import com.litedata.governance.LineageSql;
import com.litedata.model.*;
import com.litedata.store.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static com.litedata.metric.MetricLineage.*;

@Service
public class MetricLineageService {
    private static final int MAX_NODES = 160, MAX_EDGES = 400, MAX_DEPTH = 12;
    private final MetricMapper metrics;
    private final ModelMapper models;
    private final MetadataMapper metadata;
    private final LineageMapper tasks;
    private final PlatformMapper platform;
    private final ObjectMapper json;

    public MetricLineageService(MetricMapper metrics, ModelMapper models, MetadataMapper metadata,
                                LineageMapper tasks, PlatformMapper platform, ObjectMapper json) {
        this.metrics = metrics; this.models = models; this.metadata = metadata;
        this.tasks = tasks; this.platform = platform; this.json = json;
    }

    /** Called inside the metric save transaction; parsing never runs a business query. */
    public Graph capture(MetricDefinition definition) { return new Builder().metric(definition, true); }

    @Transactional(readOnly = true)
    public Graph view(MetricDefinition definition, String field) {
        Graph graph = definition.getLineageSnapshot();
        if (graph == null) graph = new Builder().metric(definition, false);
        var outputs = graph.outputs();
        if (!field.equals("all") && outputs.stream().noneMatch(o -> o.name().equals(field)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指标输出字段不存在");
        var selected = new LinkedHashMap<String,Integer>();
        outputs.stream().filter(o -> field.equals("all") || o.name().equals(field)).forEach(o -> selected.put(o.key(), 0));
        Set<String> roots = Set.copyOf(selected.keySet());
        Set<String> frontier = new LinkedHashSet<>(selected.keySet());
        boolean truncated = graph.truncated();
        for (int depth = 1; depth <= MAX_DEPTH && !frontier.isEmpty(); depth++) {
            var next = new LinkedHashSet<String>();
            for (var edge : graph.edges()) if (frontier.contains(edge.to()) && !roots.contains(edge.from())
                    && selected.getOrDefault(edge.from(), 1) > -depth) {
                selected.put(edge.from(), -depth); next.add(edge.from());
            }
            frontier = next;
        }
        if (!frontier.isEmpty()) for (var edge : graph.edges())
            if (frontier.contains(edge.to()) && !selected.containsKey(edge.from())) truncated = true;
        var nodes = graph.nodes().stream().filter(n -> selected.containsKey(n.key()))
                .map(n -> new Node(n.key(), n.label(), n.detail(), n.kind(), n.href(), n.status(), selected.get(n.key())))
                .sorted(Comparator.comparingInt(Node::level).thenComparing(Node::key)).toList();
        var edges = graph.edges().stream().filter(e -> selected.containsKey(e.from()) && selected.containsKey(e.to())).toList();
        return new Graph(nodes, edges, outputs, graph.warnings(), graph.snapshot(), truncated);
    }

    /** Archiving adds a version without changing its recorded source evidence. */
    public Graph reversion(Graph graph, MetricDefinition definition) {
        var replacement = new HashMap<String,String>();
        for (var output : graph.outputs()) replacement.put(output.key(), metricKey(definition, output.name()));
        var nodes = graph.nodes().stream().map(n -> replacement.containsKey(n.key())
                ? new Node(replacement.get(n.key()), n.label(), definition.getName() + " · v" + definition.getVersion(), n.kind(),
                    versionHref(definition) + "/lineage?field=" + n.key().substring(n.key().lastIndexOf(':') + 1), "指标版本 v" + definition.getVersion(), 0)
                : n).toList();
        var edges = graph.edges().stream().map(e -> new Edge(replacement.getOrDefault(e.from(), e.from()), replacement.getOrDefault(e.to(), e.to()),
                e.fromLabel(), e.toLabel(), e.role(), e.expression(), e.task(), e.href())).toList();
        var outputs = graph.outputs().stream().map(o -> new Output(o.name(), o.label(), replacement.get(o.key()))).toList();
        return new Graph(nodes, edges, outputs, graph.warnings(), graph.snapshot(), graph.truncated());
    }

    private static String metricKey(MetricDefinition m, String field) { return "metric:" + m.getId() + ":v" + m.getVersion() + ":" + field; }
    private static String versionHref(MetricDefinition m) { return "/metrics/" + m.getId() + "/versions/" + m.getVersion(); }
    private static List<Output> outputs(MetricDefinition metric) {
        var result = new ArrayList<Output>(); result.add(new Output("metric_value", "指标值 metric_value", metricKey(metric, "metric_value")));
        for (String dimension : MetricSql.dimensions(metric.getDimensions())) result.add(new Output(dimension, "维度 " + dimension, metricKey(metric, dimension)));
        return List.copyOf(result);
    }
    private record Writer(String name, String href, String sql, Pipeline sync) {}

    private final class Builder {
        final Map<String,Node> nodes = new LinkedHashMap<>();
        final Set<Edge> edges = new LinkedHashSet<>();
        final Set<String> warnings = new LinkedHashSet<>();
        final Map<String,Graph> built = new HashMap<>();
        final Set<String> metricPath = new HashSet<>();
        final Map<String,List<Writer>> writers = new HashMap<>();
        final Map<String,List<String>> schemas = new HashMap<>();
        final Map<String,String> evidenceLabels = new HashMap<>();
        final Map<Writer,ColumnLineageSql.Result> parsed = new HashMap<>();
        boolean loaded, truncated;

        Graph metric(MetricDefinition metric, boolean snapshot) {
            var output = outputs(metric);
            if (nodes.size() >= MAX_NODES) { truncated = true; return graph(output, snapshot); }
            for (var item : output) add(new Node(item.key(), metric.getCode() + "." + item.name(),
                    metric.getName() + " · " + metric.getType().getLabel() + " · v" + metric.getVersion(), "METRIC",
                    versionHref(metric) + "/lineage?field=" + item.name(), "指标版本 v" + metric.getVersion(), 0));
            if (!snapshot) warn("版本 " + metric.getCode() + " v" + metric.getVersion() + " 未保存字段血缘快照；指标依赖按固定版本重建，表字段链路按当前配置补充，不能作为当时任务配置的证明。");
            String identity = metric.getId() + ":" + metric.getVersion();
            if (metricPath.size() >= MAX_DEPTH || !metricPath.add(identity)) {
                truncated = true; warn("指标依赖过深或存在异常循环，已在此处停止。"); return graph(output, snapshot);
            }
            try {
                if (metric.getType() == MetricType.COMPOSITE || metric.getType() == MetricType.DERIVED) {
                    for (var dependency : metric.getDependencies()) {
                        var upstream = upstream(dependency);
                        if (upstream == null) continue;
                        merge(upstream);
                        var value = upstream.outputs().stream().filter(o -> o.name().equals("metric_value")).findFirst().orElse(null);
                        if (value != null) edge(value.key(), metricKey(metric, "metric_value"),
                                metric.getType() == MetricType.COMPOSITE ? "复合公式" : "原子指标", metric.getType() == MetricType.COMPOSITE ? metric.getFormula() : "继承基础聚合", dependency.code() + " v" + dependency.version(), "/metrics/" + dependency.id() + "/versions/" + dependency.version());
                        for (var item : upstream.outputs()) if (!item.name().equals("metric_value")) {
                            edge(item.key(), metricKey(metric, item.name()), "维度输出", "继承维度 " + item.name(), dependency.code(), "");
                            if (metric.getType() == MetricType.COMPOSITE) edge(item.key(), metricKey(metric, "metric_value"), "维度关联", "按共享维度关联各输入", dependency.code(), "");
                        }
                    }
                }
                if (metric.getType() != MetricType.COMPOSITE) {
                    String table = sourceTable(metric);
                    String context = "evidence:" + identity + ":";
                    evidenceLabels.put(context, metric.getCode() + " v" + metric.getVersion());
                    String root = metricKey(metric, "metric_value");
                    if (metric.getType() == MetricType.ATOMIC) {
                        String column = field(context, table, metric.getMeasureColumn(), new LinkedHashSet<>(), 0);
                        edge(column, root, metric.getMeasureColumn().equals("*") ? "行数聚合" : "聚合输入", metric.getAggregation() + "(" + metric.getMeasureColumn() + ")", "原子指标定义", "");
                        for (String dimension : MetricSql.dimensions(metric.getDimensions())) {
                            String source = field(context, table, dimension, new LinkedHashSet<>(), 0);
                            edge(source, root, "分组维度", "GROUP BY " + dimension, "原子指标定义", "");
                            edge(source, metricKey(metric, dimension), "维度输出", dimension, "原子指标定义", "");
                        }
                    } else {
                        String time = field(context, table, metric.getTimeColumn(), new LinkedHashSet<>(), 0);
                        for (var item : output) edge(time, item.key(), "时间窗口", "最近 " + metric.getWindowDays() + " 天，截止数据库当前时间，不含未来", "派生指标定义", "");
                        for (String filter : MetricExpressions.filterColumns(metric.getFilterExpression())) {
                            String source = field(context, table, filter, new LinkedHashSet<>(), 0);
                            for (var item : output) edge(source, item.key(), "业务筛选", metric.getFilterExpression(), "派生指标定义", "");
                        }
                    }
                }
            } catch (IllegalArgumentException exception) {
                warn("指标 " + metric.getCode() + " 的部分字段无法可靠解析，已保留可确认的依赖；请核对保存的 SQL 和模型。");
            } finally { metricPath.remove(identity); }
            return graph(output, snapshot);
        }

        Graph upstream(MetricDependency dependency) {
            String key = dependency.id() + ":" + dependency.version();
            if (built.containsKey(key)) return built.get(key);
            var version = metrics.version(dependency.id(), dependency.version());
            if (version == null) { warn("上游指标 " + dependency.code() + " v" + dependency.version() + " 缺少版本记录，无法继续追溯。"); return null; }
            var metric = json.readValue(version.definitionJson(), MetricDefinition.class);
            Graph graph = metric.getLineageSnapshot();
            if (graph == null) graph = metric(metric, false);
            built.put(key, graph); return graph;
        }
        String sourceTable(MetricDefinition metric) {
            // Atomic and derived SQL are generated by MetricSql and read exactly one physical table.
            try {
                var sql = net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(metric.getSqlText());
                var tables = new net.sf.jsqlparser.util.TablesNamesFinder<Void>().getTables(sql);
                if (tables.size() != 1) throw new IllegalArgumentException();
                String name = tables.iterator().next().replace("`", "").toLowerCase(Locale.ROOT);
                return MetricSql.identifier(name.startsWith("warehouse.") ? name.substring(10) : name);
            } catch (Exception exception) { throw new IllegalArgumentException("无法确定指标来源表"); }
        }

        String field(String context, String table, String column, Set<String> path, int depth) {
            String key = context + "warehouse." + table + "." + column;
            if (nodes.containsKey(key)) return key;
            if (nodes.size() >= MAX_NODES) { truncated = true; return key; }
            String label = "warehouse." + table + "." + column;
            var asset = metadata.find("doris", "warehouse", table);
            var model = models.modelByTable(table);
            String href = asset != null ? "/metadata/" + asset.getId() : model != null ? "/models/" + model.getId() : "";
            String detail = (column.equals("*") ? "整表行集，不代表某个具体字段" : "Doris 数仓字段") + " · 依据 " + evidenceLabels.getOrDefault(context, "指标快照");
            add(new Node(key, label, detail, "WAREHOUSE_FIELD", href, "配置来源", 0));
            if (depth >= MAX_DEPTH || !path.add(table + "." + column)) { truncated = true; warn("表字段追溯达到层数上限或发现循环，已停止展开：" + label); return key; }
            loadWriters();
            var choices = writers.getOrDefault(table, List.of());
            if (choices.size() != 1) {
                boundary(key, choices.isEmpty() ? "未登记上游写入配置" : "存在多个写入配置，来源待确认");
                warn(label + (choices.isEmpty() ? " 未登记上游写入配置，追溯止于该字段。" : " 存在多个写入配置，不能确定唯一来源，已停止推断。"));
            } else {
                var writer = choices.getFirst();
                try {
                    if (writer.sync() != null) sync(context, key, column, writer);
                    else {
                        var result = parsed.computeIfAbsent(writer, w -> ColumnLineageSql.parse(w.sql(), schema(table), this::schema));
                        if (!result.target().equals(table)) throw new IllegalArgumentException();
                        for (String warning : result.warnings()) warn(writer.name() + "：" + warning);
                        if (column.equals("*")) {
                            for (String source : LineageSql.parse(writer.sql()).sources()) {
                                if (path.contains(source + ".*")) { warn("行集依赖存在循环，已停止展开：" + source); continue; }
                                String input = field(context, source, "*", new LinkedHashSet<>(path), depth + 1);
                                edge(input, key, "SQL 行集", "表级行集依赖，行数还受关联、筛选和聚合影响", writer.name(), writer.href());
                            }
                            var conditions = result.columns().stream().flatMap(p -> p.inputs().stream()).filter(i -> !i.role().equals("表达式") && !i.role().equals("行数")).distinct().toList();
                            inputs(context, key, conditions, writer, path, depth, "SELECT 行集");
                        } else {
                            var projection = result.columns().stream().filter(p -> p.column().equals(column)).findFirst().orElseThrow(IllegalArgumentException::new);
                            inputs(context, key, projection.inputs(), writer, path, depth, projection.expression());
                            if (projection.inputs().isEmpty()) boundary(key, "SQL 常量表达式：" + projection.expression());
                        }
                    }
                } catch (IllegalArgumentException exception) {
                    boundary(key, "SQL 映射无法确定");
                    warn(writer.name() + " 的字段映射无法确定（复杂 SQL、缺失结构或歧义），追溯止于 " + label + "。");
                }
            }
            path.remove(table + "." + column);
            return key;
        }
        void inputs(String context, String target, List<ColumnLineageSql.Input> inputs, Writer writer, Set<String> path, int depth, String expression) {
            for (var input : inputs) {
                if (path.contains(input.table() + "." + input.column())) { warn("字段依赖存在循环，已停止展开：" + input.table() + "." + input.column()); continue; }
                String source = field(context, input.table(), input.column(), new LinkedHashSet<>(path), depth + 1);
                edge(source, target, input.role(), input.expression().isBlank() ? expression : input.expression(), writer.name(), writer.href());
            }
        }
        void sync(String context, String target, String column, Writer writer) {
            var pipeline = writer.sync(); var source = platform.source(pipeline.getSourceId());
            if (source == null) { boundary(target, "数据源已不存在"); warn("同步数据源缺失，无法继续追溯。"); return; }
            String catalog;
            try { catalog = URI.create(source.getJdbcUrl().substring(5)).getPath().substring(1); }
            catch (RuntimeException exception) { boundary(target, "源库信息无法确定"); return; }
            var asset = metadata.find("source:" + source.getId(), catalog, pipeline.getSourceTable());
            boolean found = asset != null && asset.isPresent() && (column.equals("*") || metadata.columns(asset.getId()).stream().anyMatch(c -> c.name().equals(column)));
            if (asset != null && asset.isPresent() && !found) {
                boundary(target, "源表当前未发现同名字段"); warn("源表元数据中未发现字段 " + column + "，同步字段映射需确认。"); return;
            }
            String key = context + "source:" + source.getId() + ":" + catalog + "." + pipeline.getSourceTable() + "." + column;
            add(new Node(key, catalog + "." + pipeline.getSourceTable() + "." + column, source.getName() + " · 依据 " + evidenceLabels.getOrDefault(context, "指标快照"), "SOURCE_FIELD",
                    asset == null ? "" : "/metadata/" + asset.getId(), found ? "已采集源结构，按同名同步配置" : "按同名同步配置，源结构未采集或已过期", 0));
            edge(key, target, column.equals("*") ? "同步行集" : "同步同名映射", "DataX 源列与 Doris 写入列使用相同字段名；增量水位 " + pipeline.getWatermarkColumn(), writer.name(), writer.href());
        }
        List<String> schema(String table) {
            return schemas.computeIfAbsent(table, name -> {
                var model = models.modelByTable(name);
                if (model != null) return models.fields(model.getId()).stream().map(ModelField::getName).toList();
                var asset = metadata.find("doris", "warehouse", name);
                return asset != null && asset.isPresent() ? metadata.columns(asset.getId()).stream().map(c -> c.name()).toList() : List.of();
            });
        }
        void loadWriters() {
            if (loaded) return; loaded = true;
            var sqlTasks = tasks.tasks(); var pipelines = tasks.pipelines();
            if (sqlTasks.size() > 5000 || pipelines.size() > 5000) {
                truncated = true; warn("任务配置超过 5000 条的追溯上限，未展开任务字段来源。"); return;
            }
            for (var task : sqlTasks) writers.computeIfAbsent(task.getTargetTable(), t -> new ArrayList<>()).add(
                    new Writer(task.getName() + " v" + task.getVersion(), "/transforms/" + task.getId(), task.getSqlText(), null));
            for (int index = 0; index < pipelines.size(); index++) {
                var pipeline = pipelines.get(index);
                String href = "/pipelines?pageNum=" + ((pipelines.size() - index - 1) / 20 + 1) + "#pipeline-" + pipeline.getId();
                var writer = new Writer(pipeline.getName(), href, "", pipeline);
                writers.computeIfAbsent(pipeline.getTargetTable(), t -> new ArrayList<>()).add(writer);
                if (pipeline.getTransformSql() != null && !pipeline.getTransformSql().isBlank()) try {
                    String target = LineageSql.parse(pipeline.getTransformSql()).target();
                    writers.computeIfAbsent(target, t -> new ArrayList<>()).add(new Writer(pipeline.getName() + "（管道加工）", writer.href(), pipeline.getTransformSql(), null));
                } catch (IllegalArgumentException ignored) { warn("有管道加工 SQL 无法解析，相关字段链路可能不完整。"); }
            }
        }
        void boundary(String key, String status) {
            var node = nodes.get(key); if (node != null) nodes.put(key, new Node(node.key(), node.label(), node.detail(), "BOUNDARY", node.href(), status, 0));
        }
        void add(Node node) {
            if (nodes.containsKey(node.key())) return;
            if (nodes.size() >= MAX_NODES) { truncated = true; return; }
            nodes.put(node.key(), node);
        }
        void edge(String from, String to, String role, String expression, String task, String href) {
            if (!nodes.containsKey(from) || !nodes.containsKey(to)) { truncated = true; return; }
            if (edges.size() >= MAX_EDGES) { truncated = true; return; }
            edges.add(new Edge(from, to, nodes.get(from).label(), nodes.get(to).label(), role, expression, task, href));
        }
        void merge(Graph graph) {
            graph.nodes().forEach(this::add);
            for (var edge : graph.edges()) edge(edge.from(), edge.to(), edge.role(), edge.expression(), edge.task(), edge.href());
            graph.warnings().forEach(this::warn); truncated |= graph.truncated();
        }
        void warn(String warning) {
            if (warnings.size() < 30) warnings.add(warning);
            else truncated = true;
        }
        Graph graph(List<Output> output, boolean snapshot) {
            return new Graph(List.copyOf(nodes.values()), List.copyOf(edges), output, List.copyOf(warnings), snapshot, truncated);
        }
    }
}
