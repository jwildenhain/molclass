package molclass.predictor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

final class V3SchemaHealthIndicatorTest {
    private static final String SCHEMA = "molclass_v3";
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

    @Test
    void startupChecksPredictorTablesWithoutLegacyDescriptorDefinition() throws Exception {
        JdbcFixture fixture = fixtureReturning(REQUIRED_TABLES);

        assertDoesNotThrow(fixture.indicator()::verifyStartupContract);

        ArgumentCaptor<String> boundValue = ArgumentCaptor.forClass(String.class);
        verify(fixture.tableStatement(), times(REQUIRED_TABLES.size() + 1))
                .setString(anyInt(), boundValue.capture());
        Set<String> checkedTables = new HashSet<>(boundValue.getAllValues());
        checkedTables.remove(SCHEMA);

        assertEquals(REQUIRED_TABLES, checkedTables);
        assertTrue(checkedTables.contains("descriptor_schema"));
        assertTrue(checkedTables.contains("molecule_descriptor_vector"));
        assertFalse(checkedTables.contains("descriptor_definition"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"descriptor_schema", "molecule_descriptor_vector"})
    void startupRejectsMissingPredictorTable(String missingTable) throws Exception {
        Set<String> availableTables = new HashSet<>(REQUIRED_TABLES);
        availableTables.remove(missingTable);
        JdbcFixture fixture = fixtureReturning(availableTables);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                fixture.indicator()::verifyStartupContract);

        assertTrue(error.getMessage().contains(missingTable));
    }

    private static JdbcFixture fixtureReturning(Set<String> availableTables) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement tableStatement = mock(PreparedStatement.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        ResultSet tableRows = mock(ResultSet.class);
        ResultSet countRow = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("information_schema.tables")))
                .thenReturn(tableStatement);
        when(connection.prepareStatement(startsWith("SELECT COUNT(*)")))
                .thenReturn(countStatement);
        when(tableStatement.executeQuery()).thenReturn(tableRows);

        Iterator<String> rows = availableTables.iterator();
        when(tableRows.next()).thenAnswer(ignored -> rows.hasNext());
        when(tableRows.getString(1)).thenAnswer(ignored -> rows.next());

        when(countStatement.executeQuery()).thenReturn(countRow);
        when(countRow.next()).thenReturn(true);
        when(countRow.getLong(1)).thenReturn(0L);

        V3SchemaHealthIndicator indicator = new V3SchemaHealthIndicator(dataSource);
        ReflectionTestUtils.setField(indicator, "schema", SCHEMA);
        ReflectionTestUtils.setField(indicator, "requireSchemaAtStartup", true);
        return new JdbcFixture(indicator, tableStatement);
    }

    private record JdbcFixture(
            V3SchemaHealthIndicator indicator,
            PreparedStatement tableStatement) {}
}
