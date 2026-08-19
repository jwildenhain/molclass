package molclass.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;

import weka.classifiers.meta.AdaBoostM1;
import weka.classifiers.meta.AttributeSelectedClassifier;
import weka.classifiers.meta.Bagging;
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.trees.J48;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.MultiFilter;
import weka.filters.supervised.instance.SMOTE;
import weka.filters.supervised.instance.SpreadSubsample;

/**
 * Coverage for the two new algorithm options ({@code Bagging}, {@code AdaBoostM1}), mirroring
 * {@link V3ModelRebuilderEnsemble2Test}'s pattern: verify the constructed classifier's shape
 * directly, then verify it trains end-to-end through the same {@code commonTrainingPipeline}
 * every other algorithm in this codebase goes through (SpreadSubsample+SMOTE, optional feature
 * selection), without needing a live database or model definition.
 */
public class V3ModelRebuilderBaggingAdaBoostTest {
    @Test
    public void baggingWrapsJ48WithExpectedTuning() {
        Bagging classifier = V3ModelRebuilder.createBaggingClassifier(4);

        assertTrue(classifier.getClassifier() instanceof J48);
        assertEquals(100, classifier.getNumIterations());
        assertEquals(1, classifier.getSeed());
        assertEquals(4, classifier.getNumExecutionSlots());
    }

    @Test
    public void adaBoostM1WrapsJ48WithExpectedTuning() {
        AdaBoostM1 classifier = V3ModelRebuilder.createAdaBoostM1Classifier();

        assertTrue(classifier.getClassifier() instanceof J48);
        assertEquals(50, classifier.getNumIterations());
        assertEquals(1, classifier.getSeed());
    }

    @Test
    public void baggingTrainsInsideCommonPipeline() throws Exception {
        assertTrainsAndPredicts(V3ModelRebuilder.createBaggingClassifier(2));
    }

    @Test
    public void adaBoostM1TrainsInsideCommonPipeline() throws Exception {
        assertTrainsAndPredicts(V3ModelRebuilder.createAdaBoostM1Classifier());
    }

    private static void assertTrainsAndPredicts(weka.classifiers.Classifier base) throws Exception {
        long seed = 20260891L;
        FilteredClassifier pipeline = V3ModelRebuilder.commonTrainingPipeline(base, "CfsSubsetEval", seed);

        assertTrue(pipeline.getFilter() instanceof MultiFilter);
        Filter[] filters = ((MultiFilter) pipeline.getFilter()).getFilters();
        assertEquals(2, filters.length);
        assertTrue(filters[0] instanceof SpreadSubsample);
        assertTrue(filters[1] instanceof SMOTE);

        assertTrue(pipeline.getClassifier() instanceof AttributeSelectedClassifier);

        Instances training = binaryTrainingData();
        pipeline.buildClassifier(training);
        for (int index = 0; index < training.numInstances(); index++) {
            double[] distribution = pipeline.distributionForInstance(training.instance(index));
            assertEquals(2, distribution.length);
            assertFalse(Double.isNaN(distribution[0]));
            assertFalse(Double.isNaN(distribution[1]));
            assertEquals(1.0, distribution[0] + distribution[1], 1.0E-9);
        }
    }

    private static Instances binaryTrainingData() {
        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("signal"));
        attributes.add(new Attribute("secondary"));
        attributes.add(new Attribute("noise"));
        attributes.add(new Attribute("class",
                new ArrayList<>(Arrays.asList("non-active", "active"))));
        Instances data = new Instances("bagging-adaboost-contract", attributes, 40);
        data.setClassIndex(attributes.size() - 1);
        for (int index = 0; index < 40; index++) {
            int label = index & 1;
            double direction = label == 0 ? -1.0 : 1.0;
            data.add(new DenseInstance(1.0, new double[]{
                    direction * (2.0 + index * 0.01),
                    direction * (1.0 + (index % 5) * 0.1),
                    (index % 7) * 0.03,
                    label
            }));
        }
        return data;
    }
}
