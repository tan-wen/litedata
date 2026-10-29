package com.litedata.data;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceSchemaInspectorTest {
    @Test void readsOrderedColumnsPrimaryKeyAndCommentsWithoutRows() throws Exception {
        Connection connection = mock(Connection.class); DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class); ResultSet tableRows = mock(ResultSet.class);
        ResultSet columnRows = mock(ResultSet.class); ResultSet keyRows = mock(ResultSet.class); ResultSet dataRows = mock(ResultSet.class);
        ResultSetMetaData result = mock(ResultSetMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata); when(connection.getCatalog()).thenReturn("sales"); when(connection.createStatement()).thenReturn(statement);
        when(metadata.getTables("sales", null, "orders", new String[] {"TABLE"})).thenReturn(tableRows);
        when(tableRows.next()).thenReturn(true, false); when(tableRows.getString("TABLE_NAME")).thenReturn("orders"); when(tableRows.getString("TABLE_SCHEM")).thenReturn(null); when(tableRows.getString("REMARKS")).thenReturn("order comment");
        when(metadata.getColumns("sales", null, "orders", "%")).thenReturn(columnRows);
        when(columnRows.next()).thenReturn(true, true, false); when(columnRows.getString("TABLE_NAME")).thenReturn("orders"); when(columnRows.getString("TABLE_SCHEM")).thenReturn(null);
        when(columnRows.getString("COLUMN_NAME")).thenReturn("id", "code"); when(columnRows.getString("REMARKS")).thenReturn("identifier", "code comment");
        when(columnRows.getInt("DECIMAL_DIGITS")).thenReturn(0); when(columnRows.wasNull()).thenReturn(false);
        when(metadata.getPrimaryKeys("sales", null, "orders")).thenReturn(keyRows); when(keyRows.next()).thenReturn(true, true, false);
        when(keyRows.getShort("KEY_SEQ")).thenReturn((short) 2, (short) 1); when(keyRows.getString("COLUMN_NAME")).thenReturn("code", "id");
        when(statement.executeQuery("SELECT * FROM `orders` WHERE 1=0")).thenReturn(dataRows); when(dataRows.getMetaData()).thenReturn(result);
        when(result.getColumnCount()).thenReturn(2); when(result.getColumnName(1)).thenReturn("id"); when(result.getColumnName(2)).thenReturn("code");
        when(result.getColumnType(1)).thenReturn(Types.BIGINT); when(result.getColumnType(2)).thenReturn(Types.VARCHAR);
        when(result.getColumnTypeName(1)).thenReturn("BIGINT"); when(result.getColumnTypeName(2)).thenReturn("VARCHAR");
        when(result.getPrecision(1)).thenReturn(19); when(result.getPrecision(2)).thenReturn(20); when(result.isNullable(anyInt())).thenReturn(ResultSetMetaData.columnNoNulls); when(result.isSigned(1)).thenReturn(true);

        var inspected = SourceSchemaInspector.inspect(connection, DatabaseType.MYSQL, "orders");
        assertEquals(List.of("id", "code"), inspected.columns().stream().map(SourceSchemaInspector.Column::name).toList());
        assertEquals(List.of("id", "code"), inspected.primaryKeys());
        assertEquals("order comment", inspected.comment()); assertEquals("code comment", inspected.columns().get(1).comment());
        verify(statement).executeQuery("SELECT * FROM `orders` WHERE 1=0");
    }

    @Test void rejectsCaseFoldedColumnCollisions() throws Exception {
        Connection connection = mock(Connection.class); DatabaseMetaData metadata = mock(DatabaseMetaData.class); Statement statement = mock(Statement.class);
        ResultSet tableRows = mock(ResultSet.class); ResultSet columnRows = mock(ResultSet.class); ResultSet keyRows = mock(ResultSet.class); ResultSet dataRows = mock(ResultSet.class); ResultSetMetaData result = mock(ResultSetMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata); when(connection.getCatalog()).thenReturn("sales"); when(connection.createStatement()).thenReturn(statement);
        when(metadata.getTables(any(), isNull(), eq("orders"), any(String[].class))).thenReturn(tableRows); when(tableRows.next()).thenReturn(true, false); when(tableRows.getString("TABLE_NAME")).thenReturn("orders");
        when(metadata.getColumns(any(), isNull(), eq("orders"), eq("%"))).thenReturn(columnRows); when(columnRows.next()).thenReturn(false);
        when(metadata.getPrimaryKeys(any(), isNull(), eq("orders"))).thenReturn(keyRows); when(keyRows.next()).thenReturn(false);
        when(statement.executeQuery("SELECT * FROM `orders` WHERE 1=0")).thenReturn(dataRows); when(dataRows.getMetaData()).thenReturn(result); when(result.getColumnCount()).thenReturn(2);
        when(result.getColumnName(1)).thenReturn("Code"); when(result.getColumnName(2)).thenReturn("code");
        assertThrows(IllegalArgumentException.class, () -> SourceSchemaInspector.inspect(connection, DatabaseType.MYSQL, "orders"));
    }

    @Test void usesKnownMetadataFractionInsteadOfTimestampDisplayPrecision() throws Exception {
        Connection connection = mock(Connection.class); DatabaseMetaData metadata = mock(DatabaseMetaData.class); Statement statement = mock(Statement.class); PreparedStatement precision = mock(PreparedStatement.class);
        ResultSet tableRows = mock(ResultSet.class), columnRows = mock(ResultSet.class), keyRows = mock(ResultSet.class), dataRows = mock(ResultSet.class);
        ResultSet precisionRows = mock(ResultSet.class); ResultSetMetaData result = mock(ResultSetMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata); when(connection.getCatalog()).thenReturn("sales"); when(connection.createStatement()).thenReturn(statement);
        String precisionSql = "SELECT COLUMN_NAME,DATETIME_PRECISION FROM information_schema.COLUMNS WHERE BINARY TABLE_SCHEMA=BINARY ? AND BINARY TABLE_NAME=BINARY ? AND DATETIME_PRECISION IS NOT NULL";
        when(connection.prepareStatement(precisionSql)).thenReturn(precision); when(precision.executeQuery()).thenReturn(precisionRows);
        when(precisionRows.next()).thenReturn(true, true, false); when(precisionRows.getString("COLUMN_NAME")).thenReturn("microsecond_at", "second_at");
        when(precisionRows.getInt("DATETIME_PRECISION")).thenReturn(6, 0); when(precisionRows.wasNull()).thenReturn(false);
        when(metadata.getTables("sales", null, "orders", new String[] {"TABLE"})).thenReturn(tableRows); when(tableRows.next()).thenReturn(true, false); when(tableRows.getString("TABLE_NAME")).thenReturn("orders");
        when(metadata.getColumns("sales", null, "orders", "%")).thenReturn(columnRows); when(columnRows.next()).thenReturn(true, true, true, false);
        when(columnRows.getString("TABLE_NAME")).thenReturn("orders"); when(columnRows.getString("COLUMN_NAME")).thenReturn("microsecond_at", "second_at", "zoned_at");
        when(columnRows.getInt("DECIMAL_DIGITS")).thenReturn(6, 0, 0); when(columnRows.wasNull()).thenReturn(false, false, true);
        when(metadata.getPrimaryKeys("sales", null, "orders")).thenReturn(keyRows); when(keyRows.next()).thenReturn(false);
        when(statement.executeQuery("SELECT * FROM `orders` WHERE 1=0")).thenReturn(dataRows); when(dataRows.getMetaData()).thenReturn(result); when(result.getColumnCount()).thenReturn(3);
        when(result.getColumnName(1)).thenReturn("microsecond_at"); when(result.getColumnName(2)).thenReturn("second_at"); when(result.getColumnName(3)).thenReturn("zoned_at");
        when(result.getColumnType(1)).thenReturn(Types.TIMESTAMP); when(result.getColumnType(2)).thenReturn(Types.TIMESTAMP); when(result.getColumnType(3)).thenReturn(Types.TIMESTAMP_WITH_TIMEZONE);
        when(result.getColumnTypeName(1)).thenReturn("DATETIME"); when(result.getColumnTypeName(2)).thenReturn("DATETIME"); when(result.getColumnTypeName(3)).thenReturn("TIMESTAMP WITH TIME ZONE");
        when(result.getPrecision(1)).thenReturn(26); when(result.getPrecision(2)).thenReturn(19); when(result.getPrecision(3)).thenReturn(35);
        when(result.getScale(1)).thenReturn(0); when(result.getScale(2)).thenReturn(0); when(result.getScale(3)).thenReturn(6);

        var columns = SourceSchemaInspector.inspect(connection, DatabaseType.MYSQL, "orders").columns();
        assertEquals(6, columns.get(0).scale());
        assertEquals(0, columns.get(1).scale());
        assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, columns.get(2).jdbcType());
    }
}
