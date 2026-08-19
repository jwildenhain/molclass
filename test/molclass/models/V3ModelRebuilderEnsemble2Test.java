package molclass.models;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;

import weka.attributeSelection.CfsSubsetEval;
import weka.attributeSelection.GreedyStepwise;
import weka.classifiers.Classifier;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.functions.LinearRegression;
import weka.classifiers.functions.Logistic;
import weka.classifiers.functions.MultilayerPerceptron;
import weka.classifiers.functions.SMO;
import weka.classifiers.functions.supportVector.PolyKernel;
import weka.classifiers.lazy.IBk;
import weka.classifiers.meta.AttributeSelectedClassifier;
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.meta.StackingC;
import weka.classifiers.rules.OneR;
import weka.classifiers.trees.J48;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.EuclideanDistance;
import weka.core.Instances;
import weka.core.OptionHandler;
import weka.core.neighboursearch.LinearNNSearch;
import weka.filters.Filter;
import weka.filters.MultiFilter;
import weka.filters.supervised.instance.SMOTE;
import weka.filters.supervised.instance.SpreadSubsample;

public class V3ModelRebuilderEnsemble2Test {
    @Test
    public void recoveredGraphPreservesClassesOrderAndOptions() throws Exception {
        StackingC ensemble = V3ModelRebuilder.createEnsemble2Classifier();

        assertEquals(10, ensemble.getNumFolds());
        assertEquals(1, ensemble.getSeed());
        assertEquals(1, ensemble.getNumExecutionSlots());

        Classifier[] bases = ensemble.getClassifiers();
        assertArrayEquals(new Class<?>[]{
                J48.class,
                Logistic.class,
                MultilayerPerceptron.class,
                NaiveBayes.class,
                OneR.class,
                SMO.class,
                IBk.class
        }, Arrays.stream(bases).map(Object::getClass).toArray(Class<?>[]::new));

        assertOption((OptionHandler) bases[0], "-C", "0.25");
        assertOption((OptionHandler) bases[0], "-M", "2");

        assertOption((OptionHandler) bases[1], "-R", "1.0E-8");
        assertOption((OptionHandler) bases[1], "-M", "-1");

        assertOption((OptionHandler) bases[2], "-L", "0.3");
        assertOption((OptionHandler) bases[2], "-M", "0.2");
        assertOption((OptionHandler) bases[2], "-N", "500");
        assertOption((OptionHandler) bases[2], "-V", "0");
        assertOption((OptionHandler) bases[2], "-S", "0");
        assertOption((OptionHandler) bases[2], "-E", "20");
        assertOption((OptionHandler) bases[2], "-H", "a");

        assertArrayEquals(
                new NaiveBayes().getOptions(), ((NaiveBayes) bases[3]).getOptions());
        assertOption((OptionHandler) bases[4], "-B", "6");

        SMO supportVector = (SMO) bases[5];
        assertOption(supportVector, "-C", "1.0");
        assertOption(supportVector, "-L", "0.001");
        assertOption(supportVector, "-P", "1.0E-12");
        assertOption(supportVector, "-N", "0");
        assertOption(supportVector, "-V", "-1");
        assertOption(supportVector, "-W", "1");
        assertTrue(supportVector.getKernel() instanceof PolyKernel);
        assertOption((PolyKernel) supportVector.getKernel(), "-C", "250007");
        assertOption((PolyKernel) supportVector.getKernel(), "-E", "1.0");

        IBk nearestNeighbour = (IBk) bases[6];
        assertEquals(1, nearestNeighbour.getKNN());
        assertEquals(0, nearestNeighbour.getWindowSize());
        assertTrue(nearestNeighbour.getNearestNeighbourSearchAlgorithm()
                instanceof LinearNNSearch);
        assertTrue(nearestNeighbour.getNearestNeighbourSearchAlgorithm()
                .getDistanceFunction() instanceof EuclideanDistance);
        assertOption((EuclideanDistance) nearestNeighbour
                .getNearestNeighbourSearchAlgorithm().getDistanceFunction(),
                "-R", "first-last");

        assertTrue(ensemble.getMetaClassifier() instanceof LinearRegression);
        assertOption((LinearRegression) ensemble.getMetaClassifier(), "-S", "1");
        assertOption((LinearRegression) ensemble.getMetaClassifier(), "-R", "1.0E-8");
    }

    @Test
    public void recoveredGraphTrainsInsideCommonPipeline() throws Exception {
        long seed = 20260861L;
        FilteredClassifier pipeline = V3ModelRebuilder.commonTrainingPipeline(
                V3ModelRebuilder.createEnsemble2Classifier(), "CfsSubsetEval", seed);

        assertTrue(pipeline.getFilter() instanceof MultiFilter);
        Filter[] filters = ((MultiFilter) pipeline.getFilter()).getFilters();
        assertEquals(2, filters.length);
        assertTrue(filters[0] instanceof SpreadSubsample);
        assertTrue(filters[1] instanceof SMOTE);
        assertOption((SpreadSubsample) filters[0], "-M", "5.0");
        assertOption((SpreadSubsample) filters[0], "-S", Long.toString(seed));
        assertOption((SMOTE) filters[1], "-S", Long.toString(seed));

        assertTrue(pipeline.getClassifier() instanceof AttributeSelectedClassifier);
        AttributeSelectedClassifier selected =
                (AttributeSelectedClassifier) pipeline.getClassifier();
        assertTrue(selected.getEvaluator() instanceof CfsSubsetEval);
        assertTrue(selected.getSearch() instanceof GreedyStepwise);
        assertTrue(selected.getClassifier() instanceof StackingC);

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
        Instances data = new Instances("ensemble2-contract", attributes, 40);
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

    private static void assertOption(OptionHandler handler, String flag, String expected) {
        String[] options = handler.getOptions();
        for (int index = 0; index + 1 < options.length; index++) {
            if (flag.equals(options[index])) {
                assertEquals(flag, expected, options[index + 1]);
                return;
            }
        }
        fail("Missing option " + flag + " in " + Arrays.toString(options));
    }
}
