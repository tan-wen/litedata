package com.litedata.modeling;

import com.litedata.model.DataModel;
import com.litedata.model.ModelPartition;
import java.util.*;

public record SchemaComparison(boolean matches, List<Difference> differences) {
    public record Difference(String item, String expected, String actual, boolean matches) {}

    public static SchemaComparison compare(DataModel model, DorisModelGateway.Schema schema) {
        var differences = new ArrayList<Difference>();
        if (schema.columns().isEmpty()) {
            differences.add(new Difference("物理表", "warehouse." + model.getTableName(), "表不存在或当前只读账号不可见", false));
            return new SchemaComparison(false, differences);
        }
        var keys = model.getFields().stream().filter(f -> f.isKeyColumn()).map(f -> f.getName()).toList();
        add(differences, "数据模型", model.getModelType(), schema.modelType());
        add(differences, "键列", String.join(", ", keys), String.join(", ", schema.keyColumns()));
        add(differences, "分桶方式", model.getDistributionType(), schema.distributionType());
        if ("HASH".equals(model.getDistributionType()))
            add(differences, "HASH 分桶字段", String.join(", ", model.getDistributionKeyColumns()), String.join(", ", schema.hashColumns()));
        add(differences, "分桶数量策略", model.isAutoBuckets() ? "AUTO" : "固定", schema.autoBuckets() ? "AUTO" : "固定");
        if (!model.isAutoBuckets()) add(differences, "分桶数", Integer.toString(model.getBuckets()), Integer.toString(schema.buckets()));
        add(differences, "默认副本数", Integer.toString(model.getReplicationNum()), Integer.toString(schema.replicationNum()));
        add(differences, "分区方式", model.getPartitionType(), schema.partitionType());
        if (!"NONE".equals(model.getPartitionType())) {
            add(differences, "分区字段", model.getPartitionColumn(), schema.partitionColumn());
        }
        if ("AUTO_RANGE".equals(model.getPartitionType())) {
            add(differences, "分区粒度", model.getPartitionUnit(), schema.partitionUnit());
        }
        if ("MANUAL_RANGE".equals(model.getPartitionType())) {
            add(differences, "手动分区", ruleText(expectedRules(model)), ruleText(normalizedRules(schema.partitionRules(), partitionFieldType(model), true)));
        }
        if ("MANUAL_LIST".equals(model.getPartitionType()))
            add(differences, "手动分区", ruleText(expectedRules(model)), ruleText(normalizedRules(schema.partitionRules(), partitionFieldType(model), false)));
        if ("DYNAMIC".equals(model.getPartitionType())) {
            Map<String, String> expected;
            try { expected = DorisPartitioning.parseDynamicProperties(model.getDynamicPartitionProperties()); }
            catch (IllegalArgumentException exception) { expected = Map.of("配置错误", exception.getMessage()); }
            var defaults = new LinkedHashMap<String, String>();
            defaults.put("dynamic_partition.start", Integer.toString(Integer.MIN_VALUE));
            defaults.put("dynamic_partition.create_history_partition", "false");
            defaults.put("dynamic_partition.history_partition_num", "-1");
            defaults.put("dynamic_partition.replication_num", Integer.toString(model.getReplicationNum()));
            if (!model.isAutoBuckets()) defaults.put("dynamic_partition.buckets", Integer.toString(model.getBuckets()));
            var effective = new LinkedHashMap<>(defaults); effective.putAll(expected);
            Map<String, String> actual = new LinkedHashMap<>();
            effective.keySet().forEach(key -> actual.put(key, schema.dynamicPartitionProperties().getOrDefault(key, defaults.getOrDefault(key, ""))));
            expected = effective;
            add(differences, "动态分区属性", propertyText(expected), propertyText(actual));
        }
        if ("UNIQUE".equals(model.getModelType()))
            add(differences, "Merge-on-Write", "true", Boolean.toString(schema.mergeOnWrite()));
        add(differences, "字段顺序", String.join(", ", model.getFields().stream().map(f -> f.getName()).toList()),
                String.join(", ", schema.columns().stream().map(c -> c.name().toLowerCase(Locale.ROOT)).toList()));
        var physical = new LinkedHashMap<String, DorisModelGateway.Column>();
        schema.columns().forEach(c -> physical.put(c.name().toLowerCase(Locale.ROOT), c));
        for (var field : model.getFields()) {
            var column = physical.remove(field.getName());
            if (column == null) {
                differences.add(new Difference(field.getName(), field.getDataType(), "缺少字段", false));
                continue;
            }
            add(differences, field.getName() + " / 类型", DorisDdl.canonicalType(field.getDataType()), DorisDdl.canonicalType(column.dataType()));
            add(differences, field.getName() + " / 可空", field.isNullable() ? "YES" : "NO", column.nullable() ? "YES" : "NO");
            add(differences, field.getName() + " / 注释", field.getComment(), column.comment());
            add(differences, field.getName() + " / 聚合函数", field.getAggregation(), schema.aggregations().getOrDefault(field.getName(), ""));
        }
        physical.forEach((name, column) -> differences.add(new Difference(name, "未定义", column.dataType() + "（多余字段）", false)));
        return new SchemaComparison(differences.stream().allMatch(Difference::matches), differences);
    }

    private static void add(List<Difference> differences, String item, String expected, String actual) {
        differences.add(new Difference(item, expected, actual, Objects.equals(expected, actual)));
    }

    private static String partitionFieldType(DataModel model) {
        return model.getFields().stream().filter(field -> field.getName().equals(model.getPartitionColumn()))
                .map(field -> DorisDdl.canonicalType(field.getDataType())).findFirst().orElse("");
    }
    private static List<DorisPartitioning.Rule> expectedRules(DataModel model) {
        boolean range = "MANUAL_RANGE".equals(model.getPartitionType());
        var rules = model.getPartitions().stream().map(item -> new DorisPartitioning.Rule(item.getName(),
                range && !"LESS_THAN".equals(model.getPartitionRangeStyle()) ? item.getLowerBound() : "",
                range ? item.getUpperBound() : "", range ? List.<String>of() : Arrays.asList(item.getValuesText().split("\\R", -1)))).toList();
        return normalizedRules(rules, partitionFieldType(model), range);
    }
    private static List<DorisPartitioning.Rule> normalizedRules(List<DorisPartitioning.Rule> source, String type, boolean range) {
        var rules = new ArrayList<DorisPartitioning.Rule>();
        String previous = minimum(type);
        for (var rule : source) {
            if (range) {
                String lower = rule.lowerBound().isEmpty() ? previous : rule.lowerBound();
                if (lower.equalsIgnoreCase("MINVALUE")) lower = minimum(type);
                String upper = rule.upperBound().equalsIgnoreCase("MAXVALUE") ? "MAXVALUE" : normalizedValue(rule.upperBound(), type);
                rules.add(new DorisPartitioning.Rule(rule.name(), normalizedValue(lower, type), upper, List.of()));
                previous = upper;
            } else rules.add(new DorisPartitioning.Rule(rule.name(), "", "",
                    rule.values().stream().map(value -> normalizedValue(value, type)).sorted().toList()));
        }
        return rules.stream().sorted(Comparator.comparing(DorisPartitioning.Rule::name)).toList();
    }
    private static String normalizedValue(String value, String type) {
        try {
            if (Set.of("TINYINT", "SMALLINT", "INT", "BIGINT", "LARGEINT").contains(type))
                return new java.math.BigInteger(value).toString();
            return DorisPartitioning.normalizeTemporal(value, type);
        } catch (RuntimeException exception) { return value; }
    }
    private static String minimum(String type) {
        return switch (type) {
            case "TINYINT" -> "-128";
            case "SMALLINT" -> "-32768";
            case "INT" -> "-2147483648";
            case "BIGINT" -> Long.toString(Long.MIN_VALUE);
            case "LARGEINT" -> java.math.BigInteger.ONE.shiftLeft(127).negate().toString();
            case "DATE" -> "0000-01-01";
            default -> type.startsWith("DATETIME") ? "0000-01-01 00:00:00" : "MINVALUE";
        };
    }
    private static String ruleText(List<DorisPartitioning.Rule> rules) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(rules);
    }
    private static String propertyText(Map<String, String> properties) {
        return properties.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ", "+ right).orElse("");
    }
}
