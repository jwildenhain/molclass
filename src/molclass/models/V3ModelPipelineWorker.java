package molclass.models;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Supervises model-scoped feature generation and one-at-a-time v3 model rebuilds. */
public final class V3ModelPipelineWorker {
    private static final Pattern SAFE_SCHEMA = Pattern.compile("[A-Za-z0-9_]+");
    private static final Set<String> VALUE_OPTIONS = Set.of(
            "poll-seconds", "feature-timeout-minutes", "model-timeout-minutes",
            "threads", "batch-size");

    private final Config config;

    private V3ModelPipelineWorker(Config config) {
        this.config = config;
    }

    public static void main(String[] args) {
        try {
            int exitCode = new V3ModelPipelineWorker(Config.parse(args, System.getenv())).run();
            if (exitCode != 0) System.exit(exitCode);
        } catch (IllegalArgumentException exception) {
            System.err.println("Configuration error: " + exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Model pipeline worker failed: " + concise(exception));
            System.exit(4);
        }
    }

    private int run() throws Exception {
        try (Connection lockConnection = connection()) {
            String lockName = "molclass-v3-model-pipeline:" + config.schema;
            if (!namedLock(lockConnection, lockName)) {
                throw new IllegalStateException("another model pipeline worker owns " + lockName);
            }
            do {
                if (!hasPendingDefinition(lockConnection)) {
                    if (config.once) return 0;
                    sleep();
                    continue;
                }

                if (needsFeatureGeneration(lockConnection)) {
                    int featureExit = child(
                            "feature generation",
                            "molclass.features.V3FeatureGenerator",
                            List.of(
                                    "--target-schema", config.schema,
                                    "--scope", "MODEL",
                                    "--threads", Integer.toString(config.threads),
                                    "--batch-size", Integer.toString(config.batchSize)),
                            config.featureTimeoutMinutes);
                    if (featureExit != 0) {
                        System.err.println("Feature generation exited with code " + featureExit);
                        if (config.once) return featureExit;
                        sleep();
                        continue;
                    }
                }

                int modelExit = child(
                        "model rebuild",
                        "molclass.models.V3ModelRebuilder",
                        List.of("--limit", "1", "--threads", Integer.toString(config.threads)),
                        config.modelTimeoutMinutes);
                if (modelExit != 0) {
                    System.err.println("Model rebuild exited with code " + modelExit);
                    if (config.once) return modelExit;
                    sleep();
                }
                if (config.once) return 0;
            } while (true);
        }
    }

    private boolean hasPendingDefinition(Connection connection) throws SQLException {
        String sql = "SELECT EXISTS(SELECT 1 FROM " + table("model_definition")
                + " md WHERE md.status='PENDING_REBUILD' AND NOT EXISTS (SELECT 1 FROM "
                + table("model_build") + " mb WHERE mb.model_definition_id=md.model_definition_id "
                + "AND mb.status='RUNNING') LIMIT 1)";
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getBoolean(1);
        }
    }

    private boolean needsFeatureGeneration(Connection connection) throws SQLException {
        String sql = "SELECT EXISTS(SELECT 1 FROM " + table("model_definition") + " md JOIN "
                + table("dataset_molecule") + " dm ON dm.dataset_id=md.dataset_id JOIN "
                + table("feature_profile_component") + " fpc ON fpc.feature_profile_id=md.feature_profile_id "
                + "LEFT JOIN " + table("molecule_descriptor_vector") + " mdv ON "
                + "mdv.molecule_id=dm.molecule_id AND mdv.descriptor_generation_id=fpc.descriptor_generation_id "
                + "LEFT JOIN " + table("molecule_fingerprint") + " mf ON "
                + "mf.molecule_id=dm.molecule_id AND mf.fingerprint_definition_id=fpc.fingerprint_definition_id "
                + "WHERE md.status='PENDING_REBUILD' AND ((fpc.descriptor_generation_id IS NOT NULL "
                + "AND mdv.molecule_id IS NULL) OR (fpc.fingerprint_definition_id IS NOT NULL "
                + "AND mf.molecule_id IS NULL)) LIMIT 1)";
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getBoolean(1);
        }
    }

    private int child(
            String label,
            String mainClass,
            List<String> arguments,
            int timeoutMinutes) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-Dweka.core.WekaPackageManager.offline=true");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(arguments);
        System.out.println("Starting " + label + " with a " + timeoutMinutes + " minute timeout.");
        Process process = new ProcessBuilder(command).inheritIO().start();
        boolean finished;
        try {
            finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        } catch (InterruptedException exception) {
            stop(process);
            Thread.currentThread().interrupt();
            throw exception;
        }
        if (!finished) {
            System.err.println(label + " exceeded its timeout; terminating child process.");
            stop(process);
            return 124;
        }
        return process.exitValue();
    }

    private static void stop(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(20, TimeUnit.SECONDS);
        }
    }

    private void sleep() throws InterruptedException {
        Thread.sleep(Duration.ofSeconds(config.pollSeconds).toMillis());
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

    private static boolean namedLock(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
            statement.setString(1, name);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && row.getInt(1) == 1;
            }
        }
    }

    private String table(String name) {
        return "`" + config.schema + "`.`" + name + "`";
    }

    private static String concise(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private record Config(
            String jdbcUrl,
            String dbUser,
            String dbPassword,
            String schema,
            boolean once,
            int pollSeconds,
            int featureTimeoutMinutes,
            int modelTimeoutMinutes,
            int threads,
            int batchSize) {

        static Config parse(String[] args, Map<String, String> environment) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean once = false;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--once".equals(argument)) {
                    if (once) throw new IllegalArgumentException("duplicate option: --once");
                    once = true;
                    continue;
                }
                if (!argument.startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument: " + argument);
                }
                String name = argument.substring(2);
                if (!VALUE_OPTIONS.contains(name)) {
                    throw new IllegalArgumentException("unknown option: --" + name);
                }
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for --" + name);
                }
                if (values.put(name, args[++index]) != null) {
                    throw new IllegalArgumentException("duplicate option: --" + name);
                }
            }
            String jdbc = required(environment, "MOLCLASS_JDBC_URL");
            String user = required(environment, "MOLCLASS_DB_USER");
            String password = required(environment, "MOLCLASS_DB_PASSWORD");
            String schema = environment.getOrDefault("MOLCLASS_V3_SCHEMA", "molclass_v3");
            if (!SAFE_SCHEMA.matcher(schema).matches()) {
                throw new IllegalArgumentException("unsafe MOLCLASS_V3_SCHEMA");
            }
            int defaultThreads = Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors()));
            int poll = integer(values, "poll-seconds", environment, "MOLCLASS_MODEL_POLL_SECONDS", 30, 1, 3600);
            int featureTimeout = integer(values, "feature-timeout-minutes", environment,
                    "MOLCLASS_FEATURE_TIMEOUT_MINUTES", 30, 1, 1440);
            int modelTimeout = integer(values, "model-timeout-minutes", environment,
                    "MOLCLASS_MODEL_TIMEOUT_MINUTES", 60, 1, 1440);
            int threads = integer(values, "threads", environment, "MOLCLASS_MODEL_THREADS",
                    defaultThreads, 1, 64);
            int batchSize = integer(values, "batch-size", environment, "MOLCLASS_FEATURE_BATCH_SIZE",
                    200, 1, 10000);
            return new Config(jdbc, user, password, schema, once, poll,
                    featureTimeout, modelTimeout, threads, batchSize);
        }

        private static String required(Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value;
        }

        private static int integer(
                Map<String, String> values,
                String option,
                Map<String, String> environment,
                String environmentName,
                int fallback,
                int minimum,
                int maximum) {
            String raw = values.getOrDefault(option, environment.getOrDefault(
                    environmentName, Integer.toString(fallback)));
            int value;
            try {
                value = Integer.parseInt(raw);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(option + " must be an integer");
            }
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(option + " must be between " + minimum + " and " + maximum);
            }
            return value;
        }
    }
}
