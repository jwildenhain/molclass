package descriptors;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.Molecule;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.exception.InvalidSmilesException;
import org.openscience.cdk.graph.ConnectivityChecker;
import org.openscience.cdk.interfaces.IMolecule;
import org.openscience.cdk.interfaces.IMoleculeSet;
import org.openscience.cdk.modeling.builder3d.ModelBuilder3D;
import org.openscience.cdk.modeling.builder3d.TemplateHandler3D;
import org.openscience.cdk.qsar.DescriptorEngine;
import org.openscience.cdk.qsar.DescriptorValue;
import org.openscience.cdk.qsar.result.BooleanResult;
import org.openscience.cdk.qsar.result.DoubleArrayResult;
import org.openscience.cdk.qsar.result.DoubleResult;
import org.openscience.cdk.qsar.result.IDescriptorResult;
import org.openscience.cdk.qsar.result.IntegerArrayResult;
import org.openscience.cdk.qsar.result.IntegerResult;
import org.openscience.cdk.smiles.SmilesParser;

//Private method to add new columns to table.
public class CreateNewColumns{
	public static void main(String[] args) throws InvalidSmilesException,
			SQLException{
		Connection conn;
		LinkedList<String> notAdded = new LinkedList<String>();

			conn = DriverManager.getConnection(
					"jdbc:mysql://prohits.bio.ed.ac.uk", "nfg",
					"ZeYq2WSXdS43adX3");
		
		Molecule molecule;
		
		SmilesParser sp = new SmilesParser(DefaultChemObjectBuilder.getInstance());
		molecule = (Molecule)sp.parseSmiles("CC(N)(Cc1ccc(O)cc1)C(O)=O");
		
		molecule = stripSalt(molecule);

		// Calculate 3D coordinates
		try {
			TemplateHandler3D template = TemplateHandler3D.getInstance();
			ModelBuilder3D mb3d = ModelBuilder3D.getInstance(template,
					"mm2");
			molecule = (Molecule) mb3d
					.generate3DCoordinates(molecule, true);
		} catch (Exception e) {
			e.printStackTrace();
		}

		// Calculate descriptors and convert to set
		DescriptorEngine engine = new DescriptorEngine(
				DescriptorEngine.MOLECULAR,null);

		List list = engine.getDescriptorClassNames();
		list.remove("org.openscience.cdk.qsar.descriptors.molecular.LengthOverBreadthDescriptor");
		list.remove("org.openscience.cdk.qsar.descriptors.molecular.TaeAminoAcidDescriptor");
		//engine = new DescriptorEngine(list,null);
                engine.setDescriptorInstances(list);
                

		try {
			engine.process(molecule);
		} catch (CDKException e) {
			e.printStackTrace();
		}
		// Get set of Molecules properties
		Set descSet = molecule.getProperties().entrySet();
		// Filter out properties which are not DescriptorValues
		Iterator itr = descSet.iterator();
		while (itr.hasNext()) {
			if (!(((Map.Entry) itr.next()).getValue() instanceof DescriptorValue)) {
				itr.remove();
			}
		}
		
		itr = descSet.iterator();
		
		PreparedStatement pstmt;
		
		int x = 1;
		
		while (itr.hasNext()) {
			DescriptorValue desc = (DescriptorValue) ((Map.Entry) itr
					.next()).getValue();
			String[] names = desc.getNames();

			for (int n = 0; n < names.length; n++) {
				String name = names[n];
				name = name.replace('-', '_'); //MySQL does not allow "-"
				name = name.replace('.', '_'); //MySQL does not allow "-"
				if ((name.compareTo("TPSA") == 0) && (n == 0)) // rename
					// sigular
					// tpsa
					name = new String("TopoPSA");

				IDescriptorResult result = desc.getValue();
					
				
				try{
					if (result instanceof BooleanResult) {
						pstmt = conn.prepareStatement("ALTER TABLE nfitz_wekatest.test_cdk_desc add " + name + " BOOL");
						pstmt.executeUpdate();
						System.out.println(x + " New column " + name + " added.");
					} else if (result instanceof DoubleArrayResult) {
						pstmt = conn.prepareStatement("ALTER TABLE nfitz_wekatest.test_cdk_desc add " + name + " DOUBLE");
						pstmt.executeUpdate();
						System.out.println(x + " New column " + name + " added.");
					} else if (result instanceof DoubleResult) {
						pstmt = conn.prepareStatement("ALTER TABLE nfitz_wekatest.test_cdk_desc add " + name + " DOUBLE");
						pstmt.executeUpdate();
						System.out.println(x + " New column " + name + " added.");
					} else if (result instanceof IntegerArrayResult) {
						pstmt = conn.prepareStatement("ALTER TABLE nfitz_wekatest.test_cdk_desc add " + name + " INT(5)");
						pstmt.executeUpdate();
						System.out.println(x + " New column " + name + " added.");
					} else if (result instanceof IntegerResult) {
						pstmt = conn.prepareStatement("ALTER TABLE nfitz_wekatest.test_cdk_desc add " + name + " INT(5)");
						pstmt.executeUpdate();
						System.out.println(x + " New column " + name + " added.");
					}
				}
				catch (SQLException e){
					System.out.println( x + e.getMessage());
					notAdded.add(name);
				}
				finally
				{
					x++;
				}
							
			}
			
		}
		System.out.println("Did not add: ");
		Iterator<String> itr2 = notAdded.iterator();
		while(itr2.hasNext())
		{
			System.out.println(itr2.next());
		}
	}

	private static Molecule stripSalt(Molecule molecule) {
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
