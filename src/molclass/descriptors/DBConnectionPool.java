package descriptors;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Simple HikariCP connection pool manager keyed by JDBC URL/user/password.
 * Ensures a single DataSource per unique DB credentials.
 */
public class DBConnectionPool {
    private static final Map<String, HikariDataSource> poolMap = new ConcurrentHashMap<>();

    /**
     * Returns a HikariDataSource for the given connection parameters, creating one if needed.
     */
    public static HikariDataSource getDataSource(String hostname, String user, String password) {
        String key = hostname + "|" + user + "|" + password;
        return poolMap.computeIfAbsent(key, k -> createDataSource(hostname, user, password));
    }

    private static HikariDataSource createDataSource(String hostname, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(hostname);
        config.setUsername(user);
        config.setPassword(password);
        // sensible defaults
        config.setMaximumPoolSize(16);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        // Optional: cache prepared statements
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        HikariDataSource ds = new HikariDataSource(config);
        return ds;
    }

    /**
     * Retrieves a connection from the appropriate pool.
     */
    public static Connection getConnection(String hostname, String user, String password) throws SQLException {
        return getDataSource(hostname, user, password).getConnection();
    }

    /**
     * Shuts down all pools. Called via JVM shutdown hook.
     */
    public static void shutdownAll() {
        for (HikariDataSource ds : poolMap.values()) {
            if (ds != null && !ds.isClosed()) {
                ds.close();
            }
        }
        poolMap.clear();
    }

    static {
        // Register a JVM shutdown hook to clean up pools.
        Runtime.getRuntime().addShutdownHook(new Thread(DBConnectionPool::shutdownAll));
    }
}
