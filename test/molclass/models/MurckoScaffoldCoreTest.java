package molclass.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.aromaticity.Aromaticity;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.tools.CDKHydrogenAdder;
import org.openscience.cdk.tools.manipulator.AtomContainerManipulator;

/**
 * Ground-truth checks for {@link MurckoScaffoldCore#frameworkSmilesFor}, exercised without a
 * database (the method takes an already-parsed molecule).
 * <p>
 * The point of a Bemis-Murcko scaffold is clustering: differently substituted molecules that
 * share a ring system must reduce to the identical framework, or scaffold_definition's
 * sha256-based deduplication silently stops working. That is what these tests check for --
 * ring-size/linker structure and cross-molecule scaffold equality, not memorized exact CDK
 * SMILES strings (which are an implementation detail of CDK's canonicalizer, not a property
 * this code should be pinned to).
 */
public class MurckoScaffoldCoreTest {
    private static final IChemObjectBuilder BUILDER = DefaultChemObjectBuilder.getInstance();
    private final MurckoScaffoldCore core = new MurckoScaffoldCore("molclass_v3");

    @Test
    public void differentlySubstitutedBenzenesShareTheSameScaffold() throws Exception {
        // This is the whole point of a Murcko scaffold: unrelated substituents on the same
        // ring collapse to one framework. Before a fix, this failed for every molecule beyond
        // the first because MurckoFragmenter.scaffold() leaves stale atom-type/hydrogen state
        // at former substituent attachment points, so molecules with a different *number* of
        // substituents canonicalized to different SMILES for the identical ring.
        String toluene = scaffoldOf("Cc1ccccc1");
        String benzoicAcid = scaffoldOf("OC(=O)c1ccccc1");
        String aniline = scaffoldOf("Nc1ccccc1");
        String ibuprofen = scaffoldOf("CC(C)Cc1ccc(cc1)C(C)C(=O)O"); // two substituents on the ring
        String aspirin = scaffoldOf("CC(=O)Oc1ccccc1C(=O)O"); // two substituents, different ring positions

        assertEquals(toluene, benzoicAcid);
        assertEquals(toluene, aniline);
        assertEquals(toluene, ibuprofen);
        assertEquals(toluene, aspirin);
    }

    @Test
    public void acyclicMoleculeHasNoScaffold() throws Exception {
        assertNull(scaffoldOf("CCO"));
        assertNull(scaffoldOf("CCCCCC"));
    }

    @Test
    public void linkerBetweenTwoRingSystemsIsPreserved() throws Exception {
        // The framework must keep the connecting atoms between ring systems, not just the
        // rings in isolation -- otherwise it is a ring-perception tool, not a Murcko fragmenter.
        String diphenylmethaneScaffold = scaffoldOf("c1ccccc1Cc2ccccc2");
        String benzeneScaffold = scaffoldOf("Cc1ccccc1");

        assertNotEquals(benzeneScaffold, diphenylmethaneScaffold);
        IAtomContainer parsed = new SmilesParser(BUILDER).parseSmiles(diphenylmethaneScaffold);
        assertTrue(parsed.getAtomCount() > 10);
    }

    @Test
    public void purineCoreIsSharedAcrossMethylationPatterns() throws Exception {
        // Caffeine and theophylline are both trimethyl/dimethylxanthines that differ only in
        // *which* ring nitrogens carry a methyl -- not in the ring system itself -- so their
        // extracted purine cores must be identical.
        String caffeine = scaffoldOf("Cn1cnc2c1c(=O)n(C)c(=O)n2C");
        String theophylline = scaffoldOf("Cn1c2c(c(=O)n(c1=O)C)[nH]c(n2)");

        assertEquals(caffeine, theophylline);
    }

    private String scaffoldOf(String smiles) throws Exception {
        IAtomContainer molecule = new SmilesParser(BUILDER).parseSmiles(smiles);
        AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(molecule);
        CDKHydrogenAdder.getInstance(BUILDER).addImplicitHydrogens(molecule);
        Aromaticity.cdkLegacy().apply(molecule);
        return core.frameworkSmilesFor(molecule);
    }
}
