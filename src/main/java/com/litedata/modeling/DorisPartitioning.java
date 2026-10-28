package com.litedata.modeling;

import com.litedata.data.JdbcConnections;
import com.litedata.model.DataModel;
import com.litedata.model.ModelField;
import com.litedata.model.ModelPartition;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates partition input and renders only values that have been parsed as data. */
public final class DorisPartitioning {
    public record Rule(String name, String lowerBound, String upperBound, List<String> values) { }
    record Definition(String type, String column, String unit, String rangeStyle, List<Rule> rules,
                      Map<String, String> dynamicProperties) { }

    private static final Set<String> TYPES = Set.of("NONE", "AUTO_RANGE", "AUTO_LIST", "MANUAL_RANGE", "MANUAL_LIST", "DYNAMIC");
    private static final Set<String> RANGE_TYPES = Set.of("TINYINT", "SMALLINT", "INT", "BIGINT", "LARGEINT", "DATE");
    private static final Set<String> LIST_TYPES = Set.of("TINYINT", "SMALLINT", "INT", "BIGINT", "LARGEINT", "BOOLEAN", "DATE", "STRING");
    private static final Set<String> DYNAMIC_KEYS = Set.of("dynamic_partition.enable", "dynamic_partition.time_unit",
            "dynamic_partition.start", "dynamic_partition.end", "dynamic_partition.prefix", "dynamic_partition.create_history_partition",
            "dynamic_partition.buckets", "dynamic_partition.replication_num", "dynamic_partition.history_partition_num",
            "dynamic_partition.start_day_of_week", "dynamic_partition.start_day_of_month", "dynamic_partition.time_zone",
            "dynamic_partition.reserved_history_periods");
    private static final Pattern PROPERTY = Pattern.compile("\\s*\\\"([^\\\"\\r\\n]+)\\\"\\s*=\\s*\\\"([^\\\"\\r\\n]*)\\\"\\s*");
    private static final Pattern PROPERTIES = Pattern.compile("(?is)^\\s*PROPERTIES\\s*\\((.*)\\)\\s*$");

    private DorisPartitioning() { }

    static Definition define(DataModel model, List<String> keys) {
        String type = upper(model.getPartitionType(), "分区方式");
        if (!TYPES.contains(type)) throw invalid("分区方式仅支持 NONE、AUTO_RANGE、AUTO_LIST、MANUAL_RANGE、MANUAL_LIST、DYNAMIC");
        model.setPartitionType(type);
        if ("NONE".equals(type)) {
            model.setPartitionColumn(""); model.setPartitionUnit("DAY"); model.setPartitionRangeStyle("FIXED");
            model.setPartitions(List.of());
            return new Definition(type, "", "DAY", "FIXED", List.of(), Map.of());
        }
        if (!type.startsWith("MANUAL_")) model.setPartitions(List.of());
        if ("DIM".equals(model.getLayer())) throw invalid("DIM/SCD1 当前仅支持不分区，确保按业务键维护唯一当前值");
        String column = identifier(model.getPartitionColumn(), "分区字段");
        if (!keys.contains(column)) throw invalid("分区字段须是不可空键列：" + column);
        ModelField field = model.getFields().stream().filter(item -> column.equals(item.getName())).findFirst()
                .orElseThrow(() -> invalid("分区字段不存在：" + column));
        String dataType = DorisDdl.canonicalType(field.getDataType());
        String unit = "AUTO_RANGE".equals(type) ? upper(model.getPartitionUnit(), "分区粒度") : "DAY";
        String style = upper(model.getPartitionRangeStyle(), "RANGE 分区形式");
        if (!Set.of("FIXED", "LESS_THAN").contains(style)) throw invalid("RANGE 分区形式仅支持 FIXED 或 LESS_THAN");
        List<Rule> rules = List.of();
        Map<String, String> dynamic = Map.of();
        switch (type) {
            case "AUTO_RANGE" -> {
                if (!dateType(dataType)) throw invalid("自动 RANGE 分区仅支持 DATE 或 DATETIME 字段");
                if (!Set.of("SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "YEAR").contains(unit)) throw invalid("自动 RANGE 分区粒度仅支持 SECOND、MINUTE、HOUR、DAY、WEEK、MONTH、YEAR");
                if (Set.of("SECOND", "MINUTE", "HOUR").contains(unit) && !dataType.startsWith("DATETIME")) throw invalid("SECOND、MINUTE、HOUR 自动 RANGE 分区仅支持 DATETIME 字段");
            }
            case "AUTO_LIST" -> {
                if (!listType(dataType)) throw invalid("自动 LIST 分区字段类型不受 Doris 支持：" + dataType);
                if (model.isAutoBuckets()) throw invalid("Doris AUTO LIST 分区不支持 AUTO 分桶，请指定固定分桶数");
                unit = "";
            }
            case "MANUAL_RANGE" -> {
                if (!rangeType(dataType)) throw invalid("手动 RANGE 分区仅支持整数、DATE 或 DATETIME 字段");
                rules = rangeRules(model.getPartitions(), dataType, style);
                unit = "";
            }
            case "MANUAL_LIST" -> {
                if (!listType(dataType)) throw invalid("手动 LIST 分区字段类型不受 Doris 支持：" + dataType);
                rules = listRules(model.getPartitions(), dataType);
                unit = "";
            }
            case "DYNAMIC" -> {
                if (!dateType(dataType)) throw invalid("动态分区仅支持 DATE 或 DATETIME 字段");
                dynamic = parseDynamicProperties(model.getDynamicPartitionProperties());
                unit = dynamic.get("dynamic_partition.time_unit");
                if ("HOUR".equals(unit) && !dataType.startsWith("DATETIME")) throw invalid("HOUR 动态分区仅支持 DATETIME 字段");
                style = "FIXED";
            }
            default -> { }
        }
        model.setPartitionColumn(column); model.setPartitionUnit(unit); model.setPartitionRangeStyle(style);
        return new Definition(type, column, unit, style, rules, dynamic);
    }

    static String ddl(Definition definition) {
        return switch (definition.type()) {
            case "NONE" -> "";
            case "AUTO_RANGE" -> "AUTO PARTITION BY RANGE(date_trunc(`" + definition.column() + "`, '" + definition.unit().toLowerCase(Locale.ROOT) + "')) ()\n";
            case "AUTO_LIST" -> "AUTO PARTITION BY LIST(`" + definition.column() + "`) ()\n";
            case "DYNAMIC" -> "PARTITION BY RANGE(`" + definition.column() + "`) ()\n";
            case "MANUAL_RANGE" -> "PARTITION BY RANGE(`" + definition.column() + "`) (\n" + rangeDdl(definition) + "\n)\n";
            case "MANUAL_LIST" -> "PARTITION BY LIST(`" + definition.column() + "`) (\n" + listDdl(definition) + "\n)\n";
            default -> throw invalid("未知分区方式");
        };
    }

    /** Parses a complete PROPERTIES(...) block or its body; no SQL fragments are retained. */
    public static Map<String, String> parseDynamicProperties(String input) {
        if (input != null && input.length() > 16000) throw invalid("动态分区属性不能超过16000字符");
        String body = input == null ? "" : input.trim();
        Matcher wrapper = PROPERTIES.matcher(body);
        if (wrapper.matches()) body = wrapper.group(1);
        if (body.isBlank() || body.length() > 16000) throw invalid("动态分区属性不能为空且不能超过16000字符");
        var values = new LinkedHashMap<String, String>();
        int cursor = 0;
        while (cursor < body.length()) {
            Matcher pair = PROPERTY.matcher(body);
            pair.region(cursor, body.length());
            if (!pair.lookingAt()) throw invalid("动态分区属性格式无效，仅支持 \"key\" = \"value\"");
            String key = pair.group(1).trim(); String value = pair.group(2).trim();
            if (!DYNAMIC_KEYS.contains(key)) throw invalid("不支持的动态分区属性：" + key);
            if (values.putIfAbsent(key, value) != null) throw invalid("动态分区属性不能重复：" + key);
            cursor = pair.end();
            if (cursor == body.length()) break;
            if (body.charAt(cursor) != ',') throw invalid("动态分区属性之间须使用逗号分隔");
            cursor++;
        }
        normalizeDynamic(values);
        validateDynamic(values);
        return Map.copyOf(values);
    }

    private static void validateDynamic(Map<String, String> values) {
        String enabled = values.getOrDefault("dynamic_partition.enable", "true");
        bool(enabled, "dynamic_partition.enable");
        String unit = required(values, "dynamic_partition.time_unit").toUpperCase(Locale.ROOT);
        if (!Set.of("HOUR", "DAY", "WEEK", "MONTH", "YEAR").contains(unit)) throw invalid("dynamic_partition.time_unit 仅支持 HOUR、DAY、WEEK、MONTH、YEAR");
        int start = values.containsKey("dynamic_partition.start") ? integer(values.get("dynamic_partition.start"), "dynamic_partition.start") : Integer.MIN_VALUE;
        int end = integer(required(values, "dynamic_partition.end"), "dynamic_partition.end");
        if (values.containsKey("dynamic_partition.start") && start >= 0) throw invalid("dynamic_partition.start 须为负整数");
        if (end <= 0) throw invalid("dynamic_partition.end 须为正整数");
        String prefix = required(values, "dynamic_partition.prefix");
        if (!prefix.matches("[A-Za-z][A-Za-z0-9_]{0,49}")) throw invalid("dynamic_partition.prefix 须以字母开头，仅含字母、数字、下划线且最长50字符");
        if (values.containsKey("dynamic_partition.create_history_partition")) bool(values.get("dynamic_partition.create_history_partition"), "dynamic_partition.create_history_partition");
        for (String key : List.of("dynamic_partition.buckets", "dynamic_partition.replication_num"))
            if (values.containsKey(key) && integer(values.get(key), key) <= 0) throw invalid(key + " 须为正整数");
        if (values.containsKey("dynamic_partition.history_partition_num") && integer(values.get("dynamic_partition.history_partition_num"), "dynamic_partition.history_partition_num") < -1)
            throw invalid("dynamic_partition.history_partition_num 须为 -1 或非负整数");
        if (values.containsKey("dynamic_partition.start_day_of_week")) {
            int day = integer(values.get("dynamic_partition.start_day_of_week"), "dynamic_partition.start_day_of_week");
            if (day < 1 || day > 7) throw invalid("dynamic_partition.start_day_of_week 须在 1 到 7 之间");
        }
        if (values.containsKey("dynamic_partition.start_day_of_month")) {
            int day = integer(values.get("dynamic_partition.start_day_of_month"), "dynamic_partition.start_day_of_month");
            if (day < 1 || day > 28) throw invalid("dynamic_partition.start_day_of_month 须在 1 到 28 之间");
        }
        if (values.containsKey("dynamic_partition.time_zone")) {
            try { java.time.ZoneId.of(values.get("dynamic_partition.time_zone")); }
            catch (Exception exception) { throw invalid("dynamic_partition.time_zone 不是合法时区"); }
        }
        if (values.containsKey("dynamic_partition.reserved_history_periods")) validateReservedHistory(values.get("dynamic_partition.reserved_history_periods"));
    }

    private static void validateReservedHistory(String value) {
        if ("NULL".equalsIgnoreCase(value)) return;
        if (value.isBlank() || value.length() > 2048) throw invalid("dynamic_partition.reserved_history_periods 格式无效");
        int cursor = 0;
        while (cursor < value.length()) {
            if (value.charAt(cursor) != '[') throw invalid("dynamic_partition.reserved_history_periods 格式无效");
            int close = value.indexOf(']', cursor + 1);
            if (close < 0) throw invalid("dynamic_partition.reserved_history_periods 格式无效");
            String[] bounds = value.substring(cursor + 1, close).split(",", -1);
            if (bounds.length != 2 || historyTime(bounds[0]).compareTo(historyTime(bounds[1])) > 0)
                throw invalid("dynamic_partition.reserved_history_periods 区间无效或起点晚于终点");
            cursor = close + 1;
            if (cursor == value.length()) break;
            if (value.charAt(cursor) != ',') throw invalid("dynamic_partition.reserved_history_periods 格式无效");
            cursor++;
        }
    }

    private static LocalDateTime historyTime(String value) {
        String text = value.trim();
        try { return text.length() == 10 ? LocalDate.parse(text).atStartOfDay() : LocalDateTime.parse(text.replace(' ', 'T')); }
        catch (DateTimeParseException exception) { throw invalid("dynamic_partition.reserved_history_periods 日期无效"); }
    }

    private static void normalizeDynamic(Map<String, String> values) {
        for (String key : List.of("dynamic_partition.enable", "dynamic_partition.create_history_partition"))
            if (values.containsKey(key)) values.put(key, values.get(key).toLowerCase(Locale.ROOT));
        if (values.containsKey("dynamic_partition.time_unit")) values.put("dynamic_partition.time_unit", values.get("dynamic_partition.time_unit").toUpperCase(Locale.ROOT));
        for (String key : List.of("dynamic_partition.start", "dynamic_partition.end", "dynamic_partition.buckets", "dynamic_partition.replication_num", "dynamic_partition.history_partition_num", "dynamic_partition.start_day_of_week", "dynamic_partition.start_day_of_month"))
            if (values.containsKey(key)) values.put(key, Integer.toString(integer(values.get(key), key)));
        values.putIfAbsent("dynamic_partition.enable", "true");
    }

    private static List<Rule> rangeRules(List<ModelPartition> source, String type, String style) {
        if (source == null || source.isEmpty() || source.size() > 100) throw invalid("手动分区数量须在 1 到 100 之间");
        var result = new ArrayList<Rule>(); var names = new LinkedHashSet<String>(); String previous = null;
        for (ModelPartition item : source) {
            if (item == null) throw invalid("手动分区不能为空");
            String name = identifier(item.getName(), "分区名称");
            if (!names.add(name)) throw invalid("分区名称不能重复：" + name);
            String lower = "LESS_THAN".equals(style) ? "" : scalar(item.getLowerBound(), type, "分区下界", false);
            String upper = scalar(item.getUpperBound(), type, "分区上界", true);
            if ("LESS_THAN".equals(style)) {
                if (previous != null && compare(previous, upper, type) >= 0) throw invalid("LESS_THAN 分区上界须严格递增且不重叠");
                previous = upper;
                item.setName(name); item.setLowerBound(""); item.setUpperBound(upper); item.setValuesText("");
                result.add(new Rule(name, "", upper, List.of()));
            } else {
                if ("MAXVALUE".equals(lower) || compare(lower, upper, type) >= 0) throw invalid("RANGE 分区下界须小于上界");
                if (previous != null && compare(previous, lower, type) > 0) throw invalid("RANGE 分区范围不能重叠，须按下界递增");
                previous = upper;
                item.setName(name); item.setLowerBound(lower); item.setUpperBound(upper); item.setValuesText("");
                result.add(new Rule(name, lower, upper, List.of()));
            }
        }
        return List.copyOf(result);
    }

    private static List<Rule> listRules(List<ModelPartition> source, String type) {
        if (source == null || source.isEmpty() || source.size() > 100) throw invalid("手动分区数量须在 1 到 100 之间");
        var result = new ArrayList<Rule>(); var names = new LinkedHashSet<String>(); var allValues = new LinkedHashSet<String>();
        int total = 0;
        for (ModelPartition item : source) {
            if (item == null) throw invalid("手动分区不能为空");
            String name = identifier(item.getName(), "分区名称");
            if (!names.add(name)) throw invalid("分区名称不能重复：" + name);
            String text = item.getValuesText() == null ? "" : item.getValuesText();
            if (text.length() > 16000 || hasControlExceptNewline(text)) throw invalid("LIST 分区值长度过长或包含控制字符");
            var values = new ArrayList<String>();
            for (String line : text.split("\\R", -1)) {
                if (line.trim().isEmpty()) continue;
                String value = scalar(line, type, "LIST 分区值", false);
                if (!allValues.add(value)) throw invalid("LIST 分区值不能跨分区重复：" + value);
                values.add(value); total += value.length();
            }
            if (values.isEmpty()) throw invalid("LIST 分区须至少填写一个值：" + name);
            if (total > 16000) throw invalid("LIST 分区值总长度不能超过16000字符");
            item.setName(name); item.setLowerBound(""); item.setUpperBound(""); item.setValuesText(String.join("\n", values));
            result.add(new Rule(name, "", "", List.copyOf(values)));
        }
        return List.copyOf(result);
    }

    private static String rangeDdl(Definition definition) {
        return definition.rules().stream().map(rule -> "  PARTITION `" + rule.name() + "` VALUES "
                + ("LESS_THAN".equals(definition.rangeStyle()) ? "LESS THAN (" + literal(rule.upperBound()) + ")"
                : "[(" + literal(rule.lowerBound()) + "), (" + literal(rule.upperBound()) + "))"))
                .reduce((left, right) -> left + ",\n" + right).orElseThrow();
    }

    private static String listDdl(Definition definition) {
        return definition.rules().stream().map(rule -> "  PARTITION `" + rule.name() + "` VALUES IN ("
                + rule.values().stream().map(DorisPartitioning::quotedLiteral).reduce((left, right) -> left + ", " + right).orElseThrow() + ")")
                .reduce((left, right) -> left + ",\n" + right).orElseThrow();
    }

    private static String quotedLiteral(String value) { return "\"" + value.replace("'", "\\'") + "\""; }
    private static String literal(String value) { return "MAXVALUE".equals(value) ? value : quotedLiteral(value); }
    private static String scalar(String raw, String type, String label, boolean allowMax) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > 256 || hasControl(value) || value.contains("\"") || value.contains("\\") || value.contains(";"))
            throw invalid(label + "不能为空，不能含控制字符、双引号、反斜杠或分号");
        if ("MAXVALUE".equalsIgnoreCase(value) && !(type.startsWith("CHAR(") || type.startsWith("VARCHAR("))) {
            if (!allowMax) throw invalid(label + "不能是 MAXVALUE");
            return "MAXVALUE";
        }
        try {
            if (integerType(type)) return checkedInteger("TINYINT".equals(type) && Set.of("true", "false").contains(value.toLowerCase(Locale.ROOT)) ? (Boolean.parseBoolean(value) ? "1" : "0") : value, type, label);
            if ("BOOLEAN".equals(type)) return Boolean.parseBoolean(value.toLowerCase(Locale.ROOT)) ? "true" : booleanValue(value, label);
            if ("DATE".equals(type)) return date(value).toString();
            if (dateType(type)) {
                LocalDateTime time = LocalDateTime.parse(value.replace(' ', 'T'));
                date(time.toLocalDate().toString());
                int precision = type.contains("(") ? Integer.parseInt(type.substring(type.indexOf('(') + 1, type.indexOf(')'))) : 0;
                if (time.getNano() % (int) Math.pow(10, 9 - precision) != 0) throw invalid(label + "超过 DATETIME 时间精度");
                return normalizeTemporal(value, type);
            }
            if (type.startsWith("CHAR(") || type.startsWith("VARCHAR(")) {
                int limit = Integer.parseInt(type.substring(type.indexOf('(') + 1, type.indexOf(')')));
                if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > limit) throw invalid(label + "超过字段长度");
            }
            return value;
        } catch (IllegalArgumentException | java.time.DateTimeException exception) { throw invalid(label + "与分区字段类型不匹配：" + value); }
    }
    private static LocalDate date(String value) {
        LocalDate date = LocalDate.parse(value);
        if (date.getYear() < 0 || date.getYear() > 9999) throw invalid("分区日期年份须在 0000 到 9999 之间");
        return date;
    }
    static String normalizeTemporal(String value, String type) {
        if ("DATE".equals(type)) return LocalDate.parse(value).toString();
        if (!type.startsWith("DATETIME")) return value;
        LocalDateTime time = LocalDateTime.parse(value.replace(' ', 'T'));
        String fraction = String.format(Locale.ROOT, "%09d", time.getNano()).replaceFirst("0+$", "");
        return time.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")) + (fraction.isEmpty() ? "" : "." + fraction);
    }
    private static String checkedInteger(String value, String type, String label) {
        BigInteger parsed = new BigInteger(value);
        Map<String, BigInteger[]> limits = Map.of("TINYINT", new BigInteger[]{BigInteger.valueOf(-128), BigInteger.valueOf(127)}, "SMALLINT", new BigInteger[]{BigInteger.valueOf(-32768), BigInteger.valueOf(32767)}, "INT", new BigInteger[]{BigInteger.valueOf(-2147483648L), BigInteger.valueOf(2147483647L)}, "BIGINT", new BigInteger[]{BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE)});
        BigInteger[] limit = limits.get(type);
        if ("LARGEINT".equals(type)) limit = new BigInteger[]{BigInteger.ONE.shiftLeft(127).negate(), BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE)};
        if (limit != null && (parsed.compareTo(limit[0]) < 0 || parsed.compareTo(limit[1]) > 0)) throw invalid(label + "超出" + type + "取值范围");
        return parsed.toString();
    }
    private static String booleanValue(String value, String label) { if (!"false".equalsIgnoreCase(value)) throw invalid(label + "须为 true 或 false"); return "false"; }
    private static int compare(String left, String right, String type) {
        if ("MAXVALUE".equals(right)) return -1; if ("MAXVALUE".equals(left)) return 1;
        if (integerType(type)) return new BigInteger(left).compareTo(new BigInteger(right));
        return left.compareTo(right);
    }
    private static boolean integerType(String type) { return RANGE_TYPES.contains(type) && !"DATE".equals(type); }
    private static boolean dateType(String type) { return "DATE".equals(type) || type.startsWith("DATETIME"); }
    private static boolean rangeType(String type) { return RANGE_TYPES.contains(type) || dateType(type); }
    private static boolean listType(String type) { return LIST_TYPES.contains(type) || dateType(type) || type.startsWith("CHAR(") || type.startsWith("VARCHAR("); }
    private static String required(Map<String, String> values, String key) { String value = values.get(key); if (value == null || value.isBlank()) throw invalid("动态分区缺少属性：" + key); return value; }
    private static int integer(String value, String key) { try { return Integer.parseInt(value); } catch (NumberFormatException exception) { throw invalid(key + " 须为整数"); } }
    private static void bool(String value, String key) { if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) throw invalid(key + " 须为 true 或 false"); }
    private static String identifier(String value, String label) { try { return JdbcConnections.identifier(value == null ? null : value.trim()).toLowerCase(Locale.ROOT); } catch (IllegalArgumentException exception) { throw invalid(label + "仅支持字母、数字和下划线，最长64字符"); } }
    private static String upper(String value, String label) { String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT); if (result.isEmpty()) throw invalid(label + "不能为空"); return result; }
    private static boolean hasControl(String value) { return value.codePoints().anyMatch(Character::isISOControl); }
    private static boolean hasControlExceptNewline(String value) { return value.codePoints().anyMatch(cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\r'); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
