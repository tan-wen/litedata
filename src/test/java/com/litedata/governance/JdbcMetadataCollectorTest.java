package com.litedata.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.litedata.config.PlatformProperties;
import com.litedata.data.JdbcConnections;
import com.litedata.data.SourceService;
import com.litedata.model.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JdbcMetadataCollectorTest {
    @Test void collectsRegisteredSourceWithParameterizedMetadataQueriesAndClosesResources() throws Exception {
        SourceService sources = mock(SourceService.class);
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        PreparedStatement tables = mock(PreparedStatement.class);
        PreparedStatement columns = mock(PreparedStatement.class);
        ResultSet tableRows = rows(List.<Object[]>of(new Object[] {"orders", "Orders"}));
        ResultSet columnRows = rows(List.of(new Object[] {"id", "bigint", "NO", "PRI", "identifier", 1},
                new Object[] {"amount", "decimal(12,2)", "YES", "", null, 2}));
        DataSource source = new DataSource();
        source.setJdbcUrl("jdbc:mysql://source.example/sales");
        when(sources.require(9L)).thenReturn(source);
        when(sources.connect(source)).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("sales");
        when(connection.prepareStatement(anyString())).thenAnswer(call -> call.getArgument(0, String.class).contains("information_schema.TABLES") ? tables : columns);
        when(tables.executeQuery()).thenReturn(tableRows);
        when(columns.executeQuery()).thenReturn(columnRows);

        JdbcMetadataCollector.Snapshot snapshot = collector(sources, connections).collect(9L);

        assertThat(snapshot.catalog()).isEqualTo("sales");
        assertThat(snapshot.tables()).containsExactly(new JdbcMetadataCollector.Table("orders", "Orders", List.of(
                new JdbcMetadataCollector.Column("id", "bigint", false, true, "identifier", 1),
                new JdbcMetadataCollector.Column("amount", "decimal(12,2)", true, false, "", 2))));
        verify(tables).setString(1, "sales");
        verify(tables).setString(2, "BASE TABLE");
        verify(tables).setString(3, "VIEW");
        verify(columns).setString(1, "sales");
        verify(columns).setString(2, "orders");
        verify(tables).setQueryTimeout(15);
        verify(columns).setQueryTimeout(15);
        verify(tableRows).close(); verify(columnRows).close(); verify(tables).close(); verify(columns).close(); verify(connection).close();
        verifyNoInteractions(connections);
    }

    @Test void usesConfiguredDorisReadOnlyConnectionWhenSourceIsNull() throws Exception {
        SourceService sources = mock(SourceService.class);
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        PreparedStatement tables = mock(PreparedStatement.class);
        when(connections.open("jdbc:mysql://doris.example/warehouse", "reader", "secret")).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("platform");
        ResultSet noTables = rows(List.of());
        when(connection.prepareStatement(anyString())).thenReturn(tables);
        when(tables.executeQuery()).thenReturn(noTables);

        JdbcMetadataCollector.Snapshot snapshot = collector(sources, connections).collect(null);

        assertThat(snapshot.catalog()).isEqualTo("warehouse");
        assertThat(snapshot.tables()).isEmpty();
        verify(tables).setString(1, "warehouse");

        verify(connections).open("jdbc:mysql://doris.example/warehouse", "reader", "secret");
        verifyNoInteractions(sources);
        verify(connection).close();
    }

    @Test void rejectsEmptyCatalogAndClosesConnection() throws Exception {
        SourceService sources = mock(SourceService.class);
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        DataSource source = new DataSource();
        source.setJdbcUrl("jdbc:mysql://source.example/sales");
        when(sources.require(9L)).thenReturn(source);
        when(sources.connect(source)).thenReturn(connection);
        when(connection.getCatalog()).thenReturn(null);

        assertThatIllegalArgumentException().isThrownBy(() -> collector(sources, connections).collect(9L));

        verify(connection).close();
    }

    @Test void collectsPostgresMetadataOnlyFromCurrentSchemaAndClosesResources() throws Exception {
        SourceService sources = mock(SourceService.class);
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        DataSource source = new DataSource();
        source.setJdbcUrl("jdbc:postgresql://source.example:5432/sales");
        when(sources.require(9L)).thenReturn(source);
        when(sources.connect(source)).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("sales");
        when(connection.getSchema()).thenReturn("reporting");
        when(connection.getMetaData()).thenReturn(metadata);
        ResultSet tables = metadataRows(List.of(
                Map.of("TABLE_SCHEM", "other", "TABLE_NAME", "orders", "REMARKS", "wrong schema"),
                Map.of("TABLE_SCHEM", "reporting", "TABLE_NAME", "orders", "REMARKS", "Orders")));
        ResultSet keys = metadataRows(List.of(Map.of("COLUMN_NAME", "id")));
        ResultSet columns = metadataRows(List.of(
                Map.of("TABLE_SCHEM", "other", "TABLE_NAME", "orders", "COLUMN_NAME", "ignored", "TYPE_NAME", "text", "NULLABLE", 1, "REMARKS", "", "ORDINAL_POSITION", 1),
                Map.of("TABLE_SCHEM", "reporting", "TABLE_NAME", "orders", "COLUMN_NAME", "id", "TYPE_NAME", "int8", "NULLABLE", 0, "REMARKS", "identifier", "ORDINAL_POSITION", 1),
                Map.of("TABLE_SCHEM", "reporting", "TABLE_NAME", "orders", "COLUMN_NAME", "amount", "TYPE_NAME", "numeric", "NULLABLE", 1, "REMARKS", "", "ORDINAL_POSITION", 2)));
        when(metadata.getTables(eq("sales"), eq("reporting"), eq("%"), any(String[].class))).thenReturn(tables);
        when(metadata.getPrimaryKeys("sales", "reporting", "orders")).thenReturn(keys);
        when(metadata.getColumns("sales", "reporting", "orders", "%")).thenReturn(columns);

        JdbcMetadataCollector.Snapshot snapshot = collector(sources, connections).collect(9L);

        assertThat(snapshot).isEqualTo(new JdbcMetadataCollector.Snapshot("reporting", List.of(
                new JdbcMetadataCollector.Table("orders", "Orders", List.of(
                        new JdbcMetadataCollector.Column("id", "int8", false, true, "identifier", 1),
                        new JdbcMetadataCollector.Column("amount", "numeric", true, false, "", 2))))));
        verify(metadata).getTables(eq("sales"), eq("reporting"), eq("%"), argThat(types -> Arrays.equals(types, new String[] {"TABLE", "VIEW"})));
        verify(tables).close(); verify(keys).close(); verify(columns).close(); verify(connection).close();
        verifyNoInteractions(connections);
    }

    @Test void propagatesSqlFailureAndClosesOpenedResources() throws Exception {
        SourceService sources = mock(SourceService.class);
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        PreparedStatement tables = mock(PreparedStatement.class);
        when(connections.open(anyString(), anyString(), anyString())).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("warehouse");
        when(connection.prepareStatement(anyString())).thenReturn(tables);
        SQLException failure = new SQLException("metadata permission denied", "42000");
        when(tables.executeQuery()).thenThrow(failure);

        assertThatThrownBy(() -> collector(sources, connections).collect(null)).isSameAs(failure);

        verify(tables).close(); verify(connection).close();
    }

    @Test void rejectsTableLimitBeforeReturningPartialSnapshot() throws Exception {
        SourceService sources = mock(SourceService.class);
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        PreparedStatement tables = mock(PreparedStatement.class);
        PreparedStatement columns = mock(PreparedStatement.class);
        when(connections.open(anyString(), anyString(), anyString())).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("warehouse");
        ResultSet tooManyTables = repeatingRows(JdbcMetadataCollector.MAX_TABLES + 1, new Object[] {"table_name", "comment"});
        when(connection.prepareStatement(anyString())).thenAnswer(call -> call.getArgument(0, String.class).contains("information_schema.TABLES") ? tables : columns);
        when(tables.executeQuery()).thenReturn(tooManyTables);
        when(columns.executeQuery()).thenAnswer(call -> rows(List.<Object[]>of(new Object[] {"field_name", "varchar(20)", "YES", "", "", 1})));

        assertThatIllegalArgumentException().isThrownBy(() -> collector(sources, connections).collect(null))
                .withMessageContaining("超过2000");

        verify(tables).close(); verify(columns, atLeastOnce()).close(); verify(connection).close();
    }

    @Test void rejectsColumnLimitBeforeReturningPartialSnapshot() throws Exception {
        SourceService sources = mock(SourceService.class);
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        PreparedStatement tables = mock(PreparedStatement.class);
        PreparedStatement columns = mock(PreparedStatement.class);
        when(connections.open(anyString(), anyString(), anyString())).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("warehouse");
        ResultSet oneTable = rows(List.<Object[]>of(new Object[] {"orders", ""}));
        ResultSet tooManyColumns = repeatingRows(JdbcMetadataCollector.MAX_COLUMNS_PER_TABLE + 1,
                new Object[] {"field_name", "varchar(20)", "YES", "", "", 1});
        when(connection.prepareStatement(anyString())).thenAnswer(call -> call.getArgument(0, String.class).contains("information_schema.TABLES") ? tables : columns);
        when(tables.executeQuery()).thenReturn(oneTable);
        when(columns.executeQuery()).thenReturn(tooManyColumns);

        assertThatIllegalArgumentException().isThrownBy(() -> collector(sources, connections).collect(null))
                .withMessageContaining("字段数量超过500");

        verify(tables).close(); verify(columns).close(); verify(connection).close();
    }

    @Test void rejectsTableWithNoColumnsBeforeReturningPartialSnapshot() throws Exception {
        SourceService sources = mock(SourceService.class);
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        PreparedStatement tables = mock(PreparedStatement.class);
        PreparedStatement columns = mock(PreparedStatement.class);
        when(connections.open(anyString(), anyString(), anyString())).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> call.getArgument(0, String.class).contains("information_schema.TABLES") ? tables : columns);
        ResultSet oneTable = rows(List.<Object[]>of(new Object[] {"orders", ""}));
        ResultSet noColumns = rows(List.of());
        when(tables.executeQuery()).thenReturn(oneTable);
        when(columns.executeQuery()).thenReturn(noColumns);

        assertThatIllegalArgumentException().isThrownBy(() -> collector(sources, connections).collect(null))
                .withMessageContaining("未读取到字段");

        verify(tables).close(); verify(columns).close(); verify(connection).close();
    }

    private static JdbcMetadataCollector collector(SourceService sources, JdbcConnections connections) {
        return new JdbcMetadataCollector(sources, new PlatformProperties("key", "admin", "token", null,
                new PlatformProperties.Doris("jdbc:mysql://doris.example/warehouse", "reader", "secret")), connections);
    }

    private static ResultSet rows(List<Object[]> values) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        AtomicInteger index = new AtomicInteger(-1);
        when(result.next()).thenAnswer(call -> index.incrementAndGet() < values.size());
        when(result.getString(anyInt())).thenAnswer(call -> (String) values.get(index.get())[call.getArgument(0, Integer.class) - 1]);
        when(result.getInt(anyInt())).thenAnswer(call -> (Integer) values.get(index.get())[call.getArgument(0, Integer.class) - 1]);
        return result;
    }

    private static ResultSet repeatingRows(int count, Object[] value) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        AtomicInteger index = new AtomicInteger();
        when(result.next()).thenAnswer(call -> index.getAndIncrement() < count);
        when(result.getString(anyInt())).thenAnswer(call -> (String) value[call.getArgument(0, Integer.class) - 1]);
        when(result.getInt(anyInt())).thenAnswer(call -> (Integer) value[call.getArgument(0, Integer.class) - 1]);
        return result;
    }

    private static ResultSet metadataRows(List<Map<String, Object>> values) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        AtomicInteger index = new AtomicInteger(-1);
        when(result.next()).thenAnswer(call -> index.incrementAndGet() < values.size());
        when(result.getString(anyString())).thenAnswer(call -> Objects.toString(values.get(index.get()).get(call.getArgument(0, String.class)), null));
        when(result.getInt(anyString())).thenAnswer(call -> (Integer) values.get(index.get()).get(call.getArgument(0, String.class)));
        return result;
    }
}
