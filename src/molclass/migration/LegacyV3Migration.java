package molclass.migration;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resumable, read-only migration from the legacy MolClass schema to molclass_v3.
 *
 * <p>This command deliberately does not copy descriptors, fingerprints, predictions,
 * serialized Weka headers, or serialized Weka models. Those artifacts must be rebuilt
 * with the versions recorded by the v3 feature and model build pipelines.</p>
 */
public final class LegacyV3Migration {
    private static final String CDK_VERSION = "2.12";
    private static final String WEKA_VERSION = "3.8.7";
    private static final String PROFILE_VERSION = "v3-cdk-2.12";
    private static final String NORMALIZATION_VERSION = "legacy-source-v1";
    private static final String SYNTHETIC_IDENTIFIER = "__legacy_mol_id";
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern VALID_INCHI_KEY =
            Pattern.compile("[A-Z]{14}-[A-Z]{10}-[A-Z]");

    private LegacyV3Migration() {
    }

    public static void main(String[] args) {
        int exitCode = execute(args, System.getenv(), System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int execute(String[] args, Map<String, String> environment,
                       PrintStream out, PrintStream err) {
        if (Arrays.asList(args).contains("--help")) {
            printUsage(out);
            return 0;
        }

        final Config config;
        try {
            config = Config.parse(args, environment);
        } catch (IllegalArgumentException exception) {
            err.println("Configuration error: " + exception.getMessage());
            printUsage(err);
            return 2;
        }

        long runId = -1;
        boolean lockHeld = false;
        try (Connection read = DriverManager.getConnection(
                    config.jdbcUrl(), config.dbUser(), config.dbPassword());
             Connection write = DriverManager.getConnection(
                    config.jdbcUrl(), config.dbUser(), config.dbPassword())) {
            configureConnections(read, write);
            lockHeld = acquireLock(write, config.targetSchema());
            if (!lockHeld) {
                throw new IllegalStateException(
                        "another legacy migration owns the target database lock");
            }

            Migration migration = new Migration(config, read, write, out, err);
            RunState state = migration.openRun();
            runId = state.runId();
            if (state.alreadyComplete()) {
                out.println("Migration run " + runId + " is already complete.");
                return 0;
            }
            migration.run(state.startStage());
            return 0;
        } catch (Exception exception) {
            err.println("Legacy migration failed: " + conciseMessage(exception));
            if (runId > 0) {
                err.println("Resume with --resume-run " + runId + " after correcting the fatal error.");
            }
            return 1;
        } finally {
            // The named lock is connection-scoped and is released automatically on close.
            if (lockHeld) {
                out.flush();
            }
        }
    }

    private static void configureConnections(Connection read, Connection write)
            throws SQLException {
        read.setAutoCommit(true);
        try {
            read.setReadOnly(true);
        } catch (SQLException ignored) {
            // Some compatible JDBC drivers do not implement the read-only hint.
        }
        write.setAutoCommit(false);
        write.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }

    private static boolean acquireLock(Connection connection, String targetSchema)
            throws SQLException {
        String lockName = limit("molclass-v3-migration:" + targetSchema, 64);
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, lockName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private static void printUsage(PrintStream stream) {
        stream.println("Usage: ./gradlew migrateLegacyV3 [migration options]");
        stream.println("Required environment: MOLCLASS_JDBC_URL, MOLCLASS_DB_USER, MOLCLASS_DB_PASSWORD");
        stream.println("Options passed with -PmigrationArgs=\"...\":");
        stream.println("  --jdbc-url URL          Overrides MOLCLASS_JDBC_URL");
        stream.println("  --db-user USER          Overrides MOLCLASS_DB_USER");
        stream.println("  --password-env NAME     Password environment variable (default MOLCLASS_DB_PASSWORD)");
        stream.println("  --source-schema NAME    Source schema (default molclass_legacy)");
        stream.println("  --target-schema NAME    Target schema (default molclass_v3)");
        stream.println("  --resume-run ID|latest  Resume an unfinished migration run");
        stream.println("  --chunk-size N          Durable commit interval (default 500)");
        stream.println("  --fetch-size N          JDBC source fetch size (default 500)");
        stream.println("  --stop-after STAGE      Pause after a named stage");
        stream.println("  --help                  Show this contract");
    }

    private enum Stage {
        PROPERTIES,
        DATASETS,
        PROPERTY_ANALYSIS,
        MOLECULES,
        DATASET_MOLECULES,
        MODEL_DEFINITIONS,
        COMPLETE;

        Stage next() {
            int next = ordinal() + 1;
            return next < values().length ? values()[next] : COMPLETE;
        }

        static Stage parse(String value) {
            try {
                Stage stage = valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
                if (stage == COMPLETE) {
                    throw new IllegalArgumentException("COMPLETE is not a runnable stop stage");
                }
                return stage;
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("invalid migration stage: " + value, exception);
            }
        }
    }

    private record Config(String jdbcUrl, String dbUser, String dbPassword,
                          String passwordEnvironment, String sourceSchema,
                          String targetSchema, String resumeRun, int chunkSize,
                          int fetchSize, Stage stopAfter) {
        private static final Set<String> ALLOWED_OPTIONS = Set.of(
                "jdbc-url", "db-user", "password-env", "source-schema",
                "target-schema", "resume-run", "chunk-size", "fetch-size",
                "stop-after");

        static Config parse(String[] args, Map<String, String> environment) {
            Map<String, String> options = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (!argument.startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument: " + argument);
                }
                String name = argument.substring(2);
                if (!ALLOWED_OPTIONS.contains(name)) {
                    throw new IllegalArgumentException("unknown option: --" + name);
                }
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for --" + name);
                }
                if (options.put(name, args[++index]) != null) {
                    throw new IllegalArgumentException("duplicate option: --" + name);
                }
            }

            String jdbcUrl = firstNonBlank(options.get("jdbc-url"),
                    environment.get("MOLCLASS_JDBC_URL"));
            String dbUser = firstNonBlank(options.get("db-user"),
                    environment.get("MOLCLASS_DB_USER"));
            String passwordEnvironment = options.getOrDefault(
                    "password-env", "MOLCLASS_DB_PASSWORD");
            String dbPassword = environment.get(passwordEnvironment);
            if (jdbcUrl == null) {
                throw new IllegalArgumentException("MOLCLASS_JDBC_URL or --jdbc-url is required");
            }
            if (dbUser == null) {
                throw new IllegalArgumentException("MOLCLASS_DB_USER or --db-user is required");
            }
            if (dbPassword == null) {
                throw new IllegalArgumentException(
                        "password environment variable " + passwordEnvironment + " is not set");
            }

            String source = options.getOrDefault("source-schema", "molclass_legacy");
            String target = options.getOrDefault("target-schema", "molclass_v3");
            validateSchema(source, "source");
            validateSchema(target, "target");
            if (source.equals(target)) {
                throw new IllegalArgumentException("source and target schemas must differ");
            }

            int chunkSize = positiveInteger(options.getOrDefault("chunk-size", "500"),
                    "chunk-size");
            int fetchSize = positiveInteger(options.getOrDefault("fetch-size", "500"),
                    "fetch-size");
            Stage stopAfter = options.containsKey("stop-after")
                    ? Stage.parse(options.get("stop-after")) : null;
            return new Config(jdbcUrl, dbUser, dbPassword, passwordEnvironment,
                    source, target, options.get("resume-run"), chunkSize,
                    fetchSize, stopAfter);
        }

        private static int positiveInteger(String value, String name) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed <= 0) {
                    throw new NumberFormatException("not positive");
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("--" + name + " must be a positive integer");
            }
        }
    }

    private record RunState(long runId, Stage startStage, boolean alreadyComplete) {
    }

    private record TypeSpec(String family, String ddl, Integer maximumLength,
                            Integer precision, Integer scale) {
    }

    private record SourceProperty(int ordinal, String originalName, TypeSpec type) {
    }

    private record TargetProperty(long propertyId, String originalName,
                                  String physicalName, TypeSpec type) {
    }

    private record DatasetRef(long legacyBatchId, long datasetId, long totalRecords,
                              String identifierProperty) {
    }

    private record Candidate(TargetProperty property, int score, int ordinal) {
    }

    private static final class Migration {
        private final Config config;
        private final Connection read;
        private final Connection write;
        private final PrintStream out;
        private final PrintStream err;
        private long runId;
        private List<SourceProperty> sourceProperties;
        private Map<String, TargetProperty> targetProperties;

        Migration(Config config, Connection read, Connection write,
                  PrintStream out, PrintStream err) {
            this.config = config;
            this.read = read;
            this.write = write;
            this.out = out;
            this.err = err;
        }

        RunState openRun() throws SQLException {
            requireV2Schema();
            byte[] fingerprint = sourceSchemaFingerprint();
            if (config.resumeRun() == null) {
                assertFreshTarget();
                String configuration = migrationConfigurationJson();
                String sql = "INSERT INTO " + table(config.targetSchema(), "legacy_migration_run")
                        + " (source_schema, source_schema_fingerprint, status, runstep,"
                        + " configuration_json, started_at) VALUES (?, ?, 'RUNNING', ?, ?, NOW(6))";
                try (PreparedStatement statement = write.prepareStatement(
                        sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, config.sourceSchema());
                    statement.setBytes(2, fingerprint);
                    statement.setString(3, Stage.PROPERTIES.name());
                    statement.setString(4, configuration);
                    statement.executeUpdate();
                    runId = generatedKey(statement);
                }
                write.commit();
                out.println("Created migration run " + runId + ".");
                return new RunState(runId, Stage.PROPERTIES, false);
            }

            long selectedRun = resolveRunId(config.resumeRun());
            String sql = "SELECT source_schema, source_schema_fingerprint, status, runstep"
                    + " FROM " + table(config.targetSchema(), "legacy_migration_run")
                    + " WHERE legacy_migration_run_id = ?";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setLong(1, selectedRun);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalArgumentException("migration run does not exist: " + selectedRun);
                    }
                    if (!config.sourceSchema().equals(result.getString(1))) {
                        throw new IllegalStateException("resume source schema does not match the run");
                    }
                    if (!MessageDigest.isEqual(fingerprint, result.getBytes(2))) {
                        throw new IllegalStateException(
                                "legacy source schema changed since this run was created");
                    }
                    String status = result.getString(3);
                    Stage stage = Stage.valueOf(result.getString(4));
                    runId = selectedRun;
                    if ("COMPLETED".equals(status) || "COMPLETED_WITH_ERRORS".equals(status)) {
                        return new RunState(runId, stage, true);
                    }
                    setRunState("RUNNING", stage, null, null, false);
                    write.commit();
                    out.println("Resuming migration run " + runId + " at " + stage + ".");
                    return new RunState(runId, stage, false);
                }
            }
        }

        void run(Stage startStage) throws Exception {
            sourceProperties = loadSourceProperties();
            Stage current = startStage;
            try {
                while (current != Stage.COMPLETE) {
                    setRunState("RUNNING", current, null, null, false);
                    write.commit();
                    out.println("Starting stage " + current + ".");
                    switch (current) {
                        case PROPERTIES -> migratePropertyDefinitions();
                        case DATASETS -> migrateDatasets();
                        case PROPERTY_ANALYSIS -> analyzeDatasetProperties();
                        case MOLECULES -> migrateMolecules();
                        case DATASET_MOLECULES -> migrateDatasetMolecules();
                        case MODEL_DEFINITIONS -> migrateModelDefinitions();
                        default -> throw new IllegalStateException("unexpected stage " + current);
                    }
                    Stage completed = current;
                    current = current.next();
                    setRunState("RUNNING", current, null, null, false);
                    write.commit();
                    out.println("Completed stage " + completed + ".");
                    if (config.stopAfter() == completed) {
                        setRunState("PAUSED", current, null, null, false);
                        write.commit();
                        out.println("Migration run " + runId + " paused at " + current + ".");
                        return;
                    }
                }

                long failures = countRecordFailures();
                String status = failures == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS";
                setRunState(status, Stage.COMPLETE, null, null, true);
                write.commit();
                out.println("Migration run " + runId + " finished with " + failures
                        + " failed records.");
            } catch (Exception exception) {
                rollbackQuietly(write);
                try {
                    setRunState("FAILED", current, errorCode(exception),
                            conciseMessage(exception), true);
                    write.commit();
                } catch (SQLException updateFailure) {
                    exception.addSuppressed(updateFailure);
                }
                throw exception;
            }
        }

        private void requireV2Schema() throws SQLException {
            String sql = "SELECT COUNT(*) FROM information_schema.TABLES"
                    + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'legacy_migration_record'";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setString(1, config.targetSchema());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(1) != 1) {
                        throw new IllegalStateException(
                                "V02__legacy_migration_tracking.sql has not been applied");
                    }
                }
            }
        }

        private void assertFreshTarget() throws SQLException {
            String unfinished = "SELECT COUNT(*) FROM "
                    + table(config.targetSchema(), "legacy_migration_run")
                    + " WHERE status NOT IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')";
            try (Statement statement = write.createStatement();
                 ResultSet result = statement.executeQuery(unfinished)) {
                result.next();
                if (result.getLong(1) != 0) {
                    throw new IllegalStateException(
                            "an unfinished migration exists; use --resume-run latest");
                }
            }
            String populated = "SELECT (SELECT COUNT(*) FROM "
                    + table(config.targetSchema(), "dataset") + ") + "
                    + "(SELECT COUNT(*) FROM " + table(config.targetSchema(), "molecule") + ") + "
                    + "(SELECT COUNT(*) FROM " + table(config.targetSchema(), "model_definition") + ")";
            try (Statement statement = write.createStatement();
                 ResultSet result = statement.executeQuery(populated)) {
                result.next();
                if (result.getLong(1) != 0) {
                    throw new IllegalStateException(
                            "target contains application data; refusing a new migration run");
                }
            }
        }

        private long resolveRunId(String requested) throws SQLException {
            if (!"latest".equalsIgnoreCase(requested)) {
                try {
                    return Long.parseLong(requested);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException(
                            "--resume-run must be a numeric ID or latest", exception);
                }
            }
            String sql = "SELECT legacy_migration_run_id FROM "
                    + table(config.targetSchema(), "legacy_migration_run")
                    + " ORDER BY legacy_migration_run_id DESC LIMIT 1";
            try (Statement statement = write.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                if (!result.next()) {
                    throw new IllegalArgumentException("there is no migration run to resume");
                }
                return result.getLong(1);
            }
        }

        private void setRunState(String status, Stage stage, String code,
                                 String message, boolean finished) throws SQLException {
            String sql = "UPDATE " + table(config.targetSchema(), "legacy_migration_run")
                    + " SET status = ?, runstep = ?, error_code = ?, error_message = ?,"
                    + " finished_at = " + (finished ? "NOW(6)" : "NULL")
                    + " WHERE legacy_migration_run_id = ?";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setString(1, status);
                statement.setString(2, stage.name());
                setNullableString(statement, 3, code);
                setNullableString(statement, 4, limit(message, 2048));
                statement.setLong(5, runId);
                statement.executeUpdate();
            }
        }

        private byte[] sourceSchemaFingerprint() throws SQLException {
            MessageDigest digest = sha256Digest();
            String sql = "SELECT t.TABLE_NAME, t.ENGINE, c.ORDINAL_POSITION, c.COLUMN_NAME,"
                    + " c.COLUMN_TYPE, c.IS_NULLABLE, c.COLUMN_KEY"
                    + " FROM information_schema.TABLES t"
                    + " JOIN information_schema.COLUMNS c"
                    + " ON c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME"
                    + " WHERE t.TABLE_SCHEMA = ? ORDER BY t.TABLE_NAME, c.ORDINAL_POSITION";
            try (PreparedStatement statement = read.prepareStatement(sql)) {
                statement.setString(1, config.sourceSchema());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        for (int index = 1; index <= 7; index++) {
                            digestField(digest, result.getString(index));
                        }
                    }
                }
            }
            return digest.digest();
        }

        private String migrationConfigurationJson() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("cdkVersion", CDK_VERSION);
            values.put("chunkSize", config.chunkSize());
            values.put("identifierPolicy", "unique-nonblank-property-or-legacy-mol-id");
            values.put("normalizationVersion", NORMALIZATION_VERSION);
            values.put("passwordEnvironment", config.passwordEnvironment());
            values.put("sourceSchema", config.sourceSchema());
            values.put("targetSchema", config.targetSchema());
            values.put("wekaVersion", WEKA_VERSION);
            return jsonObject(values);
        }

        private List<SourceProperty> loadSourceProperties() throws SQLException {
            String sql = "SELECT ORDINAL_POSITION, COLUMN_NAME, DATA_TYPE, COLUMN_TYPE,"
                    + " CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE"
                    + " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?"
                    + " AND TABLE_NAME = 'sdftags' AND COLUMN_NAME <> 'mol_id'"
                    + " ORDER BY ORDINAL_POSITION";
            List<SourceProperty> properties = new ArrayList<>();
            try (PreparedStatement statement = read.prepareStatement(sql)) {
                statement.setString(1, config.sourceSchema());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        properties.add(new SourceProperty(
                                result.getInt(1), result.getString(2),
                                inferType(result.getString(3), result.getString(4),
                                        nullableInteger(result, 5), nullableInteger(result, 6),
                                        nullableInteger(result, 7))));
                    }
                }
            }
            if (properties.isEmpty()) {
                throw new IllegalStateException("legacy sdftags has no property columns");
            }
            return List.copyOf(properties);
        }

        private void migratePropertyDefinitions() throws SQLException {
            SourceProperty synthetic = new SourceProperty(0, SYNTHETIC_IDENTIFIER,
                    new TypeSpec("BIGINT", "BIGINT", null, 19, 0));
            List<SourceProperty> all = new ArrayList<>();
            all.add(synthetic);
            all.addAll(sourceProperties);
            Map<String, TargetProperty> resolved = new LinkedHashMap<>();
            for (SourceProperty source : all) {
                TargetProperty target = ensureTargetProperty(source);
                resolved.put(source.originalName(), target);
            }
            targetProperties = Collections.unmodifiableMap(resolved);
            write.commit();
        }

        private TargetProperty ensureTargetProperty(SourceProperty source) throws SQLException {
            TargetProperty existing = findTargetProperty(source.originalName());
            long propertyId;
            String physicalName;
            TypeSpec effectiveType;
            if (existing != null) {
                propertyId = existing.propertyId();
                physicalName = existing.physicalName();
                effectiveType = existing.type();
                if (!compatible(existing.type(), source.type())) {
                    throw new IllegalStateException("property type conflict for "
                            + source.originalName() + ": target " + existing.type().ddl()
                            + ", source " + source.type().ddl());
                }
            } else {
                physicalName = allocatePhysicalName(source.originalName());
                effectiveType = source.type();
                String sql = "INSERT INTO " + table(config.targetSchema(), "property_definition")
                        + " (original_name, physical_column_name, storage_mode, sql_type_family,"
                        + " sql_type_ddl, nullable_value, maximum_length, numeric_precision_value,"
                        + " numeric_scale_value, active) VALUES (?, ?, 'WIDE', ?, ?, 1, ?, ?, ?, 1)";
                try (PreparedStatement statement = write.prepareStatement(
                        sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, source.originalName());
                    statement.setString(2, physicalName);
                    statement.setString(3, effectiveType.family());
                    statement.setString(4, effectiveType.ddl());
                    setNullableInteger(statement, 5, effectiveType.maximumLength());
                    setNullableInteger(statement, 6, effectiveType.precision());
                    setNullableInteger(statement, 7, effectiveType.scale());
                    statement.executeUpdate();
                    propertyId = generatedKey(statement);
                }
                write.commit();
            }

            if (!wideColumnExists(physicalName)) {
                String ddl = "ALTER TABLE "
                        + table(config.targetSchema(), "dataset_molecule_properties")
                        + " ADD COLUMN " + identifier(physicalName) + " "
                        + effectiveType.ddl() + " NULL";
                try (Statement statement = write.createStatement()) {
                    statement.executeUpdate(ddl);
                }
                String changeSql = "INSERT INTO "
                        + table(config.targetSchema(), "property_schema_change")
                        + " (import_run_id, property_id, change_type, previous_sql_type,"
                        + " new_sql_type, ddl_sha256, applied_at)"
                        + " VALUES (NULL, ?, 'LEGACY_ADD', NULL, ?, ?, NOW(6))";
                try (PreparedStatement statement = write.prepareStatement(changeSql)) {
                    statement.setLong(1, propertyId);
                    statement.setString(2, effectiveType.ddl());
                    statement.setBytes(3, sha256(ddl));
                    statement.executeUpdate();
                }
                write.commit();
            }
            return new TargetProperty(propertyId, source.originalName(),
                    physicalName, effectiveType);
        }

        private TargetProperty findTargetProperty(String originalName) throws SQLException {
            String sql = "SELECT property_id, physical_column_name, sql_type_family,"
                    + " sql_type_ddl, maximum_length, numeric_precision_value, numeric_scale_value"
                    + " FROM " + table(config.targetSchema(), "property_definition")
                    + " WHERE original_name = ?";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setString(1, originalName);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return null;
                    }
                    return new TargetProperty(result.getLong(1), originalName, result.getString(2),
                            new TypeSpec(result.getString(3), result.getString(4),
                                    nullableInteger(result, 5), nullableInteger(result, 6),
                                    nullableInteger(result, 7)));
                }
            }
        }

        private void loadTargetProperties() throws SQLException {
            if (targetProperties != null) {
                return;
            }
            Map<String, TargetProperty> result = new LinkedHashMap<>();
            SourceProperty synthetic = new SourceProperty(0, SYNTHETIC_IDENTIFIER,
                    new TypeSpec("BIGINT", "BIGINT", null, 19, 0));
            List<SourceProperty> all = new ArrayList<>();
            all.add(synthetic);
            all.addAll(sourceProperties);
            for (SourceProperty source : all) {
                TargetProperty target = findTargetProperty(source.originalName());
                if (target == null || !wideColumnExists(target.physicalName())) {
                    throw new IllegalStateException(
                            "property stage is incomplete for " + source.originalName());
                }
                result.put(source.originalName(), target);
            }
            targetProperties = Collections.unmodifiableMap(result);
        }

        private String allocatePhysicalName(String originalName) throws SQLException {
            String normalized = originalName.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("^_+|_+$", "");
            if (normalized.isEmpty()) {
                normalized = "property";
            }
            if (Character.isDigit(normalized.charAt(0))) {
                normalized = "p_" + normalized;
            }
            normalized = limit(normalized, 64);
            if (!physicalNameExists(normalized)) {
                return normalized;
            }
            String suffix = "_" + hex(sha256(originalName)).substring(0, 8);
            String candidate = limit(normalized, 64 - suffix.length()) + suffix;
            if (physicalNameExists(candidate)) {
                throw new IllegalStateException(
                        "cannot allocate a unique physical property name for " + originalName);
            }
            return candidate;
        }

        private boolean physicalNameExists(String physicalName) throws SQLException {
            String sql = "SELECT COUNT(*) FROM "
                    + table(config.targetSchema(), "property_definition")
                    + " WHERE physical_column_name = ?";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setString(1, physicalName);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1) != 0;
                }
            }
        }

        private boolean wideColumnExists(String physicalName) throws SQLException {
            String sql = "SELECT COUNT(*) FROM information_schema.COLUMNS"
                    + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'dataset_molecule_properties'"
                    + " AND COLUMN_NAME = ?";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setString(1, config.targetSchema());
                statement.setString(2, physicalName);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1) != 0;
                }
            }
        }

        private void migrateDatasets() throws SQLException {
            String sourceSql = "SELECT b.batch_id, b.username, b.filename, b.mol_type, b.pmid,"
                    + " b.info, COALESCE(c.total_records, 0)"
                    + " FROM " + table(config.sourceSchema(), "batchlist") + " b"
                    + " LEFT JOIN (SELECT batch_id, COUNT(*) total_records FROM "
                    + table(config.sourceSchema(), "batchmols")
                    + " GROUP BY batch_id) c ON c.batch_id = b.batch_id"
                    + " ORDER BY b.batch_id";
            String targetSql = "INSERT INTO " + table(config.targetSchema(), "dataset")
                    + " (legacy_batch_id, name, original_filename, description,"
                    + " publication_reference, molecule_type, status, total_records,"
                    + " imported_records, failed_records, not_processed_records,"
                    + " partial_acknowledgement_required, model_eligible, created_by)"
                    + " VALUES (?, ?, ?, ?, ?, ?, 'MIGRATING', ?, 0, 0, ?, 0, 0, ?)"
                    + " ON DUPLICATE KEY UPDATE dataset_id = LAST_INSERT_ID(dataset_id),"
                    + " name = VALUES(name), original_filename = VALUES(original_filename),"
                    + " description = VALUES(description),"
                    + " publication_reference = VALUES(publication_reference),"
                    + " molecule_type = VALUES(molecule_type), total_records = VALUES(total_records),"
                    + " not_processed_records = VALUES(not_processed_records),"
                    + " created_by = VALUES(created_by)";
            try (Statement sourceStatement = read.createStatement();
                 ResultSet source = sourceStatement.executeQuery(sourceSql);
                 PreparedStatement target = write.prepareStatement(
                         targetSql, Statement.RETURN_GENERATED_KEYS);
                 Tracker tracker = new Tracker(write, config.targetSchema(), runId);
                 PreparedStatement map = mapStatement()) {
                int processed = 0;
                while (source.next()) {
                    long batchId = source.getLong(1);
                    Savepoint savepoint = write.setSavepoint();
                    try {
                        tracker.attempt("DATASET", Long.toString(batchId), batchId,
                                null, Stage.DATASETS);
                        String filename = source.getString(3);
                        long total = source.getLong(7);
                        target.setLong(1, batchId);
                        target.setString(2, firstNonBlank(filename, "Legacy dataset " + batchId));
                        setNullableString(target, 3, filename);
                        setNullableString(target, 4, source.getString(6));
                        setNullableString(target, 5, source.getString(5));
                        setNullableString(target, 6, limit(source.getString(4), 32));
                        target.setLong(7, total);
                        target.setLong(8, total);
                        target.setString(9, firstNonBlank(source.getString(2), "legacy-migration"));
                        target.executeUpdate();
                        long datasetId = generatedKeyOrLastInsertId(target);
                        byte[] sourceHash = sha256(joinFields(batchId, source.getString(2),
                                filename, source.getString(4), source.getString(5),
                                source.getString(6), total));
                        putMap(map, "DATASET", batchId, datasetId, sourceHash);
                        tracker.success("DATASET", Long.toString(batchId), datasetId, sourceHash);
                        write.releaseSavepoint(savepoint);
                    } catch (Exception exception) {
                        write.rollback(savepoint);
                        tracker.failure("DATASET", Long.toString(batchId), batchId,
                                null, Stage.DATASETS, exception);
                        err.println("Dataset " + batchId + " skipped: " + conciseMessage(exception));
                    }
                    if (++processed % config.chunkSize() == 0) {
                        write.commit();
                    }
                }
                write.commit();
            }
        }

        private void analyzeDatasetProperties() throws SQLException {
            loadTargetProperties();
            Map<Long, DatasetRef> datasets = loadDatasets(false);
            Map<Long, Candidate> candidates = new LinkedHashMap<>();
            String upsertSql = "INSERT INTO " + table(config.targetSchema(), "dataset_property")
                    + " (dataset_id, property_id, selected_for_import, identifier_property,"
                    + " model_target_allowed, searchable, present_count, blank_count,"
                    + " distinct_count, inferred_sql_type, resolved_sql_type)"
                    + " VALUES (?, ?, 1, 0, 0, 0, ?, ?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE selected_for_import = 1,"
                    + " present_count = VALUES(present_count), blank_count = VALUES(blank_count),"
                    + " distinct_count = VALUES(distinct_count),"
                    + " inferred_sql_type = VALUES(inferred_sql_type),"
                    + " resolved_sql_type = VALUES(resolved_sql_type)";
            try (PreparedStatement upsert = write.prepareStatement(upsertSql)) {
                for (SourceProperty property : sourceProperties) {
                    TargetProperty target = requiredTargetProperty(property.originalName());
                    for (DatasetRef dataset : datasets.values()) {
                        upsertDatasetProperty(upsert, dataset.datasetId(), target,
                                0, dataset.totalRecords(), 0);
                    }
                    String value = "TRIM(CAST(s." + identifier(property.originalName()) + " AS CHAR))";
                    String present = "s.mol_id IS NOT NULL AND s."
                            + identifier(property.originalName()) + " IS NOT NULL AND " + value + " <> ''";
                    String statsSql = "SELECT bm.batch_id, COUNT(*),"
                            + " SUM(CASE WHEN " + present + " THEN 1 ELSE 0 END),"
                            + " SUM(CASE WHEN " + present + " THEN 0 ELSE 1 END),"
                            + " COUNT(DISTINCT CASE WHEN " + present + " THEN BINARY " + value + " END),"
                            + " MAX(CASE WHEN " + present + " THEN CHAR_LENGTH(" + value + ") END)"
                            + " FROM " + table(config.sourceSchema(), "batchmols") + " bm"
                            + " LEFT JOIN " + table(config.sourceSchema(), "sdftags")
                            + " s ON s.mol_id = bm.mol_id GROUP BY bm.batch_id";
                    try (Statement statement = read.createStatement();
                         ResultSet result = statement.executeQuery(statsSql)) {
                        while (result.next()) {
                            DatasetRef dataset = datasets.get(result.getLong(1));
                            if (dataset == null) {
                                continue;
                            }
                            long total = result.getLong(2);
                            long presentCount = result.getLong(3);
                            long blankCount = result.getLong(4);
                            long distinctCount = result.getLong(5);
                            long maximumLength = result.getLong(6);
                            if (result.wasNull()) {
                                maximumLength = 0;
                            }
                            upsertDatasetProperty(upsert, dataset.datasetId(), target,
                                    presentCount, blankCount, distinctCount);
                            if (total > 0 && presentCount == total && distinctCount == total
                                    && maximumLength <= 512) {
                                Candidate candidate = new Candidate(target,
                                        identifierScore(property.originalName()), property.ordinal());
                                candidates.merge(dataset.legacyBatchId(), candidate,
                                        Migration::preferredCandidate);
                            }
                        }
                    }
                    write.commit();
                }

                TargetProperty synthetic = requiredTargetProperty(SYNTHETIC_IDENTIFIER);
                for (DatasetRef dataset : datasets.values()) {
                    upsertDatasetProperty(upsert, dataset.datasetId(), synthetic,
                            dataset.totalRecords(), 0, dataset.totalRecords());
                }
                write.commit();

                String clearSql = "UPDATE " + table(config.targetSchema(), "dataset_property")
                        + " SET identifier_property = 0 WHERE dataset_id = ?";
                String selectSql = "UPDATE " + table(config.targetSchema(), "dataset_property")
                        + " SET identifier_property = 1, searchable = 1"
                        + " WHERE dataset_id = ? AND property_id = ?";
                String datasetSql = "UPDATE " + table(config.targetSchema(), "dataset")
                        + " SET identifier_property_id = ? WHERE dataset_id = ?";
                try (PreparedStatement clear = write.prepareStatement(clearSql);
                     PreparedStatement select = write.prepareStatement(selectSql);
                     PreparedStatement updateDataset = write.prepareStatement(datasetSql)) {
                    for (DatasetRef dataset : datasets.values()) {
                        Candidate candidate = candidates.get(dataset.legacyBatchId());
                        TargetProperty selected = candidate == null
                                ? synthetic : candidate.property();
                        clear.setLong(1, dataset.datasetId());
                        clear.executeUpdate();
                        select.setLong(1, dataset.datasetId());
                        select.setLong(2, selected.propertyId());
                        select.executeUpdate();
                        updateDataset.setLong(1, selected.propertyId());
                        updateDataset.setLong(2, dataset.datasetId());
                        updateDataset.executeUpdate();
                    }
                }
                write.commit();
            }
        }

        private static Candidate preferredCandidate(Candidate left, Candidate right) {
            Comparator<Candidate> comparator = Comparator.comparingInt(Candidate::score)
                    .thenComparingInt(Candidate::ordinal)
                    .thenComparing(candidate -> candidate.property().originalName());
            return comparator.compare(left, right) <= 0 ? left : right;
        }

        private void upsertDatasetProperty(PreparedStatement statement, long datasetId,
                                           TargetProperty property, long present,
                                           long blank, long distinct) throws SQLException {
            statement.setLong(1, datasetId);
            statement.setLong(2, property.propertyId());
            statement.setLong(3, present);
            statement.setLong(4, blank);
            statement.setLong(5, distinct);
            statement.setString(6, property.type().ddl());
            statement.setString(7, property.type().ddl());
            statement.executeUpdate();
        }

        private void migrateMolecules() throws SQLException {
            String sourceSql = "SELECT s.mol_id, s.struc, d.mol_name, i.inchi_key, i.smiles, i.inchi"
                    + " FROM " + table(config.sourceSchema(), "moldb_molstruc") + " s"
                    + " JOIN (SELECT DISTINCT mol_id FROM "
                    + table(config.sourceSchema(), "batchmols") + ") used ON used.mol_id = s.mol_id"
                    + " LEFT JOIN " + table(config.sourceSchema(), "moldb_moldata")
                    + " d ON d.mol_id = s.mol_id"
                    + " LEFT JOIN " + table(config.sourceSchema(), "inchi_key")
                    + " i ON i.mol_id = s.mol_id ORDER BY s.mol_id";
            try (Statement sourceStatement = read.createStatement();
                 ResultSet source = executeStreaming(sourceStatement, sourceSql);
                 Tracker tracker = new Tracker(write, config.targetSchema(), runId);
                 PreparedStatement map = mapStatement()) {
                int processed = 0;
                while (source.next()) {
                    long legacyMoleculeId = source.getLong(1);
                    Savepoint savepoint = write.setSavepoint();
                    try {
                        tracker.attempt("MOLECULE", Long.toString(legacyMoleculeId),
                                legacyMoleculeId, null, Stage.MOLECULES);
                        byte[] structure = source.getBytes(2);
                        if (structure == null || structure.length == 0) {
                            throw new IllegalArgumentException("source structure is empty");
                        }
                        byte[] structureHash = sha256(structure);
                        String inchiKey = validInchiKey(source.getString(4));
                        long moleculeId = findMolecule(inchiKey, structureHash);
                        if (moleculeId == 0) {
                            moleculeId = insertMolecule(structure, structureHash, source.getString(6),
                                    inchiKey, source.getString(5), source.getString(3));
                        }
                        putMap(map, "MOLECULE", legacyMoleculeId, moleculeId, structureHash);
                        tracker.success("MOLECULE", Long.toString(legacyMoleculeId),
                                moleculeId, structureHash);
                        write.releaseSavepoint(savepoint);
                    } catch (Exception exception) {
                        write.rollback(savepoint);
                        tracker.failure("MOLECULE", Long.toString(legacyMoleculeId),
                                legacyMoleculeId, null, Stage.MOLECULES, exception);
                        err.println("Molecule " + legacyMoleculeId + " skipped: "
                                + conciseMessage(exception));
                    }
                    if (++processed % config.chunkSize() == 0) {
                        write.commit();
                    }
                }
                write.commit();
            }
        }

        private long findMolecule(String inchiKey, byte[] structureHash) throws SQLException {
            if (inchiKey != null) {
                String byKey = "SELECT molecule_id FROM "
                        + table(config.targetSchema(), "molecule")
                        + " WHERE full_inchi_key = ?";
                try (PreparedStatement statement = write.prepareStatement(byKey)) {
                    statement.setString(1, inchiKey);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            return result.getLong(1);
                        }
                    }
                }
            }
            String byHash = "SELECT molecule_id FROM "
                    + table(config.targetSchema(), "molecule")
                    + " WHERE normalization_version = ? AND normalized_structure_sha256 = ?";
            try (PreparedStatement statement = write.prepareStatement(byHash)) {
                statement.setString(1, NORMALIZATION_VERSION);
                statement.setBytes(2, structureHash);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getLong(1) : 0;
                }
            }
        }

        private long insertMolecule(byte[] structure, byte[] structureHash, String inchi,
                                    String inchiKey, String smiles, String primaryName)
                throws SQLException {
            String sql = "INSERT INTO " + table(config.targetSchema(), "molecule")
                    + " (normalization_version, normalization_status, normalized_structure,"
                    + " normalized_structure_sha256, standard_inchi, full_inchi_key,"
                    + " canonical_smiles, canonical_smiles_sha256, primary_name)"
                    + " VALUES (?, 'MIGRATED_RAW', ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = write.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, NORMALIZATION_VERSION);
                statement.setBytes(2, structure);
                statement.setBytes(3, structureHash);
                setNullableString(statement, 4, trimToNull(inchi));
                setNullableString(statement, 5, inchiKey);
                String normalizedSmiles = trimToNull(smiles);
                setNullableString(statement, 6, normalizedSmiles);
                if (normalizedSmiles == null) {
                    statement.setNull(7, Types.BINARY);
                } else {
                    statement.setBytes(7, sha256(normalizedSmiles));
                }
                setNullableString(statement, 8, limit(trimToNull(primaryName), 512));
                statement.executeUpdate();
                return generatedKey(statement);
            }
        }

        private void migrateDatasetMolecules() throws SQLException {
            loadTargetProperties();
            Map<Long, DatasetRef> datasets = loadDatasets(true);
            List<TargetProperty> orderedTargets = new ArrayList<>();
            for (SourceProperty property : sourceProperties) {
                orderedTargets.add(requiredTargetProperty(property.originalName()));
            }
            TargetProperty synthetic = requiredTargetProperty(SYNTHETIC_IDENTIFIER);

            for (DatasetRef dataset : datasets.values()) {
                migrateOneDataset(dataset, synthetic, orderedTargets);
            }
        }

        private void migrateOneDataset(DatasetRef dataset, TargetProperty synthetic,
                                       List<TargetProperty> orderedTargets) throws SQLException {
            StringBuilder sourceSql = new StringBuilder();
            sourceSql.append("SELECT bm.mol_id, lm.v3_id, ms.struc, md.mol_name");
            for (SourceProperty property : sourceProperties) {
                sourceSql.append(", s.").append(identifier(property.originalName()));
            }
            sourceSql.append(" FROM ").append(table(config.sourceSchema(), "batchmols")).append(" bm")
                    .append(" LEFT JOIN ").append(table(config.targetSchema(), "legacy_id_map"))
                    .append(" lm ON lm.legacy_migration_run_id = ").append(runId)
                    .append(" AND lm.entity_type = 'MOLECULE' AND lm.legacy_id = bm.mol_id")
                    .append(" LEFT JOIN ").append(table(config.sourceSchema(), "moldb_molstruc"))
                    .append(" ms ON ms.mol_id = bm.mol_id")
                    .append(" LEFT JOIN ").append(table(config.sourceSchema(), "moldb_moldata"))
                    .append(" md ON md.mol_id = bm.mol_id")
                    .append(" LEFT JOIN ").append(table(config.sourceSchema(), "sdftags"))
                    .append(" s ON s.mol_id = bm.mol_id")
                    .append(" WHERE bm.batch_id = ? ORDER BY bm.mol_id");

            String membershipSql = "INSERT INTO "
                    + table(config.targetSchema(), "dataset_molecule")
                    + " (dataset_id, molecule_id, record_number, source_identifier,"
                    + " source_structure, source_structure_sha256, source_structure_format)"
                    + " VALUES (?, ?, ?, ?, ?, ?, 'MOLFILE')"
                    + " ON DUPLICATE KEY UPDATE dataset_molecule_id = LAST_INSERT_ID(dataset_molecule_id),"
                    + " molecule_id = VALUES(molecule_id), source_identifier = VALUES(source_identifier),"
                    + " source_structure = VALUES(source_structure),"
                    + " source_structure_sha256 = VALUES(source_structure_sha256),"
                    + " source_structure_format = VALUES(source_structure_format)";
            String propertySql = propertyUpsertSql(synthetic, orderedTargets);
            Map<String, Integer> propertyOffsets = new LinkedHashMap<>();
            for (int index = 0; index < sourceProperties.size(); index++) {
                propertyOffsets.put(sourceProperties.get(index).originalName(), index);
            }

            try (PreparedStatement source = read.prepareStatement(sourceSql.toString());
                 PreparedStatement membership = write.prepareStatement(
                         membershipSql, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement properties = write.prepareStatement(propertySql);
                 Tracker tracker = new Tracker(write, config.targetSchema(), runId)) {
                source.setLong(1, dataset.legacyBatchId());
                source.setFetchSize(config.fetchSize());
                try (ResultSet rows = source.executeQuery()) {
                    long recordNumber = 0;
                    int processed = 0;
                    while (rows.next()) {
                        recordNumber++;
                        long legacyMoleculeId = rows.getLong(1);
                        String sourceKey = dataset.legacyBatchId() + ":" + legacyMoleculeId;
                        Savepoint savepoint = write.setSavepoint();
                        try {
                            tracker.attempt("DATASET_MOLECULE", sourceKey, legacyMoleculeId,
                                    dataset.legacyBatchId(), Stage.DATASET_MOLECULES);
                            Long moleculeId = nullableLong(rows, 2);
                            if (moleculeId == null) {
                                throw new IllegalStateException("canonical molecule mapping is missing");
                            }
                            byte[] structure = rows.getBytes(3);
                            if (structure == null || structure.length == 0) {
                                throw new IllegalArgumentException("source structure is empty");
                            }
                            Object[] values = new Object[sourceProperties.size()];
                            for (int index = 0; index < values.length; index++) {
                                values[index] = rows.getObject(5 + index);
                            }
                            String sourceIdentifier;
                            if (SYNTHETIC_IDENTIFIER.equals(dataset.identifierProperty())) {
                                sourceIdentifier = Long.toString(legacyMoleculeId);
                            } else {
                                Integer offset = propertyOffsets.get(dataset.identifierProperty());
                                if (offset == null) {
                                    throw new IllegalStateException(
                                            "identifier property is not a legacy SDF tag: "
                                                    + dataset.identifierProperty());
                                }
                                sourceIdentifier = identifierValue(values[offset]);
                                if (sourceIdentifier == null) {
                                    throw new IllegalArgumentException("identifier value is blank");
                                }
                            }
                            if (sourceIdentifier.length() > 512) {
                                throw new IllegalArgumentException("identifier exceeds 512 characters");
                            }
                            byte[] structureHash = sha256(structure);
                            membership.setLong(1, dataset.datasetId());
                            membership.setLong(2, moleculeId);
                            membership.setLong(3, recordNumber);
                            membership.setString(4, sourceIdentifier);
                            membership.setBytes(5, structure);
                            membership.setBytes(6, structureHash);
                            membership.executeUpdate();
                            long datasetMoleculeId = generatedKeyOrLastInsertId(membership);

                            int bind = 1;
                            properties.setLong(bind++, datasetMoleculeId);
                            properties.setLong(bind++, legacyMoleculeId);
                            for (Object value : values) {
                                properties.setObject(bind++, value);
                            }
                            properties.executeUpdate();
                            tracker.success("DATASET_MOLECULE", sourceKey,
                                    datasetMoleculeId, structureHash);
                            write.releaseSavepoint(savepoint);
                        } catch (Exception exception) {
                            write.rollback(savepoint);
                            tracker.failure("DATASET_MOLECULE", sourceKey, legacyMoleculeId,
                                    dataset.legacyBatchId(), Stage.DATASET_MOLECULES, exception);
                            err.println("Dataset molecule " + sourceKey + " skipped: "
                                    + conciseMessage(exception));
                        }
                        if (++processed % config.chunkSize() == 0) {
                            write.commit();
                        }
                    }
                }
                write.commit();
            }
            finalizeDataset(dataset);
        }

        private String propertyUpsertSql(TargetProperty synthetic,
                                         List<TargetProperty> properties) {
            List<String> columns = new ArrayList<>();
            columns.add(synthetic.physicalName());
            for (TargetProperty property : properties) {
                columns.add(property.physicalName());
            }
            StringBuilder sql = new StringBuilder("INSERT INTO ")
                    .append(table(config.targetSchema(), "dataset_molecule_properties"))
                    .append(" (dataset_molecule_id");
            for (String column : columns) {
                sql.append(", ").append(identifier(column));
            }
            sql.append(") VALUES (?");
            for (int index = 0; index < columns.size(); index++) {
                sql.append(", ?");
            }
            sql.append(") ON DUPLICATE KEY UPDATE ");
            for (int index = 0; index < columns.size(); index++) {
                if (index > 0) {
                    sql.append(", ");
                }
                String column = identifier(columns.get(index));
                sql.append(column).append(" = VALUES(").append(column).append(')');
            }
            return sql.toString();
        }

        private void finalizeDataset(DatasetRef dataset) throws SQLException {
            String countSql = "SELECT COUNT(*) FROM "
                    + table(config.targetSchema(), "dataset_molecule")
                    + " WHERE dataset_id = ?";
            long imported;
            try (PreparedStatement statement = write.prepareStatement(countSql)) {
                statement.setLong(1, dataset.datasetId());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    imported = result.getLong(1);
                }
            }
            long failed = Math.max(0, dataset.totalRecords() - imported);
            String status = failed == 0 ? "MIGRATED" : "MIGRATED_PARTIAL";
            String updateSql = "UPDATE " + table(config.targetSchema(), "dataset")
                    + " SET status = ?, imported_records = ?, failed_records = ?,"
                    + " not_processed_records = 0, partial_acknowledgement_required = ?,"
                    + " model_eligible = ? WHERE dataset_id = ?";
            try (PreparedStatement statement = write.prepareStatement(updateSql)) {
                statement.setString(1, status);
                statement.setLong(2, imported);
                statement.setLong(3, failed);
                statement.setBoolean(4, failed != 0);
                statement.setBoolean(5, failed == 0);
                statement.setLong(6, dataset.datasetId());
                statement.executeUpdate();
            }
            write.commit();
        }

        private void migrateModelDefinitions() throws SQLException {
            loadTargetProperties();
            String sourceSql = "SELECT model_id, name, username, batch_id, data_type, class_tag,"
                    + " class_scheme, email, feature_selection, classes,"
                    + " model_data IS NOT NULL, header IS NOT NULL, SHA2(printout, 256)"
                    + " FROM " + table(config.sourceSchema(), "class_models")
                    + " ORDER BY model_id";
            String insertSql = "INSERT INTO " + table(config.targetSchema(), "model_definition")
                    + " (legacy_model_id, dataset_id, target_property_id, feature_profile_id,"
                    + " model_name, algorithm_code, algorithm_options_json,"
                    + " feature_selection_code, feature_selection_options_json,"
                    + " positive_class_label, declared_class_labels_json, status, created_by,"
                    + " definition_metadata_json) VALUES (?, ?, ?, ?, ?, ?, '{}', ?, '{}',"
                    + " NULL, ?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE model_definition_id = LAST_INSERT_ID(model_definition_id),"
                    + " dataset_id = VALUES(dataset_id), target_property_id = VALUES(target_property_id),"
                    + " feature_profile_id = VALUES(feature_profile_id),"
                    + " model_name = VALUES(model_name), algorithm_code = VALUES(algorithm_code),"
                    + " feature_selection_code = VALUES(feature_selection_code),"
                    + " declared_class_labels_json = VALUES(declared_class_labels_json),"
                    + " status = VALUES(status), created_by = VALUES(created_by),"
                    + " definition_metadata_json = VALUES(definition_metadata_json)";
            Map<String, Long> profiles = new LinkedHashMap<>();
            try (Statement sourceStatement = read.createStatement();
                 ResultSet source = sourceStatement.executeQuery(sourceSql);
                 PreparedStatement insert = write.prepareStatement(
                         insertSql, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement map = mapStatement();
                 Tracker tracker = new Tracker(write, config.targetSchema(), runId)) {
                int processed = 0;
                while (source.next()) {
                    long legacyModelId = source.getLong(1);
                    long legacyBatchId = source.getLong(4);
                    Savepoint savepoint = write.setSavepoint();
                    try {
                        tracker.attempt("MODEL_DEFINITION", Long.toString(legacyModelId),
                                legacyModelId, legacyBatchId, Stage.MODEL_DEFINITIONS);
                        long datasetId = requiredMappedId("DATASET", legacyBatchId);
                        String targetName = trimToNull(source.getString(6));
                        TargetProperty target = targetProperties.get(targetName);
                        if (target == null) {
                            throw new IllegalStateException(
                                    "target SDF property does not exist: " + targetName);
                        }
                        String dataType = firstNonBlank(source.getString(5), "UNKNOWN");
                        long profileId = profiles.computeIfAbsent(dataType, key -> {
                            try {
                                return ensureFeatureProfile(key);
                            } catch (SQLException exception) {
                                throw new DeferredSqlException(exception);
                            }
                        });
                        markModelTarget(datasetId, target.propertyId());
                        boolean eligible = datasetModelEligible(datasetId);
                        String labelsJson = jsonArray(parseClassLabels(source.getString(10)));
                        String createdBy = firstNonBlank(source.getString(3), "legacy-migration");
                        Map<String, Object> metadata = new LinkedHashMap<>();
                        metadata.put("legacyClassesRaw", source.getString(10));
                        metadata.put("legacyEmail", source.getString(8));
                        metadata.put("legacyHeaderPresent", source.getBoolean(12));
                        metadata.put("legacyModelPresent", source.getBoolean(11));
                        metadata.put("legacyPrintoutSha256", source.getString(13));
                        metadata.put("legacyUsername", source.getString(3));
                        metadata.put("sourceSchema", config.sourceSchema());
                        String metadataJson = jsonObject(metadata);

                        insert.setLong(1, legacyModelId);
                        insert.setLong(2, datasetId);
                        insert.setLong(3, target.propertyId());
                        insert.setLong(4, profileId);
                        insert.setString(5, firstNonBlank(source.getString(2),
                                "Legacy model " + legacyModelId));
                        insert.setString(6, limit(firstNonBlank(source.getString(7), "UNKNOWN"), 64));
                        insert.setString(7, limit(firstNonBlank(source.getString(9), "NONE"), 64));
                        insert.setString(8, labelsJson);
                        insert.setString(9, eligible ? "PENDING_REBUILD" : "BLOCKED_DATASET_REVIEW");
                        insert.setString(10, limit(createdBy, 255));
                        insert.setString(11, metadataJson);
                        insert.executeUpdate();
                        long definitionId = generatedKeyOrLastInsertId(insert);
                        byte[] sourceHash = sha256(joinFields(legacyModelId, legacyBatchId,
                                dataType, targetName, source.getString(7), source.getString(9),
                                source.getString(10), metadataJson));
                        putMap(map, "MODEL_DEFINITION", legacyModelId,
                                definitionId, sourceHash);
                        tracker.success("MODEL_DEFINITION", Long.toString(legacyModelId),
                                definitionId, sourceHash);
                        write.releaseSavepoint(savepoint);
                    } catch (DeferredSqlException exception) {
                        write.rollback(savepoint);
                        SQLException cause = exception.sqlException();
                        tracker.failure("MODEL_DEFINITION", Long.toString(legacyModelId),
                                legacyModelId, legacyBatchId, Stage.MODEL_DEFINITIONS, cause);
                        err.println("Model definition " + legacyModelId + " skipped: "
                                + conciseMessage(cause));
                    } catch (Exception exception) {
                        write.rollback(savepoint);
                        tracker.failure("MODEL_DEFINITION", Long.toString(legacyModelId),
                                legacyModelId, legacyBatchId, Stage.MODEL_DEFINITIONS, exception);
                        err.println("Model definition " + legacyModelId + " skipped: "
                                + conciseMessage(exception));
                    }
                    if (++processed % config.chunkSize() == 0) {
                        write.commit();
                    }
                }
                write.commit();
            }
        }

        private long ensureFeatureProfile(String dataType) throws SQLException {
            Map<String, Object> configuration = new LinkedHashMap<>();
            configuration.put("legacyDataType", dataType);
            configuration.put("regenerateWithCdk", CDK_VERSION);
            configuration.put("reuseLegacyFeatureValues", false);
            String configurationJson = jsonObject(configuration);
            String sql = "INSERT INTO " + table(config.targetSchema(), "feature_profile")
                    + " (profile_code, profile_version, description, configuration_json,"
                    + " configuration_sha256, status) VALUES (?, ?, ?, ?, ?, 'PENDING_GENERATION')"
                    + " ON DUPLICATE KEY UPDATE feature_profile_id = LAST_INSERT_ID(feature_profile_id),"
                    + " description = VALUES(description), status = VALUES(status)";
            try (PreparedStatement statement = write.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, limit(dataType, 32));
                statement.setString(2, PROFILE_VERSION);
                statement.setString(3, "Legacy " + dataType + " feature contract; values must be regenerated");
                statement.setString(4, configurationJson);
                statement.setBytes(5, sha256(configurationJson));
                statement.executeUpdate();
                return generatedKeyOrLastInsertId(statement);
            }
        }

        private void markModelTarget(long datasetId, long propertyId) throws SQLException {
            String sql = "UPDATE " + table(config.targetSchema(), "dataset_property")
                    + " SET model_target_allowed = 1 WHERE dataset_id = ? AND property_id = ?";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setLong(1, datasetId);
                statement.setLong(2, propertyId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "model target is not registered for dataset " + datasetId);
                }
            }
        }

        private boolean datasetModelEligible(long datasetId) throws SQLException {
            String sql = "SELECT model_eligible FROM " + table(config.targetSchema(), "dataset")
                    + " WHERE dataset_id = ?";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setLong(1, datasetId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException("dataset does not exist: " + datasetId);
                    }
                    return result.getBoolean(1);
                }
            }
        }

        private long requiredMappedId(String entityType, long legacyId) throws SQLException {
            String sql = "SELECT v3_id FROM " + table(config.targetSchema(), "legacy_id_map")
                    + " WHERE legacy_migration_run_id = ? AND entity_type = ? AND legacy_id = ?";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setLong(1, runId);
                statement.setString(2, entityType);
                statement.setLong(3, legacyId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException(entityType + " mapping is missing for " + legacyId);
                    }
                    return result.getLong(1);
                }
            }
        }

        private Map<Long, DatasetRef> loadDatasets(boolean requireIdentifier) throws SQLException {
            String sql = "SELECT d.legacy_batch_id, d.dataset_id, d.total_records, p.original_name"
                    + " FROM " + table(config.targetSchema(), "dataset") + " d"
                    + " LEFT JOIN " + table(config.targetSchema(), "property_definition")
                    + " p ON p.property_id = d.identifier_property_id"
                    + " WHERE d.legacy_batch_id IS NOT NULL ORDER BY d.legacy_batch_id";
            Map<Long, DatasetRef> datasets = new LinkedHashMap<>();
            try (Statement statement = write.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                while (result.next()) {
                    String identifierProperty = result.getString(4);
                    if (requireIdentifier && identifierProperty == null) {
                        throw new IllegalStateException(
                                "identifier analysis is incomplete for batch " + result.getLong(1));
                    }
                    DatasetRef dataset = new DatasetRef(result.getLong(1), result.getLong(2),
                            result.getLong(3), identifierProperty);
                    datasets.put(dataset.legacyBatchId(), dataset);
                }
            }
            return datasets;
        }

        private TargetProperty requiredTargetProperty(String originalName) {
            TargetProperty property = targetProperties.get(originalName);
            if (property == null) {
                throw new IllegalStateException("target property is missing: " + originalName);
            }
            return property;
        }

        private PreparedStatement mapStatement() throws SQLException {
            String sql = "INSERT INTO " + table(config.targetSchema(), "legacy_id_map")
                    + " (legacy_migration_run_id, entity_type, legacy_id, v3_id, source_sha256)"
                    + " VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE"
                    + " v3_id = VALUES(v3_id), source_sha256 = VALUES(source_sha256)";
            return write.prepareStatement(sql);
        }

        private void putMap(PreparedStatement statement, String entityType, long legacyId,
                            long v3Id, byte[] sourceHash) throws SQLException {
            statement.setLong(1, runId);
            statement.setString(2, entityType);
            statement.setLong(3, legacyId);
            statement.setLong(4, v3Id);
            statement.setBytes(5, sourceHash);
            statement.executeUpdate();
        }

        private long countRecordFailures() throws SQLException {
            String sql = "SELECT COUNT(*) FROM "
                    + table(config.targetSchema(), "legacy_migration_record")
                    + " WHERE legacy_migration_run_id = ? AND status = 'FAILED'";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setLong(1, runId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        }
    }

    private static final class Tracker implements AutoCloseable {
        private final long runId;
        private final PreparedStatement attempt;
        private final PreparedStatement success;
        private final PreparedStatement failure;

        Tracker(Connection connection, String targetSchema, long runId) throws SQLException {
            this.runId = runId;
            String target = table(targetSchema, "legacy_migration_record");
            this.attempt = connection.prepareStatement(
                    "INSERT INTO " + target
                            + " (legacy_migration_run_id, entity_type, source_key, legacy_id,"
                            + " legacy_parent_id, status, runstep, attempt_count, started_at)"
                            + " VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, 1, NOW(6))"
                            + " ON DUPLICATE KEY UPDATE legacy_id = VALUES(legacy_id),"
                            + " legacy_parent_id = VALUES(legacy_parent_id), status = 'RUNNING',"
                            + " runstep = VALUES(runstep), attempt_count = attempt_count + 1,"
                            + " v3_id = NULL, source_sha256 = NULL, error_code = NULL,"
                            + " error_message = NULL, started_at = NOW(6), finished_at = NULL");
            this.success = connection.prepareStatement(
                    "UPDATE " + target + " SET status = 'SUCCEEDED', v3_id = ?,"
                            + " source_sha256 = ?, error_code = NULL, error_message = NULL,"
                            + " finished_at = NOW(6) WHERE legacy_migration_run_id = ?"
                            + " AND entity_type = ? AND source_key = ?");
            this.failure = connection.prepareStatement(
                    "INSERT INTO " + target
                            + " (legacy_migration_run_id, entity_type, source_key, legacy_id,"
                            + " legacy_parent_id, status, runstep, attempt_count, error_code,"
                            + " error_message, started_at, finished_at)"
                            + " VALUES (?, ?, ?, ?, ?, 'FAILED', ?, 1, ?, ?, NOW(6), NOW(6))"
                            + " ON DUPLICATE KEY UPDATE legacy_id = VALUES(legacy_id),"
                            + " legacy_parent_id = VALUES(legacy_parent_id), status = 'FAILED',"
                            + " runstep = VALUES(runstep), attempt_count = attempt_count + 1,"
                            + " v3_id = NULL, source_sha256 = NULL, error_code = VALUES(error_code),"
                            + " error_message = VALUES(error_message), started_at = NOW(6),"
                            + " finished_at = NOW(6)");
        }

        void attempt(String entityType, String sourceKey, Long legacyId,
                     Long parentId, Stage stage) throws SQLException {
            attempt.setLong(1, runId);
            attempt.setString(2, entityType);
            attempt.setString(3, sourceKey);
            setNullableLong(attempt, 4, legacyId);
            setNullableLong(attempt, 5, parentId);
            attempt.setString(6, stage.name());
            attempt.executeUpdate();
        }

        void success(String entityType, String sourceKey, long v3Id, byte[] sourceHash)
                throws SQLException {
            success.setLong(1, v3Id);
            success.setBytes(2, sourceHash);
            success.setLong(3, runId);
            success.setString(4, entityType);
            success.setString(5, sourceKey);
            success.executeUpdate();
        }

        void failure(String entityType, String sourceKey, Long legacyId,
                     Long parentId, Stage stage, Throwable throwable) throws SQLException {
            failure.setLong(1, runId);
            failure.setString(2, entityType);
            failure.setString(3, sourceKey);
            setNullableLong(failure, 4, legacyId);
            setNullableLong(failure, 5, parentId);
            failure.setString(6, stage.name());
            failure.setString(7, errorCode(throwable));
            failure.setString(8, limit(conciseMessage(throwable), 2048));
            failure.executeUpdate();
        }

        @Override
        public void close() throws SQLException {
            SQLException failureToClose = null;
            for (PreparedStatement statement : List.of(attempt, success, failure)) {
                try {
                    statement.close();
                } catch (SQLException exception) {
                    if (failureToClose == null) {
                        failureToClose = exception;
                    } else {
                        failureToClose.addSuppressed(exception);
                    }
                }
            }
            if (failureToClose != null) {
                throw failureToClose;
            }
        }
    }

    private static final class DeferredSqlException extends RuntimeException {
        private final SQLException sqlException;

        DeferredSqlException(SQLException sqlException) {
            super(sqlException);
            this.sqlException = sqlException;
        }

        SQLException sqlException() {
            return sqlException;
        }
    }

    private static TypeSpec inferType(String dataType, String columnType,
                                      Integer characterLength, Integer precision,
                                      Integer scale) {
        String normalized = dataType.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "tinyint", "smallint", "mediumint", "int", "integer" ->
                    new TypeSpec("INT", "INT", null, precision, 0);
            case "bigint" -> new TypeSpec("BIGINT", "BIGINT", null, precision, 0);
            case "decimal", "numeric" -> {
                int resolvedPrecision = precision == null ? 65 : Math.min(65, precision);
                int resolvedScale = scale == null ? 0 : Math.min(30, scale);
                yield new TypeSpec("DECIMAL",
                        "DECIMAL(" + resolvedPrecision + "," + resolvedScale + ")",
                        null, resolvedPrecision, resolvedScale);
            }
            case "float", "double", "real" ->
                    new TypeSpec("DOUBLE", "DOUBLE", null, precision, scale);
            case "char" -> {
                int length = characterLength == null ? 1 : Math.max(1, characterLength);
                yield new TypeSpec("CHAR", "CHAR(" + length + ")", length, null, null);
            }
            case "varchar" -> {
                int length = characterLength == null ? 255 : Math.max(1, characterLength);
                yield new TypeSpec("VARCHAR", "VARCHAR(" + length + ")", length, null, null);
            }
            default -> new TypeSpec("TEXT", "LONGTEXT", characterLength, null, null);
        };
    }

    private static boolean compatible(TypeSpec target, TypeSpec source) {
        if (target.family().equals(source.family())) {
            if (("CHAR".equals(target.family()) || "VARCHAR".equals(target.family()))
                    && target.maximumLength() != null && source.maximumLength() != null) {
                return target.maximumLength() >= source.maximumLength();
            }
            return true;
        }
        return "TEXT".equals(target.family())
                && ("CHAR".equals(source.family()) || "VARCHAR".equals(source.family()));
    }

    private static int identifierScore(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.matches("(compound|molecule|mol|chem|vendor|pubchem|drugbank|chembl)?_?id")) {
            return 0;
        }
        if (normalized.endsWith("_id") || normalized.endsWith("id")) {
            return 10;
        }
        if (normalized.equals("compound_name")) {
            return 20;
        }
        if (normalized.contains("name")) {
            return 30;
        }
        return 100;
    }

    private static String validInchiKey(String value) {
        String normalized = trimToNull(value);
        return normalized != null && VALID_INCHI_KEY.matcher(normalized).matches()
                ? normalized : null;
    }

    private static String identifierValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value instanceof BigDecimal decimal
                ? decimal.toPlainString() : value.toString();
        return trimToNull(text);
    }

    private static List<String> parseClassLabels(String raw) {
        if (raw == null) {
            return List.of();
        }
        Set<String> labels = new LinkedHashSet<>();
        for (String value : raw.split("[\\t\\r\\n]+")) {
            String label = trimToNull(value);
            if (label != null) {
                labels.add(label);
            }
        }
        return List.copyOf(labels);
    }

    private static ResultSet executeStreaming(Statement statement, String sql)
            throws SQLException {
        statement.setFetchSize(500);
        return statement.executeQuery(sql);
    }

    private static long generatedKey(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("database did not return a generated key");
            }
            return keys.getLong(1);
        }
    }

    private static long generatedKeyOrLastInsertId(PreparedStatement statement)
            throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (keys.next() && keys.getLong(1) != 0) {
                return keys.getLong(1);
            }
        }
        try (Statement query = statement.getConnection().createStatement();
             ResultSet result = query.executeQuery("SELECT LAST_INSERT_ID()")) {
            if (!result.next() || result.getLong(1) == 0) {
                throw new SQLException("database did not return an insert ID");
            }
            return result.getLong(1);
        }
    }

    private static Integer nullableInteger(ResultSet result, int column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet result, int column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static void setNullableInteger(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static String firstNonBlank(String first, String second) {
        String value = trimToNull(first);
        return value == null ? trimToNull(second) : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void validateSchema(String schema, String role) {
        if (!SAFE_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException(role + " schema contains unsafe characters");
        }
    }

    private static String identifier(String value) {
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            return "`" + value.replace("`", "``") + "`";
        }
        return "`" + value + "`";
    }

    private static String table(String schema, String table) {
        validateSchema(schema, "SQL");
        if (!SAFE_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("unsafe table name");
        }
        return identifier(schema) + "." + identifier(table);
    }

    private static byte[] sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] value) {
        return sha256Digest().digest(value);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void digestField(MessageDigest digest, String value) {
        if (value != null) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) 0);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    private static String joinFields(Object... values) {
        StringBuilder result = new StringBuilder();
        for (Object value : values) {
            result.append(value == null ? "<null>" : value).append('\u0000');
        }
        return result.toString();
    }

    private static String jsonObject(Map<String, ?> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(jsonString(entry.getKey())).append(':').append(jsonValue(entry.getValue()));
        }
        return json.append('}').toString();
    }

    private static String jsonArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(jsonString(values.get(index)));
        }
        return json.append(']').toString();
    }

    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return jsonString(value.toString());
    }

    private static String jsonString(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('\"').toString();
    }

    private static String errorCode(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        if (cause instanceof SQLException sqlException) {
            String state = firstNonBlank(sqlException.getSQLState(), "UNKNOWN");
            return limit("SQL_" + state.replaceAll("[^A-Za-z0-9_]", "_"), 64);
        }
        return limit(cause.getClass().getSimpleName()
                .replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT), 64);
    }

    private static String conciseMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        return firstNonBlank(cause.getMessage(), cause.getClass().getSimpleName());
    }

    private static String limit(String value, int maximumCharacters) {
        if (value == null || value.length() <= maximumCharacters) {
            return value;
        }
        int end = value.offsetByCodePoints(0,
                Math.min(maximumCharacters, value.codePointCount(0, value.length())));
        return value.substring(0, end);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }
}
