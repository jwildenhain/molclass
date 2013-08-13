package descriptors;

import java.io.Reader;
import java.io.StringReader;
import org.openscience.cdk.Molecule;
import org.openscience.cdk.interfaces.IChemObject;
import org.openscience.cdk.io.IChemObjectReader;
import org.openscience.cdk.io.ISimpleChemObjectReader;
import org.openscience.cdk.io.ReaderFactory;

public class SDFReader {
	
	public SDFReader()
	{}
	
	public Molecule read(String s) throws Exception
	{
				Reader sr = new StringReader(s);
				
				IChemObjectReader sdfReader = new ReaderFactory().createReader(sr);

                                Molecule molecule = null;


                                if (sdfReader instanceof ISimpleChemObjectReader) {

                                        IChemObject mol = new Molecule();

                                        mol = ((ISimpleChemObjectReader)sdfReader).read(mol);

                                        molecule = (Molecule)mol;
                                } else {
                                    throw new UnsupportedOperationException("Sorry don't like your sdf-string");
                                }
				
				return molecule;
				
			}

	}
