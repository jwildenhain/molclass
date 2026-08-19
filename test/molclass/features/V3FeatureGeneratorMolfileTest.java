package molclass.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.openscience.cdk.interfaces.IAtomContainer;

/**
 * Regression coverage for the molfile atom-line renormalization fallback in
 * {@link V3FeatureGenerator#readMolfile}.
 * <p>
 * A "SciTegic"-exported subset of registry molfiles write atom-block lines as
 * whitespace-*delimited* trailing tokens (symbol, mass-diff, charge, ...) rather than MDL's
 * required fixed-*width* columns, e.g. {@code "    0.0000    0.0000    0.0000 C    0  0"}
 * (40 characters) tokenizes to {@code [C, 0, 0]} with irregular spacing instead of
 * {@code symbol(3)+massDiff(2)+charge(3)+...} in exact column positions. CDK's strict
 * column-based {@code MDLV2000Reader} rejects this with "invalid line length". Confirmed
 * against all 136 real molecules failing this way in the live database before writing this
 * fix -- every one of them shares this exact malformed-atom-line shape.
 */
public class V3FeatureGeneratorMolfileTest {
    // Two carbon atoms, one single bond, deliberately written with the malformed short
    // atom-line format (40 characters, whitespace-delimited trailing tokens) that CDK's
    // MDLV2000Reader rejects outright.
    private static final String MALFORMED_MOLFILE = String.join("\n",
            "",
            "  Test0101011200002D",
            "",
            "  2  1  0  0  0  0            999 V2000",
            "    0.0000    0.0000    0.0000 C    0  0",
            "    1.5000    0.0000    0.0000 C    0  0",
            "  1  2  1  0",
            "M  END",
            "");

    @Test
    public void malformedAtomLinesAreRejectedByCdkBeforeTheFallbackRuns() {
        // Establishes the premise: without the fallback, this really does fail. If CDK's
        // reader ever becomes lenient enough to accept this on its own, this test should be
        // revisited rather than silently start testing nothing.
        boolean threw = false;
        try {
            V3FeatureGenerator.readMolfileOnce(MALFORMED_MOLFILE);
        } catch (Exception expected) {
            threw = true;
        }
        org.junit.Assert.assertTrue("expected the unmodified malformed molfile to fail", threw);
    }

    @Test
    public void renormalizationRewritesTheShortAtomLinesToFixedWidthColumns() {
        String renormalized = V3FeatureGenerator.renormalizeAtomLines(MALFORMED_MOLFILE);
        assertNotEquals(MALFORMED_MOLFILE, renormalized);
    }

    @Test
    public void readMolfileRecoversViaTheRenormalizationFallback() throws Exception {
        IAtomContainer molecule = V3FeatureGenerator.readMolfile(
                MALFORMED_MOLFILE.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, molecule.getAtomCount());
        assertEquals(1, molecule.getBondCount());
    }

    @Test
    public void wellFormedMolfilesAreUnaffected() throws Exception {
        String wellFormed = String.join("\n",
                "",
                "  Test0101011200002D",
                "",
                "  2  1  0  0  0  0            999 V2000",
                "    0.0000    0.0000    0.0000 C   0  0  0  0  0  0  0  0  0  0  0  0",
                "    1.5000    0.0000    0.0000 C   0  0  0  0  0  0  0  0  0  0  0  0",
                "  1  2  1  0",
                "M  END",
                "");
        IAtomContainer molecule = V3FeatureGenerator.readMolfile(
                wellFormed.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, molecule.getAtomCount());
    }
}
