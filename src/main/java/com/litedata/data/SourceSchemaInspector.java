package com.litedata.data;

import com.litedata.model.DataSource;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Reads source schema only; it never reads source business rows. */
@Component
public class SourceSchemaInspector {
    static final int MAX_TABLES = 1_000;
    static final int MAX_COLUMNS = 100;
    private final SourceService sources;

    public SourceSchemaInspector(SourceService sources) { this.sources = sources; }

    public List<TableRef> tables(long sourceId) {
        DataSource source = sources.require(sourceId);
        try (Connection connection = sources.connect(source)) {
            DatabaseType type = source.getDatabaseType();
            String schema = type.schema(connection);
            DatabaseMetaData metadata = connection.getMetaData();
            var tables = new ArrayList<TableRef>();
            var folded = new HashSet<String>();
            try (ResultSet rows = metadata.getTables(connection.getCatalog(), schema, "%", new String[] {"TABLE"})) {
                while (rows.next()) {
                    if (schema != null && !schema.equals(rows.getString("TABLE_SCHEM"))) continue;
                    if (tables.size() >= MAX_TABLES) throw new IllegalArgumentException("默认库/模式内表数量超过1000，拒绝返回不完整结果");
                    String name = JdbcConnections.identifier(rows.getString("TABLE_NAME"));
                    rejectFolded(folded, name, "表");
                    tables.add(new TableRef(name, text(rows.getString("REMARKS"))));
                }
            }
            tables.sort(Comparator.comparing(TableRef::name));
            return List.copyOf(tables);
        } catch (SQLException exception) { throw unavailable(exception); }
    }

    public Table inspect(long sourceId, String table) {
        DataSource source = sources.require(sourceId);
        try (Connection connection = sources.connect(source)) { return inspect(connection, source.getDatabaseType(), table); }
        catch (SQLException exception) { throw unavailable(exception); }
    }

    public static Table inspect(Connection connection, DatabaseType type, String table) throws SQLException {
        table = JdbcConnections.identifier(table);
        String schema = type.schema(connection), catalog = connection.getCatalog();
        DatabaseMetaData metadata = connection.getMetaData();
        String comment = tableComment(metadata, catalog, schema, table);
        Map<String, ColumnMetadata> columnMetadata = columnMetadata(metadata, catalog, schema, table);
        List<String> primaryKeys = primaryKeys(metadata, catalog, schema, table);
        String qualified = (schema == null ? "" : type.quote(schema) + ".") + type.quote(table);
        var columns = new ArrayList<Column>();
        var folded = new HashSet<String>();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet rows = statement.executeQuery("SELECT * FROM " + qualified + " WHERE 1=0")) {
                ResultSetMetaData result = rows.getMetaData();
                if (result.getColumnCount() > MAX_COLUMNS) throw new IllegalArgumentException("表 " + table + " 的字段数量超过100，当前建模不支持");
                Map<String, Integer> mysqlTimestampScales = type == DatabaseType.MYSQL && hasTimestamp(result) ? mysqlTimestampScales(connection, catalog, table) : Map.of();
                for (int i = 1; i <= result.getColumnCount(); i++) {
                    String name = JdbcConnections.identifier(result.getColumnName(i));
                    rejectFolded(folded, name, "字段");
                    int jdbcType = result.getColumnType(i);
                    ColumnMetadata detail = columnMetadata.get(name);
                    int scale = timestampScale(type, jdbcType, name, detail, mysqlTimestampScales, result.getScale(i));
                    columns.add(new Column(name, jdbcType, text(result.getColumnTypeName(i)), result.getPrecision(i), scale,
                            result.isNullable(i) != ResultSetMetaData.columnNoNulls, result.isSigned(i), detail == null ? "" : detail.comment()));
                }
            }
        }
        if (columns.isEmpty()) throw new IllegalArgumentException("表 " + table + " 未读取到字段，拒绝不完整结构");
        return new Table(schema, List.copyOf(columns), primaryKeys, comment);
    }

    private static String tableComment(DatabaseMetaData metadata, String catalog, String schema, String table) throws SQLException {
        try (ResultSet rows = metadata.getTables(catalog, schema, table, new String[] {"TABLE"})) {
            while (rows.next()) if (table.equals(rows.getString("TABLE_NAME")) && (schema == null || schema.equals(rows.getString("TABLE_SCHEM"))))
                return text(rows.getString("REMARKS"));
        }
        throw new IllegalArgumentException("未找到源表；请检查表名大小写及默认模式");
    }

    private static Map<String, ColumnMetadata> columnMetadata(DatabaseMetaData metadata, String catalog, String schema, String table) throws SQLException {
        var columns = new HashMap<String, ColumnMetadata>();
        try (ResultSet rows = metadata.getColumns(catalog, schema, table, "%")) {
            while (rows.next()) if (table.equals(rows.getString("TABLE_NAME")) && (schema == null || schema.equals(rows.getString("TABLE_SCHEM")))) {
                int digits = rows.getInt("DECIMAL_DIGITS");
                Integer decimalDigits = rows.wasNull() ? null : digits;
                columns.put(JdbcConnections.identifier(rows.getString("COLUMN_NAME")), new ColumnMetadata(text(rows.getString("REMARKS")), decimalDigits));
            }
        }
        return columns;
    }

    private static List<String> primaryKeys(DatabaseMetaData metadata, String catalog, String schema, String table) throws SQLException {
        var keys = new ArrayList<Map.Entry<Short, String>>();
        try (ResultSet rows = metadata.getPrimaryKeys(catalog, schema, table)) {
            while (rows.next()) keys.add(Map.entry(rows.getShort("KEY_SEQ"), JdbcConnections.identifier(rows.getString("COLUMN_NAME"))));
        }
        keys.sort(Map.Entry.comparingByKey());
        return keys.stream().map(Map.Entry::getValue).toList();
    }

    private static boolean hasTimestamp(ResultSetMetaData result) throws SQLException {
        for (int i = 1; i <= result.getColumnCount(); i++) if (result.getColumnType(i) == Types.TIMESTAMP) return true;
        return false;
    }

    private static Map<String, Integer> mysqlTimestampScales(Connection connection, String catalog, String table) throws SQLException {
        var scales = new HashMap<String, Integer>();
        String sql = "SELECT COLUMN_NAME,DATETIME_PRECISION FROM information_schema.COLUMNS "
                + "WHERE BINARY TABLE_SCHEMA=BINARY ? AND BINARY TABLE_NAME=BINARY ? AND DATETIME_PRECISION IS NOT NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, catalog); statement.setString(2, table); statement.setQueryTimeout(10);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int precision = rows.getInt("DATETIME_PRECISION");
                    if (!rows.wasNull()) scales.put(JdbcConnections.identifier(rows.getString("COLUMN_NAME")), precision);
                }
            }
        }
        return scales;
    }

    private static int timestampScale(DatabaseType type, int jdbcType, String name, ColumnMetadata detail,
                                      Map<String, Integer> mysqlTimestampScales, int resultScale) {
        if (jdbcType != Types.TIMESTAMP) return resultScale;
        if (type == DatabaseType.MYSQL) {
            Integer precision = mysqlTimestampScales.get(name);
            if (precision == null) throw new IllegalArgumentException("无法读取 MySQL 时间字段 " + name + " 的小数秒精度，拒绝不确定映射");
            return precision;
        }
        return detail != null && detail.decimalDigits() != null ? detail.decimalDigits() : resultScale;
    }

    private static void rejectFolded(Set<String> names, String name, String kind) {
        if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException(kind + "名大小写折叠冲突，不能映射到 Doris 小写模型");
    }
    private static String text(String value) { return value == null ? "" : value; }
    private static IllegalArgumentException unavailable(SQLException ignored) { return new IllegalArgumentException("读取源表结构失败，请检查连接、权限和默认模式"); }

    private record ColumnMetadata(String comment, Integer decimalDigits) {}

    public record Column(String name, int jdbcType, String typeName, int precision, int scale, boolean nullable, boolean signed, String comment) {}
    public record Table(String schema, List<Column> columns, List<String> primaryKeys, String comment) {}
    public record TableRef(String name, String comment) {}
}
