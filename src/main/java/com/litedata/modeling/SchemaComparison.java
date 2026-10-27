package com.litedata.modeling;

import com.litedata.model.DataModel;
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
        add(differences, "HASH 分桶字段", String.join(", ", keys), String.join(", ", schema.hashColumns()));
        add(differences, "分桶数", Integer.toString(model.getBuckets()), Integer.toString(schema.buckets()));
        add(differences, "默认副本数", Integer.toString(model.getReplicationNum()), Integer.toString(schema.replicationNum()));
        add(differences, "分区方式", model.getPartitionType(), schema.partitionType());
        if ("AUTO_RANGE".equals(model.getPartitionType())) {
            add(differences, "分区字段", model.getPartitionColumn(), schema.partitionColumn());
            add(differences, "分区粒度", model.getPartitionUnit(), schema.partitionUnit());
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
}
