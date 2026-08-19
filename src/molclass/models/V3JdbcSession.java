package molclass.models;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Applies mandatory session settings before a v3 model CLI uses a JDBC connection. */
final class V3JdbcSession {
    static final String UTC_SETUP_SQL = "SET SESSION time_zone = '+00:00'";

    private V3JdbcSession() { }

    static Connection configureUtc(Connection connection) throws SQLException {
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute(UTC_SETUP_SQL);
            }
            return connection;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }
}
