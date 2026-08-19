package molclass.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;

public class V3ModelRebuilderCrossValidationTest {
    @Test
    public void supportedIndependentSplitsDoNotRequireCrossValidation() {
        assertFalse(V3ModelRebuilder.requiresCrossValidation(
                partition(10, 10), partition(10, 10)));
    }

    @Test
    public void anyUnderSupportedClassRequiresCrossValidation() {
        assertTrue(V3ModelRebuilder.requiresCrossValidation(
                partition(9, 11), partition(10, 10)));
        assertTrue(V3ModelRebuilder.requiresCrossValidation(
                partition(10, 10), partition(20, 0)));
    }

    @Test
    public void crossValidationUsesTenFoldsWhenPossible() {
        assertEquals(10, V3ModelRebuilder.crossValidationFoldCount(10));
        assertEquals(10, V3ModelRebuilder.crossValidationFoldCount(500));
        assertEquals(7, V3ModelRebuilder.crossValidationFoldCount(7));
    }

    private static Instances partition(int firstClass, int secondClass) {
        ArrayList<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("feature"));
        attributes.add(new Attribute("class", List.of("A", "B")));
        Instances data = new Instances("partition", attributes, firstClass + secondClass);
        data.setClassIndex(1);
        add(data, 0, firstClass);
        add(data, 1, secondClass);
        return data;
    }

    private static void add(Instances data, int classIndex, int count) {
        for (int index = 0; index < count; index++) {
            data.add(new DenseInstance(1.0, new double[] { index, classIndex }));
        }
    }
}
