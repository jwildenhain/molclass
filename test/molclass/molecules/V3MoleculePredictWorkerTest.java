package molclass.molecules;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class V3MoleculePredictWorkerTest {
    @Test
    public void missingModelsAreMatchedByCurrentPublishedBuild() {
        Map<Long, Long> published = new LinkedHashMap<>();
        published.put(11L, 101L);
        published.put(12L, 202L);
        published.put(13L, 303L);

        // Build 100 is an older build of definition 11 and must not suppress a
        // prediction against newly published build 101.
        Set<Long> predictedBuilds = Set.of(100L, 202L, 999L);

        assertEquals(
                List.of(11L, 13L),
                V3MoleculePredictWorker.missingModelDefinitionIds(published, predictedBuilds));
    }
}
