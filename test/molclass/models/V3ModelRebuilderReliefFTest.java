package molclass.models;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import weka.attributeSelection.AttributeSelection;
import weka.attributeSelection.Ranker;
import weka.attributeSelection.ReliefFAttributeEval;
import weka.classifiers.meta.AttributeSelectedClassifier;
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.MultiFilter;
import weka.filters.supervised.instance.SMOTE;
import weka.filters.supervised.instance.SpreadSubsample;

public class V3ModelRebuilderReliefFTest {
    @Test
    public void recoveredGraphUsesExplicitRankAllOptionsAndProvenance() {
        FilteredClassifier pipeline = V3ModelRebuilder.commonTrainingPipeline(
                new RandomForest(), "ReliefFAttributeEval", 20260930L);

        assertTrue(pipeline.getFilter() instanceof MultiFilter);
        Filter[] filters = ((MultiFilter) pipeline.getFilter()).getFilters();
        assertEquals(2, filters.length);
        assertTrue(filters[0] instanceof SpreadSubsample);
        assertTrue(filters[1] instanceof SMOTE);
        assertTrue(pipeline.getClassifier() instanceof AttributeSelectedClassifier);

        AttributeSelectedClassifier selected =
                (AttributeSelectedClassifier) pipeline.getClassifier();
        assertTrue(selected.getEvaluator() instanceof ReliefFAttributeEval);
        assertTrue(selected.getSearch() instanceof Ranker);
        assertTrue(selected.getClassifier() instanceof RandomForest);

        ReliefFAttributeEval evaluator = (ReliefFAttributeEval) selected.getEvaluator();
        assertEquals(-1, evaluator.getSampleSize());
        assertEquals(1, evaluator.getSeed());
        assertEquals(10, evaluator.getNumNeighbours());
        assertFalse(evaluator.getWeightByDistance());
        assertEquals(2, evaluator.getSigma());

        Ranker ranker = (Ranker) selected.getSearch();
        assertEquals("", ranker.getStartSet());
        assertEquals(-Double.MAX_VALUE, ranker.getThreshold(), 0.0);
        assertEquals(-1, ranker.getNumToSelect());
        assertTrue(ranker.getGenerateRanking());

        V3ModelRebuilder.FeatureSelection contract =
                V3ModelRebuilder.featureSelection("ReliefFAttributeEval");
        assertEquals("RELIEFF_RANK_ALL_V1", contract.contractVersion());
        assertEquals("weka.attributeSelection.ReliefFAttributeEval", contract.evaluatorClass());
        assertEquals("-M -1 -D 1 -K 10", contract.evaluatorOptions());
        assertEquals("weka.attributeSelection.Ranker", contract.searchClass());
        assertEquals("-T -1.7976931348623157E308 -N -1", contract.searchOptions());
        assertTrue(contract.algorithmContractFragment().contains("RELIEFF_RANK_ALL_V1"));
        assertTrue(contract.algorithmContractFragment().contains(contract.evaluatorOptions()));
        assertTrue(contract.algorithmContractFragment().contains(contract.searchOptions()));
        assertTrue(contract.manifestJson().contains(
                "\"contractVersion\":\"RELIEFF_RANK_ALL_V1\""));
        assertTrue(contract.manifestJson().contains(
                "\"evaluatorOptions\":\"-M -1 -D 1 -K 10\""));
        assertTrue(contract.manifestJson().contains(
                "\"searchOptions\":\"-T -1.7976931348623157E308 -N -1\""));
    }

    @Test
    public void rankingIsDeterministicAndRetainsEveryNonClassFeature() throws Exception {
        Instances data = trainingData();
        SelectionResult first = select(data);
        SelectionResult second = select(data);

        assertArrayEquals(first.selected(), second.selected());
        assertEquals(data.numAttributes(), first.selected().length);
        int[] sorted = first.selected().clone();
        Arrays.sort(sorted);
        assertArrayEquals(new int[]{0, 1, 2, 3}, sorted);

        assertEquals(data.numAttributes() - 1, first.ranking().length);
        assertEquals(first.ranking().length, second.ranking().length);
        for (int index = 0; index < first.ranking().length; index++) {
            assertArrayEquals(first.ranking()[index], second.ranking()[index], 0.0);
            if (index > 0) {
                assertTrue(first.ranking()[index - 1][1] >= first.ranking()[index][1]);
            }
        }
    }

    @Test
    public void unknownFeatureSelectionIsUnsupportedInsteadOfFallingBackToCfs() {
        try {
            V3ModelRebuilder.commonTrainingPipeline(
                    new RandomForest(), "FutureSelector", 1L);
            fail("Unknown feature selection should be unsupported");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("FutureSelector"));
        }
    }

    @Test
    public void missingDeclaredLabelsDeriveStableSortedObservedLabels() {
        assertEquals(
                List.of("Accumulates", "none"),
                V3ModelRebuilder.resolveClassLabels(
                        "[]", List.of("none", " Accumulates ", "none")));
        assertEquals(
                List.of("Mutual", "Proliferation"),
                V3ModelRebuilder.resolveClassLabels(
                        "[\"Mutual\",\"Proliferation\"]",
                        List.of("Proliferation", "Mutual")));
    }

    private static SelectionResult select(Instances data) throws Exception {
        AttributeSelection selection = new AttributeSelection();
        selection.setEvaluator(V3ModelRebuilder.createReliefFAttributeEvaluator());
        selection.setSearch(V3ModelRebuilder.createReliefFRanker());
        selection.SelectAttributes(data);
        return new SelectionResult(selection.selectedAttributes(), selection.rankedAttributes());
    }

    private static Instances trainingData() {
        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("signal"));
        attributes.add(new Attribute("secondary"));
        attributes.add(new Attribute("noise"));
        attributes.add(new Attribute("class",
                new ArrayList<>(Arrays.asList("non-active", "active"))));
        Instances data = new Instances("relieff-rank-all-contract", attributes, 60);
        data.setClassIndex(attributes.size() - 1);
        for (int index = 0; index < 60; index++) {
            int label = index & 1;
            double direction = label == 0 ? -1.0 : 1.0;
            data.add(new DenseInstance(1.0, new double[]{
                    direction * (3.0 + index * 0.01),
                    direction * (1.0 + (index % 5) * 0.05),
                    ((index * 17) % 29) / 29.0,
                    label
            }));
        }
        return data;
    }

    private record SelectionResult(int[] selected, double[][] ranking) { }
}
