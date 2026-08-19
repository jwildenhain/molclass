package molclass.features;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.aromaticity.Aromaticity;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.fingerprint.CircularFingerprinter;
import org.openscience.cdk.fingerprint.EStateFingerprinter;
import org.openscience.cdk.fingerprint.ExtendedFingerprinter;
import org.openscience.cdk.fingerprint.Fingerprinter;
import org.openscience.cdk.fingerprint.GraphOnlyFingerprinter;
import org.openscience.cdk.fingerprint.IBitFingerprint;
import org.openscience.cdk.fingerprint.IFingerprinter;
import org.openscience.cdk.fingerprint.KlekotaRothFingerprinter;
import org.openscience.cdk.fingerprint.MACCSFingerprinter;
import org.openscience.cdk.fingerprint.PubchemFingerprinter;
import org.openscience.cdk.fingerprint.SubstructureFingerprinter;
import org.openscience.cdk.tools.CDKHydrogenAdder;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IChemObjectBuilder;
import org.openscience.cdk.io.IChemObjectReader;
import org.openscience.cdk.io.ISimpleChemObjectReader;
import org.openscience.cdk.io.MDLV2000Reader;
import org.openscience.cdk.io.MDLV3000Reader;
import org.openscience.cdk.qsar.DescriptorEngine;
import org.openscience.cdk.qsar.DescriptorValue;
import org.openscience.cdk.qsar.IMolecularDescriptor;
import org.openscience.cdk.qsar.result.DoubleArrayResult;
import org.openscience.cdk.qsar.result.DoubleResult;
import org.openscience.cdk.qsar.result.IDescriptorResult;
import org.openscience.cdk.qsar.result.IntegerArrayResult;
import org.openscience.cdk.qsar.result.IntegerResult;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.tools.manipulator.AtomContainerManipulator;

/** Generates versioned CDK 2.12 features for migrated v3 molecules. */
public final class V3FeatureGenerator {
    private static final String CDK_VERSION = "2.12";
    private static final String NORMALIZATION_VERSION = "legacy-source-v1";
    private static final String IMPLEMENTATION_VERSION = "v3-feature-generator-1";
    private static final String GENERATION_VERSION = "v3-cdk-2.12-1";
    private static final String DESCRIPTOR_NAME = "cdk-2.12-molecular-v1";
    private static final String VECTOR_FORMAT = "IEEE754_F64_BE";
    private static final IChemObjectBuilder BUILDER = DefaultChemObjectBuilder.getInstance();
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private static final List<FingerprintSpec> FINGERPRINTS = List.of(
            new FingerprintSpec("MACCS", MACCSFingerprinter.class.getName(), null),
            new FingerprintSpec("PubChem", PubchemFingerprinter.class.getName(), null),
            new FingerprintSpec("EXT", ExtendedFingerprinter.class.getName(), null),
            new FingerprintSpec("SUB", SubstructureFingerprinter.class.getName(),
                    Fingerprinter.class.getName()),
            new FingerprintSpec("KR", KlekotaRothFingerprinter.class.getName(), null),
            new FingerprintSpec("GOFP", GraphOnlyFingerprinter.class.getName(), null),
            new FingerprintSpec("ESFP", EStateFingerprinter.class.getName(), null),
            // ECFP4-equivalent circular (Morgan-algorithm) fingerprint, folded to a fixed 1024-bit
            // vector like the other fixed-width fingerprints here. Every other fingerprint type
            // already in this pipeline is a structural-key or path-based fingerprint; circular
            // fingerprints are the de facto default in most contemporary QSAR/ML tooling (the CDK
            // analogue of RDKit's Morgan fingerprint) and were the one obvious gap.
            new FingerprintSpec("ECFP", CircularFingerprinter.class.getName(), null));

    private V3FeatureGenerator() {
    }

    public static void main(String[] args) {
        int exit = execute(args, System.getenv(), System.out, System.err);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int execute(String[] args, Map<String, String> environment,
                       PrintStream out, PrintStream err) {
        if (List.of(args).contains("--help")) {
            usage(out);
            return 0;
        }
        final Config config;
        try {
            config = Config.parse(args, environment);
        } catch (IllegalArgumentException exception) {
            err.println("Configuration error: " + exception.getMessage());
            usage(err);
            return 2;
        }

        try (Connection read = DriverManager.getConnection(
                    config.jdbcUrl(), config.dbUser(), config.dbPassword());
             Connection write = DriverManager.getConnection(
                    config.jdbcUrl(), config.dbUser(), config.dbPassword())) {
            read.setAutoCommit(true);
            try {
                read.setReadOnly(true);
            } catch (SQLException ignored) {
                // Driver hint only.
            }
            write.setAutoCommit(false);
            if (!acquireLock(write, config.targetSchema())) {
                throw new IllegalStateException("another v3 feature generator owns the database lock");
            }
            new Generator(config, read, write, out, err).run();
            return 0;
        } catch (Exception exception) {
            err.println("Feature generation failed: " + conciseMessage(exception));
            return 1;
        }
    }

    private static void usage(PrintStream stream) {
        stream.println("Usage: ./gradlew generateV3Features [feature options]");
        stream.println("Required environment: MOLCLASS_JDBC_URL, MOLCLASS_DB_USER, MOLCLASS_DB_PASSWORD");
        stream.println("Options passed with -PfeatureArgs=\"...\":");
        stream.println("  --jdbc-url URL        Overrides MOLCLASS_JDBC_URL");
        stream.println("  --db-user USER        Overrides MOLCLASS_DB_USER");
        stream.println("  --password-env NAME   Password environment variable");
        stream.println("  --target-schema NAME  Target schema (default molclass_v3)");
        stream.println("  --scope MODEL|ALL     Molecule scope (default MODEL)");
        stream.println("  --threads N           Worker count (default available CPUs, maximum 16)");
        stream.println("  --batch-size N        Database commit interval (default 200)");
        stream.println("  --limit N             Process at most N incomplete molecules");
        stream.println("  --help                Show this contract");
    }

    private enum Scope { MODEL, ALL }

    private record Config(String jdbcUrl, String dbUser, String dbPassword,
                          String passwordEnvironment, String targetSchema,
                          Scope scope, int threads, int batchSize, Long limit) {
        private static final Set<String> ALLOWED = Set.of(
                "jdbc-url", "db-user", "password-env", "target-schema",
                "scope", "threads", "batch-size", "limit");

        static Config parse(String[] args, Map<String, String> environment) {
            Map<String, String> options = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (!argument.startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument: " + argument);
                }
                String name = argument.substring(2);
                if (!ALLOWED.contains(name)) {
                    throw new IllegalArgumentException("unknown option: --" + name);
                }
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for --" + name);
                }
                if (options.put(name, args[++index]) != null) {
                    throw new IllegalArgumentException("duplicate option: --" + name);
                }
            }
            String jdbc = first(options.get("jdbc-url"), environment.get("MOLCLASS_JDBC_URL"));
            String user = first(options.get("db-user"), environment.get("MOLCLASS_DB_USER"));
            String passwordEnvironment = options.getOrDefault("password-env", "MOLCLASS_DB_PASSWORD");
            String password = environment.get(passwordEnvironment);
            if (jdbc == null || user == null || password == null) {
                throw new IllegalArgumentException("database URL, user, and password environment are required");
            }
            String schema = options.getOrDefault("target-schema", "molclass_v3");
            validateIdentifier(schema);
            Scope scope;
            try {
                scope = Scope.valueOf(options.getOrDefault("scope", "MODEL").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("--scope must be MODEL or ALL");
            }
            int defaultThreads = Math.min(16, Math.max(1, Runtime.getRuntime().availableProcessors()));
            int threads = positive(options.getOrDefault("threads", Integer.toString(defaultThreads)), "threads");
            int batch = positive(options.getOrDefault("batch-size", "200"), "batch-size");
            Long limit = options.containsKey("limit")
                    ? (long) positive(options.get("limit"), "limit") : null;
            return new Config(jdbc, user, password, passwordEnvironment, schema,
                    scope, threads, batch, limit);
        }

        private static int positive(String value, String option) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed <= 0) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("--" + option + " must be a positive integer");
            }
        }
    }

    private record FingerprintSpec(String code, String implementationClass,
                                   String fallbackClass) {
    }

    private record FingerprintDefinition(FingerprintSpec spec, long id, int size) {
    }

    private record DescriptorCatalog(List<String> classNames, List<String> names,
                                     String manifestJson, byte[] manifestHash) {
        static DescriptorCatalog create() {
            List<IMolecularDescriptor> descriptors = instantiateDescriptors(null);
            List<String> classes = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<Map<String, Object>> manifest = new ArrayList<>();
            for (IMolecularDescriptor descriptor : descriptors) {
                String className = descriptor.getClass().getName();
                classes.add(className);
                String[] descriptorNames = descriptor.getDescriptorNames();
                for (int resultIndex = 0; resultIndex < descriptorNames.length; resultIndex++) {
                    String name = descriptorNames[resultIndex];
                    if (names.contains(name)) {
                        name = descriptor.getClass().getSimpleName() + "." + name;
                    }
                    names.add(name);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("class", className);
                    item.put("name", name);
                    item.put("resultIndex", resultIndex);
                    manifest.add(item);
                }
            }
            String json = jsonArrayOfObjects(manifest);
            return new DescriptorCatalog(List.copyOf(classes), List.copyOf(names),
                    json, sha256(json));
        }
    }

    private static final class Generator {
        private final Config config;
        private final Connection read;
        private final Connection write;
        private final PrintStream out;
        private final PrintStream err;
        private final molclass.models.MurckoScaffoldCore murckoCore;
        private long jobId;
        private long descriptorGenerationId;
        private DescriptorCatalog catalog;
        private List<FingerprintDefinition> definitions;
        private long scaffoldFailures;

        Generator(Config config, Connection read, Connection write,
                  PrintStream out, PrintStream err) {
            this.config = config;
            this.read = read;
            this.write = write;
            this.out = out;
            this.err = err;
            this.murckoCore = new molclass.models.MurckoScaffoldCore(config.targetSchema());
        }

        void run() throws Exception {
            requireTrackingSchema();
            jobId = createJob();
            try {
                event("STARTED", "INITIALIZE", "Building deterministic CDK feature contracts");
                catalog = DescriptorCatalog.create();
                descriptorGenerationId = ensureDescriptorGeneration();
                definitions = ensureFingerprintDefinitions();
                linkFeatureProfiles();
                updateJob("RUNNING", "CALCULATE", null, null, false);
                write.commit();

                long selected = calculateIncompleteMolecules();
                long failures = countFailures();
                long remaining = countIncompleteMolecules();
                boolean exclusionsAccepted = remaining > 0 && failures > 0
                        && Boolean.parseBoolean(System.getenv().getOrDefault(
                                "MOLCLASS_ALLOW_FEATURE_EXCLUSIONS", "false"));
                String generationStatus;
                if (failures == 0 && remaining == 0) {
                    generationStatus = config.scope() == Scope.ALL ? "READY" : "READY_MODEL_SCOPE";
                } else if (exclusionsAccepted) {
                    generationStatus = "READY_WITH_EXCLUSIONS";
                } else {
                    generationStatus = failures == 0 ? "INCOMPLETE" : "COMPLETED_WITH_ERRORS";
                }
                boolean publish = remaining == 0 || exclusionsAccepted;
                publishStatuses(generationStatus, publish);
                String jobStatus = exclusionsAccepted ? "COMPLETED_WITH_EXCLUSIONS"
                        : failures == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS";
                String errorCode = failures == 0 ? null
                        : exclusionsAccepted ? "FEATURE_RECORD_EXCLUSIONS" : "FEATURE_RECORD_FAILURES";
                String errorMessage = failures == 0 ? null
                        : failures + " feature records "
                                + (exclusionsAccepted ? "accepted as exclusions" : "failed");
                updateJob(jobStatus, "COMPLETE", errorCode, errorMessage, true);
                event(exclusionsAccepted ? "COMPLETED_WITH_EXCLUSIONS" : "COMPLETED",
                        "COMPLETE", "Processed " + selected + " molecules with " + failures
                                + " failed feature records; remaining incomplete: " + remaining
                                + "; exclusions accepted: " + exclusionsAccepted);
                write.commit();
                out.println("Feature job " + jobId + " processed " + selected
                        + " molecules; remaining incomplete: " + remaining
                        + "; failed feature records: " + failures
                        + "; scaffold failures: " + scaffoldFailures
                        + "; exclusions accepted: " + exclusionsAccepted + ".");
            } catch (Exception exception) {
                rollbackQuietly(write);
                try {
                    updateJob("FAILED", "FAILED", errorCode(exception),
                            conciseMessage(exception), true);
                    event("FAILED", "FAILED", conciseMessage(exception));
                    write.commit();
                } catch (SQLException updateFailure) {
                    exception.addSuppressed(updateFailure);
                }
                throw exception;
            }
        }

        private void requireTrackingSchema() throws SQLException {
            String sql = "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?"
                    + " AND TABLE_NAME = 'molecule_fingerprint' AND COLUMN_NAME = 'attempt_count'";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setString(1, config.targetSchema());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(1) != 1) {
                        throw new IllegalStateException(
                                "V3__feature_generation_tracking.sql has not been applied");
                    }
                }
            }
        }

        private long createJob() throws SQLException {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("batchSize", config.batchSize());
            payload.put("cdkVersion", CDK_VERSION);
            payload.put("implementationVersion", IMPLEMENTATION_VERSION);
            payload.put("scope", config.scope().name());
            payload.put("threads", config.threads());
            String sql = "INSERT INTO " + table(config.targetSchema(), "job")
                    + " (job_type, status, runstep, priority, payload_json, available_at,"
                    + " attempt_count, maximum_attempts, created_at, started_at)"
                    + " VALUES ('FEATURE_GENERATION', 'RUNNING', 'INITIALIZE', 0, ?, NOW(6),"
                    + " 1, 1, NOW(6), NOW(6))";
            try (PreparedStatement statement = write.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, jsonObject(payload));
                statement.executeUpdate();
                long id = generatedKey(statement);
                write.commit();
                return id;
            }
        }

        private long ensureDescriptorGeneration() throws SQLException {
            Map<String, Object> configuration = new LinkedHashMap<>();
            configuration.put("descriptorClassCount", catalog.classNames().size());
            configuration.put("descriptorValueCount", catalog.names().size());
            configuration.put("implementationVersion", IMPLEMENTATION_VERSION);
            configuration.put("missingValuePolicy", "bit-mask-and-NaN");
            configuration.put("ordering", "descriptor-class-name-then-result-index");
            String json = jsonObject(configuration);
            String sql = "INSERT INTO " + table(config.targetSchema(), "descriptor_generation")
                    + " (generation_name, cdk_version, java_version, normalization_version,"
                    + " implementation_version, configuration_json, configuration_sha256,"
                    + " vector_format, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'GENERATING')"
                    + " ON DUPLICATE KEY UPDATE descriptor_generation_id ="
                    + " LAST_INSERT_ID(descriptor_generation_id), status = 'GENERATING'";
            long id;
            try (PreparedStatement statement = write.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, DESCRIPTOR_NAME);
                statement.setString(2, CDK_VERSION);
                statement.setString(3, System.getProperty("java.version"));
                statement.setString(4, NORMALIZATION_VERSION);
                statement.setString(5, IMPLEMENTATION_VERSION);
                statement.setString(6, json);
                statement.setBytes(7, sha256(json));
                statement.setString(8, VECTOR_FORMAT);
                statement.executeUpdate();
                id = generatedKeyOrLastInsertId(statement);
            }
            String schemaSql = "INSERT INTO " + table(config.targetSchema(), "descriptor_schema")
                    + " (descriptor_generation_id, descriptor_count, descriptor_manifest_json,"
                    + " manifest_sha256) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE"
                    + " descriptor_count = VALUES(descriptor_count),"
                    + " descriptor_manifest_json = VALUES(descriptor_manifest_json),"
                    + " manifest_sha256 = VALUES(manifest_sha256)";
            try (PreparedStatement statement = write.prepareStatement(schemaSql)) {
                statement.setLong(1, id);
                statement.setInt(2, catalog.names().size());
                statement.setString(3, catalog.manifestJson());
                statement.setBytes(4, catalog.manifestHash());
                statement.executeUpdate();
            }
            return id;
        }

        private List<FingerprintDefinition> ensureFingerprintDefinitions() throws Exception {
            List<FingerprintDefinition> result = new ArrayList<>();
            for (FingerprintSpec spec : FINGERPRINTS) {
                IFingerprinter fingerprinter = createFingerprinter(spec.code());
                int size = fingerprinter.getSize();
                Map<String, Object> configuration = new LinkedHashMap<>();
                configuration.put("bitEncoding", "fixed-width-lsb0");
                configuration.put("fallbackClass", spec.fallbackClass());
                configuration.put("implementationClass", spec.implementationClass());
                configuration.put("implementationVersion", IMPLEMENTATION_VERSION);
                String json = jsonObject(configuration);
                String sql = "INSERT INTO " + table(config.targetSchema(), "fingerprint_definition")
                        + " (fingerprint_code, generation_version, implementation_class,"
                        + " cdk_version, normalization_version, bit_length, configuration_json,"
                        + " configuration_sha256, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'GENERATING')"
                        + " ON DUPLICATE KEY UPDATE fingerprint_definition_id ="
                        + " LAST_INSERT_ID(fingerprint_definition_id), bit_length = VALUES(bit_length),"
                        + " status = 'GENERATING'";
                try (PreparedStatement statement = write.prepareStatement(
                        sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, spec.code());
                    statement.setString(2, GENERATION_VERSION);
                    statement.setString(3, spec.implementationClass());
                    statement.setString(4, CDK_VERSION);
                    statement.setString(5, NORMALIZATION_VERSION);
                    statement.setInt(6, size);
                    statement.setString(7, json);
                    statement.setBytes(8, sha256(json));
                    statement.executeUpdate();
                    result.add(new FingerprintDefinition(spec,
                            generatedKeyOrLastInsertId(statement), size));
                }
            }
            return List.copyOf(result);
        }

        private void linkFeatureProfiles() throws SQLException {
            Map<String, List<String>> profiles = new LinkedHashMap<>();
            profiles.put("CDK", List.of("CDK"));
            profiles.put("MACCS", List.of("MACCS"));
            profiles.put("PubChem", List.of("PubChem"));
            profiles.put("EXT", List.of("EXT"));
            profiles.put("KR", List.of("KR"));
            profiles.put("EXTGO", List.of("EXT", "GOFP"));
            profiles.put("ALL", List.of("CDK", "MACCS", "PubChem", "EXT", "SUB", "ECFP"));
            profiles.put("MCAT", List.of("CDK", "MACCS", "PubChem", "SUB", "KR"));
            profiles.put("JUMBO", List.of("CDK", "MACCS", "PubChem", "EXT", "SUB", "KR", "ECFP"));
            Map<String, FingerprintDefinition> byCode = new LinkedHashMap<>();
            for (FingerprintDefinition definition : definitions) {
                byCode.put(definition.spec().code(), definition);
            }
            String profileIdSql = "SELECT feature_profile_id FROM "
                    + table(config.targetSchema(), "feature_profile") + " WHERE profile_code = ?";
            String deleteSql = "DELETE FROM "
                    + table(config.targetSchema(), "feature_profile_component")
                    + " WHERE feature_profile_id = ?";
            String insertSql = "INSERT INTO "
                    + table(config.targetSchema(), "feature_profile_component")
                    + " (feature_profile_id, component_order, descriptor_generation_id,"
                    + " fingerprint_definition_id, transformation_json) VALUES (?, ?, ?, ?, ?)";
            String statusSql = "UPDATE " + table(config.targetSchema(), "feature_profile")
                    + " SET status = 'GENERATING' WHERE feature_profile_id = ?";
            try (PreparedStatement find = write.prepareStatement(profileIdSql);
                 PreparedStatement delete = write.prepareStatement(deleteSql);
                 PreparedStatement insert = write.prepareStatement(insertSql);
                 PreparedStatement status = write.prepareStatement(statusSql)) {
                for (Map.Entry<String, List<String>> profile : profiles.entrySet()) {
                    find.setString(1, profile.getKey());
                    long profileId;
                    try (ResultSet result = find.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalStateException("migrated feature profile is missing: "
                                    + profile.getKey());
                        }
                        profileId = result.getLong(1);
                    }
                    delete.setLong(1, profileId);
                    delete.executeUpdate();
                    int order = 0;
                    for (String component : profile.getValue()) {
                        insert.setLong(1, profileId);
                        insert.setInt(2, order++);
                        if ("CDK".equals(component)) {
                            insert.setLong(3, descriptorGenerationId);
                            insert.setNull(4, Types.BIGINT);
                            insert.setString(5,
                                    "{\"kind\":\"descriptor-vector\",\"missing\":\"weka-missing\"}");
                        } else {
                            FingerprintDefinition definition = byCode.get(component);
                            insert.setNull(3, Types.BIGINT);
                            insert.setLong(4, definition.id());
                            insert.setString(5, "{\"kind\":\"bit-vector\",\"prefix\":\""
                                    + component + "_\",\"bitOrder\":\"lsb0\"}");
                        }
                        insert.executeUpdate();
                    }
                    status.setLong(1, profileId);
                    status.executeUpdate();
                }
            }
        }

        private long calculateIncompleteMolecules() throws Exception {
            String moleculeSql = incompleteMoleculeSql();
            ExecutorService executor = Executors.newFixedThreadPool(config.threads());
            CompletionService<FeatureResult> completion = new ExecutorCompletionService<>(executor);
            ThreadLocal<FeatureCalculator> calculators = ThreadLocal.withInitial(
                    () -> new FeatureCalculator(catalog, definitions));
            long submitted = 0;
            long completed = 0;
            int inFlight = 0;
            int maximumInFlight = Math.max(config.threads(), config.threads() * 4);
            try (Statement statement = read.createStatement();
                 ResultSet molecules = statement.executeQuery(moleculeSql);
                 FeatureWriter writer = new FeatureWriter(write, config.targetSchema(),
                         descriptorGenerationId, definitions, jobId, config.batchSize())) {
                while (molecules.next()) {
                    long moleculeId = molecules.getLong(1);
                    byte[] structure = molecules.getBytes(2);
                    String canonicalSmiles = molecules.getString(3);
                    ensureScaffoldTolerantly(moleculeId);
                    completion.submit(() -> {
                        try {
                            return calculators.get().calculate(moleculeId, structure, canonicalSmiles);
                        } catch (Throwable throwable) {
                            return FeatureResult.failed(moleculeId, throwable, definitions);
                        }
                    });
                    submitted++;
                    inFlight++;
                    if (inFlight >= maximumInFlight) {
                        writer.write(await(completion));
                        completed++;
                        inFlight--;
                        reportProgress(completed, submitted);
                    }
                }
                while (inFlight > 0) {
                    writer.write(await(completion));
                    completed++;
                    inFlight--;
                    reportProgress(completed, submitted);
                }
                writer.commit();
            } finally {
                executor.shutdownNow();
            }
            return completed;
        }

        // Runs sequentially on the main thread (never inside the parallel descriptor/fingerprint
        // executor) alongside every molecule this pass touches, reusing the same `write`
        // connection the FeatureWriter uses -- both are only ever driven from this thread, so
        // there is no concurrent access to guard against. Committed per molecule rather than
        // batched: scaffold computation is comparatively cheap, and for a run spanning the
        // entire molecule table, losing an in-progress batch's scaffolds to an interrupted run
        // is worse than the extra commits. A single molecule's scaffold failure (some structures
        // have no valid Kekule form -- a real chemistry/data property, not a bug, as established
        // earlier when this exact service was built) must not abort the whole feature-generation
        // job, so it is caught, logged, and counted rather than propagated.
        private void ensureScaffoldTolerantly(long moleculeId) {
            try {
                murckoCore.ensureScaffold(write, moleculeId);
                write.commit();
            } catch (Exception scaffoldFailure) {
                scaffoldFailures++;
                try {
                    write.rollback();
                } catch (SQLException rollbackFailure) {
                    err.println("scaffold rollback failed for molecule " + moleculeId
                            + ": " + rollbackFailure.getMessage());
                }
                err.println("scaffold computation failed for molecule " + moleculeId
                        + ": " + conciseMessage(scaffoldFailure));
            }
        }

        private void reportProgress(long completed, long submitted) {
            if (completed % 1000 == 0) {
                out.println("Feature job " + jobId + ": completed " + completed
                        + " of " + submitted + " submitted molecules.");
            }
        }

        private String incompleteMoleculeSql() {
            StringBuilder sql = new StringBuilder("SELECT m.molecule_id, m.normalized_structure, m.canonical_smiles FROM ")
                    .append(table(config.targetSchema(), "molecule")).append(" m WHERE ");
            if (config.scope() == Scope.MODEL) {
                sql.append("EXISTS (SELECT 1 FROM ")
                        .append(table(config.targetSchema(), "dataset_molecule")).append(" dm JOIN ")
                        .append(table(config.targetSchema(), "model_definition"))
                        .append(" md ON md.dataset_id = dm.dataset_id WHERE dm.molecule_id = m.molecule_id) AND ");
            }
            sql.append("(NOT EXISTS (SELECT 1 FROM ")
                    .append(table(config.targetSchema(), "molecule_descriptor_vector"))
                    .append(" dv WHERE dv.descriptor_generation_id = ").append(descriptorGenerationId)
                    .append(" AND dv.molecule_id = m.molecule_id AND dv.status IN ('SUCCEEDED','SUCCEEDED_WITH_MISSING'))")
                    .append(" OR (SELECT COUNT(*) FROM ")
                    .append(table(config.targetSchema(), "molecule_fingerprint"))
                    .append(" mf WHERE mf.molecule_id = m.molecule_id AND mf.status = 'SUCCEEDED'")
                    .append(" AND mf.fingerprint_definition_id IN (");
            for (int index = 0; index < definitions.size(); index++) {
                if (index > 0) {
                    sql.append(',');
                }
                sql.append(definitions.get(index).id());
            }
            sql.append(")) < ").append(definitions.size()).append(") ORDER BY m.molecule_id");
            if (config.limit() != null) {
                sql.append(" LIMIT ").append(config.limit());
            }
            return sql.toString();
        }

        private long countIncompleteMolecules() throws SQLException {
            String query = incompleteMoleculeSql();
            int order = query.lastIndexOf(" ORDER BY ");
            if (order < 0) {
                throw new IllegalStateException("incomplete molecule query has no deterministic ordering");
            }
            String sql = "SELECT COUNT(*) FROM (" + query.substring(0, order) + ") incomplete";
            try (Statement statement = write.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                result.next();
                return result.getLong(1);
            }
        }

        private long countFailures() throws SQLException {
            StringBuilder ids = new StringBuilder();
            for (FingerprintDefinition definition : definitions) {
                if (ids.length() > 0) {
                    ids.append(',');
                }
                ids.append(definition.id());
            }
            String sql = "SELECT (SELECT COUNT(*) FROM "
                    + table(config.targetSchema(), "molecule_descriptor_vector")
                    + " WHERE descriptor_generation_id = ? AND status = 'FAILED') +"
                    + " (SELECT COUNT(*) FROM "
                    + table(config.targetSchema(), "molecule_fingerprint")
                    + " WHERE fingerprint_definition_id IN (" + ids + ") AND status = 'FAILED')";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setLong(1, descriptorGenerationId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        }

        private void publishStatuses(String status, boolean ready) throws SQLException {
            String descriptorSql = "UPDATE " + table(config.targetSchema(), "descriptor_generation")
                    + " SET status = ?, published_at = " + (ready ? "NOW(6)" : "NULL")
                    + " WHERE descriptor_generation_id = ?";
            try (PreparedStatement statement = write.prepareStatement(descriptorSql)) {
                statement.setString(1, status);
                statement.setLong(2, descriptorGenerationId);
                statement.executeUpdate();
            }
            String fingerprintSql = "UPDATE " + table(config.targetSchema(), "fingerprint_definition")
                    + " SET status = ?, published_at = " + (ready ? "NOW(6)" : "NULL")
                    + " WHERE fingerprint_definition_id = ?";
            try (PreparedStatement statement = write.prepareStatement(fingerprintSql)) {
                for (FingerprintDefinition definition : definitions) {
                    statement.setString(1, status);
                    statement.setLong(2, definition.id());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            String profileSql = "UPDATE " + table(config.targetSchema(), "feature_profile")
                    + " SET status = ? WHERE status = 'GENERATING'";
            try (PreparedStatement statement = write.prepareStatement(profileSql)) {
                statement.setString(1, status);
                statement.executeUpdate();
            }
        }

        private void updateJob(String status, String runstep, String code,
                               String message, boolean finished) throws SQLException {
            String sql = "UPDATE " + table(config.targetSchema(), "job")
                    + " SET status = ?, runstep = ?, error_code = ?, error_message = ?,"
                    + " finished_at = " + (finished ? "NOW(6)" : "NULL")
                    + " WHERE job_id = ?";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setString(1, status);
                statement.setString(2, runstep);
                nullableString(statement, 3, code);
                nullableString(statement, 4, limit(message, 2048));
                statement.setLong(5, jobId);
                statement.executeUpdate();
            }
        }

        private void event(String type, String runstep, String message) throws SQLException {
            String sql = "INSERT INTO " + table(config.targetSchema(), "job_event")
                    + " (job_id, event_type, runstep, event_message, created_at)"
                    + " VALUES (?, ?, ?, ?, NOW(6))";
            try (PreparedStatement statement = write.prepareStatement(sql)) {
                statement.setLong(1, jobId);
                statement.setString(2, type);
                statement.setString(3, runstep);
                statement.setString(4, limit(message, 2048));
                statement.executeUpdate();
            }
        }
    }

    private static final class FeatureCalculator {
        private final List<IMolecularDescriptor> descriptors;
        private final List<String> descriptorNames;
        private final List<FingerprintCalculator> fingerprints;

        FeatureCalculator(DescriptorCatalog catalog, List<FingerprintDefinition> definitions) {
            this.descriptors = instantiateDescriptors(catalog.classNames());
            this.descriptorNames = catalog.names();
            List<FingerprintCalculator> calculators = new ArrayList<>();
            for (FingerprintDefinition definition : definitions) {
                try {
                    calculators.add(new FingerprintCalculator(definition,
                            createFingerprinter(definition.spec().code())));
                } catch (Exception exception) {
                    throw new IllegalStateException("cannot initialize fingerprint "
                            + definition.spec().code(), exception);
                }
            }
            this.fingerprints = List.copyOf(calculators);
        }

        FeatureResult calculate(long moleculeId, byte[] structure, String canonicalSmiles) {
            try {
                IAtomContainer molecule = parseMolecule(structure, canonicalSmiles);
                DescriptorPayload descriptor = calculateDescriptors(molecule);
                Map<String, FingerprintPayload> fingerprintResults = new LinkedHashMap<>();
                for (FingerprintCalculator calculator : fingerprints) {
                    fingerprintResults.put(calculator.definition.spec().code(),
                            calculator.calculate(molecule));
                }
                return new FeatureResult(moleculeId, descriptor, fingerprintResults);
            } catch (Throwable throwable) {
                return FeatureResult.failed(moleculeId, throwable,
                        fingerprints.stream().map(value -> value.definition).toList());
            }
        }

        private DescriptorPayload calculateDescriptors(IAtomContainer molecule) {
            double[] values = new double[descriptorNames.size()];
            BitSet missing = new BitSet(values.length);
            int offset = 0;
            for (IMolecularDescriptor descriptor : descriptors) {
                int expected = descriptor.getDescriptorNames().length;
                try {
                    DescriptorValue value = descriptor.calculate(molecule);
                    if (value.getException() != null) {
                        throw value.getException();
                    }
                    IDescriptorResult result = value.getValue();
                    for (int index = 0; index < expected; index++) {
                        double numeric = descriptorValue(result, index);
                        if (Double.isFinite(numeric)) {
                            values[offset + index] = numeric;
                        } else {
                            values[offset + index] = Double.NaN;
                            missing.set(offset + index);
                        }
                    }
                } catch (Throwable throwable) {
                    for (int index = 0; index < expected; index++) {
                        values[offset + index] = Double.NaN;
                        missing.set(offset + index);
                    }
                }
                offset += expected;
            }
            byte[] encoded = encodeDoubles(values);
            return new DescriptorPayload(missing.isEmpty()
                    ? "SUCCEEDED" : "SUCCEEDED_WITH_MISSING", encoded,
                    fixedBitBytes(missing, values.length), sha256(encoded), null, null);
        }
    }

    private static final class FingerprintCalculator {
        private final FingerprintDefinition definition;
        private final IFingerprinter primary;

        FingerprintCalculator(FingerprintDefinition definition, IFingerprinter primary) {
            this.definition = definition;
            this.primary = primary;
        }

        FingerprintPayload calculate(IAtomContainer molecule) {
            try {
                return success(primary.getBitFingerprint(molecule), false);
            } catch (Throwable primaryFailure) {
                if (!"SUB".equals(definition.spec().code())) {
                    return FingerprintPayload.failed(primaryFailure);
                }
                try {
                    IFingerprinter fallback = new Fingerprinter(definition.size());
                    return success(fallback.getBitFingerprint(molecule), true);
                } catch (Throwable fallbackFailure) {
                    fallbackFailure.addSuppressed(primaryFailure);
                    return FingerprintPayload.failed(fallbackFailure);
                }
            }
        }

        private FingerprintPayload success(IBitFingerprint value, boolean fallback) {
            BitSet bits = value.asBitSet();
            byte[] encoded = fixedBitBytes(bits, definition.size());
            return new FingerprintPayload("SUCCEEDED", encoded, bits.cardinality(),
                    sha256(encoded), fallback, null, null);
        }
    }

    private record DescriptorPayload(String status, byte[] values, byte[] missingMask,
                                     byte[] hash, String errorCode, String errorMessage) {
        static DescriptorPayload failed(Throwable throwable) {
            return new DescriptorPayload("FAILED", null, null, null,
                    V3FeatureGenerator.errorCode(throwable), limit(conciseMessage(throwable), 2048));
        }
    }

    private record FingerprintPayload(String status, byte[] bits, Integer bitCount,
                                      byte[] hash, boolean fallback, String errorCode,
                                      String errorMessage) {
        static FingerprintPayload failed(Throwable throwable) {
            return new FingerprintPayload("FAILED", null, null, null, false,
                    V3FeatureGenerator.errorCode(throwable), limit(conciseMessage(throwable), 2048));
        }
    }

    private record FeatureResult(long moleculeId, DescriptorPayload descriptor,
                                 Map<String, FingerprintPayload> fingerprints) {
        static FeatureResult failed(long moleculeId, Throwable throwable,
                                    List<FingerprintDefinition> definitions) {
            Map<String, FingerprintPayload> failed = new LinkedHashMap<>();
            for (FingerprintDefinition definition : definitions) {
                failed.put(definition.spec().code(), FingerprintPayload.failed(throwable));
            }
            return new FeatureResult(moleculeId, DescriptorPayload.failed(throwable), failed);
        }
    }

    private static final class FeatureWriter implements AutoCloseable {
        private final Connection connection;
        private final PreparedStatement descriptor;
        private final PreparedStatement fingerprint;
        private final Map<String, FingerprintDefinition> definitions;
        private final long descriptorGenerationId;
        private final long jobId;
        private final int batchSize;
        private int pending;

        FeatureWriter(Connection connection, String schema, long descriptorGenerationId,
                      List<FingerprintDefinition> definitions, long jobId, int batchSize)
                throws SQLException {
            this.connection = connection;
            this.descriptorGenerationId = descriptorGenerationId;
            this.jobId = jobId;
            this.batchSize = batchSize;
            Map<String, FingerprintDefinition> byCode = new LinkedHashMap<>();
            for (FingerprintDefinition definition : definitions) {
                byCode.put(definition.spec().code(), definition);
            }
            this.definitions = Collections.unmodifiableMap(byCode);
            this.descriptor = connection.prepareStatement(
                    "INSERT INTO " + table(schema, "molecule_descriptor_vector")
                            + " (descriptor_generation_id, molecule_id, status, last_job_id,"
                            + " attempt_count, descriptor_values, missing_value_mask, values_sha256,"
                            + " error_code, error_message, calculated_at)"
                            + " VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, NOW(6))"
                            + " ON DUPLICATE KEY UPDATE status = VALUES(status), last_job_id = VALUES(last_job_id),"
                            + " attempt_count = attempt_count + 1, descriptor_values = VALUES(descriptor_values),"
                            + " missing_value_mask = VALUES(missing_value_mask), values_sha256 = VALUES(values_sha256),"
                            + " error_code = VALUES(error_code), error_message = VALUES(error_message),"
                            + " calculated_at = NOW(6)");
            this.fingerprint = connection.prepareStatement(
                    "INSERT INTO " + table(schema, "molecule_fingerprint")
                            + " (fingerprint_definition_id, molecule_id, status, last_job_id, attempt_count,"
                            + " fingerprint_bits, bit_count, bits_sha256, fallback_used, error_code,"
                            + " error_message, calculated_at) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, NOW(6))"
                            + " ON DUPLICATE KEY UPDATE status = VALUES(status), last_job_id = VALUES(last_job_id),"
                            + " attempt_count = attempt_count + 1, fingerprint_bits = VALUES(fingerprint_bits),"
                            + " bit_count = VALUES(bit_count), bits_sha256 = VALUES(bits_sha256),"
                            + " fallback_used = VALUES(fallback_used), error_code = VALUES(error_code),"
                            + " error_message = VALUES(error_message), calculated_at = NOW(6)");
        }

        void write(FeatureResult result) throws SQLException {
            DescriptorPayload descriptorValue = result.descriptor();
            descriptor.setLong(1, descriptorGenerationId);
            descriptor.setLong(2, result.moleculeId());
            descriptor.setString(3, descriptorValue.status());
            descriptor.setLong(4, jobId);
            nullableBytes(descriptor, 5, descriptorValue.values());
            nullableBytes(descriptor, 6, descriptorValue.missingMask());
            nullableBytes(descriptor, 7, descriptorValue.hash());
            nullableString(descriptor, 8, descriptorValue.errorCode());
            nullableString(descriptor, 9, descriptorValue.errorMessage());
            descriptor.executeUpdate();

            for (Map.Entry<String, FingerprintPayload> entry : result.fingerprints().entrySet()) {
                FingerprintDefinition definition = definitions.get(entry.getKey());
                FingerprintPayload value = entry.getValue();
                fingerprint.setLong(1, definition.id());
                fingerprint.setLong(2, result.moleculeId());
                fingerprint.setString(3, value.status());
                fingerprint.setLong(4, jobId);
                nullableBytes(fingerprint, 5, value.bits());
                nullableInteger(fingerprint, 6, value.bitCount());
                nullableBytes(fingerprint, 7, value.hash());
                fingerprint.setBoolean(8, value.fallback());
                nullableString(fingerprint, 9, value.errorCode());
                nullableString(fingerprint, 10, value.errorMessage());
                fingerprint.executeUpdate();
            }
            if (++pending >= batchSize) {
                commit();
            }
        }

        void commit() throws SQLException {
            connection.commit();
            pending = 0;
        }

        @Override
        public void close() throws SQLException {
            descriptor.close();
            fingerprint.close();
        }
    }

    private static List<IMolecularDescriptor> instantiateDescriptors(List<String> requestedClasses) {
        DescriptorEngine engine = requestedClasses == null
                ? new DescriptorEngine(IMolecularDescriptor.class, BUILDER)
                : new DescriptorEngine(requestedClasses, BUILDER);
        List<IMolecularDescriptor> descriptors = new ArrayList<>();
        for (Object value : engine.getDescriptorInstances()) {
            if (value instanceof IMolecularDescriptor descriptor) {
                descriptor.initialise(BUILDER);
                descriptors.add(descriptor);
            }
        }
        descriptors.sort(Comparator.comparing(value -> value.getClass().getName()));
        if (descriptors.isEmpty()) {
            throw new IllegalStateException("CDK did not discover molecular descriptors");
        }
        return List.copyOf(descriptors);
    }

    private static IFingerprinter createFingerprinter(String code) throws CDKException {
        return switch (code) {
            case "MACCS" -> new MACCSFingerprinter(BUILDER);
            case "PubChem" -> new PubchemFingerprinter(BUILDER);
            case "EXT" -> new ExtendedFingerprinter();
            case "SUB" -> new SubstructureFingerprinter();
            case "KR" -> new KlekotaRothFingerprinter();
            case "GOFP" -> new GraphOnlyFingerprinter();
            case "ESFP" -> new EStateFingerprinter();
            case "ECFP" -> new CircularFingerprinter(CircularFingerprinter.CLASS_ECFP4, 1024);
            default -> throw new IllegalArgumentException("unsupported fingerprint: " + code);
        };
    }

    private static IAtomContainer parseMolecule(byte[] structure, String canonicalSmiles) throws Exception {
        Exception molfileFailure;
        try {
            return configureMolecule(readMolfile(structure));
        } catch (Exception exception) {
            molfileFailure = exception;
        }
        String smiles = trim(canonicalSmiles);
        if (smiles != null) {
            try {
                return configureMolecule(new SmilesParser(BUILDER).parseSmiles(smiles));
            } catch (Exception smilesFailure) {
                smilesFailure.addSuppressed(molfileFailure);
                throw smilesFailure;
            }
        }
        throw molfileFailure;
    }

    static IAtomContainer readMolfile(byte[] structure) throws Exception {
        if (structure == null || structure.length == 0) {
            throw new IllegalArgumentException("molecule structure is empty");
        }
        String molfile = new String(structure, StandardCharsets.UTF_8);
        try {
            return readMolfileOnce(molfile);
        } catch (Exception firstFailure) {
            // Some registry records (traced to a "SciTegic" export tool) write atom-block lines
            // as whitespace-*delimited* trailing tokens (symbol, mass-diff, charge, ...) rather
            // than MDL's required fixed-*width* columns, e.g. "...0.0000 C    0  0" tokenizes to
            // [C, 0, 0] with irregular spacing instead of symbol(3)+massDiff(2)+charge(3)+... in
            // exact column positions. CDK's strict column reader rejects that with "invalid line
            // length" -- confirmed against all 136 real molecules failing this way in the live
            // database (none were a different failure mode). Only attempted as a fallback after
            // a normal read fails, and only for V2000 (V3000 has a different block structure
            // this transform doesn't understand) -- zero behavior change for every molfile that
            // already reads correctly today.
            if (molfile.contains("V3000")) throw firstFailure;
            String renormalized = renormalizeAtomLines(molfile);
            if (renormalized.equals(molfile)) throw firstFailure;
            try {
                return readMolfileOnce(renormalized);
            } catch (Exception secondFailure) {
                secondFailure.addSuppressed(firstFailure);
                throw secondFailure;
            }
        }
    }

    static IAtomContainer readMolfileOnce(String molfile) throws Exception {
        ISimpleChemObjectReader reader = molfile.contains("V3000")
                ? new MDLV3000Reader(new StringReader(molfile), IChemObjectReader.Mode.RELAXED)
                : new MDLV2000Reader(new StringReader(molfile), IChemObjectReader.Mode.RELAXED);
        try {
            IAtomContainer molecule = reader.read(BUILDER.newInstance(IAtomContainer.class));
            if (molecule == null || molecule.getAtomCount() == 0) {
                throw new IllegalArgumentException("molfile contains no atoms");
            }
            return molecule;
        } finally {
            reader.close();
        }
    }

    /**
     * Re-tokenizes and re-emits V2000 atom-block lines shorter than a normally-compliant line
     * in MDL's true fixed-width column format. See {@link #readMolfile} for why this is needed.
     * A no-op (returns the input unchanged) if the counts line can't be parsed, so callers can
     * safely compare the result against the input to detect whether anything would change.
     */
    static String renormalizeAtomLines(String molfile) {
        String[] lines = molfile.split("\n", -1);
        if (lines.length < 4) return molfile;
        int atomCount;
        try {
            atomCount = Integer.parseInt(lines[3].substring(0, Math.min(3, lines[3].length())).trim());
        } catch (Exception e) {
            return molfile;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean isAtomLine = i >= 4 && i < 4 + atomCount;
            if (isAtomLine && line.length() < 48 && line.length() >= 31) {
                line = renormalizeAtomLine(line);
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static String renormalizeAtomLine(String line) {
        String coords = line.substring(0, 31);
        String[] tokens = line.substring(31).trim().split("\\s+");
        String symbol = tokens.length > 0 && !tokens[0].isEmpty() ? tokens[0] : "C";
        StringBuilder rebuilt = new StringBuilder(coords);
        rebuilt.append(fixedWidthField(symbol, 3, false));
        int[] fieldWidths = {2, 3, 3, 3, 3, 3}; // massDiff,charge,stereoParity,hCount,careBox,valence
        for (int field = 0; field < fieldWidths.length; field++) {
            String value = tokens.length > field + 1 ? tokens[field + 1] : "0";
            rebuilt.append(fixedWidthField(value, fieldWidths[field], true));
        }
        return rebuilt.toString();
    }

    private static String fixedWidthField(String value, int width, boolean rightJustify) {
        if (value.length() >= width) return value.substring(0, width);
        StringBuilder spaces = new StringBuilder();
        while (spaces.length() < width - value.length()) spaces.append(' ');
        return rightJustify ? spaces + value : value + spaces;
    }

    private static IAtomContainer configureMolecule(IAtomContainer molecule) throws Exception {
        AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(molecule);
        CDKHydrogenAdder.getInstance(BUILDER).addImplicitHydrogens(molecule);
        Aromaticity.cdkLegacy().apply(molecule);
        return molecule;
    }

    private static double descriptorValue(IDescriptorResult result, int index) {
        if (result instanceof DoubleResult value) {
            return index == 0 ? value.doubleValue() : Double.NaN;
        }
        if (result instanceof IntegerResult value) {
            return index == 0 ? value.intValue() : Double.NaN;
        }
        if (result instanceof DoubleArrayResult value) {
            return index < value.length() ? value.get(index) : Double.NaN;
        }
        if (result instanceof IntegerArrayResult value) {
            return index < value.length() ? value.get(index) : Double.NaN;
        }
        if (index == 0 && result != null && result.length() == 1) {
            try {
                return Double.parseDouble(result.toString());
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static byte[] encodeDoubles(double[] values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(values.length * Double.BYTES);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (double value : values) {
                    output.writeDouble(value);
                }
            }
            return bytes.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("cannot encode descriptor vector", exception);
        }
    }

    private static byte[] fixedBitBytes(BitSet bits, int bitLength) {
        byte[] bytes = new byte[(bitLength + 7) / 8];
        for (int bit = bits.nextSetBit(0); bit >= 0 && bit < bitLength;
             bit = bits.nextSetBit(bit + 1)) {
            bytes[bit >>> 3] |= (byte) (1 << (bit & 7));
        }
        return bytes;
    }

    private static FeatureResult await(CompletionService<FeatureResult> completion)
            throws InterruptedException, ExecutionException {
        Future<FeatureResult> future = completion.take();
        return future.get();
    }

    private static boolean acquireLock(Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, limit("molclass-v3-features:" + schema, 64));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private static long generatedKey(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("database did not return a generated key");
            }
            return keys.getLong(1);
        }
    }

    private static long generatedKeyOrLastInsertId(PreparedStatement statement) throws SQLException {
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

    private static void nullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }

    private static void nullableBytes(PreparedStatement statement, int index, byte[] value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.BINARY); else statement.setBytes(index, value);
    }

    private static void nullableInteger(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER); else statement.setInt(index, value);
    }

    private static String first(String first, String second) {
        String value = trim(first);
        return value == null ? trim(second) : value;
    }

    private static String trim(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private static void validateIdentifier(String value) {
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("unsafe SQL identifier: " + value);
        }
    }

    private static String table(String schema, String name) {
        validateIdentifier(schema);
        validateIdentifier(name);
        return "`" + schema + "`.`" + name + "`";
    }

    private static byte[] sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String jsonObject(Map<String, ?> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append(jsonString(entry.getKey())).append(':').append(jsonValue(entry.getValue()));
        }
        return json.append('}').toString();
    }

    private static String jsonArrayOfObjects(List<Map<String, Object>> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) json.append(',');
            json.append(jsonObject(values.get(index)));
        }
        return json.append(']').toString();
    }

    private static String jsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return jsonString(value.toString());
    }

    private static String jsonString(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) json.append(String.format("\\u%04x", (int) character));
                    else json.append(character);
                }
            }
        }
        return json.append('\"').toString();
    }

    private static String errorCode(Throwable throwable) {
        Throwable cause = root(throwable);
        if (cause instanceof SQLException sql) {
            return limit("SQL_" + first(sql.getSQLState(), "UNKNOWN")
                    .replaceAll("[^A-Za-z0-9_]", "_"), 64);
        }
        return limit(cause.getClass().getSimpleName().replaceAll("([a-z])([A-Z])", "$1_$2")
                .toUpperCase(Locale.ROOT), 64);
    }

    private static String conciseMessage(Throwable throwable) {
        Throwable cause = root(throwable);
        return first(cause.getMessage(), cause.getClass().getSimpleName());
    }

    private static Throwable root(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause;
    }

    private static String limit(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        return value.substring(0, maximum);
    }

    private static void rollbackQuietly(Connection connection) {
        try { connection.rollback(); } catch (SQLException ignored) { }
    }
}
