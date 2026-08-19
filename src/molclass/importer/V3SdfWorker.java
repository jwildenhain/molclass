package molclass.importer;

import java.security.DigestInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class V3SdfWorker {
    private static final Pattern SAFE_SCHEMA = Pattern.compile("[A-Za-z0-9_]+");
    private static final String JOB_TYPE = "SDF_ANALYZE";
    private static final String LOCK_NAME = "molclass_v3_sdf_worker";
    private static final long MAX_ANALYSIS_JSON_BYTES = 32L * 1024 * 1024;

    private final Config config;
    private final String workerId;

    private V3SdfWorker(Config config) throws Exception {
        this.config = config;
        this.workerId = config.workerId != null
                ? config.workerId
                : InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
    }

    public static void main(String[] args) {
        try {
            Config config = Config.parse(args);
            new V3SdfWorker(config).run();
        } catch (IllegalArgumentException exception) {
            System.err.println("Configuration error: " + exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("SDF worker failed: " + safeMessage(exception));
            System.exit(4);
        }
    }

    private void run() throws Exception {
        Files.createDirectories(config.uploadRoot);
        Path root = config.uploadRoot.toRealPath();
        try (Connection lockConnection = connection()) {
            if (!acquireWorkerLock(lockConnection)) {
                throw new IllegalStateException("another SDF worker owns " + LOCK_NAME);
            }
            recoverExpiredJobs();
            V3SdfImporter.recoverExpired(config.jdbcUrl, config.dbUser, config.dbPassword,
                    config.schema);
            do {
                Claim claim = claim();
                if (claim == null) {
                    boolean imported = V3SdfImporter.runOne(
                            config.jdbcUrl, config.dbUser, config.dbPassword, config.schema,
                            root, workerId, config.leaseSeconds);
                    if (imported) {
                        if (config.once) return;
                        continue;
                    }
                    if (config.once) return;
                    Thread.sleep(Duration.ofSeconds(config.pollSeconds).toMillis());
                    recoverExpiredJobs();
                    V3SdfImporter.recoverExpired(config.jdbcUrl, config.dbUser,
                            config.dbPassword, config.schema);
                    continue;
                }
                process(root, claim);
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
            String selectSql = "SELECT j.job_id,u.upload_id,j.attempt_count,j.maximum_attempts "
                    + "FROM " + t("job") + " j JOIN " + t("upload_artifact") + " u ON "
                    + "u.upload_id=CAST(JSON_UNQUOTE(JSON_EXTRACT(j.payload_json,'$.uploadId')) AS UNSIGNED) "
                    + "WHERE j.job_type=? AND j.status IN ('LEASED','RUNNING') "
                    + "AND j.lease_expires_at<UTC_TIMESTAMP(6) FOR UPDATE";
            try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                select.setString(1, JOB_TYPE);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        long jobId = rows.getLong(1);
                        long uploadId = rows.getLong(2);
                        boolean retry = rows.getInt(3) < rows.getInt(4);
                        updateRecovered(connection, jobId, uploadId, retry);
                    }
                }
            }
            connection.commit();
        }
    }

    private void updateRecovered(
            Connection connection, long jobId, long uploadId, boolean retry) throws SQLException {
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
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + t("upload_artifact") + " SET status=?,analysis_error_code=?,"
                        + "analysis_error_message=? WHERE upload_id=?")) {
            update.setString(1, retry ? "ANALYSIS_QUEUED" : "ANALYSIS_FAILED");
            update.setString(2, retry ? null : "MAXIMUM_ATTEMPTS_EXHAUSTED");
            update.setString(3, retry ? null : "analysis worker exhausted all attempts");
            update.setLong(4, uploadId);
            update.executeUpdate();
        }
        event(connection, jobId, retry ? "LEASE_RECOVERED" : "JOB_FAILED", runstep,
                retry ? "expired lease returned to queue" : "expired lease exhausted attempts");
    }

    private Claim claim() throws SQLException {
        try (Connection connection = connection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            String sql = "SELECT j.job_id,u.upload_id,u.storage_key,u.content_sha256,u.content_length "
                    + "FROM " + t("job") + " j JOIN " + t("upload_artifact") + " u ON "
                    + "u.upload_id=CAST(JSON_UNQUOTE(JSON_EXTRACT(j.payload_json,'$.uploadId')) AS UNSIGNED) "
                    + "WHERE j.job_type=? AND j.status='QUEUED' "
                    + "AND j.available_at<=UTC_TIMESTAMP(6) "
                    + "ORDER BY j.priority DESC,j.job_id LIMIT 1 FOR UPDATE SKIP LOCKED";
            Claim claim = null;
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                select.setString(1, JOB_TYPE);
                try (ResultSet row = select.executeQuery()) {
                    if (row.next()) {
                        claim = new Claim(
                                row.getLong(1),
                                row.getLong(2),
                                row.getString(3),
                                row.getBytes(4),
                                row.getLong(5));
                    }
                }
            }
            if (claim == null) {
                connection.rollback();
                return null;
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("job") + " SET status='RUNNING',runstep='VERIFY_UPLOAD',"
                            + "lease_owner=?,lease_expires_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),"
                            + "heartbeat_at=UTC_TIMESTAMP(6),attempt_count=attempt_count+1,"
                            + "started_at=COALESCE(started_at,UTC_TIMESTAMP(6)),"
                            + "error_code=NULL,error_message=NULL WHERE job_id=?")) {
                update.setString(1, workerId);
                update.setInt(2, config.leaseSeconds);
                update.setLong(3, claim.jobId);
                if (update.executeUpdate() != 1) throw new SQLException("job claim update lost");
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("upload_artifact")
                            + " SET status='ANALYZING',analysis_error_code=NULL,"
                            + "analysis_error_message=NULL WHERE upload_id=?")) {
                update.setLong(1, claim.uploadId);
                update.executeUpdate();
            }
            event(connection, claim.jobId, "JOB_STARTED", "VERIFY_UPLOAD",
                    "analysis claimed by " + workerId);
            connection.commit();
            return claim;
        }
    }

    private void process(Path root, Claim claim) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sdf-worker-heartbeat");
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
                heartbeatPeriod,
                heartbeatPeriod,
                TimeUnit.SECONDS);
        try {
            Path source = resolveStoragePath(root, claim.storageKey);
            verifyUpload(source, claim.expectedLength, claim.expectedSha256);
            transition(claim.jobId, "ANALYZE_SDF");
            Path analysisPath = resolveStoragePath(root, claim.storageKey + ".analysis.json");
            SdfAnalyzer.analyze(source, analysisPath);
            if (leaseLost.get()) throw new IllegalStateException("worker lease was lost");
            long analysisSize = Files.size(analysisPath);
            if (analysisSize <= 0 || analysisSize > MAX_ANALYSIS_JSON_BYTES) {
                throw new PermanentJobException("analysis JSON size is invalid: " + analysisSize);
            }
            String analysisJson = Files.readString(analysisPath, StandardCharsets.UTF_8);
            complete(claim, analysisJson);
            System.out.println("Analyzed upload " + claim.uploadId + " for job " + claim.jobId);
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

    private Path resolveStoragePath(Path root, String storageKey) throws Exception {
        if (storageKey == null || storageKey.isBlank() || storageKey.contains("/")
                || storageKey.contains("\\") || storageKey.contains("..")) {
            throw new PermanentJobException("unsafe upload storage key");
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) throw new PermanentJobException("upload path escapes root");
        return resolved;
    }

    private void verifyUpload(Path source, long expectedLength, byte[] expectedHash) throws Exception {
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new PermanentJobException("upload file is missing");
        }
        if (Files.size(source) != expectedLength) {
            throw new PermanentJobException("upload file length does not match database metadata");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(Files.newInputStream(source), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (!MessageDigest.isEqual(expectedHash, digest.digest())) {
            throw new PermanentJobException("upload file checksum does not match database metadata");
        }
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

    private void complete(Claim claim, String analysisJson) throws Exception {
        byte[] analysisHash = MessageDigest.getInstance("SHA-256")
                .digest(analysisJson.getBytes(StandardCharsets.UTF_8));
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("upload_artifact") + " SET status='ANALYZED',"
                            + "analysis_version=?,analysis_json=?,analysis_error_code=NULL,"
                            + "analysis_error_message=NULL,analyzed_at=UTC_TIMESTAMP(6) "
                            + "WHERE upload_id=?")) {
                update.setString(1, SdfAnalyzer.ANALYSIS_VERSION);
                update.setString(2, analysisJson);
                update.setLong(3, claim.uploadId);
                update.executeUpdate();
            }
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
                    "analysis_sha256=" + HexFormat.of().formatHex(analysisHash));
            connection.commit();
        }
    }

    private void fail(Claim claim, Exception exception) throws SQLException {
        boolean permanent = exception instanceof PermanentJobException;
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
                update.setString(3, permanent ? "UPLOAD_INTEGRITY_FAILED" : "ANALYSIS_FAILED");
                update.setString(4, message);
                update.setLong(5, claim.jobId);
                update.setString(6, workerId);
                update.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("upload_artifact")
                            + " SET status=?,analysis_error_code=?,analysis_error_message=? "
                            + "WHERE upload_id=?")) {
                update.setString(1, retry ? "ANALYSIS_QUEUED" : "ANALYSIS_FAILED");
                update.setString(2, permanent ? "UPLOAD_INTEGRITY_FAILED" : "ANALYSIS_FAILED");
                update.setString(3, message);
                update.setLong(4, claim.uploadId);
                update.executeUpdate();
            }
            event(connection, claim.jobId, retry ? "JOB_RETRY_SCHEDULED" : "JOB_FAILED",
                    retry ? "RETRY_WAIT" : "FAILED", message);
            connection.commit();
        }
    }

    private void event(
            Connection connection, long jobId, String eventType, String runstep, String message)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + t("job_event")
                        + " (job_id,event_type,runstep,event_message,event_details_json) "
                        + "VALUES (?,?,?,?,NULL)")) {
            insert.setLong(1, jobId);
            insert.setString(2, eventType);
            insert.setString(3, runstep);
            insert.setString(4, truncate(message, 2048));
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
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private record Claim(
            long jobId,
            long uploadId,
            String storageKey,
            byte[] expectedSha256,
            long expectedLength) {
        Claim {
            Objects.requireNonNull(storageKey);
            Objects.requireNonNull(expectedSha256);
        }
    }

    private static final class PermanentJobException extends Exception {
        PermanentJobException(String message) {
            super(message);
        }
    }

    private static final class Config {
        final String jdbcUrl;
        final String dbUser;
        final String dbPassword;
        final String schema;
        final Path uploadRoot;
        final boolean once;
        final int pollSeconds;
        final int leaseSeconds;
        final String workerId;

        private Config(
                String jdbcUrl,
                String dbUser,
                String dbPassword,
                String schema,
                Path uploadRoot,
                boolean once,
                int pollSeconds,
                int leaseSeconds,
                String workerId) {
            this.jdbcUrl = jdbcUrl;
            this.dbUser = dbUser;
            this.dbPassword = dbPassword;
            this.schema = schema;
            this.uploadRoot = uploadRoot;
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
            Path root = Path.of(env("MOLCLASS_UPLOAD_ROOT", "uploads/v3"))
                    .toAbsolutePath().normalize();
            return new Config(jdbc, user, password, schema, root, once,
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
