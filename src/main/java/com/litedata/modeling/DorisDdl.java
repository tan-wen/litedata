package com.litedata.modeling;

import com.litedata.data.JdbcConnections;
import com.litedata.model.DataModel;
import com.litedata.model.ModelField;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Generates a validated, structured Doris CREATE TABLE; no raw DDL fragments are accepted. */
@Component
public class DorisDdl {
    public static final List<String> TYPE_OPTIONS = List.of(
            "TINYINT", "SMALLINT", "INT", "BIGINT", "LARGEINT", "BOOLEAN", "DATE", "DATETIME",
            "CHAR(1)", "VARCHAR(128)", "STRING", "DECIMAL(18,2)", "FLOAT", "DOUBLE");
    private static final Pattern SIMPLE = Pattern.compile("^(TINYINT|SMALLINT|INT|BIGINT|LARGEINT|BOOLEAN|DATE|STRING|FLOAT|DOUBLE)$");
    private static final Pattern INTEGER_WIDTH = Pattern.compile("^(TINYINT|SMALLINT|INT|BIGINT|LARGEINT)\\s*\\(\\s*\\d+\\s*\\)$");
    private static final Pattern DATETIME = Pattern.compile("^(?:DATETIME|DATETIMEV2)(?:\\s*\\(\\s*([0-6])\\s*\\))?$");
    private static final Pattern CHAR_OR_VARCHAR = Pattern.compile("^(CHAR|VARCHAR)\\s*\\(\\s*(\\d+)\\s*\\)$");
    private static final Pattern DECIMAL = Pattern.compile("^(?:DECIMAL|DECIMALV3)\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)$");

    public String generate(DataModel model) {
        if (model == null) throw invalid("模型不能为空");
        model.setTableName(identifier(model.getTableName(), "表名"));
        model.setName(metadata(model.getName(), "模型中文名", 100, true));
        model.setSubjectArea(metadata(model.getSubjectArea(), "主题域", 100, true));
        model.setLayer(layer(model.getLayer()));
        model.setDescription(metadata(model.getDescription(), "描述", 500, false));
        model.setModelType(choice(model.getModelType(), Set.of("DUPLICATE", "UNIQUE", "AGGREGATE"), "数据模型仅支持 DUPLICATE、UNIQUE、AGGREGATE"));
        if (model.getReplicationNum() < 1 || model.getReplicationNum() > 10) throw invalid("副本数须在 1 到 10 之间，且不能超过可用 BE 节点数");
        if (!model.isAutoBuckets() && (model.getBuckets() < 1 || model.getBuckets() > 32)) throw invalid("分桶数须在 1 到 32 之间");
        if (model.getFields() == null || model.getFields().isEmpty() || model.getFields().size() > 100)
            throw invalid("字段数量须在 1 到 100 之间");

        var columns = new ArrayList<String>();
        var keys = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        boolean encounteredNonKey = false;
        for (ModelField field : model.getFields()) {
            if (field == null) throw invalid("字段不能为空");
            String name = identifier(field.getName(), "字段名");
            if (!seen.add(name)) throw invalid("字段名不能重复：" + name);
            String type = acceptedType(field.getDataType());
            field.setName(name);
            field.setDataType(type);
            field.setComment(metadata(field.getComment(), "字段注释", 500, false));
            field.setStandard(metadata(field.getStandard(), "数据标准", 100, false));
            String aggregation = aggregation(model, field, type);
            if (field.isKeyColumn()) {
                if (encounteredNonKey) throw invalid("键列必须连续位于字段列表前缀：" + name);
                if (field.isNullable()) throw invalid("键列不能为可空：" + name);
                if (Set.of("FLOAT", "DOUBLE", "STRING").contains(type))
                    throw invalid("键列不支持 " + type + "：" + name);
                keys.add(name);
            } else encounteredNonKey = true;
            columns.add("  `" + name + "` " + type + (aggregation.isEmpty() ? "" : " " + aggregation) + (field.isNullable() ? " NULL" : " NOT NULL")
                    + " COMMENT '" + sqlComment(field.getComment()) + "'");
        }
        if (keys.isEmpty()) throw invalid("至少需要一个键列");
        if ("AGGREGATE".equals(model.getModelType()) && keys.size() == model.getFields().size())
            throw invalid("AGGREGATE 模型至少需要一个非键聚合字段");
        validateDimension(model, keys, seen);
        String partition = partition(model, keys);

        String tableComment = model.getDescription().isBlank() ? model.getName()
                : model.getName() + " - " + model.getDescription();
        return "CREATE TABLE `warehouse`.`" + model.getTableName() + "` (\n"
                + String.join(",\n", columns) + "\n)\nENGINE=OLAP\n" + model.getModelType() + " KEY(" + quoted(keys) + ")\nCOMMENT '"
                + sqlComment(tableComment) + "'\n" + partition + "DISTRIBUTED BY HASH(" + quoted(keys) + ") BUCKETS " + (model.isAutoBuckets() ? "AUTO" : model.getBuckets())
                + "\nPROPERTIES (\n  \"replication_num\" = \"" + model.getReplicationNum() + "\""
                + ("UNIQUE".equals(model.getModelType()) ? ",\n  \"enable_unique_key_merge_on_write\" = \"true\"" : "") + "\n);";
    }

    private static String aggregation(DataModel model, ModelField field, String type) {
        String function = field.getAggregation() == null ? "" : field.getAggregation().trim().toUpperCase(Locale.ROOT);
        if (!"AGGREGATE".equals(model.getModelType()) || field.isKeyColumn()) {
            if (!function.isEmpty()) throw invalid("仅 AGGREGATE 的非键字段可设置聚合函数：" + field.getName());
        } else {
            if (!Set.of("SUM", "MIN", "MAX", "REPLACE", "REPLACE_IF_NOT_NULL").contains(function))
                throw invalid("AGGREGATE 的每个非键字段须选择 SUM、MIN、MAX、REPLACE 或 REPLACE_IF_NOT_NULL：" + field.getName());
            if (function.equals("SUM") && !(Set.of("TINYINT", "SMALLINT", "INT", "BIGINT", "LARGEINT", "FLOAT", "DOUBLE").contains(type)
                    || type.startsWith("DECIMAL("))) throw invalid("SUM 仅支持数值类型：" + field.getName());
            if (function.equals("REPLACE_IF_NOT_NULL") && !field.isNullable())
                throw invalid("REPLACE_IF_NOT_NULL 字段须允许 NULL：" + field.getName());
        }
        field.setAggregation(function);
        return function;
    }

    private static String partition(DataModel model, List<String> keys) {
        String kind = choice(model.getPartitionType(), Set.of("NONE", "AUTO_RANGE"), "分区方式仅支持 NONE 或 AUTO_RANGE");
        model.setPartitionType(kind);
        if (kind.equals("NONE")) {
            model.setPartitionColumn(""); model.setPartitionUnit("DAY");
            return "";
        }
        if ("DIM".equals(model.getLayer())) throw invalid("DIM/SCD1 当前仅支持不分区，确保按业务键维护唯一当前值");
        String column = identifier(model.getPartitionColumn(), "分区字段");
        if (!keys.contains(column)) throw invalid("日期自动分区字段须是键列中的 DATE 或 DATETIME 字段");
        var field = model.getFields().stream().filter(item -> item.getName().equals(column)).findFirst().orElseThrow();
        if (!(field.getDataType().equals("DATE") || field.getDataType().startsWith("DATETIME")))
            throw invalid("日期自动分区仅支持 DATE 或 DATETIME 字段");
        String unit = choice(model.getPartitionUnit(), Set.of("DAY", "MONTH", "YEAR"), "分区粒度仅支持 DAY、MONTH、YEAR");
        model.setPartitionColumn(column); model.setPartitionUnit(unit);
        return "AUTO PARTITION BY RANGE(date_trunc(`" + column + "`, '" + unit.toLowerCase(Locale.ROOT) + "')) ()\n";
    }

    private static String choice(String value, Set<String> supported, String message) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!supported.contains(normalized)) throw invalid(message);
        return normalized;
    }

    /** Canonical form is also used for comparing Doris metadata aliases. */
    public static String canonicalType(String input) {
        if (input == null) return "";
        String value = input.trim().toUpperCase(Locale.ROOT);
        if (value.equals("DATEV2")) return "DATE";
        if (value.equals("BOOL") || value.equals("BOOLEAN")) return "TINYINT";
        Matcher integer = INTEGER_WIDTH.matcher(value);
        if (integer.matches()) return integer.group(1);
        Matcher datetime = DATETIME.matcher(value);
        if (datetime.matches()) return datetime.group(1) == null || datetime.group(1).equals("0")
                ? "DATETIME" : "DATETIME(" + datetime.group(1) + ")";
        Matcher sized = CHAR_OR_VARCHAR.matcher(value);
        if (sized.matches()) return sized.group(1) + "(" + sized.group(2) + ")";
        Matcher decimal = DECIMAL.matcher(value);
        if (decimal.matches()) return "DECIMAL(" + decimal.group(1) + "," + decimal.group(2) + ")";
        return value.replaceAll("\\s+", " ");
    }

    private static String acceptedType(String input) {
        String canonical = canonicalType(input);
        if (SIMPLE.matcher(canonical).matches()) return canonical;
        Matcher datetime = DATETIME.matcher(canonical);
        if (datetime.matches()) return canonical;
        Matcher sized = CHAR_OR_VARCHAR.matcher(canonical);
        if (sized.matches()) {
            try {
                int size = Integer.parseInt(sized.group(2));
                int max = sized.group(1).equals("CHAR") ? 255 : 65533;
                if (size >= 1 && size <= max) return canonical;
            } catch (NumberFormatException ignored) { }
        }
        Matcher decimal = DECIMAL.matcher(canonical);
        if (decimal.matches()) {
            try {
                int precision = Integer.parseInt(decimal.group(1));
                int scale = Integer.parseInt(decimal.group(2));
                if (precision >= 1 && precision <= 38 && scale >= 0 && scale <= precision) return canonical;
            } catch (NumberFormatException ignored) { }
        }
        throw invalid("不支持的 Doris 字段类型：" + input);
    }

    private static String identifier(String value, String label) {
        try {
            return JdbcConnections.identifier(value == null ? null : value.trim()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) { throw invalid(label + "仅支持字母、数字和下划线，最长64字符"); }
    }

    private static String metadata(String value, String label, int max, boolean required) {
        String normalized = value == null ? "" : value.trim();
        if ((required && normalized.isEmpty()) || normalized.length() > max || hasControl(normalized))
            throw invalid(label + (required ? "不能为空且" : "") + "最长" + max + "字符，且不能含控制字符");
        return normalized;
    }

    private static String layer(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ODS", "DIM", "DWD", "DWS", "ADS").contains(normalized)) throw invalid("分层仅支持 ODS、DIM、DWD、DWS、ADS");
        return normalized;
    }

    private static void validateDimension(DataModel model, List<String> keys, Set<String> fields) {
        if (!"DIM".equals(model.getLayer())) {
            model.setDimensionKeys(""); model.setDimensionLabel(""); model.setDimensionStrategy("");
            return;
        }
        if (!"UNIQUE".equals(model.getModelType())) throw invalid("DIM/SCD1 仅支持 UNIQUE 主键模型");
        String strategy = model.getDimensionStrategy() == null ? "" : model.getDimensionStrategy().trim().toUpperCase(Locale.ROOT);
        if (strategy.isEmpty()) strategy = "SCD1";
        if (!strategy.equals("SCD1")) throw invalid("DIM 当前仅支持 SCD1（按业务键覆盖当前值）");
        String raw = model.getDimensionKeys() == null ? "" : model.getDimensionKeys().trim();
        String[] parts = raw.split(",", -1);
        if (raw.isEmpty() || parts.length > 10) throw invalid("DIM 须指定 1 到 10 个业务键字段，以逗号分隔");
        var businessKeys = new LinkedHashSet<String>();
        for (String part : parts) {
            String key = identifier(part, "维度业务键");
            if (!businessKeys.add(key)) throw invalid("维度业务键不能重复：" + key);
            if (!fields.contains(key)) throw invalid("维度业务键字段不存在：" + key);
        }
        if (!new ArrayList<>(businessKeys).equals(keys))
            throw invalid("SCD1 业务键须与物理主键字段及顺序一致，确保每个成员只保留当前值");
        String label = model.getDimensionLabel() == null ? "" : model.getDimensionLabel().trim();
        if (!label.isEmpty()) {
            label = identifier(label, "维度展示字段");
            if (!fields.contains(label)) throw invalid("维度展示字段不存在：" + label);
            if (businessKeys.contains(label)) throw invalid("维度展示字段须为非业务键属性");
        }
        model.setDimensionKeys(String.join(",", businessKeys));
        model.setDimensionLabel(label);
        model.setDimensionStrategy(strategy);
    }

    private static boolean hasControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static String sqlComment(String value) {
        // Doris 4.1.3's COMMENT parser strips every backslash after SQL literal parsing,
        // including with NO_BACKSLASH_ESCAPES. Reject it rather than silently changing metadata.
        if (value.contains("\\")) throw invalid("Doris 注释暂不支持反斜杠，以避免元数据被静默改变");
        return value.replace("'", "\\'");
    }

    private static String quoted(List<String> names) {
        return names.stream().map(name -> "`" + name + "`").reduce((left, right) -> left + ", " + right).orElseThrow();
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
