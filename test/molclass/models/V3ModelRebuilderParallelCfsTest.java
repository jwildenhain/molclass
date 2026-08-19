package molclass.models;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import weka.attributeSelection.ASEvaluation;
import weka.attributeSelection.AttributeSelection;
import weka.attributeSelection.CfsSubsetEval;
import weka.attributeSelection.GreedyStepwise;
import weka.attributeSelection.SubsetEvaluator;
import weka.classifiers.meta.AttributeSelectedClassifier;
import weka.classifiers.trees.J48;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.SparseInstance;
import weka.core.ThreadSafe;
import weka.core.Utils;

public class V3ModelRebuilderParallelCfsTest {
    private static final int[] THREAD_COUNTS = {1, 2, 8};

    @Test
    public void definition19UsesOnlyStockWekaClassesAndConfiguredCandidateSlots() {
        for (int threads : THREAD_COUNTS) {
            V3ModelRebuilder.FeatureSelection selection =
                    V3ModelRebuilder.featureSelectionForModel(
                            19L, "CfsSubsetEval", threads, true);

            assertEquals(CfsSubsetEval.class, selection.evaluator().getClass());
            assertEquals(GreedyStepwise.class, selection.search().getClass());
            assertEquals(threads,
                    ((GreedyStepwise) selection.search()).getNumExecutionSlots());
            assertEquals(V3ModelRebuilder.PARALLEL_CFS_CONTRACT,
                    selection.contractVersion());
            assertTrue(selection.manifestJson().contains(
                    "\"contractVersion\":\""
                            + V3ModelRebuilder.PARALLEL_CFS_CONTRACT + "\""));
            assertTrue(selection.manifestJson().contains(
                    "-num-slots " + threads));
        }

        V3ModelRebuilder.FeatureSelection other =
                V3ModelRebuilder.featureSelectionForModel(
                        20L, "CfsSubsetEval", 8, true);
        assertEquals(1,
                ((GreedyStepwise) other.search()).getNumExecutionSlots());
        assertEquals("CFS_SUBSET_GREEDY_V1", other.contractVersion());

        assertThrows(IllegalArgumentException.class,
                () -> V3ModelRebuilder.featureSelectionForModel(
                        19L, "CfsSubsetEval", 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> V3ModelRebuilder.featureSelectionForModel(
                        19L, "CfsSubsetEval", 65, true));
        assertThrows(UnsupportedOperationException.class,
                () -> V3ModelRebuilder.featureSelectionForModel(
                        19L, "CfsSubsetEval", 8, false));
    }

    @Test
    public void stockAndParallelSelectionsAreExactForDenseSparseTiesAndMissingValues()
            throws Exception {
        for (Instances data : List.of(denseData(), sparseData())) {
            SelectionResult stock = select(data, 0);
            for (int threads : THREAD_COUNTS) {
                SelectionResult parallel = select(data, threads);

                assertArrayEquals(stock.selected(), parallel.selected());
                assertInstancesExactly(stock.transformed(), parallel.transformed());
                assertArrayEquals(stock.evaluatorOptions(),
                        parallel.evaluatorOptions());
                assertEquals(withoutExecutionSlots(stock.searchOptions()),
                        withoutExecutionSlots(parallel.searchOptions()));
                assertEquals(threads,
                        Integer.parseInt(optionValue(
                                parallel.searchOptions(), "-num-slots")));
            }
        }
    }

    @Test
    public void serializedClassifiersRetainExactlyEquivalentDownstreamModels()
            throws Exception {
        Instances data = denseData();
        AttributeSelectedClassifier stock = roundTrip(buildClassifier(data, 0));

        for (int threads : THREAD_COUNTS) {
            AttributeSelectedClassifier parallel =
                    roundTrip(buildClassifier(data, threads));
            assertEquals(stock.getClassifier().getClass(),
                    parallel.getClassifier().getClass());
            assertEquals(stock.getClassifier().toString(),
                    parallel.getClassifier().toString());
            for (Instance instance : data) {
                assertDoublesExactly(
                        stock.distributionForInstance(instance),
                        parallel.distributionForInstance(instance));
            }
        }
    }

    @Test
    public void workerFailureCancelsAndCleansUpTheBoundedExecutor()
            throws Exception {
        FailingEvaluator evaluator = new FailingEvaluator();
        InspectableGreedyStepwise search = new InspectableGreedyStepwise();
        search.setNumExecutionSlots(8);

        Exception failure = assertThrows(Exception.class,
                () -> search.search(evaluator, failureHeader()));
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertEquals("planned candidate failure", failure.getCause().getMessage());

        ExecutorService executor = search.executor();
        assertTrue(executor.isShutdown());
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(evaluator.interruptedWorkers.get() > 0);
    }

    private static SelectionResult select(Instances source, int threads)
            throws Exception {
        CfsSubsetEval evaluator = new CfsSubsetEval();
        GreedyStepwise search = new GreedyStepwise();
        if (threads > 0) {
            search.setNumExecutionSlots(threads);
        }
        AttributeSelection selection = new AttributeSelection();
        selection.setEvaluator(evaluator);
        selection.setSearch(search);
        selection.SelectAttributes(new Instances(source));

        int[] selected = selection.selectedAttributes();
        Instances transformed = selection.reduceDimensionality(
                new Instances(source));
        AttributeSelection restored = roundTrip(selection);
        assertArrayEquals(selected, restored.selectedAttributes());
        assertInstancesExactly(transformed,
                restored.reduceDimensionality(new Instances(source)));

        return new SelectionResult(
                selected,
                transformed,
                evaluator.getOptions(),
                search.getOptions());
    }

    private static AttributeSelectedClassifier buildClassifier(
            Instances source, int threads) throws Exception {
        CfsSubsetEval evaluator = new CfsSubsetEval();
        GreedyStepwise search = new GreedyStepwise();
        if (threads > 0) {
            search.setNumExecutionSlots(threads);
        }
        AttributeSelectedClassifier classifier = new AttributeSelectedClassifier();
        classifier.setEvaluator(evaluator);
        classifier.setSearch(search);
        classifier.setClassifier(new J48());
        classifier.buildClassifier(new Instances(source));
        return classifier;
    }

    private static Instances denseData() {
        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("signal"));
        attributes.add(new Attribute("signal_tie"));
        attributes.add(new Attribute("category",
                new ArrayList<>(List.of("a", "b", "c"))));
        attributes.add(new Attribute("noise"));
        attributes.add(new Attribute("with_missing"));
        attributes.add(new Attribute("class",
                new ArrayList<>(List.of("inactive", "active"))));

        Instances data = new Instances("cfs-equivalence", attributes, 20);
        data.setClassIndex(attributes.size() - 1);
        double missing = Utils.missingValue();
        double[][] rows = {
            {0, 0, 0, 0, missing, 0},
            {1, 1, 1, 3, 2, 0},
            {0, 0, 2, 1, 1, 0},
            {2, 2, 0, 4, 0, 0},
            {1, 1, 1, 2, missing, 0},
            {2, 2, 2, 0, 2, 0},
            {0, 0, 0, 3, 1, 0},
            {1, 1, 2, 1, 0, 0},
            {2, 2, 1, 4, missing, 0},
            {0, 0, 2, 2, 2, 0},
            {8, 8, 2, 0, 7, 1},
            {9, 9, 1, 3, missing, 1},
            {10, 10, 0, 1, 8, 1},
            {8, 8, 2, 4, 9, 1},
            {11, 11, 1, 2, missing, 1},
            {9, 9, 0, 0, 7, 1},
            {10, 10, 2, 3, 8, 1},
            {11, 11, 0, 1, 9, 1},
            {8, 8, 1, 4, missing, 1},
            {10, 10, 2, 2, 7, 1}
        };
        for (double[] row : rows) {
            data.add(new DenseInstance(1.0, row));
        }
        return data;
    }

    private static Instances sparseData() {
        Instances dense = denseData();
        Instances sparse = new Instances(dense, 0);
        for (Instance instance : dense) {
            sparse.add(new SparseInstance(instance.weight(),
                    instance.toDoubleArray()));
        }
        return sparse;
    }

    private static Instances failureHeader() {
        ArrayList<Attribute> attributes = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            attributes.add(new Attribute("a" + index));
        }
        attributes.add(new Attribute("class",
                new ArrayList<>(List.of("no", "yes"))));
        Instances data = new Instances("failure", attributes, 0);
        data.setClassIndex(attributes.size() - 1);
        return data;
    }

    private static List<String> withoutExecutionSlots(String[] options) {
        ArrayList<String> semantic = new ArrayList<>();
        for (int index = 0; index < options.length; index++) {
            if ("-num-slots".equals(options[index])) {
                index++;
            } else {
                semantic.add(options[index]);
            }
        }
        return semantic;
    }

    private static String optionValue(String[] options, String option) {
        for (int index = 0; index + 1 < options.length; index++) {
            if (option.equals(options[index])) {
                return options[index + 1];
            }
        }
        throw new AssertionError("missing option " + option
                + " in " + Arrays.toString(options));
    }

    private static void assertInstancesExactly(
            Instances expected, Instances actual) {
        assertEquals(expected.relationName(), actual.relationName());
        assertTrue(expected.equalHeaders(actual));
        assertEquals(expected.classIndex(), actual.classIndex());
        assertEquals(expected.numInstances(), actual.numInstances());
        for (int row = 0; row < expected.numInstances(); row++) {
            Instance expectedInstance = expected.instance(row);
            Instance actualInstance = actual.instance(row);
            assertEquals(expectedInstance.getClass(), actualInstance.getClass());
            assertEquals(Double.doubleToRawLongBits(expectedInstance.weight()),
                    Double.doubleToRawLongBits(actualInstance.weight()));
            assertDoublesExactly(expectedInstance.toDoubleArray(),
                    actualInstance.toDoubleArray());
        }
    }

    private static void assertDoublesExactly(
            double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals("value " + index,
                    Double.doubleToRawLongBits(expected[index]),
                    Double.doubleToRawLongBits(actual[index]));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) input.readObject();
        }
    }

    private record SelectionResult(
            int[] selected,
            Instances transformed,
            String[] evaluatorOptions,
            String[] searchOptions) { }

    private static final class InspectableGreedyStepwise
            extends GreedyStepwise {
        private static final long serialVersionUID = 1L;

        ExecutorService executor() {
            return m_pool;
        }
    }

    private static final class FailingEvaluator extends ASEvaluation
            implements SubsetEvaluator, ThreadSafe {
        private static final long serialVersionUID = 1L;
        private final CountDownLatch blockerStarted = new CountDownLatch(1);
        private final AtomicInteger interruptedWorkers = new AtomicInteger();

        @Override
        public void buildEvaluator(Instances data) {
            // The failure-path test drives GreedyStepwise directly.
        }

        @Override
        public double evaluateSubset(BitSet subset) throws Exception {
            int candidate = subset.nextSetBit(0);
            if (candidate < 0) {
                return 0.0;
            }
            if (candidate == 0) {
                assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
                throw new IllegalStateException("planned candidate failure");
            }
            blockerStarted.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                return candidate;
            } catch (InterruptedException interrupted) {
                interruptedWorkers.incrementAndGet();
                throw interrupted;
            }
        }
    }
}
