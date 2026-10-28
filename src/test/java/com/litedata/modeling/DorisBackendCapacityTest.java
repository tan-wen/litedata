package com.litedata.modeling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.litedata.config.ModelingProperties;
import com.litedata.config.PlatformProperties;
import com.litedata.data.JdbcConnections;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DorisBackendCapacityTest {
    @Test void countsOnlyLiveNonDecommissionedDefaultStorageBackendsAndClosesResources() throws Exception {
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = rows(List.of(
                node("true", "false", "mix", "{\"location\":\"default\"}"),
                node("1", "0", "MIX", "{\"location\":\"default\"}"),
                node("false", "false", "mix", "{\"location\":\"default\"}"),
                node("true", "true", "mix", "{\"location\":\"default\"}"),
                node("true", "false", "computation", "{\"location\":\"default\"}"),
                node("true", "false", "mix", "{\"location\":\"cold\"}")));
        when(connections.open("jdbc:mysql://doris.example:9030/warehouse", "writer", "writer-secret")).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW BACKENDS")).thenReturn(result);

        DorisModelGateway.BackendCapacity capacity = gateway(connections, new ModelingProperties("writer", "writer-secret")).backendCapacity();

        assertThat(capacity).isEqualTo(new DorisModelGateway.BackendCapacity(6, 2));
        verify(statement).setQueryTimeout(5);
        verify(result).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test void returnsZeroForAnEmptyBackendList() throws Exception {
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = rows(List.of());
        when(connections.open(anyString(), anyString(), anyString())).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW BACKENDS")).thenReturn(result);

        assertThat(gateway(connections, new ModelingProperties("writer", "writer-secret")).backendCapacity())
                .isEqualTo(new DorisModelGateway.BackendCapacity(0, 0));
    }

    @Test void propagatesQueryFailuresAndClosesOpenedResources() throws Exception {
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        SQLException failure = new SQLException("SHOW BACKENDS denied", "42000");
        when(connections.open(anyString(), anyString(), anyString())).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW BACKENDS")).thenThrow(failure);

        assertThatThrownBy(() -> gateway(connections, new ModelingProperties("writer", "writer-secret")).backendCapacity())
                .isSameAs(failure);

        verify(statement).setQueryTimeout(5);
        verify(statement).close();
        verify(connection).close();
    }

    @Test void rejectsMalformedBackendTagAndClosesResources() throws Exception {
        JdbcConnections connections = mock(JdbcConnections.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = rows(List.of(node("true", "false", "mix", "not-json")));
        when(connections.open(anyString(), anyString(), anyString())).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW BACKENDS")).thenReturn(result);

        assertThatThrownBy(() -> gateway(connections, new ModelingProperties("writer", "writer-secret")).backendCapacity())
                .isInstanceOf(SQLException.class);

        verify(result).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test void rejectsMissingWriterConfigurationBeforeOpeningAConnection() {
        JdbcConnections connections = mock(JdbcConnections.class);

        assertThatIllegalStateException()
                .isThrownBy(() -> gateway(connections, new ModelingProperties("", "writer-secret")).backendCapacity())
                .withMessageContaining("DORIS_WRITER_USER");

        verifyNoInteractions(connections);
    }

    private static DorisModelGateway gateway(JdbcConnections connections, ModelingProperties modeling) {
        return new DorisModelGateway(new PlatformProperties("key", "admin", "token", null,
                new PlatformProperties.Doris("jdbc:mysql://doris.example:9030/warehouse", "reader", "reader-secret")), modeling, connections);
    }

    private static Map<String, String> node(String alive, String decommissioned, String nodeRole, String tag) {
        return Map.of("Alive", alive, "SystemDecommissioned", decommissioned, "NodeRole", nodeRole, "Tag", tag);
    }

    private static ResultSet rows(List<Map<String, String>> values) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        AtomicInteger index = new AtomicInteger(-1);
        when(result.next()).thenAnswer(call -> index.incrementAndGet() < values.size());
        when(result.getString(anyString())).thenAnswer(call -> values.get(index.get()).get(call.getArgument(0, String.class)));
        return result;
    }
}
