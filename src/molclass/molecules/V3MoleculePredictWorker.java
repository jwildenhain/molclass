package molclass.molecules;

import molclass.features.V3FeatureGenerator;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Polls for MOLECULE_PREDICT_REQUEST jobs: register a raw SMILES string as a molecule if it
 * isn't already known, compute its descriptors, then call the predictor service's existing
 * per-molecule prediction endpoint. One job covers the whole pipeline so a caller (the FastAPI
 * endpoint, and in turn an MCP client) only has to poll one thing.
 *
 * Modeled on V3SdfWorker's poll/claim/lease/heartbeat/event pattern, but as its own worker
 * rather than a third job type wedged into that class: the claim query, payload shape, and
 * processing steps here have nothing in common with SDF_ANALYZE/SDF_IMPORT beyond both being
 * rows in the same job table.
 */
public final class V3MoleculePredictWorker {
    private static final Pattern SAFE_SCHEMA = Pattern.compile("[A-Za-z0-9_]+");
    private static final String JOB_TYPE = "MOLECULE_PREDICT_REQUEST";
    private static final String LOCK_NAME = "molclass_v3_molecule_predict_worker";

    private final Config config;
    private final String workerId;
    private final HttpClient http;

    private V3MoleculePredictWorker(Config config) throws Exception {
        this.config = config;
        this.workerId = config.workerId != null
                ? config.workerId
                : InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static void main(String[] args) {
        try {
            Config config = Config.parse(args);
            new V3MoleculePredictWorker(config).run();
        } catch (IllegalArgumentException exception) {
            System.err.println("Configuration error: " + exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Molecule predict worker failed: " + safeMessage(exception));
            System.exit(4);
        }
    }

    private void run() throws Exception {
        try (Connection lockConnection = connection()) {
            if (!acquireWorkerLock(lockConnection)) {
                throw new IllegalStateException("another molecule predict worker owns " + LOCK_NAME);
            }
            recoverExpiredJobs();
            do {
                Claim claim = claim();
                if (claim == null) {
                    if (config.once) return;
                    Thread.sleep(Duration.ofSeconds(config.pollSeconds).toMillis());
                    recoverExpiredJobs();
                    continue;
                }
                process(claim);
            } while (!config.once);
        }
    }

    private boolean acquireWorkerLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && row.getInt(1) == 1;
            }
        }
    }

    private void recoverExpiredJobs() throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            String selectSql = "SELECT job_id,attempt_count,maximum_attempts FROM " + t("job")
                    + " WHERE job_type=? AND status IN ('LEASED','RUNNING')"
                    + " AND lease_expires_at<UTC_TIMESTAMP(6) FOR UPDATE";
            try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                select.setString(1, JOB_TYPE);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        long jobId = rows.getLong(1);
                        boolean retry = rows.getInt(2) < rows.getInt(3);
                        recoverOne(connection, jobId, retry);
                    }
                }
            }
            connection.commit();
        }
    }

    private void recoverOne(Connection connection, long jobId, boolean retry) throws SQLException {
        String status = retry ? "QUEUED" : "FAILED";
        String runstep = retry ? "RECOVERED" : "ATTEMPTS_EXHAUSTED";
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + t("job") + " SET status=?,runstep=?,lease_owner=NULL,"
                        + "lease_expires_at=NULL,heartbeat_at=NULL,available_at=UTC_TIMESTAMP(6),"
                        + "error_code=?,error_message=?,finished_at="
                        + (retry ? "NULL" : "UTC_TIMESTAMP(6)") + " WHERE job_id=?")) {
            update.setString(1, status);
            update.setString(2, runstep);
            update.setString(3, retry ? "WORKER_LEASE_EXPIRED" : "MAXIMUM_ATTEMPTS_EXHAUSTED");
            update.setString(4, retry
                    ? "expired worker lease recovered for retry"
                    : "expired worker lease exhausted all attempts");
            update.setLong(5, jobId);
            update.executeUpdate();
        }
        event(connection, jobId, retry ? "LEASE_RECOVERED" : "JOB_FAILED", runstep,
                retry ? "expired lease returned to queue" : "expired lease exhausted attempts", null);
    }

    private Claim claim() throws SQLException {
        try (Connection connection = connection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            String sql = "SELECT job_id,"
                    + "JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.smiles')),"
                    + "CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.modelDefinitionId')) AS UNSIGNED)"
                    + " FROM " + t("job")
                    + " WHERE job_type=? AND status='QUEUED' AND available_at<=UTC_TIMESTAMP(6)"
                    + " ORDER BY priority DESC,job_id LIMIT 1 FOR UPDATE SKIP LOCKED";
            Claim claim = null;
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                select.setString(1, JOB_TYPE);
                try (ResultSet row = select.executeQuery()) {
                    if (row.next()) {
                        claim = new Claim(row.getLong(1), row.getString(2), row.getLong(3));
                    }
                }
            }
            if (claim == null) {
                connection.rollback();
                return null;
            }
            if (claim.smiles == null || claim.smiles.isBlank() || claim.modelDefinitionId <= 0) {
                connection.rollback();
                failPermanently(claim.jobId, "MOLECULE_PREDICT_REQUEST payload is missing smiles or modelDefinitionId");
                return null;
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("job") + " SET status='RUNNING',runstep='REGISTER_MOLECULE',"
                            + "lease_owner=?,lease_expires_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),"
                            + "heartbeat_at=UTC_TIMESTAMP(6),attempt_count=attempt_count+1,"
                            + "started_at=COALESCE(started_at,UTC_TIMESTAMP(6)),"
                            + "error_code=NULL,error_message=NULL WHERE job_id=?")) {
                update.setString(1, workerId);
                update.setInt(2, config.leaseSeconds);
                update.setLong(3, claim.jobId);
                if (update.executeUpdate() != 1) throw new SQLException("job claim update lost");
            }
            event(connection, claim.jobId, "JOB_STARTED", "REGISTER_MOLECULE",
                    "claimed by " + workerId, null);
            connection.commit();
            return claim;
        }
    }

    private void failPermanently(long jobId, String message) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("job") + " SET status='FAILED',runstep='FAILED',"
                            + "error_code='INVALID_PAYLOAD',error_message=?,finished_at=UTC_TIMESTAMP(6)"
                            + " WHERE job_id=?")) {
                update.setString(1, message);
                update.setLong(2, jobId);
                update.executeUpdate();
            }
            event(connection, jobId, "JOB_FAILED", "FAILED", message, null);
            connection.commit();
        }
    }

    private void process(Claim claim) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "molecule-predict-worker-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        AtomicBoolean leaseLost = new AtomicBoolean();
        long heartbeatPeriod = Math.max(5, config.leaseSeconds / 3L);
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        if (!heartbeat(claim.jobId)) leaseLost.set(true);
                    } catch (Exception exception) {
                        System.err.println("Heartbeat failed for job " + claim.jobId + ": "
                                + safeMessage(exception));
                    }
                },
                heartbeatPeriod, heartbeatPeriod, TimeUnit.SECONDS);
        try {
            long moleculeId;
            byte[] structure;
            String canonicalSmiles;
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                V3AdhocMoleculeRegistrar.Registered registered =
                        V3AdhocMoleculeRegistrar.register(connection, config.schema, claim.smiles);
                connection.commit();
                moleculeId = registered.moleculeId();
                structure = registered.normalizedStructure();
                canonicalSmiles = registered.canonicalSmiles();
            }
            if (leaseLost.get()) throw new IllegalStateException("worker lease was lost");
            transition(claim.jobId, "COMPUTE_DESCRIPTORS");
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                V3FeatureGenerator.registerFeaturesForMolecule(
                        connection, config.schema, claim.jobId, moleculeId, structure, canonicalSmiles);
                connection.commit();
            }
            if (leaseLost.get()) throw new IllegalStateException("worker lease was lost");
            transition(claim.jobId, "PREDICT");
            String predictionJson = predict(claim.modelDefinitionId, moleculeId);
            complete(claim, moleculeId, predictionJson);
            System.out.println("Molecule predict job " + claim.jobId + " completed for molecule "
                    + moleculeId + " against model " + claim.modelDefinitionId);
        } catch (Exception exception) {
            try {
                fail(claim, exception);
            } catch (Exception persistenceFailure) {
                System.err.println("Could not persist failure for job " + claim.jobId + ": "
                        + safeMessage(persistenceFailure));
            }
        } finally {
            heartbeat.cancel(true);
            scheduler.shutdownNow();
        }
    }

    private String predict(long modelDefinitionId, long moleculeId) throws Exception {
        URI uri = URI.create(config.predictorUrl + "/api/v3/models/" + modelDefinitionId
                + "/molecules/" + moleculeId + "/predict");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new PredictionFailedException(
                    "predictor returned " + response.statusCode() + ": " + truncate(response.body(), 1024));
        }
        return response.body();
    }

    private void transition(long jobId, String runstep) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + t("job") + " SET runstep=?,heartbeat_at=UTC_TIMESTAMP(6),"
                             + "lease_expires_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)) "
                             + "WHERE job_id=? AND status='RUNNING' AND lease_owner=?")) {
            statement.setString(1, runstep);
            statement.setInt(2, config.leaseSeconds);
            statement.setLong(3, jobId);
            statement.setString(4, workerId);
            if (statement.executeUpdate() != 1) throw new SQLException("worker lease was lost");
        }
    }

    private boolean heartbeat(long jobId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + t("job") + " SET heartbeat_at=UTC_TIMESTAMP(6),"
                             + "lease_expires_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)) "
                             + "WHERE job_id=? AND status='RUNNING' AND lease_owner=?")) {
            statement.setInt(1, config.leaseSeconds);
            statement.setLong(2, jobId);
            statement.setString(3, workerId);
            return statement.executeUpdate() == 1;
        }
    }

    private void complete(Claim claim, long moleculeId, String predictionJson) throws Exception {
        String resultJson = "{\"moleculeId\":" + moleculeId + ",\"modelDefinitionId\":"
                + claim.modelDefinitionId + ",\"prediction\":" + predictionJson + "}";
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("job") + " SET status='SUCCEEDED',runstep='COMPLETE',"
                            + "heartbeat_at=UTC_TIMESTAMP(6),lease_owner=NULL,lease_expires_at=NULL,"
                            + "finished_at=UTC_TIMESTAMP(6),error_code=NULL,error_message=NULL "
                            + "WHERE job_id=? AND status='RUNNING' AND lease_owner=?")) {
                update.setLong(1, claim.jobId);
                update.setString(2, workerId);
                if (update.executeUpdate() != 1) throw new SQLException("worker lease was lost");
            }
            event(connection, claim.jobId, "JOB_SUCCEEDED", "COMPLETE",
                    "molecule " + moleculeId + " predicted against model " + claim.modelDefinitionId,
                    resultJson);
            connection.commit();
        }
    }

    private void fail(Claim claim, Exception exception) throws SQLException {
        boolean permanent = exception instanceof IllegalArgumentException
                || exception instanceof PredictionFailedException;
        String message = safeMessage(exception);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            int attempt = 0;
            int maximum = 1;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT attempt_count,maximum_attempts FROM " + t("job")
                            + " WHERE job_id=? FOR UPDATE")) {
                select.setLong(1, claim.jobId);
                try (ResultSet row = select.executeQuery()) {
                    if (row.next()) {
                        attempt = row.getInt(1);
                        maximum = row.getInt(2);
                    }
                }
            }
            boolean retry = !permanent && attempt < maximum;
            String status = retry ? "QUEUED" : "FAILED";
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("job") + " SET status=?,runstep=?,lease_owner=NULL,"
                            + "lease_expires_at=NULL,heartbeat_at=NULL,available_at="
                            + (retry ? "TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6))" : "available_at")
                            + ",error_code=?,error_message=?,finished_at="
                            + (retry ? "NULL" : "UTC_TIMESTAMP(6)")
                            + " WHERE job_id=? AND lease_owner=?")) {
                update.setString(1, status);
                update.setString(2, retry ? "RETRY_WAIT" : "FAILED");
                update.setString(3, permanent ? "MOLECULE_PREDICT_FAILED" : "MOLECULE_PREDICT_TRANSIENT");
                update.setString(4, message);
                update.setLong(5, claim.jobId);
                update.setString(6, workerId);
                update.executeUpdate();
            }
            event(connection, claim.jobId, retry ? "JOB_RETRY_SCHEDULED" : "JOB_FAILED",
                    retry ? "RETRY_WAIT" : "FAILED", message, null);
            connection.commit();
        }
    }

    private void event(Connection connection, long jobId, String eventType, String runstep,
            String message, String detailsJson) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + t("job_event")
                        + " (job_id,event_type,runstep,event_message,event_details_json) "
                        + "VALUES (?,?,?,?,?)")) {
            insert.setLong(1, jobId);
            insert.setString(2, eventType);
            insert.setString(3, runstep);
            insert.setString(4, truncate(message, 2048));
            if (detailsJson == null) {
                insert.setNull(5, java.sql.Types.LONGVARCHAR);
            } else {
                insert.setString(5, detailsJson);
            }
            insert.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(
                config.jdbcUrl, config.dbUser, config.dbPassword);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET time_zone = '+00:00'");
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    private String t(String table) {
        return "`" + config.schema + "`.`" + table + "`";
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) message = throwable.getClass().getSimpleName();
        return truncate(message.replace('\n', ' ').replace('\r', ' '), 2048);
    }

    private static String truncate(String value, int maximum) {
        if (value == null) return null;
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static final class PredictionFailedException extends Exception {
        PredictionFailedException(String message) {
            super(message);
        }
    }

    private record Claim(long jobId, String smiles, long modelDefinitionId) {
    }

    private static final class Config {
        final String jdbcUrl;
        final String dbUser;
        final String dbPassword;
        final String schema;
        final String predictorUrl;
        final boolean once;
        final int pollSeconds;
        final int leaseSeconds;
        final String workerId;

        private Config(String jdbcUrl, String dbUser, String dbPassword, String schema,
                String predictorUrl, boolean once, int pollSeconds, int leaseSeconds, String workerId) {
            this.jdbcUrl = jdbcUrl;
            this.dbUser = dbUser;
            this.dbPassword = dbPassword;
            this.schema = schema;
            this.predictorUrl = predictorUrl;
            this.once = once;
            this.pollSeconds = pollSeconds;
            this.leaseSeconds = leaseSeconds;
            this.workerId = workerId;
        }

        static Config parse(String[] args) {
            boolean once = false;
            int pollSeconds = 5;
            int leaseSeconds = 120;
            String workerId = null;
            for (int index = 0; index < args.length; index++) {
                String option = args[index];
                if ("--once".equals(option)) {
                    once = true;
                    continue;
                }
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                String value = args[++index];
                switch (option) {
                    case "--poll-seconds" -> pollSeconds = positiveInt(option, value);
                    case "--lease-seconds" -> leaseSeconds = positiveInt(option, value);
                    case "--worker-id" -> workerId = value.trim();
                    default -> throw new IllegalArgumentException("unknown option: " + option);
                }
            }
            if (leaseSeconds < 30) {
                throw new IllegalArgumentException("--lease-seconds must be at least 30");
            }
            String jdbc = requiredEnv("MOLCLASS_JDBC_URL");
            String user = requiredEnv("MOLCLASS_DB_USER");
            String password = requiredEnv("MOLCLASS_DB_PASSWORD");
            String schema = env("MOLCLASS_V3_SCHEMA", "molclass_v3");
            if (!SAFE_SCHEMA.matcher(schema).matches()) {
                throw new IllegalArgumentException("unsafe v3 schema name");
            }
            String predictorUrl = requiredEnv("MOLCLASS_PREDICTOR_URL");
            if (predictorUrl.endsWith("/")) {
                predictorUrl = predictorUrl.substring(0, predictorUrl.length() - 1);
            }
            return new Config(jdbc, user, password, schema, predictorUrl, once,
                    pollSeconds, leaseSeconds, workerId == null || workerId.isBlank() ? null : workerId);
        }

        private static int positiveInt(String option, String value) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed <= 0) throw new NumberFormatException();
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(option + " requires a positive integer");
            }
        }

        private static String requiredEnv(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value;
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
