package molclass.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class V3ModelRebuilderEligibilityTest {
    @Test
    public void explicitModelAllowsBoundedRetryStatuses() {
        assertTrue(V3ModelRebuilder.stillEligible("PENDING_REBUILD", true));
        assertTrue(V3ModelRebuilder.stillEligible("REBUILD_FAILED", true));
        assertTrue(V3ModelRebuilder.stillEligible("UNSUPPORTED_CONFIGURATION", true));
        assertFalse(V3ModelRebuilder.stillEligible("AWAITING_APPROVAL", true));
        assertFalse(V3ModelRebuilder.stillEligible("PUBLISHED", true));
        assertFalse(V3ModelRebuilder.stillEligible(null, true));
        assertEquals(
                "md.status IN ('PENDING_REBUILD','REBUILD_FAILED','UNSUPPORTED_CONFIGURATION')",
                V3ModelRebuilder.definitionStatusPredicate(true));
    }

    @Test
    public void allModeAllowsOnlyPendingRebuild() {
        assertTrue(V3ModelRebuilder.stillEligible("PENDING_REBUILD", false));
        assertFalse(V3ModelRebuilder.stillEligible("REBUILD_FAILED", false));
        assertFalse(V3ModelRebuilder.stillEligible("UNSUPPORTED_CONFIGURATION", false));
        assertFalse(V3ModelRebuilder.stillEligible("AWAITING_APPROVAL", false));
        assertEquals("md.status='PENDING_REBUILD'",
                V3ModelRebuilder.definitionStatusPredicate(false));
    }
}
