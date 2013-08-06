package fingerprints;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.BitSet;
import org.openscience.cdk.ConformerContainer;

import org.openscience.cdk.Molecule;
import org.openscience.cdk.inchi.InChIGenerator;
import org.openscience.cdk.inchi.InChIGeneratorFactory;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IMolecule;
import org.openscience.cdk.smiles.SmilesGenerator;


//This class calculates fingerprints for all the molecules with a given batch_id
public class InChiGenerator {

	/**
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
                int batch_id = new Integer(1);
                if (args.length != 1)
		{
			System.out.println("Usage: java -jar MolClass.jar:lib/* InChiGenerator <batch_id>");
                        System.out.println("...... Running test with batch_id = " + batch_id);


		} else {

                        batch_id = new Integer(args[0]);
                        System.out.println("...... Running test with batch_id = " + batch_id);
                }

		String host = XMLReader.getTag("hostname");
		String database = XMLReader.getTag("database");
		String user = XMLReader.getTag("rw_user");
		String password = XMLReader.getTag("rw_password");
		String fptablename = XMLReader.getTag("fingerprinttable");
		String structablename = XMLReader.getTag("molstructable");
		String batchmoltable = XMLReader.getTag("batchmoltable");

		String hostname = new String("jdbc:mysql://" + host + "/" + database);

		Connection con = DriverManager.getConnection(hostname, user, password);

		//get all molecules with batch_id which do not already have fingerprints.
		String nstmt = new String("SELECT " + structablename + ".mol_id, " + structablename + ".struc FROM " + structablename + ", " + batchmoltable + " WHERE " + batchmoltable + ".mol_id = " + structablename + ".mol_id AND " + batchmoltable
				+ ".batch_id = ?");
		//System.out.println(nstmt);
		PreparedStatement stmt = con.prepareStatement(nstmt,
				ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
		stmt.setInt(1, batch_id);

		ResultSet rs = stmt.executeQuery();
		Molecule molecule = new Molecule();
		SDFReader sr = new SDFReader();

		int x = 0;

		while (rs.next()) {
			try {

                            Blob struc = rs.getBlob("struc");
                            byte[] bdata = struc.getBytes(1, (int) struc.length());
                            String sdf_structure = new String(bdata);

                            //convert sdf to Molecule
                            molecule = sr.read(sdf_structure);

                            // System.out.println(molecule);

                            // write InChiKey, Smiles into the identifier table
                            //IAtomContainer atomContainer = (Molecule) molecule;
                            //IAtomContainer cc = molecule;
                            //IMolecule ccc =molecule;
                            SmilesGenerator sg = new SmilesGenerator(true);
                            String smiles = "NULL"; // C1CCCCC1
                            Boolean SmileCheck = true;
                            try
                            {
                                    smiles = sg.createSMILES(molecule); // C1CCCCC1
                            }
                            catch(NullPointerException e)
                            {
                                    System.out.println("Issue with Smiles generation.");
                                    SmileCheck = false;
                            }
                            if (SmileCheck)
                            {
                            try
                            {
                                    smiles = sg.createSMILES(molecule); // C1CCCCC1
                            }
                            catch(IllegalArgumentException e)
                            {
                                    System.out.println("Issue with Smiles generation.");
                            }
                            }
                            //sg.setUseAromaticityFlag(true);
                            //IMolecule benzene2; // one of the two kekule structures with explicit double bond orders
                            //String smiles2 = sg.createChiralSMILES(molecule,true);

                            // Get InChIGenerator
                            InChIGeneratorFactory fac = InChIGeneratorFactory.getInstance();// factory = new InChIGeneratorFactory.get();
                            InChIGenerator gen = fac.getInChIGenerator(molecule);
                            
                            String inchi = "NULL";
                            try
                            {
                                    inchi = gen.getInchi();
                            }
                            catch(IllegalArgumentException e)
                            {
                                    System.out.println("Issue with InChi generation.");
                            }
                            
                            //String auxinfo = gen.getAuxInfo();

                            String inchikey = "NULL";
                            try
                            {
                                    inchikey = gen.getInchiKey();
                            }
                            catch(IllegalArgumentException e)
                            {
                                    System.out.println("Issue with InChiKey generation.");
                            }

                            Statement sqlstmt = con.createStatement();
                            String sqladdsmile = "insert into `inchi_key` (`mol_id`,`inchi`,`smiles`,`inchi_key`) VALUES (" + rs.getString("mol_id") + ",'" + inchi + "','" + smiles + "','"+ inchikey +"') ON DUPLICATE KEY UPDATE inchi_key='" + inchikey + "', smiles='" + smiles + "', inchi ='" + inchi + "'";
                            //System.out.println(sqladdsmile);
                            int updateCount = sqlstmt.executeUpdate(sqladdsmile);

                            /*    INCHI_RET ret = gen.getReturnStatus();
                            if (ret == INCHI_RET.WARNING) {
                            // InChI generated, but with warning message
                            System.out.println("InChI warning: " + gen.getMessage());
                            } else if (ret != INCHI_RET.OKAY) {
                            // InChI generation failed
                            throw new CDKException("InChI failed: " + ret.toString()
                            + " [" + gen.getMessage() + "]");
                            }
                             */


				x++;
				// if ((x % 100) == 0)
				System.out.println(x);
			} catch (Exception e) {
				e.printStackTrace();
				continue;
			}
		}

	}
}
