package com.litedata.metric;

import com.litedata.model.DataModel;
import com.litedata.modeling.DorisDdl;
import java.util.*;

public final class MetricSql {
    private MetricSql() {}
    public static List<String> dimensions(String input) {
        if (input == null || input.isBlank()) return List.of();
        var result = new TreeSet<String>();
        for (String part : input.split(",", -1)) {
            String name = identifier(part);
            if (name.equals("metric_value") || !result.add(name)) throw new IllegalArgumentException("维度不能重复或使用保留名 metric_value");
        }
        if (result.size() > 10) throw new IllegalArgumentException("最多支持 10 个维度");
        return List.copyOf(result);
    }
    public static String atomic(MetricDefinition metric, DataModel model, String filter) {
        var columns = new HashMap<String,String>();
        for (var field : model.getFields()) columns.put(field.getName(), DorisDdl.canonicalType(field.getDataType()));
        var dimensions = dimensions(metric.getDimensions());
        if (!columns.keySet().containsAll(dimensions)) throw new IllegalArgumentException("统计维度不在来源模型中");
        String measure = metric.getMeasureColumn();
        if (!(measure.equals("*") && metric.getAggregation().equals("COUNT"))) {
            measure = identifier(measure);
            if (!columns.containsKey(measure)) throw new IllegalArgumentException("度量字段不在来源模型中");
            if (!Set.of("COUNT", "COUNT_DISTINCT").contains(metric.getAggregation()) &&
                    !columns.get(measure).matches("(?:TINYINT|SMALLINT|INT|BIGINT|LARGEINT|FLOAT|DOUBLE|DECIMAL\\(.*\\))"))
                throw new IllegalArgumentException("SUM / AVG / MIN / MAX 的度量字段须为数值类型");
        }
        metric.setMeasureColumn(measure);
        if (!metric.getTimeColumn().isBlank()) {
            String time = identifier(metric.getTimeColumn());
            if (!columns.containsKey(time) || !columns.get(time).matches("DATE|DATETIME(?:\\([0-6]\\))?"))
                throw new IllegalArgumentException("时间字段须为来源模型中的 DATE / DATETIME 字段");
            metric.setTimeColumn(time);
        }
        String value = metric.getAggregation().equals("COUNT_DISTINCT") ? "COUNT(DISTINCT `" + measure + "`)"
                : metric.getAggregation() + "(" + (measure.equals("*") ? "*" : "`" + measure + "`") + ")";
        String keys = quoted(dimensions);
        return "SELECT " + (keys.isEmpty() ? "" : keys + ", ") + value + " AS metric_value\nFROM `warehouse`.`" + identifier(model.getTableName()) + "`"
                + (filter.isBlank() ? "" : "\nWHERE " + filter) + (keys.isEmpty() ? "" : "\nGROUP BY " + keys);
    }
    public static String composite(MetricDefinition metric, List<MetricDefinition> upstreams) {
        var aliases = new HashMap<String,String>();
        for (int i = 0; i < upstreams.size(); i++) aliases.put(upstreams.get(i).getCode(), "m" + i + ".metric_value");
        String expression = MetricExpressions.formula(metric.getFormula(), aliases::get);
        var dims = dimensions(metric.getDimensions());
        String keys = quoted(dims);
        if (dims.isEmpty()) {
            var inputs = new ArrayList<String>();
            for (int i = 0; i < upstreams.size(); i++) inputs.add("(\n" + upstreams.get(i).getSqlText() + "\n) m" + i);
            return "SELECT " + expression + " AS metric_value\nFROM " + String.join("\nCROSS JOIN ", inputs);
        }
        // Union of dimension keys avoids dropping groups absent from one of the inputs.
        var keySets = new ArrayList<String>();
        for (int i = 0; i < upstreams.size(); i++) keySets.add("SELECT " + keys + " FROM (\n" + upstreams.get(i).getSqlText() + "\n) k" + i);
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(String.join(", ", dims.stream().map(d -> "g.`" + d + "`").toList())).append(", ").append(expression)
                .append(" AS metric_value\nFROM (\n").append(String.join("\nUNION\n", keySets)).append("\n) g");
        for (int i = 0; i < upstreams.size(); i++) {
            int index = i;
            sql.append("\nLEFT JOIN (\n").append(upstreams.get(i).getSqlText()).append("\n) m").append(i).append(" ON ")
                    .append(String.join(" AND ", dims.stream().map(d -> "g.`" + d + "` <=> m" + index + ".`" + d + "`").toList()));
        }
        return sql.toString();
    }
    public static String identifier(String value) {
        String result = Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z_][a-z0-9_]{0,63}")) throw new IllegalArgumentException("字段和编码须以字母或下划线开头，最长 64 字符");
        return result;
    }
    private static String quoted(List<String> names) { return String.join(", ", names.stream().map(n -> "`" + n + "`").toList()); }
}
