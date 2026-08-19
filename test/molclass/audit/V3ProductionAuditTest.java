package molclass.audit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class V3ProductionAuditTest {
    @Test
    public void completeMetricContractAppliesOnlyToPublishableBuilds() {
        assertTrue(V3ProductionAudit.requiresCompleteMetricContract("AWAITING_APPROVAL"));
        assertTrue(V3ProductionAudit.requiresCompleteMetricContract("PUBLISHED"));
        assertFalse(V3ProductionAudit.requiresCompleteMetricContract("REJECTED"));
        assertFalse(V3ProductionAudit.requiresCompleteMetricContract("SUPERSEDED"));
        assertFalse(V3ProductionAudit.requiresCompleteMetricContract(null));
    }

    @Test
    public void rejectedBuildRequiresExactlyOneMatchingRejectDecision() {
        assertFalse(V3ProductionAudit.violatesRejectedDecisionContract("REJECTED", 1, 1));

        assertTrue(V3ProductionAudit.violatesRejectedDecisionContract("REJECTED", 0, 0));
        assertTrue(V3ProductionAudit.violatesRejectedDecisionContract("REJECTED", 1, 0));
        assertTrue(V3ProductionAudit.violatesRejectedDecisionContract("REJECTED", 2, 2));

        assertFalse(V3ProductionAudit.violatesRejectedDecisionContract("PUBLISHED", 0, 0));
        assertFalse(V3ProductionAudit.violatesRejectedDecisionContract(
                "AWAITING_APPROVAL", 0, 0));
    }

    @Test
    public void supersededBuildRequiresOneLifecycleRecordAndNoApproval() {
        assertFalse(V3ProductionAudit.violatesSupersededLifecycleContract(
                "SUPERSEDED", 0, 1));

        assertTrue(V3ProductionAudit.violatesSupersededLifecycleContract(
                "SUPERSEDED", 1, 1));
        assertTrue(V3ProductionAudit.violatesSupersededLifecycleContract(
                "SUPERSEDED", 0, 0));
        assertTrue(V3ProductionAudit.violatesSupersededLifecycleContract(
                "SUPERSEDED", 0, 2));
        assertTrue(V3ProductionAudit.violatesSupersededLifecycleContract(
                "PUBLISHED", 1, 1));
        assertFalse(V3ProductionAudit.violatesSupersededLifecycleContract(
                "PUBLISHED", 1, 0));
    }
}
