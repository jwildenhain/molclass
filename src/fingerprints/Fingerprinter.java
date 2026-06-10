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

import org.openscience.cdk.AtomContainer;
import org.openscience.cdk.fingerprint.EStateFingerprinter;
import org.openscience.cdk.fingerprint.ExtendedFingerprinter;
import org.openscience.cdk.fingerprint.GraphOnlyFingerprinter;
import org.openscience.cdk.fingerprint.MACCSFingerprinter;
import org.openscience.cdk.fingerprint.PubchemFingerprinter;
import org.openscience.cdk.fingerprint.SubstructureFingerprinter;
import org.openscience.cdk.fingerprint.KlekotaRothFingerprinter;
import org.openscience.cdk.inchi.InChIGenerator;
import org.openscience.cdk.inchi.InChIGeneratorFactory;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.smiles.SmilesGenerator;


//This class calculates fingerprints for all the molecules with a given batch_id
public class Fingerprinter {

	private static final ThreadLocal<Connection> threadConnCache = new ThreadLocal<Connection>();

	private static Connection getThreadConnection(String host, String user, String pass) throws SQLException {
		Connection conn = threadConnCache.get();
		if (conn == null || conn.isClosed()) {
			conn = DriverManager.getConnection(host, user, pass);
			threadConnCache.set(conn);
		}
		return conn;
	}

	/**
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
                int batch_id = new Integer(1);
                if (args.length != 1)
		{
			System.out.println("Usage: java -jar MolClass.jar:lib/* Fingerprinter <batch_id>");
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

		//fingerprinters
		MACCSFingerprinter mfp = new MACCSFingerprinter();
		ExtendedFingerprinter efp = new ExtendedFingerprinter();
                PubchemFingerprinter pcfp = new PubchemFingerprinter(org.openscience.cdk.silent.SilentChemObjectBuilder.getInstance());
                EStateFingerprinter esfp = new EStateFingerprinter();
                SubstructureFingerprinter subfp = new SubstructureFingerprinter();
                GraphOnlyFingerprinter stdfp = new GraphOnlyFingerprinter();
                KlekotaRothFingerprinter krfp = new KlekotaRothFingerprinter();

		String hostname = new String("jdbc:mysql://" + host + "/" + database);

		Connection con = DriverManager.getConnection(hostname, user, password);

		//get all molecules with batch_id which do not already have fingerprints.
		String nstmt = new String("SELECT " + structablename + ".mol_id, "
				+ structablename + ".struc, " + fptablename + ".MACCS, "
				+ fptablename + ".EXT FROM " + structablename + ", "
				+ fptablename + ", " + batchmoltable + " WHERE " + fptablename + ".mol_id = "
				+ structablename + ".mol_id AND " + fptablename
				+ ".SUB IS NULL AND " + batchmoltable + ".mol_id = "
				+ fptablename + ".mol_id AND " + batchmoltable
				+ ".batch_id = ?");
		//System.out.println(nstmt);
		PreparedStatement stmt = con.prepareStatement(nstmt,
				ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
		stmt.setInt(1, batch_id);

		ResultSet rs = stmt.executeQuery();
		IAtomContainer molecule = new AtomContainer();
		SDFReader sr = new SDFReader();

		int numThreads = 16;
		try {
			String threadsVal = XMLReader.getTag("numThreads");
			if (threadsVal != null) {
				numThreads = Integer.parseInt(threadsVal.trim());
			}
		} catch (Exception e) {
			// fallback to 16
		}
		System.out.println("...... Running Fingerprinter with thread pool size = " + numThreads);
		
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(numThreads);
		final String threadHost = hostname;
		final String threadUser = user;
		final String threadPassword = password;
		final String threadFpTable = fptablename;

		int x = 0;

		while (rs.next()) {
			final String molId = rs.getString("mol_id");
			Blob struc = rs.getBlob("struc");
			final byte[] bdata = struc.getBytes(1, (int) struc.length());
			
			pool.submit(new Runnable() {
				public void run() {
					try {
						Connection threadConn = getThreadConnection(threadHost, threadUser, threadPassword);
						SDFReader srThread = new SDFReader();
						IAtomContainer mol = srThread.read(new String(bdata));
						
						MACCSFingerprinter mfp = new MACCSFingerprinter();
						ExtendedFingerprinter efp = new ExtendedFingerprinter();
						PubchemFingerprinter pcfp = new PubchemFingerprinter(org.openscience.cdk.silent.SilentChemObjectBuilder.getInstance());
						EStateFingerprinter esfp = new EStateFingerprinter();
						SubstructureFingerprinter subfp = new SubstructureFingerprinter();
						GraphOnlyFingerprinter stdfp = new GraphOnlyFingerprinter();
						KlekotaRothFingerprinter krfp = new KlekotaRothFingerprinter();
						
						BitSet ESset = esfp.getBitFingerprint(mol).asBitSet();
						BitSet MACCSset = mfp.getBitFingerprint(mol).asBitSet();
						BitSet EXTset = efp.getBitFingerprint(mol).asBitSet();
						BitSet PCset = pcfp.getBitFingerprint(mol).asBitSet();
						BitSet STDset = stdfp.getBitFingerprint(mol).asBitSet();
						BitSet SUBset = subfp.getBitFingerprint(mol).asBitSet();
						BitSet KRset = krfp.getBitFingerprint(mol).asBitSet();
						
						String updateSQL = "UPDATE " + threadFpTable + " SET MACCS=?, EXT=?, PubChem=?, GOFP=?, SUB=?, KR=?, ESFP=? WHERE mol_id=?";
						try (PreparedStatement updateStmt = threadConn.prepareStatement(updateSQL)) {
							updateStmt.setString(1, MACCSset.toString());
							updateStmt.setString(2, EXTset.toString());
							updateStmt.setString(3, PCset.toString());
							updateStmt.setString(4, STDset.toString());
							updateStmt.setString(5, SUBset.toString());
							updateStmt.setString(6, KRset.toString());
							updateStmt.setString(7, ESset.toString());
							updateStmt.setString(8, molId);
							updateStmt.executeUpdate();
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});
			x++;
		}
		
		pool.shutdown();
		pool.awaitTermination(1, java.util.concurrent.TimeUnit.HOURS);
		System.out.println("Fingerprinter finished. Processed " + x + " molecules.");

	}
}
