package molclass.predictor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

@Component("molclassV3Schema")
public final class V3SchemaHealthIndicator implements HealthIndicator {
    private static final Pattern SAFE_SCHEMA = Pattern.compile("[A-Za-z0-9_]+");
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "dataset_molecule",
            "descriptor_schema",
            "feature_profile",
            "feature_profile_component",
            "fingerprint_definition",
            "model_artifact",
            "model_build",
            "model_definition",
            "model_evaluation",
            "molecule",
            "molecule_descriptor_vector",
            "molecule_fingerprint");

    private final DataSource dataSource;

    @Value("${molclass.v3.schema:molclass_v3}")
    private String schema;

    @Value("${molclass.v3.require-schema-at-startup:true}")
    private boolean requireSchemaAtStartup;

    public V3SchemaHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void verifyStartupContract() {
        validateSchemaName();
        if (!requireSchemaAtStartup) return;
        try {
            SchemaState state = inspect();
            if (!state.missingTables().isEmpty()) {
                throw new IllegalStateException(
                        "v3 schema is missing required tables: " + state.missingTables());
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("cannot verify the v3 schema at startup", exception);
        }
    }

    @Override
    public Health health() {
        try {
            SchemaState state = inspect();
            if (!state.missingTables().isEmpty()) {
                return Health.status(Status.OUT_OF_SERVICE)
                        .withDetail("schema", schema)
                        .withDetail("missingTableCount", state.missingTables().size())
                        .build();
            }
            return Health.up()
                    .withDetail("schema", schema)
                    .withDetail("publishedModels", state.publishedModels())
                    .build();
        } catch (SQLException exception) {
            return Health.down(exception).withDetail("schema", schema).build();
        }
    }

    private SchemaState inspect() throws SQLException {
        validateSchemaName();
        TreeSet<String> missing = new TreeSet<>(REQUIRED_TABLES);
        String placeholders = String.join(",", REQUIRED_TABLES.stream().map(ignored -> "?").toList());
        String tableSql = "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema=? AND table_name IN (" + placeholders + ")";
        long publishedModels = 0;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(tableSql)) {
            statement.setString(1, schema);
            int index = 2;
            for (String table : REQUIRED_TABLES) statement.setString(index++, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) missing.remove(rows.getString(1));
            }
            if (missing.isEmpty()) publishedModels = countPublishedModels(connection);
        }
        return new SchemaState(new ArrayList<>(missing), publishedModels);
    }

    private long countPublishedModels(Connection connection) throws SQLException {
        String sql = "SELECT COUNT(*) FROM `" + schema + "`.`model_definition` md "
                + "JOIN `" + schema + "`.`model_build` mb "
                + "ON mb.model_build_id=md.published_model_build_id "
                + "WHERE md.status='ACTIVE' AND mb.status='PUBLISHED'";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private void validateSchemaName() {
        if (schema == null || !SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("unsafe v3 schema name");
        }
    }

    private record SchemaState(List<String> missingTables, long publishedModels) {}
}
