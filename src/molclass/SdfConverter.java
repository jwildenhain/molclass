package molclass;

import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.inchi.InChIGeneratorFactory;
import org.openscience.cdk.inchi.InChIToStructure;
import org.openscience.cdk.io.SDFWriter;
import org.openscience.cdk.silent.SilentChemObjectBuilder;

import java.io.StringWriter;

public class SdfConverter {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: SdfConverter <smiles|inchi> <structure_string>");
            System.exit(1);
        }

        String type = args[0];
        String input = args[1];
        IAtomContainer mol = null;

        try {
            if (type.equalsIgnoreCase("smiles")) {
                SmilesParser sp = new SmilesParser(SilentChemObjectBuilder.getInstance());
                mol = sp.parseSmiles(input);
            } else if (type.equalsIgnoreCase("inchi")) {
                InChIGeneratorFactory factory = InChIGeneratorFactory.getInstance();
                InChIToStructure intostruct = factory.getInChIToStructure(input, SilentChemObjectBuilder.getInstance());
                mol = intostruct.getAtomContainer();
            }
        } catch (Exception e) {
            System.err.println("Error parsing structure: " + e.getMessage());
            System.exit(1);
        }

        if (mol == null) {
            System.err.println("Error: Parse resulted in null molecule");
            System.exit(1);
        }

        StringWriter sw = new StringWriter();
        SDFWriter writer = new SDFWriter(sw);
        writer.write(mol);
        writer.close();

        System.out.print(sw.toString());
    }
}
