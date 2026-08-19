package molclass.models;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Technical lifecycle transition for replacing an unapproved immutable model build. */
public final class V3ModelSupersession {
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_]+");

    private V3ModelSupersession() { }

    public static void main(String[] arguments) {
        try {
            Config config = Config.parse(arguments);
            try (Connection connection = V3JdbcSession.configureUtc(DriverManager.getConnection(
                    connectionUrl(config.jdbcUrl, config.schema),
                    config.user, config.password))) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try {
                    supersede(connection, config);
                    connection.commit();
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                }
            }
        } catch (Exception exception) {
            System.err.println("Model supersession failed: " + diagnostic(exception));
            System.exit(1);
        }
    }

    private static void supersede(Connection connection, Config config) throws SQLException {
        BuildState build = lockBuild(connection, config);
        long approvalCount = scalar(connection,
                "SELECT COUNT(*) FROM " + table(config, "model_approval")
                        + " WHERE model_build_id=?", config.buildId);
        long supersessionCount = scalar(connection,
                "SELECT COUNT(*) FROM " + table(config, "model_build_supersession")
                        + " WHERE model_build_id=?", config.buildId);
        verifySupersedable(build.status, approvalCount, supersessionCount);

        String insert = "INSERT INTO " + table(config, "model_build_supersession")
                + " (model_build_id,superseded_by,supersession_reason,replacement_contract,"
                + "superseded_at) VALUES (?,?,?,?,NOW(6))";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setLong(1, config.buildId);
            statement.setString(2, config.actor);
            statement.setString(3, config.reason);
            statement.setString(4, config.replacementContract);
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table(config, "model_build")
                        + " SET status='SUPERSEDED' WHERE model_build_id=?"
                        + " AND status='AWAITING_APPROVAL'")) {
            statement.setLong(1, config.buildId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("model build changed during supersession");
            }
        }

        String definitionStatus = definitionStatusAfterSupersession(build.publishedBuildId);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table(config, "model_definition")
                        + " SET status=? WHERE model_definition_id=?")) {
            statement.setString(1, definitionStatus);
            statement.setLong(2, build.definitionId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("model definition changed during supersession");
            }
        }

        String audit = "INSERT INTO " + table(config, "audit_event")
                + " (actor,action_code,entity_type,entity_id,event_details_json,created_at)"
                + " VALUES (?,'MODEL_SUPERSEDED','MODEL_BUILD',?,"
                + "JSON_OBJECT('modelDefinitionId',?,'replacementContract',?,'reason',?),NOW(6))";
        try (PreparedStatement statement = connection.prepareStatement(audit)) {
            statement.setString(1, config.actor);
            statement.setString(2, Long.toString(config.buildId));
            statement.setLong(3, build.definitionId);
            statement.setString(4, config.replacementContract);
            statement.setString(5, config.reason);
            statement.executeUpdate();
        }

        System.out.println("Model build " + config.buildId + " superseded; definition "
                + build.definitionId + " is now " + definitionStatus + ".");
    }

    private static BuildState lockBuild(Connection connection, Config config) throws SQLException {
        String sql = "SELECT mb.model_definition_id,mb.status,md.published_model_build_id FROM "
                + table(config, "model_build") + " mb JOIN "
                + table(config, "model_definition")
                + " md ON md.model_definition_id=mb.model_definition_id"
                + " WHERE mb.model_build_id=? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, config.buildId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalArgumentException("unknown model build " + config.buildId);
                }
                long published = row.getLong(3);
                Long nullablePublished = row.wasNull() ? null : published;
                return new BuildState(
                        row.getLong(1), row.getString(2), nullablePublished);
            }
        }
    }

    static void verifySupersedable(
            String buildStatus, long approvalCount, long supersessionCount) {
        if (!"AWAITING_APPROVAL".equals(buildStatus)) {
            throw new IllegalStateException(
                    "only an AWAITING_APPROVAL build can be superseded");
        }
        if (approvalCount != 0) {
            throw new IllegalStateException(
                    "a build with an approval decision cannot be superseded");
        }
        if (supersessionCount != 0) {
            throw new IllegalStateException("model build is already superseded");
        }
    }

    static String definitionStatusAfterSupersession(Long publishedBuildId) {
        return publishedBuildId == null ? "PENDING_REBUILD" : "ACTIVE";
    }

    private static long scalar(Connection connection, String sql, long buildId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, buildId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("count query returned no row");
                return row.getLong(1);
            }
        }
    }

    private static String table(Config config, String name) {
        if (!SAFE.matcher(config.schema).matches() || !SAFE.matcher(name).matches()) {
            throw new IllegalArgumentException("unsafe database identifier");
        }
        return "`" + config.schema + "`.`" + name + "`";
    }

    private static String connectionUrl(String jdbcUrl, String schema) {
        int queryAt = jdbcUrl.indexOf('?');
        String query = queryAt < 0 ? "" : jdbcUrl.substring(queryAt);
        String head = queryAt < 0 ? jdbcUrl : jdbcUrl.substring(0, queryAt);
        int authorityEnd = head.indexOf('/', "jdbc:mysql://".length());
        if (authorityEnd < 0) return head + "/" + schema + query;
        return head.substring(0, authorityEnd + 1) + schema + query;
    }

    private static String diagnostic(Exception exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record BuildState(
            long definitionId, String status, Long publishedBuildId) { }

    private record Config(String jdbcUrl, String user, String password, String schema,
            long buildId, String actor, String reason, String replacementContract) {
        static Config parse(String[] arguments) {
            String jdbcUrl = env("MOLCLASS_JDBC_URL", "jdbc:mysql://127.0.0.1:3306/");
            String user = env("MOLCLASS_DB_USER", null);
            String password = env("MOLCLASS_DB_PASSWORD", null);
            String schema = env("MOLCLASS_DB_SCHEMA",
                    env("MOLCLASS_V3_SCHEMA", "molclass_v3"));
            Long buildId = null;
            String actor = null;
            String reason = null;
            String replacementContract = null;
            for (int index = 0; index < arguments.length; index++) {
                String option = arguments[index];
                if ("--help".equals(option)) {
                    usage();
                    System.exit(0);
                }
                if (index + 1 >= arguments.length) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                String value = arguments[++index];
                switch (option) {
                    case "--jdbc-url" -> jdbcUrl = value;
                    case "--db-user" -> user = value;
                    case "--db-password" -> password = value;
                    case "--schema" -> schema = value;
                    case "--build-id" -> buildId = Long.parseLong(value);
                    case "--actor" -> actor = value;
                    case "--reason" -> reason = value;
                    case "--replacement-contract" -> replacementContract = value;
                    default -> throw new IllegalArgumentException("unknown option " + option);
                }
            }
            if (user == null || password == null) {
                throw new IllegalArgumentException("database credentials are required");
            }
            if (buildId == null || buildId <= 0) {
                throw new IllegalArgumentException("--build-id is required");
            }
            if (actor == null || actor.isBlank() || actor.length() > 255) {
                throw new IllegalArgumentException(
                        "--actor is required and limited to 255 characters");
            }
            if (reason == null || reason.isBlank() || reason.length() > 2048) {
                throw new IllegalArgumentException(
                        "--reason is required and limited to 2048 characters");
            }
            if (replacementContract == null || replacementContract.isBlank()
                    || replacementContract.length() > 128
                    || !SAFE.matcher(replacementContract).matches()) {
                throw new IllegalArgumentException(
                        "--replacement-contract is required and must be a safe 128-character code");
            }
            if (!SAFE.matcher(schema).matches()) {
                throw new IllegalArgumentException("unsafe schema name");
            }
            return new Config(jdbcUrl, user, password, schema, buildId, actor, reason,
                    replacementContract.toUpperCase(Locale.ROOT));
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    private static void usage() {
        System.out.println("./gradlew supersedeV3Model -PsupersessionArgs=\""
                + "--build-id ID --actor NAME --reason TEXT --replacement-contract CODE\"");
    }
}
