package molclass.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class V3JdbcSessionTest {
    @Test
    public void configureUtcEmitsSessionSetupAndClosesOnlyTheStatement() throws Exception {
        List<String> commands = new ArrayList<>();
        AtomicBoolean statementClosed = new AtomicBoolean();
        AtomicBoolean connectionClosed = new AtomicBoolean();
        Statement statement = statement(commands, statementClosed);
        Connection connection = connection(statement, connectionClosed);

        Connection configured = V3JdbcSession.configureUtc(connection);

        assertSame(connection, configured);
        assertEquals(List.of("SET SESSION time_zone = '+00:00'"), commands);
        assertTrue(statementClosed.get());
        assertFalse(connectionClosed.get());
    }

    @Test
    public void configureUtcClosesConnectionWhenSessionSetupFails() {
        AtomicBoolean connectionClosed = new AtomicBoolean();
        Connection connection = connection(failingStatement(), connectionClosed);

        try {
            V3JdbcSession.configureUtc(connection);
        } catch (SQLException expected) {
            assertEquals("setup failed", expected.getMessage());
            assertTrue(connectionClosed.get());
            return;
        }
        throw new AssertionError("Expected UTC session setup to fail");
    }

    private static Statement statement(List<String> commands, AtomicBoolean closed) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[] { Statement.class }, (proxy, method, arguments) -> {
                    if ("execute".equals(method.getName())) {
                        commands.add((String) arguments[0]);
                        return false;
                    }
                    if ("close".equals(method.getName())) {
                        closed.set(true);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Statement failingStatement() {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[] { Statement.class }, (proxy, method, arguments) -> {
                    if ("execute".equals(method.getName())) throw new SQLException("setup failed");
                    if ("close".equals(method.getName())) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Connection connection(Statement statement, AtomicBoolean closed) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class }, (proxy, method, arguments) -> {
                    if ("createStatement".equals(method.getName())) return statement;
                    if ("close".equals(method.getName())) {
                        closed.set(true);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
