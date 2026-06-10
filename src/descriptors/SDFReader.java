package descriptors;

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
				
				IChemObjectReader sdfReader = new ReaderFactory().createReader(sr);

                                IAtomContainer molecule = null;


                                if (sdfReader instanceof ISimpleChemObjectReader) {

                                        IChemObject mol = new AtomContainer();

                                        mol = ((ISimpleChemObjectReader)sdfReader).read(mol);

                                        molecule = (IAtomContainer)mol;
                                } else {
                                    throw new UnsupportedOperationException("Sorry don't like your sdf-string");
                                }
				
				return molecule;
				
			}

	}
