package com.litedata.governance;

import com.litedata.config.PlatformProperties;
import com.litedata.data.JdbcConnections;
import com.litedata.data.SourceService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Reads only schema metadata from a registered MySQL source or the Doris warehouse. */
@Component
public class JdbcMetadataCollector {
    static final int MAX_TABLES = 2_000;
    static final int MAX_COLUMNS_PER_TABLE = 500;
    private static final String TABLES_SQL = "SELECT TABLE_NAME,TABLE_COMMENT FROM information_schema.TABLES "
            + "WHERE TABLE_SCHEMA=? AND TABLE_TYPE IN (?,?) ORDER BY TABLE_NAME";
    private static final String COLUMNS_SQL = "SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_KEY,COLUMN_COMMENT,ORDINAL_POSITION "
            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
    private static final String COLUMNS_WITHOUT_KEY_SQL = "SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_COMMENT,ORDINAL_POSITION "
            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";

    private final SourceService sources;
    private final PlatformProperties properties;
    private final JdbcConnections connections;

    public JdbcMetadataCollector(SourceService sources, PlatformProperties properties, JdbcConnections connections) {
        this.sources = sources;
        this.properties = properties;
        this.connections = connections;
    }

    public Snapshot collect(Long sourceId) throws SQLException {
        try (Connection connection = connection(sourceId)) {
            String catalog = sourceId == null ? "warehouse" : JdbcConnections.identifier(connection.getCatalog());
            return new Snapshot(catalog, tables(connection, catalog));
        }
    }

    private Connection connection(Long sourceId) throws SQLException {
        if (sourceId != null) return sources.connect(sources.require(sourceId));
        var doris = properties.doris();
        if (doris == null) throw new IllegalStateException("未配置 Doris 只读采集连接");
        return connections.open(doris.jdbcUrl(), doris.username(), doris.password());
    }

    private List<Table> tables(Connection connection, String catalog) throws SQLException {
        var tables = new ArrayList<Table>();
        try (PreparedStatement statement = connection.prepareStatement(TABLES_SQL)) {
            statement.setString(1, catalog);
            statement.setString(2, "BASE TABLE");
            statement.setString(3, "VIEW");
            statement.setQueryTimeout(15);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (tables.size() == MAX_TABLES) throw new IllegalArgumentException("元数据表数量超过2000，拒绝采集部分结果");
                    String name = JdbcConnections.identifier(result.getString(1));
                    tables.add(new Table(name, Objects.toString(result.getString(2), ""), columns(connection, catalog, name)));
                }
            }
        }
        return List.copyOf(tables);
    }

    private List<Column> columns(Connection connection, String catalog, String table) throws SQLException {
        try {
            return columns(connection, catalog, table, true);
        } catch (SQLException exception) {
            if (!unsupportedColumnKey(exception)) throw exception;
            return columns(connection, catalog, table, false);
        }
    }

    private List<Column> columns(Connection connection, String catalog, String table, boolean includeKey) throws SQLException {
        var columns = new ArrayList<Column>();
        try (PreparedStatement statement = connection.prepareStatement(includeKey ? COLUMNS_SQL : COLUMNS_WITHOUT_KEY_SQL)) {
            statement.setString(1, catalog);
            statement.setString(2, table);
            statement.setQueryTimeout(15);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (columns.size() == MAX_COLUMNS_PER_TABLE) throw new IllegalArgumentException("表 " + table + " 的字段数量超过500，拒绝采集部分结果");
                    String name = JdbcConnections.identifier(result.getString(1));
                    columns.add(new Column(name, Objects.toString(result.getString(2), ""), "YES".equalsIgnoreCase(result.getString(3)),
                            includeKey && "PRI".equalsIgnoreCase(result.getString(4)),
                            Objects.toString(result.getString(includeKey ? 5 : 4), ""), result.getInt(includeKey ? 6 : 5)));
                }
            }
        }
        if (columns.isEmpty()) throw new IllegalArgumentException("表 " + table + " 未读取到字段，拒绝采集不完整结构");
        return List.copyOf(columns);
    }

    private static boolean unsupportedColumnKey(SQLException exception) {
        String message = exception.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("column_key")
                && (message.toLowerCase(java.util.Locale.ROOT).contains("unknown") || message.toLowerCase(java.util.Locale.ROOT).contains("not found"));
    }

    public record Column(String name, String dataType, boolean nullable, boolean keyColumn, String comment, int position) {}
    public record Table(String name, String comment, List<Column> columns) {}
    public record Snapshot(String catalog, List<Table> tables) {}
}
