package molclass;

import org.junit.Test;
import static org.junit.Assert.*;
import org.openscience.cdk.interfaces.IAtomContainer;

public class SDFReaderTest {
    @Test
    public void testReadSimpleSDF() throws Exception {
        // Minimal SDF for water (H2O)
        String sdf = "\n  CDK     0910201524\n\n  3  2  0  0  0  0            999 V2000\n    0.0000    0.0000    0.0000 O   0  0  0  0  0  0  0  0  0  0  0  0\n    0.9572    0.0000    0.0000 H   0  0  0  0  0  0  0  0  0  0  0  0\n   -0.2396    0.9271    0.0000 H   0  0  0  0  0  0  0  0  0  0  0  0\n  1  2  1  0  0  0  0\n  1  3  1  0  0  0  0\nM  END\n$$$$";
        SDFReader reader = new SDFReader();
        IAtomContainer mol = reader.read(sdf);
        assertNotNull("Molecule should be parsed", mol);
        // Expect 3 atoms (O and 2 H)
        assertEquals(3, mol.getAtomCount());
    }
}
