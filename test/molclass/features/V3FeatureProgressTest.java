package molclass.features;

import org.junit.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class V3FeatureProgressTest {
    @Test public void reportsCompletedPropertyCalculatorsAndFingerprintsInOrder() throws Exception {
        Class<?> catalogType = Class.forName("molclass.features.V3FeatureGenerator$DescriptorCatalog");
        Method create = catalogType.getDeclaredMethod("create");
        create.setAccessible(true);
        Object catalog = create.invoke(null);
        Class<?> specType = Class.forName("molclass.features.V3FeatureGenerator$FingerprintSpec");
        Constructor<?> specConstructor = specType.getDeclaredConstructors()[0];
        specConstructor.setAccessible(true);
        Object spec = specConstructor.newInstance("MACCS", "", "");
        Class<?> definitionType = Class.forName("molclass.features.V3FeatureGenerator$FingerprintDefinition");
        Constructor<?> definitionConstructor = definitionType.getDeclaredConstructors()[0];
        definitionConstructor.setAccessible(true);
        Object definition = definitionConstructor.newInstance(spec, 1L, 166, "test", new byte[32]);
        Class<?> calculatorType = Class.forName("molclass.features.V3FeatureGenerator$FeatureCalculator");
        Constructor<?> constructor = calculatorType.getDeclaredConstructor(catalogType, List.class);
        constructor.setAccessible(true);
        Object calculator = constructor.newInstance(catalog, List.of(definition));
        Method calculate = calculatorType.getDeclaredMethod("calculate", long.class, byte[].class, String.class, Consumer.class);
        calculate.setAccessible(true);
        List<V3FeatureGenerator.FeatureProgress> events = new ArrayList<>();
        calculate.invoke(calculator, 1L, null, "CCO", (Consumer<V3FeatureGenerator.FeatureProgress>) events::add);
        assertFalse(events.isEmpty());
        var properties = events.stream().filter(p -> p.stage().equals("COMPUTE_DESCRIPTORS")).toList();
        assertEquals(0, properties.get(0).completed());
        assertTrue(properties.get(0).total() > 0);
        assertEquals(properties.get(0).total() + 1, properties.size());
        for (int i = 0; i < properties.size(); i++) assertEquals(i, properties.get(i).completed());
        var fingerprints = events.stream().filter(p -> p.stage().equals("COMPUTE_FINGERPRINTS")).toList();
        assertEquals(2, fingerprints.size());
        assertEquals(0, fingerprints.get(0).completed());
        assertEquals(1, fingerprints.get(1).completed());
        assertEquals(1, fingerprints.get(1).total());
        assertEquals("MACCS", fingerprints.get(1).detail());
        assertEquals("COMPUTE_FINGERPRINTS", events.get(events.size() - 1).stage());
    }
}
