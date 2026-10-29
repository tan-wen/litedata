package com.litedata.pipeline;

import com.litedata.data.DatabaseType;
import com.litedata.data.SourceSchemaInspector;
import java.sql.Types;
import java.util.Locale;
import java.util.Set;

/** Conservative, lossless mapping from a JDBC source column to a Doris ODS type. */
public final class OdsTypeMapping {
    private OdsTypeMapping() {}

    public static String convert(DatabaseType source, SourceSchemaInspector.Column column) {
        String name = column.name();
        String type = column.typeName() == null ? "" : column.typeName().trim().toUpperCase(Locale.ROOT);
        int jdbc = column.jdbcType();
        if(jdbc==Types.TIMESTAMP_WITH_TIMEZONE || jdbc==Types.TIME_WITH_TIMEZONE)
            throw unsupported(name,type,"带时区时间不能无损映射");
        if (jdbc == Types.BOOLEAN || type.equals("BOOL") || type.equals("BOOLEAN")) return "TINYINT";
        if (jdbc == Types.BIT || type.equals("BIT")) {
            if (column.precision() == 1) return "TINYINT";
            throw unsupported(name, type, "多位 BIT 不能无损映射");
        }
        if (integer(jdbc, type)) return integerType(type, jdbc, column.signed(), name);
        if (jdbc == Types.DECIMAL || jdbc == Types.NUMERIC || type.equals("DECIMAL") || type.equals("NUMERIC")) {
            if (column.precision() < 1 || column.precision() > 38 || column.scale() < 0 || column.scale() > column.precision())
                throw unsupported(name, type, "DECIMAL 精度须为 1..38 且 scale 不大于 precision");
            return "DECIMAL(" + column.precision() + "," + column.scale() + ")";
        }
        if (jdbc == Types.DOUBLE || type.contains("DOUBLE")) return "DOUBLE";
        if (jdbc == Types.REAL) return "FLOAT";
        if (jdbc == Types.FLOAT || type.equals("FLOAT")) {
            if(source==DatabaseType.ORACLE && column.precision()>53)
                throw unsupported(name,type,"Oracle FLOAT 精度超过 DOUBLE 的可靠范围");
            return "DOUBLE";
        }
        if (jdbc == Types.DATE || type.equals("DATE")) return source == DatabaseType.ORACLE && type.equals("DATE") ? "DATETIME" : "DATE";
        if (jdbc == Types.TIMESTAMP || type.equals("DATETIME") || type.equals("DATETIME2") || type.startsWith("TIMESTAMP"))
            return datetime(column, type);
        if (jdbc == Types.CHAR || jdbc == Types.VARCHAR || jdbc == Types.NCHAR || jdbc == Types.NVARCHAR
                || type.equals("CHAR") || type.equals("VARCHAR") || type.equals("NCHAR") || type.equals("NVARCHAR")) {
            if (column.precision() < 1) throw unsupported(name, type, "字符长度无效");
            long bytes = (long) column.precision() * 4;
            return bytes <= 65533 ? "VARCHAR(" + bytes + ")" : "STRING";
        }
        if (jdbc == Types.LONGVARCHAR || jdbc == Types.LONGNVARCHAR || jdbc == Types.CLOB || jdbc == Types.NCLOB
                || type.contains("TEXT")) return "STRING";
        throw unsupported(name, type, "该 JDBC 类型尚不支持");
    }

    private static boolean integer(int jdbc, String type) {
        return jdbc == Types.TINYINT || jdbc == Types.SMALLINT || jdbc == Types.INTEGER || jdbc == Types.BIGINT
                || type.matches("(?:TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT)(?:\\s+UNSIGNED)?");
    }

    private static String integerType(String type, int jdbc, boolean signed, String name) {
        boolean unsigned = !signed || type.endsWith(" UNSIGNED");
        String base = type.replace(" UNSIGNED", "");
        base = switch (base) { case "INT2" -> "SMALLINT"; case "INT4" -> "INT"; case "INT8" -> "BIGINT"; default -> base; };
        if (!Set.of("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT").contains(base)) base = switch (jdbc) {
            case Types.TINYINT -> "TINYINT"; case Types.SMALLINT -> "SMALLINT"; case Types.INTEGER -> "INT"; case Types.BIGINT -> "BIGINT";
            default -> base;
        };
        if (base.equals("MEDIUMINT")) base = "INT";
        if (!unsigned) return switch (base) {
            case "TINYINT" -> "TINYINT"; case "SMALLINT" -> "SMALLINT";
            case "INT", "INTEGER" -> "INT"; case "BIGINT" -> "BIGINT";
            default -> throw unsupported(name, type, "整数类型无法确认");
        };
        return switch (base) {
            case "TINYINT" -> "SMALLINT"; case "SMALLINT" -> "INT";
            case "INT", "INTEGER", "MEDIUMINT" -> "BIGINT";
            case "BIGINT" -> throw unsupported(name, type, "BIGINT UNSIGNED 尚不能保证无损");
            default -> throw unsupported(name, type, "整数类型无法确认");
        };
    }

    private static String datetime(SourceSchemaInspector.Column column, String type) {
        if (type.contains("TIME ZONE") || type.contains("TZ"))
            throw unsupported(column.name(), type, "带时区时间不能无损映射");
        int scale = column.scale();
        if (scale < 0 || scale > 6) throw unsupported(column.name(), type, "时间精度须为 0..6");
        return scale == 0 ? "DATETIME" : "DATETIME(" + scale + ")";
    }

    private static IllegalArgumentException unsupported(String name, String type, String reason) {
        return new IllegalArgumentException("字段 " + name + " 的类型 " + (type.isEmpty() ? "未知" : type) + " 不支持：" + reason);
    }
}
