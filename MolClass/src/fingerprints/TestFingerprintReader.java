package fingerprints;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.BitSet;

public class TestFingerprintReader {

	/**
	 * @param args
	 * @throws Exception 
	 */
	public static void main(String[] args) throws Exception {
		String host = args[0];
		String user = args[1];
		
		//BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		
		//System.out.println("Enter Password: ");
		//String password = br.readLine();
		String password = new String("ZeYq2WSXdS43adX3");
		
		String hostname = new String ("jdbc:mysql://" + host + "/nfitz_test");
		
		Connection con = DriverManager.getConnection(hostname, user, password);
		
		String nstmt = new String("SELECT mol_id, MACCS FROM test_fingerprints limit 2");
		PreparedStatement stmt = con.prepareStatement(nstmt, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
		
		ResultSet rs = stmt.executeQuery();
	
		
		//rs.next();
		while(rs.next())
		{
		String molid = rs.getString("mol_id");
		Blob blob = rs.getBlob("MACCS");
		byte[] bytes = blob.getBytes(1, (int)blob.length());
		BitSet bitSet = fromByteArray(bytes);
		
		System.out.println(molid + "  -  " + bitSet.toString());
		}

	}
	
    public static BitSet fromByteArray(byte[] bytes) {
        BitSet bits = new BitSet();
        for (int i=0; i<bytes.length*8; i++) {
            if ((bytes[bytes.length-i/8-1]&(1<<(i%8))) > 0) {
                bits.set(i);
            }
        }
        return bits;
    }

}
