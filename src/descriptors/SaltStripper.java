package descriptors;

import org.openscience.cdk.graph.ConnectivityChecker;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IAtomContainerSet;

public class SaltStripper {
	
	public void SaltStripper(){}
	
	public IAtomContainer stripSalt(IAtomContainer molecule) {
		if (!ConnectivityChecker.isConnected(molecule)) {
			IAtomContainerSet molSet = ConnectivityChecker
					.partitionIntoMolecules(molecule);
			IAtomContainer biggest = molSet.getAtomContainer(0);
			for (int i = 1; i < molSet.getAtomContainerCount(); i++) {
				if (molSet.getAtomContainer(i).getBondCount() > biggest
						.getBondCount()) {
					biggest = molSet.getAtomContainer(i);
				}
			}

			molecule = biggest;
		}
		return molecule;
	}
}
