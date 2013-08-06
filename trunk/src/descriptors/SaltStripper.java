package descriptors;

import org.openscience.cdk.Molecule;
import org.openscience.cdk.graph.ConnectivityChecker;
import org.openscience.cdk.interfaces.IMolecule;
import org.openscience.cdk.interfaces.IMoleculeSet;

public class SaltStripper {
	
	public void SaltStipper(){}
	
	public Molecule stripSalt(Molecule molecule) {
		if (!ConnectivityChecker.isConnected(molecule)) {
			IMoleculeSet molSet = ConnectivityChecker
					.partitionIntoMolecules(molecule);
			IMolecule biggest = molSet.getMolecule(0);
			for (int i = 1; i < molSet.getMoleculeCount(); i++) {
				if (molSet.getMolecule(i).getBondCount() > biggest
						.getBondCount()) {
					biggest = molSet.getMolecule(i);
				}
			}

			molecule = (Molecule) biggest;
		}
		return molecule;
	}
}
