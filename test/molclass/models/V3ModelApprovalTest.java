package molclass.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.List;

import org.junit.Test;

public class V3ModelApprovalTest {
    @Test
    public void exactMandatoryAggregateMetricsAreAccepted() {
        V3ModelApproval.verifyMetricEvidence("HOLDOUT", 100, mandatoryMetrics(100));
    }

    @Test(expected = IllegalStateException.class)
    public void arbitrarySixMetricsAreRejected() {
        List<V3ModelApproval.MetricEvidence> evidence = mandatoryMetrics(100);
        evidence.set(5, new V3ModelApproval.MetricEvidence("ROC_AUC", 100L));
        V3ModelApproval.verifyMetricEvidence("HOLDOUT", 100, evidence);
    }

    @Test(expected = IllegalStateException.class)
    public void duplicateMetricCannotReplaceMissingMandatoryMetric() {
        List<V3ModelApproval.MetricEvidence> evidence = mandatoryMetrics(100);
        evidence.set(5, new V3ModelApproval.MetricEvidence("ACCURACY", 100L));
        V3ModelApproval.verifyMetricEvidence("HOLDOUT", 100, evidence);
    }

    @Test(expected = IllegalStateException.class)
    public void metricSupportMustMatchBuildCounter() {
        List<V3ModelApproval.MetricEvidence> evidence = mandatoryMetrics(100);
        evidence.set(0, new V3ModelApproval.MetricEvidence("ACCURACY", 99L));
        V3ModelApproval.verifyMetricEvidence("HOLDOUT", 100, evidence);
    }

    @Test
    public void requiredCrossValidationContractReturnsFoldCount() {
        assertEquals(10, V3ModelApproval.requiredCrossValidationFolds(
                "{\"crossValidation\":{\"required\":true,\"folds\":10}}"));
        assertEquals(0, V3ModelApproval.requiredCrossValidationFolds(
                "{\"crossValidation\":{\"required\":false,\"folds\":0}}"));
    }

    @Test(expected = IllegalStateException.class)
    public void requiredCrossValidationRequiresAValidFoldCount() {
        V3ModelApproval.requiredCrossValidationFolds(
                "{\"crossValidation\":{\"required\":true,\"folds\":1}}");
    }

    @Test(expected = IllegalStateException.class)
    public void nullMetricWithoutNotApplicableProvenanceIsRejected() {
        List<V3ModelApproval.MetricEvidence> evidence = mandatoryMetrics(100);
        evidence.set(5, new V3ModelApproval.MetricEvidence("WEIGHTED_AUC", 100L, null, null));
        V3ModelApproval.verifyMetricEvidence("HOLDOUT", 100, evidence);
    }

    @Test
    public void oneClassAucWithExplicitNotApplicableProvenanceIsAccepted() {
        List<V3ModelApproval.MetricEvidence> evidence = mandatoryMetrics(100);
        evidence.set(5, new V3ModelApproval.MetricEvidence("WEIGHTED_AUC", 100L, null,
                "{\"status\":\"NOT_APPLICABLE\","
                        + "\"reason\":\"NO_EVALUABLE_ONE_VS_REST_CLASS\"}"));
        V3ModelApproval.verifyMetricEvidence("HOLDOUT", 100, evidence);
    }

    @Test
    public void exactPartitionAndTotalMembershipCountsAreAccepted() {
        V3ModelApproval.verifyMembershipCounts(70, 15, 10, 5,
                new V3ModelApproval.MembershipCounts(100, 70, 15, 10, 5));
    }

    @Test(expected = IllegalStateException.class)
    public void partitionMismatchIsRejectedEvenWhenTotalMatches() {
        V3ModelApproval.verifyMembershipCounts(70, 15, 10, 5,
                new V3ModelApproval.MembershipCounts(100, 69, 16, 10, 5));
    }

    @Test(expected = IllegalStateException.class)
    public void totalMembershipMismatchIsRejected() {
        V3ModelApproval.verifyMembershipCounts(70, 15, 10, 5,
                new V3ModelApproval.MembershipCounts(99, 70, 15, 10, 5));
    }

    @Test
    public void awaitingDefinitionMayReplaceAnExistingPublishedBuild() {
        V3ModelApproval.DefinitionState state =
                new V3ModelApproval.DefinitionState("AWAITING_APPROVAL", 41L);
        V3ModelApproval.verifyPublicationState(7, state, state);
    }

    @Test(expected = IllegalStateException.class)
    public void concurrentPublicationStateChangeIsRejected() {
        V3ModelApproval.verifyPublicationState(7,
                new V3ModelApproval.DefinitionState("AWAITING_APPROVAL", 41L),
                new V3ModelApproval.DefinitionState("ACTIVE", 42L));
    }

    @Test(expected = IllegalStateException.class)
    public void secondSequentialPublicationWithoutANewBuildIsRejected() {
        V3ModelApproval.DefinitionState active =
                new V3ModelApproval.DefinitionState("ACTIVE", 42L);
        V3ModelApproval.verifyPublicationState(7, active, active);
    }

    @Test
    public void artifactPayloadIsHashedThroughABoundedBuffer() throws Exception {
        byte[] payload = new byte[V3ModelApproval.ARTIFACT_DIGEST_BUFFER_SIZE * 3 + 17];
        for (int index = 0; index < payload.length; index++) payload[index] = (byte) index;
        BoundedReadInputStream stream = new BoundedReadInputStream(payload);

        V3ModelApproval.verifyArtifactPayload(
                stream, payload.length, sha256(payload), "MODEL");

        assertTrue(stream.readCalls > 1);
        assertTrue(stream.maximumRequested <= V3ModelApproval.ARTIFACT_DIGEST_BUFFER_SIZE);
    }

    @Test(expected = IllegalStateException.class)
    public void emptyArtifactPayloadIsRejected() throws Exception {
        byte[] payload = new byte[0];
        V3ModelApproval.verifyArtifactPayload(
                new ByteArrayInputStream(payload), 1, sha256(payload), "HEADER");
    }

    @Test(expected = IllegalStateException.class)
    public void artifactPayloadSizeMustMatchStoredSize() throws Exception {
        byte[] payload = new byte[] { 1, 2, 3 };
        V3ModelApproval.verifyArtifactPayload(
                new ByteArrayInputStream(payload), 2, sha256(payload), "MODEL");
    }

    @Test(expected = IllegalStateException.class)
    public void artifactPayloadHashMustMatchStoredHash() throws Exception {
        byte[] payload = new byte[] { 1, 2, 3 };
        byte[] wrongHash = sha256(payload);
        wrongHash[0] ^= 1;
        V3ModelApproval.verifyArtifactPayload(
                new ByteArrayInputStream(payload), payload.length, wrongHash, "MODEL");
    }

    private static List<V3ModelApproval.MetricEvidence> mandatoryMetrics(long support) {
        return new java.util.ArrayList<>(List.of(
                new V3ModelApproval.MetricEvidence("ACCURACY", support),
                new V3ModelApproval.MetricEvidence("KAPPA", support),
                new V3ModelApproval.MetricEvidence("WEIGHTED_PRECISION", support),
                new V3ModelApproval.MetricEvidence("WEIGHTED_RECALL", support),
                new V3ModelApproval.MetricEvidence("WEIGHTED_F1", support),
                new V3ModelApproval.MetricEvidence("WEIGHTED_AUC", support)));
    }

    private static byte[] sha256(byte[] payload) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(payload);
    }

    private static final class BoundedReadInputStream extends ByteArrayInputStream {
        private int maximumRequested;
        private int readCalls;

        private BoundedReadInputStream(byte[] payload) {
            super(payload);
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            maximumRequested = Math.max(maximumRequested, length);
            readCalls++;
            return super.read(target, offset, length);
        }
    }
}
