package molclass.models;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.core.Utils;
import weka.filters.Filter;
import weka.filters.supervised.instance.SMOTE;

public class DeterministicParallelSmoteTest {
    private static final int[] THREAD_COUNTS = {1, 2, 8};
    private static final int[] SEEDS = {1, 20260833};
    private static final double[] PERCENTAGES = {50.0, 100.0, 250.0};
    private static final String[] CLASS_SELECTIONS = {"0", "first", "last", "2"};

    @Test
    public void exactlyMatchesStockForMixedNumericNominalAndMissingValues()
            throws Exception {
        assertContractMatrix(mixedDataset());
    }

    @Test
    public void exactlyMatchesStockForEqualDistanceTies() throws Exception {
        assertContractMatrix(tiedDistanceDataset());
    }

    @Test
    public void exactlyMatchesStockForEmptyInput() throws Exception {
        Instances empty = new Instances(mixedDataset(), 0);
        for (int threads : THREAD_COUNTS) {
            assertEquivalent(empty, 17, 100.0, 2, "0", threads);
        }
    }

    @Test
    public void matchesStockSmallMinorityError() {
        Instances input = singleMinorityDataset();
        Throwable stock = captureFailure(() -> apply(stock(1, 100.0, 5, "0"), input));
        for (int threads : THREAD_COUNTS) {
            Throwable parallel = captureFailure(() -> apply(
                    parallel(threads, 1, 100.0, 5, "0"), input));
            assertEquivalentFailure(stock, parallel);
        }
        assertEquals("Cannot use 0 neighbors!", stock.getMessage());
    }

    @Test
    public void matchesStockInvalidExplicitClassError() {
        Instances input = mixedDataset();
        Throwable stock = captureFailure(() -> apply(stock(1, 100.0, 2, "4"), input));
        for (int threads : THREAD_COUNTS) {
            Throwable parallel = captureFailure(() -> apply(
                    parallel(threads, 1, 100.0, 2, "4"), input));
            assertEquivalentFailure(stock, parallel);
        }
        assertEquals("value index must be <= the number of classes", stock.getMessage());
    }

    @Test
    public void workerFailureIsUnwrappedAndExecutorThreadsAreCleanedUp() {
        Instances input = relationalAttributeDataset();
        Throwable stock = captureFailure(() -> apply(stock(1, 100.0, 2, "0"), input));
        Throwable parallel = captureFailure(() -> apply(
                parallel(8, 1, 100.0, 2, "0"), input));
        assertEquals(stock.getClass(), parallel.getClass());
        assertFalse(hasParallelSmoteThread());
    }

    @Test
    public void rejectsUnboundedThreadCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeterministicParallelSmote(0));
        assertThrows(IllegalArgumentException.class,
                () -> new DeterministicParallelSmote(65));
    }

    @Test
    public void rebuilderScopesParallelFilterAndManifestByTrainingSetSize() {
        V3ModelRebuilder.SmoteExecution parallel =
                V3ModelRebuilder.smoteExecution(8_343L, 8, 5_000L);
        assertTrue(parallel.filter() instanceof DeterministicParallelSmote);
        assertEquals(8, parallel.threads());
        assertTrue(parallel.manifestJson().contains(
                DeterministicParallelSmote.IMPLEMENTATION_ID));
        assertTrue(parallel.manifestJson().contains("\"threads\":8"));

        V3ModelRebuilder.SmoteExecution stock =
                V3ModelRebuilder.smoteExecution(4_999L, 8, 5_000L);
        assertEquals(SMOTE.class, stock.filter().getClass());
        assertEquals(1, stock.threads());
        assertTrue(stock.manifestJson().contains(
                V3ModelRebuilder.STOCK_SMOTE_IMPLEMENTATION));
        assertTrue(stock.manifestJson().contains("\"threads\":1"));
    }

    @Test
    public void rebuilderUsesParallelSmoteExactlyAtTheThreshold() {
        V3ModelRebuilder.SmoteExecution atThreshold =
                V3ModelRebuilder.smoteExecution(5_000L, 4, 5_000L);
        assertTrue(atThreshold.filter() instanceof DeterministicParallelSmote);

        V3ModelRebuilder.SmoteExecution belowThreshold =
                V3ModelRebuilder.smoteExecution(4_999L, 4, 5_000L);
        assertEquals(SMOTE.class, belowThreshold.filter().getClass());
    }

    private static void assertContractMatrix(Instances input) throws Exception {
        for (int seed : SEEDS) {
            for (double percentage : PERCENTAGES) {
                for (String classSelection : CLASS_SELECTIONS) {
                    for (int threads : THREAD_COUNTS) {
                        assertEquivalent(input, seed, percentage, 2, classSelection, threads);
                    }
                }
            }
        }
    }

    private static void assertEquivalent(
            Instances input,
            int seed,
            double percentage,
            int neighbors,
            String classSelection,
            int threads) throws Exception {
        byte[] expected = apply(stock(seed, percentage, neighbors, classSelection), input);
        byte[] actual = apply(
                parallel(threads, seed, percentage, neighbors, classSelection), input);
        assertArrayEquals(
                "serialized Instances differ for threads=" + threads
                        + ", seed=" + seed
                        + ", percentage=" + percentage
                        + ", class=" + classSelection,
                expected,
                actual);
    }

    private static SMOTE stock(
            int seed, double percentage, int neighbors, String classSelection) {
        return configure(new SMOTE(), seed, percentage, neighbors, classSelection);
    }

    private static DeterministicParallelSmote parallel(
            int threads,
            int seed,
            double percentage,
            int neighbors,
            String classSelection) {
        return configure(
                new DeterministicParallelSmote(threads),
                seed,
                percentage,
                neighbors,
                classSelection);
    }

    private static <T extends SMOTE> T configure(
            T filter,
            int seed,
            double percentage,
            int neighbors,
            String classSelection) {
        filter.setRandomSeed(seed);
        filter.setPercentage(percentage);
        filter.setNearestNeighbors(neighbors);
        filter.setClassValue(classSelection);
        return filter;
    }

    private static byte[] apply(Filter filter, Instances source) throws Exception {
        Instances input = new Instances(source);
        filter.setInputFormat(input);
        Instances output = Filter.useFilter(input, filter);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream stream = new ObjectOutputStream(bytes)) {
            stream.writeObject(output);
        }
        return bytes.toByteArray();
    }

    private static Instances mixedDataset() {
        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("x"));
        attributes.add(new Attribute("y"));
        attributes.add(new Attribute("kind", Arrays.asList("red", "green", "blue")));
        attributes.add(new Attribute("class", Arrays.asList("A", "B", "C")));
        Instances data = new Instances("mixed", attributes, 13);
        data.setClassIndex(3);

        add(data, 0, 0, 0, 0);
        add(data, 2, 0, 1, 0);
        add(data, 0, 2, 2, 0);
        add(data, Utils.missingValue(), 1, 0, 0);

        add(data, 10, 10, 0, 1);
        add(data, 11, 10, 1, 1);
        add(data, 10, 11, 2, 1);
        add(data, 12, 10, 0, 1);
        add(data, 10, 12, 1, 1);
        add(data, 12, 12, 2, 1);

        add(data, -4, -4, 0, 2);
        add(data, -5, -4, Utils.missingValue(), 2);
        add(data, -4, Utils.missingValue(), 1, 2);
        return data;
    }

    private static Instances tiedDistanceDataset() {
        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("x"));
        attributes.add(new Attribute("y"));
        attributes.add(new Attribute("kind", List.of("left", "right")));
        attributes.add(new Attribute("class", List.of("minor", "major")));
        Instances data = new Instances("ties", attributes, 10);
        data.setClassIndex(3);

        add(data, 0, 0, 0, 0);
        add(data, 1, 0, 1, 0);
        add(data, -1, 0, 1, 0);
        add(data, 0, 1, 0, 0);
        add(data, 10, 10, 0, 1);
        add(data, 11, 10, 1, 1);
        add(data, 9, 10, 1, 1);
        add(data, 10, 11, 0, 1);
        add(data, 10, 9, 0, 1);
        add(data, 12, 10, 1, 1);
        return data;
    }

    private static Instances singleMinorityDataset() {
        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("x"));
        attributes.add(new Attribute("class", List.of("minor", "major")));
        Instances data = new Instances("small", attributes, 4);
        data.setClassIndex(1);
        add(data, 0, 0);
        add(data, 10, 1);
        add(data, 11, 1);
        add(data, 12, 1);
        return data;
    }

    private static Instances relationalAttributeDataset() {
        ArrayList<Attribute> payloadAttributes = new ArrayList<>();
        payloadAttributes.add(new Attribute("value"));
        Instances payloadHeader = new Instances("payload", payloadAttributes, 0);

        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("payload", payloadHeader));
        attributes.add(new Attribute("class", List.of("minor", "major")));
        Instances data = new Instances("relational", attributes, 7);
        data.setClassIndex(1);
        addRelational(data, 1_000, 0);
        addRelational(data, 2_000, 0);
        addRelational(data, 3_000, 0);
        addRelational(data, 10_000, 1);
        addRelational(data, 11_000, 1);
        addRelational(data, 12_000, 1);
        addRelational(data, 13_000, 1);
        return data;
    }

    private static void addRelational(
            Instances data, double payloadValue, double classValue) {
        Instances payload = new Instances(data.attribute(0).relation(), 1);
        payload.add(new DenseInstance(1.0, new double[]{payloadValue}));
        int relationIndex = data.attribute(0).addRelation(payload);
        add(data, relationIndex, classValue);
    }

    private static void add(Instances data, double... values) {
        data.add(new DenseInstance(1.0, values));
    }

    private static Throwable captureFailure(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable throwable) {
            return throwable;
        }
        throw new AssertionError("expected operation to fail");
    }

    private static void assertEquivalentFailure(Throwable expected, Throwable actual) {
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(expected.getMessage(), actual.getMessage());
    }

    private static boolean hasParallelSmoteThread() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive()
                        && thread.getName().startsWith(
                                DeterministicParallelSmote.THREAD_NAME_PREFIX));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
