package molclass.audit;

import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Read-only integrity and production-gate audit for the MolClass v3 schema. */
public final class V3ProductionAudit {
    private static final String COMPLETED_BUILD_STATUSES =
            "('AWAITING_APPROVAL','PUBLISHED','REJECTED','SUPERSEDED')";
    private static final long REQUIRED_AGGREGATE_METRIC_COUNT = 18;
    private static final List<String> CORE_TABLES = List.of(
            "dataset", "import_run", "import_record", "job", "feature_profile",
            "model_definition", "model_build", "model_build_supersession",
            "model_training_member", "model_evaluation", "model_artifact", "model_approval");

    private V3ProductionAudit() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            Config config = Config.parse(args);
            if (config.help) {
                usage();
                return;
            }
            exitCode = audit(config);
        } catch (IllegalArgumentException exception) {
            System.err.println("Configuration error: " + exception.getMessage());
            usage();
            exitCode = 2;
        } catch (Exception exception) {
            System.err.println("Audit failed: " + exception.getClass().getSimpleName()
                    + ": " + safe(exception.getMessage()));
            exitCode = 2;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int audit(Config config) throws Exception {
        AuditResult result = new AuditResult(config.productionGate);
        try (Connection connection = DriverManager.getConnection(
                config.jdbcUrl, config.user, config.password)) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION time_zone = '+00:00'");
            }

            long coreTableCount = coreTableCount(connection, config.schema);
            if (coreTableCount != CORE_TABLES.size()) {
                result.fail("core_tables",
                        "found " + coreTableCount + " of " + CORE_TABLES.size()
                                + " required tables");
                result.print();
                return 1;
            }
            result.pass("core_tables", CORE_TABLES.size() + " required tables present");

            String dataset = table(config, "dataset");
            String importRun = table(config, "import_run");
            String importRecord = table(config, "import_record");
            String job = table(config, "job");
            String definition = table(config, "model_definition");
            String build = table(config, "model_build");
            String member = table(config, "model_training_member");
            String evaluation = table(config, "model_evaluation");
            String artifact = table(config, "model_artifact");
            String approval = table(config, "model_approval");
            String supersession = table(config, "model_build_supersession");

            result.zero("dataset_record_counters", count(connection,
                    "SELECT COUNT(*) FROM " + dataset
                            + " WHERE total_records <> imported_records + failed_records"
                            + " + not_processed_records"));

            result.zero("finished_import_record_counters", count(connection,
                    "SELECT COUNT(*) FROM " + importRun + " ir LEFT JOIN ("
                            + " SELECT import_run_id,COUNT(*) total,"
                            + " SUM(status='SUCCEEDED') succeeded,"
                            + " SUM(status='FAILED') failed,"
                            + " SUM(status='NOT_PROCESSED') not_processed"
                            + " FROM " + importRecord + " GROUP BY import_run_id) x"
                            + " ON x.import_run_id=ir.import_run_id"
                            + " WHERE ir.finished_at IS NOT NULL AND ("
                            + " ir.total_records<>COALESCE(x.total,0)"
                            + " OR ir.success_records<>COALESCE(x.succeeded,0)"
                            + " OR ir.failed_records<>COALESCE(x.failed,0)"
                            + " OR ir.not_processed_records<>COALESCE(x.not_processed,0))"));

            result.zero("expired_running_job_leases", count(connection,
                    "SELECT COUNT(*) FROM " + job
                            + " WHERE status='RUNNING' AND (lease_expires_at IS NULL"
                            + " OR lease_expires_at < UTC_TIMESTAMP(6))"));

            result.zero("orphan_running_builds", count(connection,
                    "SELECT COUNT(*) FROM " + build + " mb LEFT JOIN " + job
                            + " j ON j.job_id=mb.job_id AND j.status='RUNNING'"
                            + " WHERE mb.status='RUNNING' AND j.job_id IS NULL"));

            result.zero("completed_build_manifests", count(connection,
                    "SELECT COUNT(*) FROM " + build
                            + " WHERE status IN " + COMPLETED_BUILD_STATUSES
                            + " AND (runstep<>'COMPLETE' OR finished_at IS NULL"
                            + " OR build_manifest_json IS NULL OR manifest_sha256 IS NULL"
                            + " OR manifest_sha256<>UNHEX(SHA2(build_manifest_json,256)))"));

            result.zero("completed_build_artifact_sets", count(connection,
                    "SELECT COUNT(*) FROM " + build + " mb LEFT JOIN ("
                            + " SELECT model_build_id,COUNT(*) artifact_count,"
                            + " SUM(artifact_kind='MODEL') model_count,"
                            + " SUM(artifact_kind='HEADER') header_count,"
                            + " SUM(artifact_size<>OCTET_LENGTH(artifact_payload)) bad_size"
                            + " FROM " + artifact + " GROUP BY model_build_id) a"
                            + " ON a.model_build_id=mb.model_build_id"
                            + " WHERE mb.status IN " + COMPLETED_BUILD_STATUSES + " AND ("
                            + " COALESCE(a.artifact_count,0)<>2"
                            + " OR COALESCE(a.model_count,0)<>1"
                            + " OR COALESCE(a.header_count,0)<>1"
                            + " OR COALESCE(a.bad_size,0)<>0)"));

            BuildReviewContractViolations reviewContract = completedBuildReviewContractViolations(
                    connection, build, evaluation, approval, supersession);
            result.zero("completed_build_evaluation_sets",
                    reviewContract.evaluationMetricViolations());
            result.zero("rejected_build_decisions",
                    reviewContract.rejectedDecisionViolations());
            result.zero("superseded_build_lifecycle",
                    reviewContract.supersededLifecycleViolations());

            result.zero("completed_build_split_membership", count(connection,
                    "SELECT COUNT(*) FROM " + build + " mb LEFT JOIN ("
                            + " SELECT model_build_id,"
                            + " SUM(partition_name='TRAIN') train_count,"
                            + " SUM(partition_name='VALIDATION') validation_count,"
                            + " SUM(partition_name='HOLDOUT') holdout_count,"
                            + " SUM(partition_name='EXCLUDED') excluded_count"
                            + " FROM " + member + " GROUP BY model_build_id) m"
                            + " ON m.model_build_id=mb.model_build_id"
                            + " WHERE mb.status IN " + COMPLETED_BUILD_STATUSES + " AND ("
                            + " mb.training_count<>COALESCE(m.train_count,0)"
                            + " OR mb.validation_count<>COALESCE(m.validation_count,0)"
                            + " OR mb.holdout_count<>COALESCE(m.holdout_count,0)"
                            + " OR mb.excluded_count<>COALESCE(m.excluded_count,0))"));

            result.zero("published_definition_integrity", count(connection,
                    "SELECT COUNT(*) FROM " + definition + " md LEFT JOIN " + build
                            + " mb ON mb.model_build_id=md.published_model_build_id"
                            + " AND mb.model_definition_id=md.model_definition_id"
                            + " LEFT JOIN " + approval + " ma ON ma.model_build_id=mb.model_build_id"
                            + " AND ma.approval_status='APPROVE'"
                            + " WHERE md.published_model_build_id IS NOT NULL AND ("
                            + " md.status<>'ACTIVE' OR mb.status<>'PUBLISHED'"
                            + " OR ma.model_approval_id IS NULL)"));

            result.zero("orphan_published_builds", count(connection,
                    "SELECT COUNT(*) FROM " + build + " mb LEFT JOIN " + definition
                            + " md ON md.published_model_build_id=mb.model_build_id"
                            + " WHERE mb.status='PUBLISHED' AND md.model_definition_id IS NULL"));

            if (config.verifyArtifactDigests) {
                verifyArtifactDigests(connection, artifact, result);
            } else {
                result.skip("artifact_payload_digests",
                        "use --verify-artifact-digests for streaming SHA-256 verification");
            }

            long unfinishedDefinitions = count(connection,
                    "SELECT COUNT(*) FROM " + definition
                            + " WHERE status IN ('PENDING_REBUILD','REBUILD_FAILED',"
                            + "'UNSUPPORTED_CONFIGURATION','AWAITING_APPROVAL')");
            result.gate("unfinished_model_definitions", unfinishedDefinitions,
                    "definitions require rebuild or human disposition");

            long activePublished = count(connection,
                    "SELECT COUNT(*) FROM " + definition + " md JOIN " + build
                            + " mb ON mb.model_build_id=md.published_model_build_id"
                            + " WHERE md.status='ACTIVE' AND mb.status='PUBLISHED'");
            result.gateMinimum("active_published_models", activePublished, 1,
                    "at least one approved model must be active");

            long activeWork = count(connection,
                    "SELECT COUNT(*) FROM " + job + " WHERE status IN ('QUEUED','RUNNING')");
            result.gate("active_or_queued_jobs", activeWork,
                    "production gate requires a quiescent queue");
        }

        result.print();
        return result.failed() ? 1 : 0;
    }

    private static long coreTableCount(Connection connection, String schema) throws Exception {
        String placeholders = String.join(",", CORE_TABLES.stream().map(value -> "?").toList());
        String sql = "SELECT COUNT(DISTINCT table_name) FROM information_schema.tables"
                + " WHERE table_schema=? AND table_name IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            for (int index = 0; index < CORE_TABLES.size(); index++) {
                statement.setString(index + 2, CORE_TABLES.get(index));
            }
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static BuildReviewContractViolations completedBuildReviewContractViolations(
            Connection connection, String build, String evaluation, String approval,
            String supersession) throws Exception {
        String sql = "SELECT mb.status,COALESCE(e.metric_count,0),"
                + " COALESCE(a.decision_count,0),COALESCE(a.reject_count,0),"
                + " COALESCE(s.supersession_count,0)"
                + " FROM " + build + " mb LEFT JOIN ("
                + " SELECT model_build_id,"
                + " COUNT(DISTINCT CONCAT(evaluation_set,':',metric_code)) metric_count"
                + " FROM " + evaluation
                + " WHERE class_label IS NULL"
                + " AND evaluation_set IN ('TRAIN','VALIDATION','HOLDOUT')"
                + " AND metric_code IN ('ACCURACY','KAPPA','WEIGHTED_AUC',"
                + " 'WEIGHTED_F1','WEIGHTED_PRECISION','WEIGHTED_RECALL')"
                + " GROUP BY model_build_id) e ON e.model_build_id=mb.model_build_id"
                + " LEFT JOIN (SELECT model_build_id,COUNT(*) decision_count,"
                + " SUM(approval_status='REJECT') reject_count"
                + " FROM " + approval + " GROUP BY model_build_id) a"
                + " ON a.model_build_id=mb.model_build_id"
                + " LEFT JOIN (SELECT model_build_id,COUNT(*) supersession_count"
                + " FROM " + supersession + " GROUP BY model_build_id) s"
                + " ON s.model_build_id=mb.model_build_id"
                + " WHERE mb.status IN " + COMPLETED_BUILD_STATUSES;
        long evaluationMetricViolations = 0;
        long rejectedDecisionViolations = 0;
        long supersededLifecycleViolations = 0;
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                String status = rows.getString(1);
                if (requiresCompleteMetricContract(status)
                        && rows.getLong(2) != REQUIRED_AGGREGATE_METRIC_COUNT) {
                    evaluationMetricViolations++;
                }
                if (violatesRejectedDecisionContract(
                        status, rows.getLong(3), rows.getLong(4))) {
                    rejectedDecisionViolations++;
                }
                if (violatesSupersededLifecycleContract(
                        status, rows.getLong(3), rows.getLong(5))) {
                    supersededLifecycleViolations++;
                }
            }
        }
        return new BuildReviewContractViolations(
                evaluationMetricViolations, rejectedDecisionViolations,
                supersededLifecycleViolations);
    }

    static boolean requiresCompleteMetricContract(String status) {
        return "AWAITING_APPROVAL".equals(status) || "PUBLISHED".equals(status);
    }

    static boolean violatesRejectedDecisionContract(
            String status, long decisionCount, long rejectCount) {
        return "REJECTED".equals(status)
                && (decisionCount != 1 || rejectCount != 1);
    }

    static boolean violatesSupersededLifecycleContract(
            String status, long decisionCount, long supersessionCount) {
        if ("SUPERSEDED".equals(status)) {
            return decisionCount != 0 || supersessionCount != 1;
        }
        return supersessionCount != 0;
    }

    private static void verifyArtifactDigests(Connection connection, String artifact,
            AuditResult result) throws Exception {
        long checked = 0;
        long failed = 0;
        String sql = "SELECT model_artifact_id,artifact_size,artifact_sha256,artifact_payload"
                + " FROM " + artifact + " ORDER BY model_artifact_id";
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            byte[] buffer = new byte[64 * 1024];
            while (rows.next()) {
                checked++;
                long expectedSize = rows.getLong(2);
                byte[] expectedDigest = rows.getBytes(3);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long actualSize = 0;
                try (InputStream input = rows.getBinaryStream(4)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        digest.update(buffer, 0, read);
                        actualSize += read;
                    }
                }
                if (actualSize != expectedSize
                        || !MessageDigest.isEqual(expectedDigest, digest.digest())) {
                    failed++;
                    System.err.println("Artifact integrity failure: model_artifact_id="
                            + rows.getLong(1));
                }
            }
        }
        if (failed == 0) {
            result.pass("artifact_payload_digests", checked + " artifacts verified");
        } else {
            result.fail("artifact_payload_digests",
                    failed + " of " + checked + " artifacts failed SHA-256 or size verification");
        }
    }

    private static String table(Config config, String name) {
        return "`" + config.schema + "`.`" + name + "`";
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "no detail";
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static void usage() {
        System.out.println("Usage: V3ProductionAudit [options]");
        System.out.println("  --schema NAME                v3 schema (default MOLCLASS_V3_SCHEMA or molclass_v3)");
        System.out.println("  --production-gate            fail on backlog, active jobs, or no published model");
        System.out.println("  --verify-artifact-digests    stream and verify every artifact BLOB SHA-256");
        System.out.println("  --help                       show this contract");
        System.out.println("Environment: MOLCLASS_JDBC_URL, MOLCLASS_DB_USER, MOLCLASS_DB_PASSWORD");
    }

    private static final class Config {
        private final String jdbcUrl;
        private final String schema;
        private final String user;
        private final String password;
        private final boolean productionGate;
        private final boolean verifyArtifactDigests;
        private final boolean help;

        private Config(String jdbcUrl, String schema, String user, String password,
                boolean productionGate, boolean verifyArtifactDigests, boolean help) {
            this.jdbcUrl = jdbcUrl;
            this.schema = schema;
            this.user = user;
            this.password = password;
            this.productionGate = productionGate;
            this.verifyArtifactDigests = verifyArtifactDigests;
            this.help = help;
        }

        private static Config parse(String[] args) {
            String jdbcUrl = env("MOLCLASS_JDBC_URL", "jdbc:mysql://127.0.0.1:3306/");
            String schema = env("MOLCLASS_V3_SCHEMA", "molclass_v3");
            String user = System.getenv("MOLCLASS_DB_USER");
            String password = System.getenv("MOLCLASS_DB_PASSWORD");
            boolean productionGate = false;
            boolean verifyDigests = false;
            boolean help = false;

            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--schema" -> {
                        if (++index >= args.length) throw new IllegalArgumentException("--schema requires a value");
                        schema = args[index];
                    }
                    case "--production-gate" -> productionGate = true;
                    case "--verify-artifact-digests" -> verifyDigests = true;
                    case "--help", "-h" -> help = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + args[index]);
                }
            }

            if (!schema.matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("schema must contain only letters, digits, and underscore");
            }
            if (!help && (user == null || user.isBlank())) {
                throw new IllegalArgumentException("MOLCLASS_DB_USER is required");
            }
            if (!help && password == null) {
                throw new IllegalArgumentException("MOLCLASS_DB_PASSWORD is required");
            }
            return new Config(jdbcUrl, schema, user, password,
                    productionGate, verifyDigests, help);
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    private static final class AuditResult {
        private final boolean productionGate;
        private final List<Check> checks = new ArrayList<>();

        private AuditResult(boolean productionGate) {
            this.productionGate = productionGate;
        }

        private void pass(String name, String detail) {
            checks.add(new Check("PASS", name, detail));
        }

        private void skip(String name, String detail) {
            checks.add(new Check("SKIP", name, detail));
        }

        private void fail(String name, String detail) {
            checks.add(new Check("FAIL", name, detail));
        }

        private void zero(String name, long failures) {
            if (failures == 0) pass(name, "0 violations");
            else fail(name, failures + " violations");
        }

        private void gate(String name, long count, String detail) {
            if (count == 0) {
                pass(name, "0");
            } else if (productionGate) {
                fail(name, count + " - " + detail);
            } else {
                checks.add(new Check("WARN", name, count + " - " + detail));
            }
        }

        private void gateMinimum(String name, long count, long minimum, String detail) {
            if (count >= minimum) {
                pass(name, Long.toString(count));
            } else if (productionGate) {
                fail(name, count + " - " + detail);
            } else {
                checks.add(new Check("WARN", name, count + " - " + detail));
            }
        }

        private boolean failed() {
            return checks.stream().anyMatch(check -> "FAIL".equals(check.level));
        }

        private void print() {
            for (Check check : checks) {
                System.out.printf(Locale.ROOT, "[%s] %s: %s%n",
                        check.level, check.name, check.detail);
            }
            long failures = checks.stream().filter(check -> "FAIL".equals(check.level)).count();
            long warnings = checks.stream().filter(check -> "WARN".equals(check.level)).count();
            System.out.printf(Locale.ROOT,
                    "AUDIT %s (failures=%d, warnings=%d, productionGate=%s)%n",
                    failures == 0 ? "PASS" : "FAIL", failures, warnings, productionGate);
        }
    }

    private record BuildReviewContractViolations(
            long evaluationMetricViolations, long rejectedDecisionViolations,
            long supersededLifecycleViolations) {
    }

    private static final class Check {
        private final String level;
        private final String name;
        private final String detail;

        private Check(String level, String name, String detail) {
            this.level = level;
            this.name = name;
            this.detail = detail;
        }
    }
}
