package com.litedata.modeling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.litedata.config.ModelingProperties;
import com.litedata.config.PlatformProperties;
import com.litedata.data.JdbcConnections;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DorisTableExistenceTest {
    @Test
    void findsExistingRequestedTablesInOneQueryAndClosesResources() throws Exception {
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = rows("ods_orders", "dwd_orders");
        when(connections.open("jdbc:mysql://doris.example:9030/warehouse", "reader", "reader-secret")).thenReturn(connection);
        when(connection.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME IN (?,?,?)")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);

        assertThat(gateway(connections, new ModelingProperties("", "")).existingTables(List.of("ods_orders", "dwd_orders", "missing")))
            .containsExactlyInAnyOrder("ods_orders", "dwd_orders");

        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(statement, times(4)).setString(anyInt(), values.capture());
        assertThat(values.getAllValues()).containsExactly("warehouse", "ods_orders", "dwd_orders", "missing");
        verify(statement).setQueryTimeout(15);
        verify(connection, times(1)).prepareStatement(anyString());
        verify(result).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void emptyRequestDoesNotOpenConnection() throws Exception {
        JdbcConnections connections = mock(JdbcConnections.class);

        assertThat(gateway(connections, new ModelingProperties("", "")).existingTables(List.of())).isEmpty();

        verifyNoInteractions(connections);
    }

    @Test
    void validatesEveryIdentifierBeforeOpeningConnection() {
        JdbcConnections connections = mock(JdbcConnections.class);

        assertThatIllegalArgumentException().isThrownBy(() -> gateway(connections, new ModelingProperties("", ""))
            .existingTables(List.of("ods_valid", "bad-name")));

        verifyNoInteractions(connections);
    }

    @Test
    void propagatesQueryFailureAndClosesResources() throws Exception {
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        SQLException failure = new SQLException("metadata unavailable");
        when(connections.open(anyString(), anyString(), anyString())).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(failure);

        assertThatThrownBy(() -> gateway(connections, new ModelingProperties("", "")).existingTables(List.of("ods_orders")))
            .isSameAs(failure);

        verify(statement).close();
        verify(connection).close();
    }

    private static DorisModelGateway gateway(JdbcConnections connections, ModelingProperties modeling) {
        return new DorisModelGateway(new PlatformProperties("key", "admin", "token", null,
            new PlatformProperties.Doris("jdbc:mysql://doris.example:9030/warehouse", "reader", "reader-secret")), modeling, connections);
    }

    private static ResultSet rows(String... names) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        AtomicInteger index = new AtomicInteger(-1);
        when(result.next()).thenAnswer(call -> index.incrementAndGet() < names.length);
        when(result.getString(1)).thenAnswer(call -> names[index.get()]);
        return result;
    }
}
