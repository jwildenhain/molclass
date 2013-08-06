package fingerprints;

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
						
				ISimpleChemObjectReader sdfReader = new ReaderFactory().createReader(sr);

				IChemObject mol = new Molecule();
				
				mol = sdfReader.read(mol);
				
				Molecule molecule = (Molecule)mol;
				
				return molecule;
				
			}

	}
