package molclass.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import molclass.models.V3ModelRebuilder.PartitionAssignment;
import molclass.models.V3ModelRebuilder.PartitionCandidate;

/**
 * Unit coverage for {@link V3ModelRebuilder#assignPartitions}, the pure greedy scaffold-group
 * assignment step behind {@code --split-strategy SCAFFOLD}. Exercised directly against small
 * in-memory lists -- no database, no Weka {@code Instances} -- the same separation already
 * used for {@link V3ModelRebuilder#requiresCrossValidation}.
 */
public class V3ModelRebuilderScaffoldSplitTest {

    @Test
    public void groupsAreNeverSplitAcrossPartitions() {
        // Three scaffold groups of size 4/3/3. Whichever partition a group's first id lands
        // in, every other id sharing that group key must land in the same partition -- that
        // is the entire point of grouping by scaffold: no leakage across the split.
        List<PartitionCandidate> candidates = new ArrayList<>();
        addGroup(candidates, "A", 4, 100);
        addGroup(candidates, "B", 3, 200);
        addGroup(candidates, "C", 3, 300);

        PartitionAssignment assignment = V3ModelRebuilder.assignPartitions(candidates, 3, 3);

        assertGroupNeverSplit(candidates, "A", assignment);
        assertGroupNeverSplit(candidates, "B", assignment);
        assertGroupNeverSplit(candidates, "C", assignment);
    }

    @Test
    public void largestGroupFillsValidationFirst() {
        // DeepChem-style ordering: largest scaffold group placed first. A group of 5 should
        // satisfy a validationCount of 3 by itself, landing entirely in VALIDATION.
        List<PartitionCandidate> candidates = new ArrayList<>();
        addGroup(candidates, "big", 5, 1);
        addGroup(candidates, "small1", 1, 2);
        addGroup(candidates, "small2", 1, 3);

        PartitionAssignment assignment = V3ModelRebuilder.assignPartitions(candidates, 3, 2);

        assertEquals(5, assignment.validationIds().size());
        assertGroupNeverSplit(candidates, "big", assignment);
    }

    @Test
    public void singleDominantScaffoldOvershootsRatherThanSplitting() {
        // A scaffold group larger than the validation target is still assigned whole -- an
        // imperfect 80/10/10 ratio is the accepted cost of never leaking a scaffold, matching
        // DeepChem's ScaffoldSplitter reference behavior.
        List<PartitionCandidate> candidates = new ArrayList<>();
        addGroup(candidates, "dominant", 20, 1);
        addGroup(candidates, "s1", 1, 2);
        addGroup(candidates, "s2", 1, 3);
        addGroup(candidates, "s3", 1, 4);

        PartitionAssignment assignment = V3ModelRebuilder.assignPartitions(candidates, 2, 2);

        assertGroupNeverSplit(candidates, "dominant", assignment);
        // The dominant group alone (20) overshoots the validationCount target of 2.
        assertTrue(assignment.validationIds().size() >= 20);
    }

    @Test
    public void allSingletonGroupsHitExactTargetsLikePerInstanceAssignment() {
        // When every id is its own group (e.g. every molecule is acyclic / unscaffoldable),
        // whole-group assignment degrades to exact per-instance precision, same as today's
        // HASH split -- confirming the new code path doesn't regress this common case.
        List<PartitionCandidate> candidates = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            candidates.add(new PartitionCandidate(id, "M:" + id, id));
        }

        PartitionAssignment assignment = V3ModelRebuilder.assignPartitions(candidates, 2, 3);

        assertEquals(2, assignment.validationIds().size());
        assertEquals(3, assignment.holdoutIds().size());
    }

    @Test
    public void zeroTargetsPutEverythingInTrainByExclusion() {
        List<PartitionCandidate> candidates = new ArrayList<>();
        addGroup(candidates, "only", 6, 1);

        PartitionAssignment assignment = V3ModelRebuilder.assignPartitions(candidates, 0, 0);

        assertTrue(assignment.validationIds().isEmpty());
        assertTrue(assignment.holdoutIds().isEmpty());
    }

    @Test
    public void tieBreaksBySmallestScoreForDeterminism() {
        // Two same-size groups: the one containing the smaller (unsigned) score sorts first
        // and is placed into validation first.
        List<PartitionCandidate> candidates = new ArrayList<>();
        candidates.add(new PartitionCandidate(1, "lowScore", 10));
        candidates.add(new PartitionCandidate(2, "highScore", 999));

        PartitionAssignment assignment = V3ModelRebuilder.assignPartitions(candidates, 1, 1);

        assertTrue(assignment.validationIds().contains(1L));
        assertTrue(assignment.holdoutIds().contains(2L));
    }

    @Test
    public void resultIsDeterministicAcrossRepeatedCalls() {
        List<PartitionCandidate> candidates = new ArrayList<>();
        addGroup(candidates, "A", 4, 55);
        addGroup(candidates, "B", 3, 12);
        addGroup(candidates, "C", 2, 900);
        addGroup(candidates, "D", 1, 4);

        PartitionAssignment first = V3ModelRebuilder.assignPartitions(candidates, 3, 2);
        PartitionAssignment second = V3ModelRebuilder.assignPartitions(new ArrayList<>(candidates), 3, 2);

        assertEquals(first.validationIds(), second.validationIds());
        assertEquals(first.holdoutIds(), second.holdoutIds());
    }

    private static void addGroup(List<PartitionCandidate> candidates, String groupKey, int size, long baseScore) {
        for (int index = 0; index < size; index++) {
            long id = candidates.size() + 1000L;
            candidates.add(new PartitionCandidate(id, groupKey, baseScore + index));
        }
    }

    private static void assertGroupNeverSplit(
            List<PartitionCandidate> candidates, String groupKey, PartitionAssignment assignment) {
        boolean anyInValidation = false, anyInHoldout = false, anyInTrain = false;
        for (PartitionCandidate candidate : candidates) {
            if (!candidate.groupKey().equals(groupKey)) continue;
            if (assignment.validationIds().contains(candidate.id())) anyInValidation = true;
            else if (assignment.holdoutIds().contains(candidate.id())) anyInHoldout = true;
            else anyInTrain = true;
        }
        int partitionsTouched = (anyInValidation ? 1 : 0) + (anyInHoldout ? 1 : 0) + (anyInTrain ? 1 : 0);
        assertEquals("group '" + groupKey + "' must land entirely in one partition", 1, partitionsTouched);
    }
}
