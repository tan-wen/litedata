package com.litedata.metric;

import com.litedata.model.DataModel;
import com.litedata.service.QueryPage;
import com.litedata.store.*;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MetricService {
    private final MetricMapper mapper;
    private final ModelMapper models;
    private final PlatformMapper platform;
    private final ObjectMapper json;
    private final MetricLineageService lineage;
    public MetricService(MetricMapper mapper, ModelMapper models, PlatformMapper platform, ObjectMapper json, MetricLineageService lineage) {
        this.mapper = mapper; this.models = models; this.platform = platform; this.json = json;
        this.lineage = lineage;
    }
    public record Option(Long id, String code, String name, MetricType type, int version) {}
    public record ModelOption(Long id, String name, String tableName) {}
    public String keyword(String value) {
        String query = Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
        if (query.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索词最长 100 字符");
        return query;
    }
    public String type(String value) {
        if (value == null || value.isBlank()) return "";
        try { return MetricType.valueOf(value).name(); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指标类型无效"); }
    }
    public long count(String keyword, String type) { return mapper.count(keyword(keyword), type(type)); }
    public List<MetricDefinition> list(String keyword, String type, QueryPage page) {
        return mapper.page(keyword(keyword), type(type), page.pageSize(), page.offset());
    }
    public List<Option> candidates(Long exclude) {
        return mapper.candidates().stream().filter(m -> !Objects.equals(m.getId(), exclude)).map(MetricService::option).toList();
    }
    public List<ModelOption> modelOptions() { return models.models().stream().map(m -> new ModelOption(m.getId(), m.getName(), m.getTableName())).toList(); }
    public List<Option> dependents(long id) { return mapper.dependents(id).stream().map(MetricService::option).toList(); }
    @Transactional(readOnly = true)
    public MetricDefinition require(long id) {
        var metric = active(mapper.metric(id));
        return historyDefinition(id, metric.getVersion());
    }
    public MetricDefinition historyDefinition(long id, int version) {
        var snapshot = mapper.version(id, version);
        if (snapshot == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "指标版本不存在");
        return json.readValue(snapshot.definitionJson(), MetricDefinition.class);
    }
    public long versionCount(long id) { return mapper.versionCount(id); }
    public List<MetricVersion> versions(long id, QueryPage page) {
        return mapper.versions(id, page.pageSize(), page.offset()).stream().map(v -> new MetricVersion(v.metricId(), v.version(), v.actor(),
                v.createdAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime(), v.definitionJson(), v.sqlText())).toList();
    }
    @Transactional
    public long save(Long id, MetricDefinition input, String actor) {
        mapper.lockGraph();
        common(input);
        if (id != null) {
            var current = active(mapper.metric(id));
            checkVersion(current, input.getVersion());
            if (!current.getCode().equals(input.getCode()) || current.getType() != input.getType())
                throw new IllegalArgumentException("指标编码和类型保存后不可更改，请新建指标");
        }
        input.setId(id); input.setArchived(false); input.setLineageSnapshot(null);
        var upstreams = new ArrayList<MetricDefinition>();
        switch (input.getType()) {
            case ATOMIC -> {
                var model = model(input.getModelId());
                input.setBaseMetricId(null); input.setFormula(""); input.setFilterExpression(""); input.setWindowDays(7);
                input.setModelVersion(model.getVersion());
                input.setSqlText(MetricSql.atomic(input, model, ""));
            }
            case DERIVED -> {
                if (input.getBaseMetricId() == null) throw new IllegalArgumentException("请选择一个原子指标作为派生来源");
                var available = mapper.metric(input.getBaseMetricId());
                if (available == null || available.isArchived()) throw new IllegalArgumentException("来源原子指标不存在或已归档，请重新选择");
                var base = require(input.getBaseMetricId());
                if (base.getType() != MetricType.ATOMIC) throw new IllegalArgumentException("派生指标只能引用原子指标");
                if (input.getWindowDays() < 1 || input.getWindowDays() > 3660) throw new IllegalArgumentException("滚动窗口须在 1 到 3660 天之间");
                if (base.getTimeColumn().isBlank()) throw new IllegalArgumentException("原子指标未定义时间字段，无法配置滚动窗口");
                var model = model(base.getModelId());
                if (!Objects.equals(base.getModelVersion(), model.getVersion())) throw new IllegalArgumentException("来源模型已变化，请先重新保存原子指标以确认口径");
                input.setModelId(base.getModelId()); input.setModelVersion(base.getModelVersion());
                input.setAggregation(base.getAggregation()); input.setMeasureColumn(base.getMeasureColumn());
                input.setTimeColumn(base.getTimeColumn()); input.setDimensions(base.getDimensions()); input.setFormula("");
                var columns = new HashSet<String>(); model.getFields().forEach(f -> columns.add(f.getName()));
                String condition = MetricExpressions.filter(input.getFilterExpression(), columns);
                String time = "`" + MetricSql.identifier(base.getTimeColumn()) + "`";
                String window = time + " >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL " + input.getWindowDays() + " DAY) AND " + time + " < CURRENT_TIMESTAMP";
                input.setSqlText(MetricSql.atomic(input, model, window + (condition.isBlank() ? "" : " AND (" + condition + ")")));
                upstreams.add(base);
            }
            case COMPOSITE -> {
                for (String code : MetricExpressions.references(input.getFormula())) {
                    var upstream = mapper.byCode(code);
                    if (upstream == null) throw new IllegalArgumentException("公式引用的指标不存在或已归档：" + code);
                    upstreams.add(require(upstream.getId()));
                }
                String dimensions = upstreams.getFirst().getDimensions();
                for (var upstream : upstreams) if (!dimensions.equals(upstream.getDimensions()))
                    throw new IllegalArgumentException("复合指标的所有上游须有相同的统计维度");
                input.setDimensions(dimensions); input.setModelId(null); input.setModelVersion(null); input.setBaseMetricId(null);
                input.setAggregation(""); input.setMeasureColumn(""); input.setTimeColumn(""); input.setFilterExpression(""); input.setWindowDays(7);
                input.setSqlText(MetricSql.composite(input, upstreams));
            }
        }
        for (var upstream : upstreams) if (id != null && reaches(upstream.getId(), id)) throw new IllegalArgumentException("指标依赖不能包含自身或形成循环");
        if (input.getSqlText().length() > 20000) throw new IllegalArgumentException("生成 SQL 超过 20000 字符，请减少公式引用或嵌套层数");
        input.setDependencies(upstreams.stream().map(m -> new MetricDependency(m.getId(), m.getCode(), m.getName(), m.getType(), m.getVersion())).toList());
        if (id == null) { input.setVersion(1); mapper.insert(input); id = input.getId(); }
        else { mapper.update(input); input.setVersion(input.getVersion() + 1); mapper.clearDependencies(id); }
        for (var upstream : upstreams) mapper.dependency(id, upstream.getId(), upstream.getVersion());
        snapshot(input, actor); platform.audit(actor, "METRIC_SAVE", id + ":v" + input.getVersion(), "SUCCESS");
        return id;
    }
    @Transactional
    public void archive(long id, int version, String actor) {
        mapper.lockGraph(); var metric = active(mapper.metric(id)); checkVersion(metric, version);
        if (!mapper.dependents(id).isEmpty()) throw new IllegalArgumentException("指标仍被下游引用，请先修改或归档下游指标");
        var snapshot = historyDefinition(id, version); snapshot.setArchived(true); snapshot.setVersion(version + 1);
        mapper.archive(id); snapshot(snapshot, actor);
        platform.audit(actor, "METRIC_ARCHIVE", id + ":v" + snapshot.getVersion(), "SUCCESS");
    }
    private void snapshot(MetricDefinition metric, String actor) {
        metric.setLineageSnapshot(metric.getLineageSnapshot() == null ? lineage.capture(metric) : lineage.reversion(metric.getLineageSnapshot(), metric));
        mapper.snapshot(metric.getId(), metric.getVersion(), actor, json.writeValueAsString(metric), metric.getSqlText());
    }
    private DataModel model(Long id) {
        if (id == null) throw new IllegalArgumentException("请选择来源逻辑模型");
        var model = models.lockModel(id);
        if (model == null) throw new IllegalArgumentException("来源模型不存在或已归档");
        model.setFields(models.fields(id)); return model;
    }
    private boolean reaches(long upstream, long target) {
        var pending = new ArrayDeque<Long>(); var visited = new HashSet<Long>(); pending.add(upstream);
        while (!pending.isEmpty()) {
            long next = pending.removeFirst();
            if (next == target) return true;
            if (visited.add(next)) pending.addAll(mapper.upstreams(next));
        }
        return false;
    }
    private static Option option(MetricDefinition m) { return new Option(m.getId(), m.getCode(), m.getName(), m.getType(), m.getVersion()); }
    private static MetricDefinition active(MetricDefinition metric) {
        if (metric == null || metric.isArchived()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "指标不存在或已归档");
        return metric;
    }
    private static void checkVersion(MetricDefinition metric, int version) {
        if (metric.getVersion() != version) throw new IllegalArgumentException("指标已变化，请刷新页面后重试");
    }
    private static void common(MetricDefinition metric) {
        metric.setCode(MetricSql.identifier(metric.getCode()));
        metric.setName(text(metric.getName(), "指标名称", 100, true));
        metric.setSubjectArea(text(metric.getSubjectArea(), "主题域", 100, true));
        metric.setOwner(text(metric.getOwner(), "负责人", 100, true));
        metric.setUnit(text(metric.getUnit(), "单位", 30, false));
        metric.setDescription(text(metric.getDescription(), "口径说明", 1000, true));
        metric.setFilterExpression(text(metric.getFilterExpression(), "筛选条件", 2000, false));
        metric.setFormula(text(metric.getFormula(), "复合公式", 2000, false));
        metric.setDimensions(String.join(",", MetricSql.dimensions(metric.getDimensions())));
        metric.setTimeColumn(text(metric.getTimeColumn(), "时间字段", 64, false));
        metric.setMeasureColumn(text(metric.getMeasureColumn(), "度量字段", 64, false));
        if (metric.getType() == null) throw new IllegalArgumentException("请选择指标类型");
        if (metric.getType() == MetricType.ATOMIC && !Set.of("SUM", "COUNT", "COUNT_DISTINCT", "AVG", "MIN", "MAX").contains(Objects.toString(metric.getAggregation(), "")))
            throw new IllegalArgumentException("不支持的聚合方式");
    }
    private static String text(String input, String label, int limit, boolean required) {
        String value = Objects.toString(input, "").trim();
        if (required && value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        if (value.length() > limit || value.indexOf('\0') >= 0) throw new IllegalArgumentException(label + "过长或包含无效字符");
        return value;
    }
}
