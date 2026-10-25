package com.litedata.data;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class JdbcConnections {
    private static final Set<String> OPTIONS = Set.of("usessl", "sslmode", "servertimezone",
            "characterencoding", "connecttimeout", "sockettimeout", "allowpublickeyretrieval", "zerodatetimebehavior");
    public void validateUrl(String url) {
        try {
            DatabaseType type=DatabaseType.fromUrl(url);
            if (url.length() > 1000 || url.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException();
            if(type==DatabaseType.SQLSERVER) { validateSqlServer(url); return; }
            if(type==DatabaseType.ORACLE) {
                if(!url.matches("jdbc:oracle:thin:@//[A-Za-z0-9._-]+:[0-9]{1,5}/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException();
                URI oracle=URI.create("oracle://"+url.substring(type.getPrefix().length()));
                if(oracle.getPort()<1 || oracle.getPort()>65535) throw new IllegalArgumentException();
                return;
            }
            URI uri = URI.create(url.substring(5));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null ||
                    uri.getPort()==0 || uri.getPort()>65535 || uri.getPath() == null || !uri.getPath().matches("/[A-Za-z0-9_]+")) throw new IllegalArgumentException();
            Set<String> allowed=type==DatabaseType.MYSQL?OPTIONS:Set.of("sslmode","connecttimeout","sockettimeout","currentschema","applicationname");
            Set<String> seen=new HashSet<>();
            if (uri.getRawQuery() != null) for (String item : uri.getRawQuery().split("&")) {
                String[] pair=item.split("=",2);
                String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (pair.length!=2 || !allowed.contains(name) || !seen.add(name)) throw new IllegalArgumentException();
                if(name.equals("currentschema")) {
                    if(!pair[0].equals("currentSchema")) throw new IllegalArgumentException();
                    identifier(URLDecoder.decode(pair[1],StandardCharsets.UTF_8));
                }
            }
        } catch (RuntimeException e) { throw new IllegalArgumentException("数据库 JDBC 地址或连接参数不在允许范围内；请按所选类型的示例填写"); }
    }
    private static void validateSqlServer(String url) {
        String[] parts=url.substring("jdbc:sqlserver://".length()).split(";",-1);
        URI server=URI.create("sqlserver://"+parts[0]);
        if(server.getHost()==null || server.getUserInfo()!=null || server.getPort()==0 || server.getPort()>65535 ||
                !server.getPath().isEmpty() || server.getQuery()!=null || server.getFragment()!=null) throw new IllegalArgumentException();
        Set<String> seen=new HashSet<>();
        for(int i=1;i<parts.length;i++) {
            String[] pair=parts[i].split("=",2);
            String name=pair[0].toLowerCase(Locale.ROOT);
            if(pair.length!=2 || !Set.of("databasename","encrypt","trustservercertificate","logintimeout","sockettimeout").contains(name) || !seen.add(name)) throw new IllegalArgumentException();
            if(name.equals("databasename")) identifier(pair[1]);
            else if(!pair[1].matches("[A-Za-z0-9]+")) throw new IllegalArgumentException();
        }
        if(!seen.contains("databasename")) throw new IllegalArgumentException();
    }
    public Connection open(String url, String user, String password) throws SQLException {
        validateUrl(url);
        Properties props = new Properties();
        props.setProperty("user", user); props.setProperty("password", password);
        switch(DatabaseType.fromUrl(url)) {
            case MYSQL -> {
                props.setProperty("connectTimeout", "5000"); props.setProperty("socketTimeout", "30000");
                props.setProperty("allowMultiQueries", "false"); props.setProperty("allowLoadLocalInfile", "false");
                props.setProperty("autoDeserialize", "false");
            }
            case POSTGRESQL -> { props.setProperty("connectTimeout","5"); props.setProperty("socketTimeout","30"); props.setProperty("currentSchema","public"); }
            case SQLSERVER -> { props.setProperty("loginTimeout","5"); props.setProperty("socketTimeout","30000"); }
            case ORACLE -> { props.setProperty("oracle.net.CONNECT_TIMEOUT","5000"); props.setProperty("oracle.jdbc.ReadTimeout","30000"); }
        }
        return DriverManager.getConnection(url, props);
    }
    public static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
            throw new IllegalArgumentException("表名/字段名仅支持字母、数字和下划线，最长64字符");
        return value;
    }
}

