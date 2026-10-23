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
            if (url == null || !url.startsWith("jdbc:mysql://") || url.length() > 1000) throw new IllegalArgumentException();
            URI uri = URI.create(url.substring(5));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null ||
                    uri.getPath() == null || !uri.getPath().matches("/[A-Za-z0-9_]+")) throw new IllegalArgumentException();
            if (uri.getRawQuery() != null) for (String item : uri.getRawQuery().split("&")) {
                String name = URLDecoder.decode(item.split("=", 2)[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (!OPTIONS.contains(name)) throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) { throw new IllegalArgumentException("仅支持标准 MySQL JDBC URL，连接参数不在允许范围内"); }
    }
    public Connection open(String url, String user, String password) throws SQLException {
        validateUrl(url);
        Properties props = new Properties();
        props.setProperty("user", user); props.setProperty("password", password);
        props.setProperty("connectTimeout", "5000"); props.setProperty("socketTimeout", "30000");
        props.setProperty("allowMultiQueries", "false"); props.setProperty("allowLoadLocalInfile", "false");
        props.setProperty("autoDeserialize", "false");
        return DriverManager.getConnection(url, props);
    }
    public static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
            throw new IllegalArgumentException("表名/字段名仅支持字母、数字和下划线，最长64字符");
        return value;
    }
}

