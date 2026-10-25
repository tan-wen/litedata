package com.litedata.data;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/** Source dialects; the platform store and warehouse remain MySQL and Doris. */
public enum DatabaseType {
    MYSQL("MySQL", "jdbc:mysql://", "mysqlreader", "SELECT 1", "SELECT CURRENT_TIMESTAMP(6)"),
    POSTGRESQL("PostgreSQL", "jdbc:postgresql://", "postgresqlreader", "SELECT 1", "SELECT LOCALTIMESTAMP(6)"),
    SQLSERVER("SQL Server", "jdbc:sqlserver://", "sqlserverreader", "SELECT 1", "SELECT SYSDATETIME()"),
    ORACLE("Oracle", "jdbc:oracle:thin:@//", "oraclereader", "SELECT 1 FROM DUAL", "SELECT LOCALTIMESTAMP FROM DUAL");

    private final String label, prefix, reader, testSql, clockSql;
    DatabaseType(String label,String prefix,String reader,String testSql,String clockSql) {
        this.label=label; this.prefix=prefix; this.reader=reader; this.testSql=testSql; this.clockSql=clockSql;
    }
    public String getLabel() { return label; }
    public String getPrefix() { return prefix; }
    public String getReader() { return reader; }
    public String getTestSql() { return testSql; }
    public String getClockSql() { return clockSql; }
    public static DatabaseType fromUrl(String url) {
        if(url!=null) for(var type:values()) if(url.startsWith(type.prefix)) return type;
        throw new IllegalArgumentException("仅支持 MySQL、PostgreSQL、SQL Server 和 Oracle 标准 JDBC 地址");
    }
    public String namespace(String url,String username) {
        if(this==SQLSERVER) return "dbo";
        if(this==ORACLE) return JdbcConnections.identifier(username.toUpperCase(Locale.ROOT));
        var uri=java.net.URI.create(url.substring(5));
        if(this==MYSQL) return JdbcConnections.identifier(uri.getPath().substring(1));
        if(uri.getRawQuery()!=null) for(String item:uri.getRawQuery().split("&")) {
            String[] pair=item.split("=",2);
            if(pair.length==2 && pair[0].equals("currentSchema"))
                return JdbcConnections.identifier(java.net.URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8));
        }
        return "public";
    }
    public String schema(Connection connection) throws SQLException {
        return switch(this) {
            case MYSQL -> null;
            case POSTGRESQL -> JdbcConnections.identifier(connection.getSchema());
            case SQLSERVER -> "dbo";
            case ORACLE -> JdbcConnections.identifier(connection.getMetaData().getUserName().toUpperCase(Locale.ROOT));
        };
    }
    public String quote(String name) {
        name=JdbcConnections.identifier(name);
        return switch(this) { case MYSQL -> "`"+name+"`"; case SQLSERVER -> "["+name+"]"; default -> "\""+name+"\""; };
    }
}
