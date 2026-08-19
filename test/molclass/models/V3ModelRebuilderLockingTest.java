package molclass.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class V3ModelRebuilderLockingTest {
    @Test
    public void lockNameIsDefinitionScopedAndReadableWhenItFits() {
        assertEquals(
                "molclass-v3-model-rebuild:molclass_v3:47",
                V3ModelRebuilder.definitionLockName("molclass_v3", 47));
        assertFalse(V3ModelRebuilder.definitionLockName("molclass_v3", 47)
                .equals(V3ModelRebuilder.definitionLockName("molclass_v3", 48)));
        assertFalse(V3ModelRebuilder.definitionLockName("molclass_v3", 47)
                .equals(V3ModelRebuilder.definitionLockName("another_schema", 47)));
    }

    @Test
    public void longSchemaLockNamesRemainStableAndWithinMariaDbLimit() {
        String schema = "s".repeat(64);
        String first = V3ModelRebuilder.definitionLockName(schema, Long.MAX_VALUE);
        String repeated = V3ModelRebuilder.definitionLockName(schema, Long.MAX_VALUE);
        String otherDefinition = V3ModelRebuilder.definitionLockName(schema, Long.MAX_VALUE - 1);

        assertEquals(first, repeated);
        assertTrue(first.length() <= 64);
        assertFalse(first.equals(otherDefinition));
    }

    @Test
    public void interruptedRecoveryStatementsAreAllDefinitionScoped() {
        List<String> statements = V3ModelRebuilder.interruptedRecoverySql("molclass_v3");

        assertEquals(4, statements.size());
        for (String sql : statements) {
            assertTrue(sql, sql.contains("model_definition_id=?"));
            assertEquals(sql, 1, occurrences(sql, '?'));
            assertTrue(sql, sql.contains("`molclass_v3`."));
            assertTrue(sql, sql.contains("status='RUNNING'"));
        }
    }

    @Test
    public void postLockStatusRejectsAStalePendingSnapshot() {
        assertTrue(V3ModelRebuilder.stillEligible("PENDING_REBUILD", false));

        assertFalse(V3ModelRebuilder.eligibleAfterDefinitionLock(
                "AWAITING_APPROVAL", false));
        assertFalse(V3ModelRebuilder.eligibleAfterDefinitionLock(
                "PUBLISHED", false));
    }

    @Test
    public void explicitPostLockStatusRetainsBoundedRetryContract() {
        assertTrue(V3ModelRebuilder.eligibleAfterDefinitionLock(
                "REBUILD_FAILED", true));
        assertTrue(V3ModelRebuilder.eligibleAfterDefinitionLock(
                "UNSUPPORTED_CONFIGURATION", true));
        assertFalse(V3ModelRebuilder.eligibleAfterDefinitionLock(
                "UNSUPPORTED_CONFIGURATION", false));
        assertEquals(
                "model definition 47 is already active in another rebuild worker",
                V3ModelRebuilder.activeDefinitionMessage(47));
    }

    private static int occurrences(String value, char expected) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == expected) {
                count++;
            }
        }
        return count;
    }
}
