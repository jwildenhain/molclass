package molclass.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import weka.classifiers.lazy.IBk;
import weka.classifiers.meta.CVParameterSelection;

public class KnnTuningContractTest {
    @Test
    public void productionSizedTrainingUsesEveryOddKThroughTwentyFive() throws Exception {
        KnnTuningContract.Plan plan = KnnTuningContract.forTrainingInstances(1258);

        assertEquals(10, plan.folds());
        assertEquals(1132, plan.smallestCvTrainingFold());
        assertEquals(25, plan.upper());
        assertEquals(13, plan.steps());
        assertEquals("K 1 25 13", plan.parameter());
        assertWekaAccepts(plan);
    }

    @Test
    public void tinyTrainingSetsFallBackToDirectNearestNeighbour() {
        for (int size = 1; size <= 3; size++) {
            KnnTuningContract.Plan plan = KnnTuningContract.forTrainingInstances(size);
            assertFalse(plan.tuned());
            assertEquals(1, plan.upper());
            assertEquals(1, plan.steps());
        }
    }

    @Test
    public void generatedRangesAreValidAndBoundedForSmallAndLargeSets() throws Exception {
        for (int size = 1; size <= 10_000; size++) {
            KnnTuningContract.Plan plan = KnnTuningContract.forTrainingInstances(size);
            assertTrue(plan.folds() >= 1 && plan.folds() <= 10);
            assertTrue(plan.upper() >= 1 && plan.upper() <= 25);
            assertEquals(1, plan.upper() & 1);
            if (plan.tuned()) {
                assertTrue(plan.upper() <= plan.smallestCvTrainingFold());
                assertEquals((plan.upper() + 1) / 2, plan.steps());
                assertWekaAccepts(plan);
            } else {
                assertEquals(1, plan.upper());
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroTrainingInstancesAreRejected() {
        KnnTuningContract.forTrainingInstances(0);
    }

    private static void assertWekaAccepts(KnnTuningContract.Plan plan) throws Exception {
        CVParameterSelection selection = new CVParameterSelection();
        selection.setClassifier(new IBk());
        selection.setNumFolds(plan.folds());
        selection.addCVParameter(plan.parameter());
    }
}
