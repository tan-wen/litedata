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

/** Generates the deliberately small, fixed Doris DDL surface used by data models. */
@Component
public class DorisDdl {
    public static final List<String> TYPE_HINTS = List.of(
            "TINYINT", "SMALLINT", "INT", "BIGINT", "LARGEINT", "BOOLEAN", "DATE", "DATETIME",
            "CHAR(n)", "VARCHAR(n)", "STRING", "DECIMAL(p,s)", "FLOAT", "DOUBLE");
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
        if (model.getBuckets() < 1 || model.getBuckets() > 32) throw invalid("分桶数须在 1 到 32 之间");
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
            if (field.isKeyColumn()) {
                if (encounteredNonKey) throw invalid("主键字段必须位于字段列表前缀：" + name);
                if (field.isNullable()) throw invalid("主键字段不能为可空：" + name);
                if (Set.of("FLOAT", "DOUBLE", "STRING").contains(type))
                    throw invalid("主键字段不支持 " + type + "：" + name);
                keys.add(name);
            } else encounteredNonKey = true;
            columns.add("  `" + name + "` " + type + (field.isNullable() ? " NULL" : " NOT NULL")
                    + " COMMENT '" + sqlComment(field.getComment()) + "'");
        }
        if (keys.isEmpty()) throw invalid("至少需要一个主键字段");

        String tableComment = model.getDescription().isBlank() ? model.getName()
                : model.getName() + " - " + model.getDescription();
        return "CREATE TABLE `warehouse`.`" + model.getTableName() + "` (\n"
                + String.join(",\n", columns) + "\n)\nENGINE=OLAP\nUNIQUE KEY(" + quoted(keys) + ")\nCOMMENT '"
                + sqlComment(tableComment) + "'\nDISTRIBUTED BY HASH(" + quoted(keys) + ") BUCKETS " + model.getBuckets()
                + "\nPROPERTIES (\n  \"replication_num\" = \"1\",\n  \"enable_unique_key_merge_on_write\" = \"true\"\n);";
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
        if (!Set.of("ODS", "DWD", "DWS", "ADS").contains(normalized)) throw invalid("分层仅支持 ODS、DWD、DWS、ADS");
        return normalized;
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
