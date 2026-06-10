package fingerprints;

import java.io.Reader;
import java.io.StringReader;
import org.openscience.cdk.AtomContainer;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IChemObject;
import org.openscience.cdk.io.IChemObjectReader;
import org.openscience.cdk.io.ISimpleChemObjectReader;
import org.openscience.cdk.io.ReaderFactory;

public class SDFReader {
	
	public SDFReader()
	{}
	
	public IAtomContainer read(String s) throws Exception
	{
				Reader sr = new StringReader(s);
						
				ISimpleChemObjectReader sdfReader = new ReaderFactory().createReader(sr);

				IChemObject mol = new AtomContainer();
				
				mol = sdfReader.read(mol);
				
				IAtomContainer molecule = (IAtomContainer)mol;
				
				return molecule;
				
			}

	}
