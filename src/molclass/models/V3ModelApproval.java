package molclass.models;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Transactional human approval and rejection boundary for immutable v3 model builds. */
public final class V3ModelApproval {
    private static final String PRODUCTION_LABEL = "v3-cdk-2.12-weka-3.8.7-stratified-gzip-v1";
    private static final String ARTIFACT_FORMAT = "JAVA_SERIALIZATION_WEKA_3_8_7_GZIP";
    static final int ARTIFACT_DIGEST_BUFFER_SIZE = 8192;
    private static final Set<String> MANDATORY_METRIC_CODES = Set.of(
            "ACCURACY", "KAPPA", "WEIGHTED_PRECISION", "WEIGHTED_RECALL",
            "WEIGHTED_F1", "WEIGHTED_AUC");
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern CROSS_VALIDATION_BLOCK = Pattern.compile(
            "\"crossValidation\"\\s*:\\s*\\{([^}]*)}");
    private static final Pattern CROSS_VALIDATION_REQUIRED = Pattern.compile(
            "\"required\"\\s*:\\s*true");
    private static final Pattern CROSS_VALIDATION_FOLDS = Pattern.compile(
            "\"folds\"\\s*:\\s*(\\d+)");
    private static final Pattern NOT_APPLICABLE = Pattern.compile(
            "\"status\"\\s*:\\s*\"NOT_APPLICABLE\"");
    private static final Pattern NO_EVALUABLE_AUC = Pattern.compile(
            "\"reason\"\\s*:\\s*\"NO_EVALUABLE_ONE_VS_REST_CLASS\"");
    private static final Pattern DEGENERATE_KAPPA = Pattern.compile(
            "\"reason\"\\s*:\\s*\"DEGENERATE_CONFUSION_MATRIX\"");

    private V3ModelApproval() { }

    public static void main(String[] arguments) {
        try {
            Config config = Config.parse(arguments);
            try (Connection connection = V3JdbcSession.configureUtc(DriverManager.getConnection(
                    connectionUrl(config.jdbcUrl, config.schema), config.user, config.password))) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                decide(connection, config);
                connection.commit();
            }
        } catch (Exception exception) {
            System.err.println("Model approval failed: " + rootMessage(exception));
            System.exit(2);
        }
    }

    private static void decide(Connection connection, Config config) throws Exception {
        ApprovalTarget target = readTarget(connection, config);
        DefinitionState lockedDefinition = lockDefinition(connection, config, target.definitionId);
        Build build = lockBuild(connection, config);
        if (build.definitionId != target.definitionId) {
            throw new IllegalStateException("build " + build.id + " changed model definition");
        }
        if (!"AWAITING_APPROVAL".equals(build.status)) {
            throw new IllegalStateException("build " + build.id + " is " + build.status
                    + ", expected AWAITING_APPROVAL");
        }
        if (config.decision == Decision.APPROVE) {
            verifyPublicationState(build.definitionId, target.definitionState, lockedDefinition);
            verifyPublishable(connection, config, build);
        }
        insertDecision(connection, config, build);
        if (config.decision == Decision.APPROVE) publish(connection, config, build);
        else reject(connection, config, build);
        insertAudit(connection, config, build);
        System.out.println("Build " + build.id + " "
                + (config.decision == Decision.APPROVE ? "published" : "rejected")
                + " by " + config.actor + ".");
    }

    private static ApprovalTarget readTarget(Connection connection, Config config)
            throws SQLException {
        String sql = "SELECT mb.model_definition_id,md.status,md.published_model_build_id FROM "
                + table(config, "model_build") + " mb JOIN " + table(config, "model_definition")
                + " md ON md.model_definition_id=mb.model_definition_id"
                + " WHERE mb.model_build_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, config.buildId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("unknown model build " + config.buildId);
                return new ApprovalTarget(row.getLong(1),
                        new DefinitionState(row.getString(2), nullableLong(row, 3)));
            }
        }
    }

    private static DefinitionState lockDefinition(Connection connection, Config config,
            long definitionId) throws SQLException {
        String sql = "SELECT status,published_model_build_id FROM "
                + table(config, "model_definition") + " WHERE model_definition_id=? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, definitionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("unknown model definition " + definitionId);
                return new DefinitionState(row.getString(1), nullableLong(row, 2));
            }
        }
    }

    static void verifyPublicationState(long definitionId, DefinitionState observed,
            DefinitionState locked) {
        if (!observed.equals(locked)) {
            throw new IllegalStateException("model definition " + definitionId
                    + " publication state changed while the decision was waiting");
        }
        if (!"AWAITING_APPROVAL".equals(locked.status)) {
            throw new IllegalStateException("model definition " + definitionId + " is "
                    + locked.status + ", expected AWAITING_APPROVAL");
        }
    }

    private static Build lockBuild(Connection connection, Config config) throws SQLException {
        String sql = "SELECT model_build_id,model_definition_id,status,generation_label,code_revision,"
                + "training_count,validation_count,holdout_count,excluded_count,build_manifest_json,manifest_sha256 "
                + "FROM " + table(config, "model_build") + " WHERE model_build_id=? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, config.buildId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("unknown model build " + config.buildId);
                return new Build(row.getLong(1), row.getLong(2), row.getString(3), row.getString(4),
                        row.getString(5), row.getLong(6), row.getLong(7), row.getLong(8), row.getLong(9),
                        row.getString(10), row.getBytes(11));
            }
        }
    }

    private static void verifyPublishable(Connection connection, Config config, Build build) throws Exception {
        if (!PRODUCTION_LABEL.equals(build.generationLabel)) {
            throw new IllegalStateException("build generation is not the production contract: "
                    + build.generationLabel);
        }
        if (build.codeRevision == null || build.codeRevision.isBlank()
                || "working-tree".equals(build.codeRevision)) {
            throw new IllegalStateException("publishable build requires MOLCLASS_CODE_REVISION");
        }
        if (build.manifest == null || build.manifestSha256 == null
                || !MessageDigest.isEqual(sha(build.manifest.getBytes(StandardCharsets.UTF_8)),
                        build.manifestSha256)) {
            throw new IllegalStateException("build manifest checksum is missing or invalid");
        }
        verifyMembership(connection, config, build);
        if (scalar(connection, "SELECT COUNT(*) FROM " + table(config, "model_class")
                + " WHERE model_build_id=?", build.id) < 2) {
            throw new IllegalStateException("model build has fewer than two persisted classes");
        }
        verifyMetrics(connection, config, build.id, "TRAIN", build.training);
        verifyMetrics(connection, config, build.id, "VALIDATION", build.validation);
        verifyMetrics(connection, config, build.id, "HOLDOUT", build.holdout);
        int crossValidationFolds = requiredCrossValidationFolds(build.manifest);
        if (crossValidationFolds > 0) {
            verifyCrossValidation(connection, config, build.id, crossValidationFolds,
                    build.training + build.validation + build.holdout);
        }
        verifyArtifacts(connection, config, build.id);
    }

    private static void verifyMembership(Connection connection, Config config, Build build)
            throws SQLException {
        String sql = "SELECT COUNT(*),"
                + "COALESCE(SUM(CASE WHEN partition_name='TRAIN' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN partition_name='VALIDATION' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN partition_name='HOLDOUT' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN partition_name='EXCLUDED' THEN 1 ELSE 0 END),0) FROM "
                + table(config, "model_training_member") + " WHERE model_build_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, build.id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("membership query returned no row");
                verifyMembershipCounts(build.training, build.validation, build.holdout,
                        build.excluded, new MembershipCounts(row.getLong(1), row.getLong(2),
                                row.getLong(3), row.getLong(4), row.getLong(5)));
            }
        }
    }

    static void verifyMembershipCounts(long training, long validation, long holdout,
            long excluded, MembershipCounts actual) {
        long expectedTotal = training + validation + holdout + excluded;
        if (training == 0 || actual.total != expectedTotal || actual.training != training
                || actual.validation != validation || actual.holdout != holdout
                || actual.excluded != excluded) {
            throw new IllegalStateException("model membership counts do not match the build");
        }
    }

    private static void verifyMetrics(Connection connection, Config config, long buildId,
            String evaluationSet, long expectedSupport) throws SQLException {
        if (expectedSupport == 0) return;
        String sql = "SELECT metric_code,support_count,metric_value,metric_details_json FROM "
                + table(config, "model_evaluation")
                + " WHERE model_build_id=? AND evaluation_set=?"
                + " AND fold_number IS NULL AND class_label IS NULL";
        List<MetricEvidence> evidence = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, buildId);statement.setString(2, evaluationSet);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    evidence.add(metricEvidence(rows));
                }
            }
        }
        verifyMetricEvidence(evaluationSet, expectedSupport, evidence);
    }

    static void verifyMetricEvidence(String evaluationSet, long expectedSupport,
            List<MetricEvidence> evidence) {
        Set<String> codes = new HashSet<>();
        boolean complete = evidence.size() == MANDATORY_METRIC_CODES.size();
        for (MetricEvidence metric : evidence) {
            complete &= metric.code != null && MANDATORY_METRIC_CODES.contains(metric.code)
                    && codes.add(metric.code);
            complete &= metric.support != null && metric.support == expectedSupport;
            complete &= validMetricValue(metric);
        }
        if (!complete || !codes.equals(MANDATORY_METRIC_CODES)) {
            throw new IllegalStateException(evaluationSet + " evaluation is incomplete");
        }
    }

    static int requiredCrossValidationFolds(String manifest) {
        if (manifest == null) return 0;
        java.util.regex.Matcher block = CROSS_VALIDATION_BLOCK.matcher(manifest);
        if (!block.find()) return 0;
        String contract = block.group(1);
        if (!CROSS_VALIDATION_REQUIRED.matcher(contract).find()) return 0;
        java.util.regex.Matcher folds = CROSS_VALIDATION_FOLDS.matcher(contract);
        if (!folds.find()) {
            throw new IllegalStateException("required cross-validation fold count is missing");
        }
        int count = Integer.parseInt(folds.group(1));
        if (count < 2 || count > 10) {
            throw new IllegalStateException("required cross-validation fold count is invalid");
        }
        return count;
    }

    private static void verifyCrossValidation(Connection connection, Config config, long buildId,
            int folds, long expectedSupport) throws SQLException {
        verifyMetrics(connection, config, buildId, "CROSS_VALIDATION", expectedSupport);
        String sql = "SELECT metric_code,support_count,metric_value,metric_details_json,fold_number FROM "
                + table(config, "model_evaluation")
                + " WHERE model_build_id=? AND evaluation_set='CROSS_VALIDATION'"
                + " AND fold_number IS NOT NULL AND class_label IS NULL ORDER BY fold_number";
        Map<Integer, List<MetricEvidence>> evidenceByFold = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, buildId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    evidenceByFold.computeIfAbsent(rows.getInt(5), ignored -> new ArrayList<>())
                            .add(metricEvidence(rows));
                }
            }
        }
        if (evidenceByFold.size() != folds) {
            throw new IllegalStateException("CROSS_VALIDATION fold evidence is incomplete");
        }
        long totalSupport = 0;
        for (int fold = 1; fold <= folds; fold++) {
            List<MetricEvidence> evidence = evidenceByFold.get(fold);
            if (evidence == null || evidence.isEmpty() || evidence.get(0).support == null
                    || evidence.get(0).support <= 0) {
                throw new IllegalStateException("CROSS_VALIDATION fold evidence is incomplete");
            }
            long foldSupport = evidence.get(0).support;
            verifyMetricEvidence("CROSS_VALIDATION fold " + fold, foldSupport, evidence);
            totalSupport += foldSupport;
        }
        if (totalSupport != expectedSupport) {
            throw new IllegalStateException("CROSS_VALIDATION fold support does not match the build");
        }
    }

    private static MetricEvidence metricEvidence(ResultSet rows) throws SQLException {
        long support = rows.getLong(2);Long nullableSupport = rows.wasNull() ? null : support;
        double value = rows.getDouble(3);Double nullableValue = rows.wasNull() ? null : value;
        return new MetricEvidence(rows.getString(1), nullableSupport, nullableValue, rows.getString(4));
    }

    private static boolean validMetricValue(MetricEvidence metric) {
        if (metric.value == null) {
            if (metric.detailsJson == null || !NOT_APPLICABLE.matcher(metric.detailsJson).find()) {
                return false;
            }
            return ("WEIGHTED_AUC".equals(metric.code)
                    && NO_EVALUABLE_AUC.matcher(metric.detailsJson).find())
                    || ("KAPPA".equals(metric.code)
                    && DEGENERATE_KAPPA.matcher(metric.detailsJson).find());
        }
        if (!Double.isFinite(metric.value)) return false;
        if ("KAPPA".equals(metric.code)) return metric.value >= -1.0 && metric.value <= 1.0;
        return metric.value >= 0.0 && metric.value <= 1.0;
    }

    private static void verifyArtifacts(Connection connection, Config config, long buildId)
            throws Exception {
        String sql = "SELECT artifact_kind,artifact_format,artifact_size,artifact_sha256,artifact_payload FROM "
                + table(config, "model_artifact") + " WHERE model_build_id=?";
        Set<String> kinds = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(Integer.MIN_VALUE);
            statement.setLong(1, buildId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String kind = rows.getString(1);String format = rows.getString(2);
                    long size = rows.getLong(3);byte[] expected = rows.getBytes(4);
                    if (!("MODEL".equals(kind) || "HEADER".equals(kind)) || !kinds.add(kind)) {
                        throw new IllegalStateException("unexpected or duplicate artifact kind " + kind);
                    }
                    if (!ARTIFACT_FORMAT.equals(format)) {
                        throw new IllegalStateException("artifact verification failed for " + kind);
                    }
                    try (InputStream payload = rows.getBinaryStream(5)) {
                        verifyArtifactPayload(payload, size, expected, kind);
                    }
                }
            }
        }
        if (!kinds.equals(Set.of("MODEL", "HEADER"))) {
            throw new IllegalStateException("build requires exactly MODEL and HEADER artifacts");
        }
    }

    static void verifyArtifactPayload(InputStream payload, long expectedSize,
            byte[] expectedSha256, String kind) throws Exception {
        if (payload == null || expectedSize <= 0 || expectedSha256 == null
                || expectedSha256.length != 32) {
            throw new IllegalStateException("artifact verification failed for " + kind);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[ARTIFACT_DIGEST_BUFFER_SIZE];
        long actualSize = 0;
        while (true) {
            int count = payload.read(buffer);
            if (count == -1) break;
            if (count == 0) {
                int value = payload.read();
                if (value == -1) break;
                buffer[0] = (byte) value;
                count = 1;
            }
            if (count > expectedSize - actualSize) {
                throw new IllegalStateException("artifact verification failed for " + kind);
            }
            digest.update(buffer, 0, count);
            actualSize += count;
        }
        if (actualSize == 0 || actualSize != expectedSize
                || !MessageDigest.isEqual(expectedSha256, digest.digest())) {
            throw new IllegalStateException("artifact verification failed for " + kind);
        }
    }

    private static void insertDecision(Connection connection, Config config, Build build)
            throws SQLException {
        String sql = "INSERT INTO " + table(config, "model_approval")
                + " (model_build_id,approval_status,approved_by,approval_note,approved_at)"
                + " VALUES (?,?,?,?,NOW(6))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, build.id);statement.setString(2, config.decision.name());
            statement.setString(3, config.actor);nullable(statement, 4, config.note);statement.executeUpdate();
        }
    }

    private static void publish(Connection connection, Config config, Build build) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + table(config, "model_build")
                + " SET status='SUPERSEDED' WHERE model_definition_id=? AND status='PUBLISHED' AND model_build_id<>?")) {
            statement.setLong(1, build.definitionId);statement.setLong(2, build.id);statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + table(config, "model_build")
                + " SET status='PUBLISHED',published_at=NOW(6) WHERE model_build_id=?")) {
            statement.setLong(1, build.id);statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + table(config, "model_definition")
                + " SET status='ACTIVE',published_model_build_id=? WHERE model_definition_id=?")) {
            statement.setLong(1, build.id);statement.setLong(2, build.definitionId);statement.executeUpdate();
        }
    }

    private static void reject(Connection connection, Config config, Build build) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + table(config, "model_build")
                + " SET status='REJECTED' WHERE model_build_id=?")) {
            statement.setLong(1, build.id);statement.executeUpdate();
        }
        String sql = "UPDATE " + table(config, "model_definition") + " md SET md.status=CASE WHEN EXISTS("
                + "SELECT 1 FROM " + table(config, "model_build")
                + " mb WHERE mb.model_definition_id=md.model_definition_id AND mb.status='PUBLISHED')"
                + " THEN 'ACTIVE' ELSE 'PENDING_REBUILD' END WHERE md.model_definition_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, build.definitionId);statement.executeUpdate();
        }
    }

    private static void insertAudit(Connection connection, Config config, Build build)
            throws SQLException {
        String sql = "INSERT INTO " + table(config, "audit_event")
                + " (actor,action_code,entity_type,entity_id,event_details_json,created_at)"
                + " VALUES (?,?,?,?,?,NOW(6))";
        String details = "{\"modelDefinitionId\":" + build.definitionId + ",\"decision\":"
                + quote(config.decision.name()) + ",\"note\":" + quote(config.note) + "}";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, config.actor);statement.setString(2, "MODEL_" + config.decision.name());
            statement.setString(3, "MODEL_BUILD");statement.setString(4, Long.toString(build.id));
            statement.setString(5, details);statement.executeUpdate();
        }
    }

    private static long scalar(Connection connection, String sql, long buildId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, buildId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("scalar query returned no row");
                return row.getLong(1);
            }
        }
    }

    private static Long nullableLong(ResultSet row, int index) throws SQLException {
        long value = row.getLong(index);
        return row.wasNull() ? null : value;
    }

    private static String table(Config config, String name) {
        if (!SAFE.matcher(config.schema).matches() || !SAFE.matcher(name).matches()) {
            throw new IllegalArgumentException("unsafe database identifier");
        }
        return "`" + config.schema + "`.`" + name + "`";
    }

    private static String connectionUrl(String jdbcUrl, String schema) {
        if (jdbcUrl.endsWith("/")) return jdbcUrl + schema;
        return jdbcUrl;
    }

    private static void nullable(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static byte[] sha(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static String quote(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private static String rootMessage(Throwable exception) {
        while (exception.getCause() != null && exception.getCause() != exception) {
            exception = exception.getCause();
        }
        return exception.getMessage() == null ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private enum Decision { APPROVE, REJECT }

    record DefinitionState(String status, Long publishedBuildId) { }

    record MembershipCounts(long total, long training, long validation, long holdout,
            long excluded) { }

    record MetricEvidence(String code, Long support, Double value, String detailsJson) {
        MetricEvidence(String code, Long support) { this(code, support, 0.5, null); }
    }

    private record ApprovalTarget(long definitionId, DefinitionState definitionState) { }

    private record Build(long id, long definitionId, String status, String generationLabel,
            String codeRevision, long training, long validation, long holdout, long excluded,
            String manifest, byte[] manifestSha256) { }

    private record Config(String jdbcUrl, String user, String password, String schema,
            long buildId, Decision decision, String actor, String note) {
        static Config parse(String[] arguments) {
            String jdbcUrl = env("MOLCLASS_JDBC_URL", "jdbc:mysql://127.0.0.1:3306/");
            String user = env("MOLCLASS_DB_USER", null);String password = env("MOLCLASS_DB_PASSWORD", null);
            String schema = env("MOLCLASS_DB_SCHEMA", "molclass_v3");String actor = null,note = null;
            Long buildId = null;Decision decision = null;
            for (int index = 0; index < arguments.length; index++) {
                String option = arguments[index];
                if (index + 1 >= arguments.length) throw new IllegalArgumentException("missing value for " + option);
                String value = arguments[++index];
                switch (option) {
                    case "--jdbc-url" -> jdbcUrl = value;
                    case "--db-user" -> user = value;
                    case "--db-password" -> password = value;
                    case "--schema" -> schema = value;
                    case "--build-id" -> buildId = Long.parseLong(value);
                    case "--decision" -> decision = Decision.valueOf(value.toUpperCase(Locale.ROOT));
                    case "--actor" -> actor = value;
                    case "--note" -> note = value;
                    default -> throw new IllegalArgumentException("unknown option " + option);
                }
            }
            if (user == null || password == null) throw new IllegalArgumentException("database credentials are required");
            if (buildId == null || buildId <= 0 || decision == null) throw new IllegalArgumentException("--build-id and --decision are required");
            if (actor == null || actor.isBlank() || actor.length() > 255) throw new IllegalArgumentException("--actor is required and limited to 255 characters");
            if (note != null && note.length() > 2048) throw new IllegalArgumentException("--note is limited to 2048 characters");
            return new Config(jdbcUrl,user,password,schema,buildId,decision,actor,note);
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);return value == null || value.isBlank() ? fallback : value;
        }
    }
}
