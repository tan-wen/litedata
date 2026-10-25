package com.litedata.data;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresSourceIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17-alpine");
    @Test void inspectsOnlyTheConfiguredSchemaAndReadsOrderedColumnsAndMicrosecondClock() throws Exception {
        try(var connection=open(); var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS source_data");
            statement.execute("CREATE TABLE source_data.source_orders(id bigint PRIMARY KEY, updated_at timestamp(6) NOT NULL)");
            statement.execute("CREATE INDEX ix_source_orders ON source_data.source_orders(updated_at)");
            statement.execute("CREATE TABLE public.source_orders(id bigint, updated_at text)");
        }
        String url=jdbcUrl()+"?currentSchema=source_data";
        try(var connection=new JdbcConnections().open(url,POSTGRES.getUsername(),POSTGRES.getPassword())) {
            var inspected=SourceTable.inspect(connection,DatabaseType.POSTGRESQL,"source_orders","updated_at");
            assertEquals("source_data",inspected.schema());
            assertEquals(java.util.List.of("id","updated_at"),inspected.columns());
            assertEquals(java.util.List.of("updated_at"),inspected.timestampColumns());
            assertNotNull(inspected.upperBound());
            assertEquals(0,inspected.upperBound().getNano()%1000);
        }
        var source=new com.litedata.model.DataSource(); source.setJdbcUrl(url);
        var sources=org.mockito.Mockito.mock(SourceService.class);
        org.mockito.Mockito.when(sources.require(1L)).thenReturn(source);
        org.mockito.Mockito.when(sources.connect(source)).thenAnswer(call->new JdbcConnections().open(url,POSTGRES.getUsername(),POSTGRES.getPassword()));
        var snapshot=new com.litedata.governance.JdbcMetadataCollector(sources,null,new JdbcConnections()).collect(1L);
        assertEquals("source_data",snapshot.catalog());
        assertEquals(1,snapshot.tables().size());
        assertEquals("source_orders",snapshot.tables().getFirst().name());
        assertTrue(snapshot.tables().getFirst().columns().getFirst().keyColumn());
    }
    @Test void rejectsTimezoneWatermarksAndPartialIndexes() throws Exception {
        try(var connection=open();var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE timezone_orders(id bigint, updated_at timestamptz NOT NULL)");
            statement.execute("CREATE INDEX ix_timezone ON timezone_orders(updated_at)");
            assertThrows(IllegalArgumentException.class,()->SourceTable.inspect(connection,DatabaseType.POSTGRESQL,"timezone_orders","updated_at"));
            statement.execute("CREATE TABLE partial_orders(id bigint, updated_at timestamp NOT NULL)");
            statement.execute("CREATE INDEX ix_partial ON partial_orders(updated_at) WHERE id>0");
            assertThrows(IllegalArgumentException.class,()->SourceTable.inspect(connection,DatabaseType.POSTGRESQL,"partial_orders","updated_at"));
        }
    }
    private String jdbcUrl() { return POSTGRES.getJdbcUrl().split("\\?",2)[0]; }
    private Connection open() throws SQLException { return new JdbcConnections().open(jdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
}
