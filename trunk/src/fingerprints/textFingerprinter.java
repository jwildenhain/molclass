package fingerprints;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.BitSet;

import org.openscience.cdk.Molecule;
import org.openscience.cdk.fingerprint.ExtendedFingerprinter;
import org.openscience.cdk.fingerprint.MACCSFingerprinter;

public class textFingerprinter {

	/**
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		// TODO Auto-generated method stub

		String host = args[0];
		String user = args[1];

		// BufferedReader br = new BufferedReader(new
		// InputStreamReader(System.in));

		// System.out.println("Enter Password: ");
		// String password = br.readLine();
		String password = new String("ZeYq2WSXdS43adX3");

		MACCSFingerprinter mfp = new MACCSFingerprinter();
		ExtendedFingerprinter efp = new ExtendedFingerprinter();

		String hostname = new String("jdbc:mysql://" + host + "/nfitz_wekatest");

		Connection con = DriverManager.getConnection(hostname, user, password);

		String nstmt = new String(
				"SELECT test_molstruc.mol_id, test_molstruc.struc, test_fingerprints.MACCS, test_fingerprints.EXT FROM test_molstruc, test_fingerprints WHERE test_fingerprints.mol_id = test_molstruc.mol_id AND test_fingerprints.MACCS IS NULL");
		PreparedStatement stmt = con.prepareStatement(nstmt,
				ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

		ResultSet rs = stmt.executeQuery();
		Molecule molecule = new Molecule();
                        //Molecule();
		SDFReader sr = new SDFReader();

		int x = 0;

		while (rs.next()) {
			try {
				String stmt2 = new String(
						"SELECT * FROM test_fingerprints WHERE mol_id = "
								+ rs.getString("mol_id"));
				PreparedStatement pstmt = con
						.prepareStatement(stmt2, ResultSet.TYPE_FORWARD_ONLY,
								ResultSet.CONCUR_UPDATABLE);

				ResultSet rs2 = pstmt.executeQuery();
				rs2.next();

				Blob struc = rs.getBlob("struc");
				byte[] bdata = struc.getBytes(1, (int) struc.length());
				String sdf_structure = new String(bdata);

				molecule = sr.read(sdf_structure);

				//BitSet MACCSset = mfp.getBitFingerprint(molecule).asBitSet();
				//BitSet EXTset = efp.getBitFingerprint(molecule).asBitSet();
                                BitSet MACCSset = mfp.getFingerprint(molecule);
				BitSet EXTset = efp.getFingerprint(molecule);

				rs2.updateString("MACCS", MACCSset.toString());
				rs2.updateString("EXT", EXTset.toString());

				rs2.updateRow();
				x++;
				if ((x % 100) == 0)
					System.out.println(x);
			} catch (Exception e) {
				continue;
			}
		}

	}

	private static byte[] toByteArray(BitSet bits) {
		byte[] bytes = new byte[bits.length() / 8 + 1];
		for (int i = 0; i < bits.length(); i++) {
			if (bits.get(i)) {
				bytes[bytes.length - i / 8 - 1] |= 1 << (i % 8);
			}
		}
		return bytes;
	}

}
