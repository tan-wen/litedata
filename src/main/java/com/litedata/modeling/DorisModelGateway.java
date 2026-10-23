package com.litedata.modeling;

import com.litedata.config.ModelingProperties;
import com.litedata.config.PlatformProperties;
import com.litedata.data.JdbcConnections;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DorisModelGateway {
    public record Column(String name, String dataType, boolean nullable, String comment) {}
    public record Schema(List<Column> columns, List<String> uniqueKeys, List<String> hashColumns, int buckets) {}
    private final PlatformProperties properties;
    private final ModelingProperties modeling;
    private final JdbcConnections connections;

    public DorisModelGateway(PlatformProperties properties, ModelingProperties modeling, JdbcConnections connections) {
        this.properties = properties; this.modeling = modeling; this.connections = connections;
    }

    public void create(String ddl) throws SQLException {
        if (modeling.username() == null || modeling.username().isBlank())
            throw new IllegalStateException("未配置 Doris 建表账号，请配置 DORIS_WRITER_USER / DORIS_WRITER_PASSWORD");
        try (var connection = connections.open(properties.doris().jdbcUrl(), modeling.username(), modeling.password());
             var statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            // Keep generated literal escaping deterministic even when the server default uses NO_BACKSLASH_ESCAPES.
            statement.execute("SET SESSION sql_mode='ONLY_FULL_GROUP_BY'");
            statement.execute(ddl);
        }
    }

    public Schema inspect(String tableName) throws SQLException {
        JdbcConnections.identifier(tableName);
        var doris = properties.doris();
        try (var connection = connections.open(doris.jdbcUrl(), doris.username(), doris.password())) {
            var columns = new ArrayList<Column>();
            try (var statement = connection.prepareStatement("SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
                statement.setString(1, "warehouse"); statement.setString(2, tableName); statement.setQueryTimeout(15);
                try (var result = statement.executeQuery()) {
                    while (result.next()) columns.add(new Column(result.getString(1), result.getString(2),
                            "YES".equalsIgnoreCase(result.getString(3)), Objects.toString(result.getString(4), "")));
                }
            }
            if (columns.isEmpty()) return new Schema(List.of(), List.of(), List.of(), 0);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(15);
                try (var result = statement.executeQuery("SHOW CREATE TABLE `warehouse`.`" + tableName + "`")) {
                    if (!result.next()) throw new SQLException("No table definition returned");
                    String ddl = result.getString(2);
                    var bucketMatcher = Pattern.compile("(?i)\\bBUCKETS\\s+(\\d+)").matcher(ddl);
                    return new Schema(columns, columnsIn(ddl, "UNIQUE\\s+KEY"),
                            columnsIn(ddl, "DISTRIBUTED\\s+BY\\s+HASH"), bucketMatcher.find() ? Integer.parseInt(bucketMatcher.group(1)) : 0);
                }
            }
        }
    }

    private static List<String> columnsIn(String ddl, String prefix) {
        var matcher = Pattern.compile("(?i)" + prefix + "\\s*\\(([^)]+)\\)").matcher(ddl);
        if (!matcher.find()) return List.of();
        return Arrays.stream(matcher.group(1).split(","))
                .map(value -> value.replace("`", "").trim().toLowerCase(Locale.ROOT)).toList();
    }
}
