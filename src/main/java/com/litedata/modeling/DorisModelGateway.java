package com.litedata.modeling;

import com.litedata.config.ModelingProperties;
import com.litedata.config.PlatformProperties;
import com.litedata.data.JdbcConnections;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DorisModelGateway {
    public record BackendCapacity(int total, int available) {}
    public record Column(String name, String dataType, boolean nullable, String comment) {}
    public record Schema(List<Column> columns, List<String> keyColumns, List<String> hashColumns, int buckets,
                         String modelType, int replicationNum, String partitionType, String partitionColumn,
                         String partitionUnit, Map<String,String> aggregations, boolean mergeOnWrite, boolean autoBuckets,
                         String distributionType) {
        public Schema(List<Column> columns, List<String> keys, List<String> hashColumns, int buckets,
                      String modelType, int replicationNum, String partitionType, String partitionColumn,
                      String partitionUnit, Map<String,String> aggregations, boolean mergeOnWrite, boolean autoBuckets) {
            this(columns, keys, hashColumns, buckets, modelType, replicationNum, partitionType, partitionColumn, partitionUnit, aggregations, mergeOnWrite, autoBuckets, "HASH");
        }
        public Schema(List<Column> columns, List<String> keys, List<String> hashColumns, int buckets) {
            this(columns, keys, hashColumns, buckets, "UNIQUE", 1, "NONE", "", "DAY", Map.of(), true, false);
        }
        public Schema(List<Column> columns, List<String> keys, List<String> hashColumns, int buckets,
                      String modelType, int replicationNum, String partitionType, String partitionColumn,
                      String partitionUnit, Map<String,String> aggregations, boolean mergeOnWrite) {
            this(columns, keys, hashColumns, buckets, modelType, replicationNum, partitionType, partitionColumn, partitionUnit, aggregations, mergeOnWrite, false);
        }
    }
    private final PlatformProperties properties;
    private final ModelingProperties modeling;
    private final JdbcConnections connections;
    private final JsonMapper json = JsonMapper.builder().build();

    public DorisModelGateway(PlatformProperties properties, ModelingProperties modeling, JdbcConnections connections) {
        this.properties = properties; this.modeling = modeling; this.connections = connections;
    }

    /** The generated replication_num uses the default location tag and requires storage-capable BEs. */
    public BackendCapacity backendCapacity() throws SQLException {
        requireWriter();
        try (var connection = connections.open(properties.doris().jdbcUrl(), modeling.username(), modeling.password());
             var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var result = statement.executeQuery("SHOW BACKENDS")) {
                int total = 0, available = 0;
                while (result.next()) {
                    total++;
                    if (flag(result.getString("Alive"), true)
                            && flag(result.getString("SystemDecommissioned"), false)
                            && "mix".equalsIgnoreCase(result.getString("NodeRole"))
                            && defaultLocation(result.getString("Tag"))) available++;
                }
                return new BackendCapacity(total, available);
            }
        }
    }

    private boolean defaultLocation(String tag) throws SQLException {
        try {
            var value = json.readTree(tag);
            if (value == null || !value.isObject() || !value.path("location").isString())
                throw new SQLException("Invalid Doris backend tag metadata");
            return "default".equals(value.path("location").asString());
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new SQLException("Invalid Doris backend tag metadata");
        }
    }

    private static boolean flag(String value, boolean expected) {
        return expected ? "true".equalsIgnoreCase(value) || "1".equals(value)
                : "false".equalsIgnoreCase(value) || "0".equals(value);
    }

    private void requireWriter() {
        if (modeling.username() == null || modeling.username().isBlank())
            throw new IllegalStateException("未配置 Doris 建表账号，请配置 DORIS_WRITER_USER / DORIS_WRITER_PASSWORD");
    }

    public void create(String ddl) throws SQLException {
        requireWriter();
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
                    return parseSchema(columns, result.getString(2));
                }
            }
        }
    }

    static Schema parseSchema(List<Column> columns, String ddl) {
        var key = Pattern.compile("(?im)^\\s*(UNIQUE|DUPLICATE|AGGREGATE)\\s+KEY\\s*\\(([^)]+)\\)").matcher(ddl);
        String type = key.find() ? key.group(1).toUpperCase(Locale.ROOT) : "UNKNOWN";
        var keys = type.equals("UNKNOWN") ? List.<String>of() : names(key.group(2));
        var bucket = Pattern.compile("(?im)^\\s*DISTRIBUTED\\s+BY\\s+(HASH\\s*\\([^)]+\\)|RANDOM)\\s+BUCKETS\\s+(AUTO|\\d+)\\b").matcher(ddl);
        boolean hasDistribution = bucket.find();
        String distributionType = hasDistribution ? (bucket.group(1).equalsIgnoreCase("RANDOM") ? "RANDOM" : "HASH") : "UNKNOWN";
        String bucketValue = hasDistribution ? bucket.group(2) : "0";
        boolean autoBuckets = bucketValue.equalsIgnoreCase("AUTO") || "true".equalsIgnoreCase(property(ddl, "auto_bucket"));
        var auto = Pattern.compile("(?im)^\\s*AUTO\\s+PARTITION\\s+BY\\s+RANGE\\s*\\(\\s*date_trunc\\s*\\(\\s*`?([A-Za-z_][A-Za-z0-9_]*)`?\\s*,\\s*['\"](day|month|year)['\"]\\s*\\)\\s*\\)").matcher(ddl);
        String partitionType = Pattern.compile("(?im)^\\s*(?:AUTO\\s+)?PARTITION\\s+BY\\b").matcher(ddl).find() ? "OTHER" : "NONE";
        String partitionColumn = "", partitionUnit = "DAY";
        if (auto.find()) {
            partitionType = "AUTO_RANGE"; partitionColumn = auto.group(1).toLowerCase(Locale.ROOT);
            partitionUnit = auto.group(2).toUpperCase(Locale.ROOT);
        }
        int replicas = 0;
        String allocation = property(ddl, "replication_allocation");
        if (!allocation.isEmpty()) {
            for (String tag : allocation.split(",")) {
                var value = Pattern.compile("\\s*tag\\.location\\.[A-Za-z0-9_-]+\\s*:\\s*(\\d+)\\s*").matcher(tag);
                if (!value.matches()) { replicas = 0; break; }
                replicas += Integer.parseInt(value.group(1));
            }
        } else {
            String value = property(ddl, "replication_num");
            if (value.matches("\\d+")) replicas = Integer.parseInt(value);
        }
        var aggregations = new LinkedHashMap<String,String>();
        for (Column column : columns) {
            var function = Pattern.compile("(?im)^\\s*`" + Pattern.quote(column.name())
                    + "`\\s+[A-Za-z0-9_]+(?:\\s*\\([^)]*\\))?\\s+(SUM|MIN|MAX|REPLACE_IF_NOT_NULL|REPLACE)\\b").matcher(ddl);
            if (function.find()) aggregations.put(column.name().toLowerCase(Locale.ROOT), function.group(1).toUpperCase(Locale.ROOT));
        }
        return new Schema(columns, keys, columnsIn(ddl, "^\\s*DISTRIBUTED\\s+BY\\s+HASH"),
                bucketValue.equalsIgnoreCase("AUTO") ? 0 : Integer.parseInt(bucketValue), type, replicas, partitionType, partitionColumn,
                partitionUnit, Map.copyOf(aggregations), "true".equalsIgnoreCase(property(ddl, "enable_unique_key_merge_on_write")), autoBuckets, distributionType);
    }

    private static String property(String ddl, String name) {
        var matcher = Pattern.compile("(?im)^\\s*\"" + Pattern.quote(name) + "\"\\s*=\\s*\"([^\"]*)\"").matcher(ddl);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<String> columnsIn(String ddl, String prefix) {
        var matcher = Pattern.compile("(?im)" + prefix + "\\s*\\(([^)]+)\\)").matcher(ddl);
        if (!matcher.find()) return List.of();
        return names(matcher.group(1));
    }

    private static List<String> names(String values) {
        return Arrays.stream(values.split(","))
                .map(value -> value.replace("`", "").trim().toLowerCase(Locale.ROOT)).toList();
    }
}
