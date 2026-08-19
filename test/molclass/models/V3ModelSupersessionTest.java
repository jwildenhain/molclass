package molclass.models;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class V3ModelSupersessionTest {
    @Test
    public void awaitingUndecidedBuildCanBeSuperseded() {
        V3ModelSupersession.verifySupersedable("AWAITING_APPROVAL", 0, 0);
    }

    @Test(expected = IllegalStateException.class)
    public void publishedBuildCannotBeSuperseded() {
        V3ModelSupersession.verifySupersedable("PUBLISHED", 1, 0);
    }

    @Test(expected = IllegalStateException.class)
    public void decidedBuildCannotBeSuperseded() {
        V3ModelSupersession.verifySupersedable("AWAITING_APPROVAL", 1, 0);
    }

    @Test(expected = IllegalStateException.class)
    public void supersessionIsImmutable() {
        V3ModelSupersession.verifySupersedable("AWAITING_APPROVAL", 0, 1);
    }

    @Test
    public void definitionReturnsToCorrectServingState() {
        assertEquals("PENDING_REBUILD",
                V3ModelSupersession.definitionStatusAfterSupersession(null));
        assertEquals("ACTIVE",
                V3ModelSupersession.definitionStatusAfterSupersession(41L));
    }
}
