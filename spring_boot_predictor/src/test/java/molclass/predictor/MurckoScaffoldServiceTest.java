package molclass.predictor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.aromaticity.Aromaticity;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.tools.CDKHydrogenAdder;
import org.openscience.cdk.tools.manipulator.AtomContainerManipulator;

/**
 * Delegation smoke test only. The substantive CDK scaffold-behavior assertions (clustering,
 * acyclic handling, linker preservation, purine core sharing) live in
 * {@code molclass.models.MurckoScaffoldCoreTest} in the root project, alongside the class that
 * actually implements them -- this class is a thin Spring {@code @Service} wrapper around it.
 */
class MurckoScaffoldServiceTest {
    private static final IChemObjectBuilder BUILDER = DefaultChemObjectBuilder.getInstance();
    private final MurckoScaffoldService service = new MurckoScaffoldService();

    @Test
    void delegatesFrameworkSmilesComputationToCore() throws Exception {
        IAtomContainer toluene = new SmilesParser(BUILDER).parseSmiles("Cc1ccccc1");
        AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(toluene);
        CDKHydrogenAdder.getInstance(BUILDER).addImplicitHydrogens(toluene);
        Aromaticity.cdkLegacy().apply(toluene);

        String scaffold = service.frameworkSmilesFor(toluene);
        assertNotNull(scaffold);

        IAtomContainer benzoicAcid = new SmilesParser(BUILDER).parseSmiles("OC(=O)c1ccccc1");
        AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(benzoicAcid);
        CDKHydrogenAdder.getInstance(BUILDER).addImplicitHydrogens(benzoicAcid);
        Aromaticity.cdkLegacy().apply(benzoicAcid);

        assertEquals(scaffold, service.frameworkSmilesFor(benzoicAcid));
    }

    @Test
    void frameworkFingerprintDelegatesToCore() throws Exception {
        assertNotNull(service.frameworkFingerprint("c1ccccc1"));
    }
}
