package molclass.audit;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Writes a read-only Markdown audit of sealed v3 feature-profile contracts. */
public final class V3FeatureCoverageAudit {
    private V3FeatureCoverageAudit() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        List<Profile> profiles = new ArrayList<>();
        long capturedBuilds;
        long legacyBuilds;
        try (Connection connection = DriverManager.getConnection(
                config.jdbcUrl, config.user, config.password)) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION time_zone = '+00:00'");
            }
            String sql = "SELECT fp.feature_profile_id,fp.profile_code,fp.profile_version,"
                    + "fp.status,HEX(fp.contract_sha256),fp.raw_feature_count,"
                    + "fp.required_molecule_count,fp.ready_molecule_count,"
                    + "fp.failed_molecule_count,fp.missing_molecule_count,"
                    + "fp.readiness_checked_at,fp.superseded_by_feature_profile_id,"
                    + "COUNT(DISTINCT md.model_definition_id),"
                    + "COUNT(DISTINCT CASE WHEN mb.status='COMPLETE' THEN mb.model_build_id END),"
                    + "COUNT(DISTINCT CASE WHEN mb.status='PUBLISHED' THEN mb.model_build_id END)"
                    + " FROM " + table(config.schema, "feature_profile") + " fp LEFT JOIN "
                    + table(config.schema, "model_definition")
                    + " md ON md.feature_profile_id=fp.feature_profile_id LEFT JOIN "
                    + table(config.schema, "model_build")
                    + " mb ON mb.model_definition_id=md.model_definition_id"
                    + " GROUP BY fp.feature_profile_id,fp.profile_code,fp.profile_version,fp.status,"
                    + "fp.contract_sha256,fp.raw_feature_count,fp.required_molecule_count,"
                    + "fp.ready_molecule_count,fp.failed_molecule_count,fp.missing_molecule_count,"
                    + "fp.readiness_checked_at,fp.superseded_by_feature_profile_id"
                    + " ORDER BY fp.profile_code,fp.feature_profile_id";
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    profiles.add(new Profile(rows.getLong(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5), nullableLong(rows, 6),
                            nullableLong(rows, 7), nullableLong(rows, 8), nullableLong(rows, 9),
                            nullableLong(rows, 10), rows.getString(11), nullableLong(rows, 12),
                            rows.getLong(13), rows.getLong(14), rows.getLong(15)));
                }
            }
            String buildSql = "SELECT SUM(feature_contract_capture='CAPTURED_AT_BUILD'),"
                    + " SUM(feature_contract_capture='LEGACY_NOT_CAPTURED') FROM "
                    + table(config.schema, "model_build");
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(buildSql)) {
                rows.next();
                capturedBuilds = rows.getLong(1);
                legacyBuilds = rows.getLong(2);
            }
        }

        PrintWriter output = config.output == null
                ? new PrintWriter(System.out, true, StandardCharsets.UTF_8)
                : new PrintWriter(Files.newBufferedWriter(config.output, StandardCharsets.UTF_8));
        try (output) {
            output.println("# MolClass v3 Feature Contract Coverage Audit");
            output.println();
            output.println("Generated: `" + Instant.now() + "`");
            output.println();
            output.println("Schema: `" + config.schema + "`");
            output.println();
            output.println("- Builds with an exact build-time contract snapshot: " + capturedBuilds);
            output.println("- Earlier builds explicitly marked `LEGACY_NOT_CAPTURED`: " + legacyBuilds);
            output.println();
            output.println("| Profile | ID | Version | State | Raw features | Ready / required | Failed | Missing | Contract SHA-256 | Definitions | Complete | Published | Superseded by |");
            output.println("|---|---:|---|---|---:|---:|---:|---:|---|---:|---:|---:|---:|");
            for (Profile profile : profiles) {
                output.printf(Locale.ROOT,
                        "| %s | %d | `%s` | %s | %s | %s / %s | %s | %s | `%s` | %d | %d | %d | %s |%n",
                        profile.code, profile.id, profile.version, profile.status,
                        value(profile.rawFeatures), value(profile.ready), value(profile.required),
                        value(profile.failed), value(profile.missing), value(profile.hash),
                        profile.definitions, profile.completeBuilds, profile.publishedBuilds,
                        value(profile.supersededBy));
            }
            output.println();
            output.println("A `READY_WITH_EXCLUSIONS` profile is executable, but failed molecules remain excluded with persisted provenance. Null coverage means the immutable-contract migration was applied but that revision has not yet passed through the profile-specific readiness calculation.");
        }
    }

    private static Long nullableLong(ResultSet rows, int column) throws Exception {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static String value(Object value) {
        return value == null ? "n/a" : value.toString();
    }

    private static String table(String schema, String name) {
        return "`" + schema + "`.`" + name + "`";
    }

    private record Profile(long id, String code, String version, String status, String hash,
                           Long rawFeatures, Long required, Long ready, Long failed, Long missing,
                           String checkedAt, Long supersededBy, long definitions,
                           long completeBuilds, long publishedBuilds) {
    }

    private record Config(String jdbcUrl, String schema, String user, String password, Path output) {
        private static Config parse(String[] args) {
            String jdbcUrl = env("MOLCLASS_JDBC_URL", "jdbc:mysql://127.0.0.1:3306/");
            String schema = env("MOLCLASS_V3_SCHEMA", "molclass_v3");
            String user = System.getenv("MOLCLASS_DB_USER");
            String password = System.getenv("MOLCLASS_DB_PASSWORD");
            Path output = null;
            for (int i = 0; i < args.length; i++) {
                if ("--output".equals(args[i]) && ++i < args.length) output = Path.of(args[i]);
                else if ("--schema".equals(args[i]) && ++i < args.length) schema = args[i];
                else throw new IllegalArgumentException("unknown or incomplete argument: " + args[i]);
            }
            if (!schema.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("unsafe schema");
            if (user == null || user.isBlank() || password == null) {
                throw new IllegalArgumentException("MOLCLASS_DB_USER and MOLCLASS_DB_PASSWORD are required");
            }
            return new Config(jdbcUrl, schema, user, password, output);
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
