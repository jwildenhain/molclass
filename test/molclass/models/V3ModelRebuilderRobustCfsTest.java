package molclass.models;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import weka.attributeSelection.AttributeSelection;
import weka.attributeSelection.CfsSubsetEval;
import weka.attributeSelection.GreedyStepwise;
import weka.classifiers.meta.AttributeSelectedClassifier;
import weka.classifiers.trees.J48;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.SparseInstance;
import weka.core.Utils;
import weka.filters.Filter;
import weka.filters.supervised.attribute.Discretize;

public class V3ModelRebuilderRobustCfsTest {
    private static final int[] THREAD_COUNTS = {1, 2, 8};
    private static final String DUPLICATE_RANGE_MESSAGE =
            "A duplicate bin range was detected. Try increasing the bin range precision.";

    @Test
    public void stockLabelsCollideButBinNumberLabelsAndRobustCfsComplete()
            throws Exception {
        Instances data = collisionData();
        Discretize stock = discretizer(false);
        stock.setInputFormat(data);
        IllegalArgumentException collision = assertThrows(
                IllegalArgumentException.class,
                () -> Filter.useFilter(data, stock));
        assertEquals(DUPLICATE_RANGE_MESSAGE, collision.getMessage());

        Discretize safe = discretizer(true);
        safe.setInputFormat(data);
        Instances discretized = Filter.useFilter(data, safe);
        assertTrue(discretized.attribute(0).isNominal());
        assertTrue(discretized.attribute(0).value(0).contains("B"));

        for (int threads : THREAD_COUNTS) {
            SelectionResult result = select(
                    data, new RobustCfsSubsetEval(), threads);
            assertTrue(result.selected().length > 1);
            assertInstancesExactly(
                    reduceOriginal(data, result.selected()), result.transformed());
        }
    }

    @Test
    public void binNumberLabelsPreserveCutPointsAndEveryRawBinAssignment()
            throws Exception {
        Instances data = wellSeparatedDiscretizationData();
        Discretize stock = discretizer(false);
        stock.setInputFormat(data);
        Instances stockOutput = Filter.useFilter(data, stock);
        Discretize safe = discretizer(true);
        safe.setInputFormat(data);
        Instances safeOutput = Filter.useFilter(data, safe);

        for (int attribute = 0; attribute < data.numAttributes(); attribute++) {
            assertRawDoublesExactly(
                    stock.getCutPoints(attribute), safe.getCutPoints(attribute));
        }
        assertEquals(stockOutput.numInstances(), safeOutput.numInstances());
        assertEquals(stockOutput.numAttributes(), safeOutput.numAttributes());
        for (int row = 0; row < stockOutput.numInstances(); row++) {
            assertDoublesExactly(stockOutput.instance(row).toDoubleArray(),
                    safeOutput.instance(row).toDoubleArray());
        }
        assertFalse(stockOutput.attribute(0).value(0).startsWith("B"));
        assertTrue(safeOutput.attribute(0).value(0).contains("B"));
    }

    @Test
    public void wrapperMatchesStockSelectionOriginalReductionAndPredictions()
            throws Exception {
        for (Instances data : List.of(mixedData(false), mixedData(true))) {
            for (int threads : THREAD_COUNTS) {
                SelectionResult stock = select(
                        data, new CfsSubsetEval(), threads);
                SelectionResult robust = select(
                        data, new RobustCfsSubsetEval(), threads);
                assertArrayEquals(stock.selected(), robust.selected());
                assertInstancesExactly(stock.transformed(), robust.transformed());

                AttributeSelectedClassifier stockModel = buildClassifier(
                        data, new CfsSubsetEval(), threads);
                AttributeSelectedClassifier robustModel = roundTrip(
                        buildClassifier(data, new RobustCfsSubsetEval(), threads));
                assertEquals(RobustCfsSubsetEval.class,
                        robustModel.getEvaluator().getClass());
                for (Instance instance : data) {
                    assertDoublesExactly(
                            stockModel.distributionForInstance(instance),
                            robustModel.distributionForInstance(instance));
                }
            }
        }
    }

    @Test
    public void cfsOptionsRemainStockAndActivationIsOnly103And104()
            throws Exception {
        CfsSubsetEval stock = new CfsSubsetEval();
        RobustCfsSubsetEval robust = new RobustCfsSubsetEval();
        assertArrayEquals(stock.getOptions(), robust.getOptions());
        String[] configured = Utils.splitOptions("-M -L -Z -P 2 -E 3 -D");
        stock.setOptions(configured.clone());
        robust.setOptions(configured.clone());
        assertArrayEquals(stock.getOptions(), robust.getOptions());

        for (long definitionId : new long[]{103L, 104L}) {
            for (int threads : THREAD_COUNTS) {
                V3ModelRebuilder.FeatureSelection selection =
                        V3ModelRebuilder.featureSelectionForModel(
                                definitionId, "CfsSubsetEval", threads, true);
                assertEquals(RobustCfsSubsetEval.class,
                        selection.evaluator().getClass());
                assertEquals(GreedyStepwise.class, selection.search().getClass());
                assertEquals(threads,
                        ((GreedyStepwise) selection.search()).getNumExecutionSlots());
                assertEquals(V3ModelRebuilder.ROBUST_CFS_CONTRACT,
                        selection.contractVersion());
                assertTrue(selection.manifestJson().contains(
                        "\"evaluationDiscretizerClass\":\""
                                + Discretize.class.getName() + "\""));
                assertTrue(selection.manifestJson().contains(
                        "\"evaluationDiscretizerOptions\":\""));
                assertTrue(selection.manifestJson().contains("-Y"));
                assertTrue(selection.manifestJson().contains("-E"));
                assertTrue(selection.algorithmContractFragment().contains(
                        V3ModelRebuilder.ROBUST_CFS_CONTRACT));
            }
        }

        for (long definitionId : new long[]{0L, 102L, 105L}) {
            V3ModelRebuilder.FeatureSelection selection =
                    V3ModelRebuilder.featureSelectionForModel(
                            definitionId, "CfsSubsetEval", 8, true);
            assertEquals(CfsSubsetEval.class, selection.evaluator().getClass());
            assertEquals(1,
                    ((GreedyStepwise) selection.search()).getNumExecutionSlots());
            assertFalse(selection.manifestJson().contains(
                    "evaluationDiscretizerOptions"));
        }
        V3ModelRebuilder.FeatureSelection definition19 =
                V3ModelRebuilder.featureSelectionForModel(
                        19L, "CfsSubsetEval", 8, true);
        assertEquals(CfsSubsetEval.class, definition19.evaluator().getClass());
        assertEquals(8,
                ((GreedyStepwise) definition19.search()).getNumExecutionSlots());

        assertThrows(UnsupportedOperationException.class,
                () -> V3ModelRebuilder.featureSelectionForModel(
                        103L, "CfsSubsetEval", 8, false));
        assertThrows(IllegalArgumentException.class,
                () -> V3ModelRebuilder.featureSelectionForModel(
                        104L, "CfsSubsetEval", 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> V3ModelRebuilder.featureSelectionForModel(
                        104L, "CfsSubsetEval", 65, true));
    }

    private static SelectionResult select(
            Instances source, CfsSubsetEval evaluator, int threads)
            throws Exception {
        GreedyStepwise search = new GreedyStepwise();
        search.setNumExecutionSlots(threads);
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
        return new SelectionResult(selected, transformed);
    }

    private static AttributeSelectedClassifier buildClassifier(
            Instances source, CfsSubsetEval evaluator, int threads)
            throws Exception {
        GreedyStepwise search = new GreedyStepwise();
        search.setNumExecutionSlots(threads);
        AttributeSelectedClassifier classifier = new AttributeSelectedClassifier();
        classifier.setEvaluator(evaluator);
        classifier.setSearch(search);
        classifier.setClassifier(new J48());
        classifier.buildClassifier(new Instances(source));
        return classifier;
    }

    private static Discretize discretizer(boolean binNumbers) {
        Discretize result = new Discretize();
        result.setUseBetterEncoding(true);
        result.setUseBinNumbers(binNumbers);
        return result;
    }

    private static Instances collisionData() {
        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("close_cut_points"));
        attributes.add(new Attribute("stable_signal"));
        attributes.add(new Attribute("class",
                new ArrayList<>(List.of("no", "yes"))));
        Instances data = new Instances("duplicate-range-collision", attributes, 40);
        data.setClassIndex(2);
        for (int group = 0; group < 4; group++) {
            for (int row = 0; row < 10; row++) {
                data.add(new DenseInstance(1.0, new double[]{
                        group * 1.0e-14,
                        group + (row % 7) * 0.001,
                        group & 1
                }));
            }
        }
        return data;
    }

    private static Instances wellSeparatedDiscretizationData() {
        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("numeric"));
        attributes.add(new Attribute("with_missing"));
        attributes.add(new Attribute("category",
                new ArrayList<>(List.of("a", "b"))));
        attributes.add(new Attribute("class",
                new ArrayList<>(List.of("no", "yes"))));
        Instances data = new Instances("cut-point-equivalence", attributes, 240);
        data.setClassIndex(3);
        for (int row = 0; row < 240; row++) {
            int group = row / 80;
            data.add(new DenseInstance(1.0, new double[]{
                    -10.0 + group * 10.0 + (row % 5) * 0.01,
                    row % 13 == 0 ? Utils.missingValue() : group * 4.0,
                    row & 1,
                    group == 1 ? 1.0 : 0.0
            }));
        }
        return data;
    }

    private static Instances mixedData(boolean sparse) {
        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("signal"));
        attributes.add(new Attribute("signal_tie"));
        attributes.add(new Attribute("category",
                new ArrayList<>(List.of("a", "b", "c"))));
        attributes.add(new Attribute("noise"));
        attributes.add(new Attribute("with_missing"));
        attributes.add(new Attribute("class",
                new ArrayList<>(List.of("inactive", "active"))));
        Instances data = new Instances(
                sparse ? "mixed-sparse" : "mixed-dense", attributes, 120);
        data.setClassIndex(5);
        for (int row = 0; row < 120; row++) {
            int label = row < 60 ? 0 : 1;
            double signal = label == 0
                    ? (row % 6) * 0.5
                    : 20.0 + (row % 6) * 0.5;
            double[] values = {
                signal,
                signal,
                row % 3,
                ((row * 17) % 31) / 31.0,
                row % 11 == 0 ? Utils.missingValue() : signal / 2.0,
                label
            };
            Instance instance = sparse
                    ? new SparseInstance(1.0, values)
                    : new DenseInstance(1.0, values);
            data.add(instance);
        }
        return data;
    }

    private static Instances reduceOriginal(Instances source, int[] selected) {
        ArrayList<Attribute> attributes = new ArrayList<>();
        for (int index : selected) {
            attributes.add((Attribute) source.attribute(index).copy());
        }
        StringBuilder selectedRange = new StringBuilder();
        for (int index : selected) {
            if (selectedRange.length() > 0) {
                selectedRange.append(",");
            }
            selectedRange.append(index + 1);
        }
        Instances result = new Instances(
                source.relationName()
                        + "-weka.filters.unsupervised.attribute.Remove-V-R"
                        + selectedRange,
                attributes, source.numInstances());
        result.setClassIndex(selected.length - 1);
        for (Instance instance : source) {
            double[] values = new double[selected.length];
            for (int index = 0; index < selected.length; index++) {
                values[index] = instance.value(selected[index]);
            }
            result.add(instance instanceof SparseInstance
                    ? new SparseInstance(instance.weight(), values)
                    : new DenseInstance(instance.weight(), values));
        }
        return result;
    }

    private static void assertInstancesExactly(
            Instances expected, Instances actual) {
        assertEquals(expected.relationName(), actual.relationName());
        assertTrue(expected.equalHeaders(actual));
        assertEquals(expected.classIndex(), actual.classIndex());
        assertEquals(expected.numInstances(), actual.numInstances());
        for (int row = 0; row < expected.numInstances(); row++) {
            assertEquals(expected.instance(row).getClass(),
                    actual.instance(row).getClass());
            assertEquals(Double.doubleToRawLongBits(expected.instance(row).weight()),
                    Double.doubleToRawLongBits(actual.instance(row).weight()));
            assertDoublesExactly(expected.instance(row).toDoubleArray(),
                    actual.instance(row).toDoubleArray());
        }
    }

    private static void assertRawDoublesExactly(
            double[] expected, double[] actual) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual);
            return;
        }
        assertDoublesExactly(expected, actual);
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

    private record SelectionResult(int[] selected, Instances transformed) { }
}
