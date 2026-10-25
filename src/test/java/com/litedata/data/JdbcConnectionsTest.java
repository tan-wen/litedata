package com.litedata.data;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class JdbcConnectionsTest {
    private final JdbcConnections connections=new JdbcConnections();
    @Test void supportsPackagedDriversAndStandardUrls() throws Exception {
        String[] urls={"jdbc:mysql://host:3306/sales?useSSL=false", "jdbc:postgresql://host:5432/sales?currentSchema=source_data&sslmode=require",
                "jdbc:sqlserver://host:1433;databaseName=sales;encrypt=true", "jdbc:oracle:thin:@//host:1521/FREEPDB1"};
        for(String url:urls) {
            connections.validateUrl(url);
            assertNotNull(DriverManager.getDriver(url));
        }
        assertEquals("SELECT 1 FROM DUAL",DatabaseType.ORACLE.getTestSql());
    }
    @Test void rejectsCredentialInjectionDriverHooksAndAmbiguousSchemas() {
        for(String url: new String[]{"jdbc:mysql://user:secret@host/db", "jdbc:mysql://host/db?autoDeserialize=true",
                "jdbc:postgresql://host/db?socketFactory=example.Factory", "jdbc:postgresql://host/db?currentSchema=public,private",
                "jdbc:postgresql://host/db?currentSchema=public&currentSchema=other", "jdbc:sqlserver://host;databaseName=db;user=sa",
                "jdbc:sqlserver://host;databaseName=db;authentication=ActiveDirectoryIntegrated", "jdbc:sqlserver://host;databaseName=db;encrypt=true;encrypt=false",
                "jdbc:oracle:thin:user/password@host:1521:SID", "jdbc:oracle:thin:@//host:1521/service?password=secret", "jdbc:h2:mem:test",
                "jdbc:mysql://host:99999/db"})
            assertThrows(IllegalArgumentException.class,()->connections.validateUrl(url),url);
    }
}
