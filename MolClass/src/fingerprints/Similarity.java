/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 * 
 * author jan wildenhain
 */
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
import java.util.Scanner;
import org.openscience.cdk.ConformerContainer;

import org.openscience.cdk.Molecule;
import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.exception.InvalidSmilesException;
import org.openscience.cdk.fingerprint.Fingerprinter;
import org.openscience.cdk.interfaces.IMolecule;
import org.openscience.cdk.similarity.Tanimoto;
import org.openscience.cdk.smiles.SmilesParser;


/**
 *
 * @author zahir
 */
public class Similarity {
    
    
    /**
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
                int batch_id = new Integer(1);
                if (args.length != 1)
		{
			System.out.println("Usage: java -jar MolClass.jar Similarity <batch_id>");
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
                String setTanimotoCutoff = XMLReader.getTag("setTanimotoCutoff");
                Double TanimotoCutoff = Double.parseDouble(setTanimotoCutoff);

		String hostname = new String("jdbc:mysql://" + host + "/" + database);

		Connection con = DriverManager.getConnection(hostname, user, password);

		//get all molecules with new batch_id .
		String nstmt = new String("SELECT " + fptablename + ".mol_id, " + fptablename + ".EXT, " + fptablename + ".KR FROM " + fptablename + ", " + batchmoltable + " WHERE " + batchmoltable + ".mol_id = " + fptablename + ".mol_id AND " + batchmoltable
				+ ".batch_id = ?");
		//System.out.println(nstmt);
		PreparedStatement stmt = con.prepareStatement(nstmt,
				ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
		stmt.setInt(1, batch_id);
		ResultSet rs = stmt.executeQuery();


		int x = 0;

		while (rs.next()) {
                    
                        System.out.println(rs.getInt("mol_id"));
 
                        //get all batch_ids in MolClass
		        String nstmtdb = new String("SELECT batch_id FROM batchlist");
		        //System.out.println(nstmt);
		        PreparedStatement stmtdb = con.prepareStatement(nstmtdb,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                        ResultSet rsdb = stmtdb.executeQuery();
                                      
                        while (rsdb.next()) {
                            // read all molecules from batch 
                            String nstmtint = new String("SELECT " + fptablename + ".mol_id, " + fptablename + ".EXT, " + fptablename + ".KR FROM " + fptablename + ", " + batchmoltable + " WHERE " + batchmoltable + ".mol_id = " + fptablename + ".mol_id AND " + batchmoltable
				+ ".batch_id = ?");
                            //System.out.println(nstmt);
                            PreparedStatement stmtint = con.prepareStatement(nstmtint,
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                            stmtint.setInt(1, rsdb.getInt("batch_id"));
                            ResultSet rsint = stmtint.executeQuery();
                            while (rsint.next()) {
                                try {

                                  //convert Molecule fingerprints
                                  String cmp1e = rs.getString("EXT");
                                  BitSet bs1 = bsFromString(cmp1e);
                                  String cmp1kr = rs.getString("KR");
                                  BitSet bs1k = bsFromString(cmp1kr);
                        
                                    String cmp2e = rsint.getString("EXT");
                                    BitSet bs2 = bsFromString(cmp2e);
                                    String cmp2kr = rsint.getString("KR");  
                                    BitSet bs2k = bsFromString(cmp2kr);

                                    Double extscore = calculateSimilarity(bs1,bs2);                                 
                                    Double krscore = calculateSimilarity(bs1k,bs2k);
                                    
                                    if ((extscore >= TanimotoCutoff || krscore >= TanimotoCutoff) && extscore < 1) {
                                        
                                        if (rs.getInt("mol_id") != rsint.getInt("mol_id")) {
                                            System.out.println(rs.getInt("mol_id") + " " + rsint.getInt("mol_id") + "Scores:" + extscore + " " + krscore);
                                        
                                            Statement sqlstmt = con.createStatement();
                                            String sqladdsmile = "insert into `tanimoto` (`mol_id1`,`mol_id2`,`ext`,`kr`) VALUES (" + rs.getString("mol_id") + ",'" + rsint.getString("mol_id") + "'," + extscore +", " + krscore +" ) ON DUPLICATE KEY UPDATE ext=" + extscore + ", kr =" + krscore; 
                                            //System.out.println(sqladdsmile);
                                            int updateCount = sqlstmt.executeUpdate(sqladdsmile);
                                        }
                                        
                                    }
                            

                                    x++;
                                    // if ((x % 100) == 0)
                                    //System.out.println(x);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    continue;
                                }
                            }
                        }
		}

	}
        
        
        private static BitSet bsFromString(String string) {
		String s = new String(string);
		BitSet bs = new BitSet();
		
		s = s.replace('{', ' ');
		s = s.replace('}', ' ');
		s = s.replace(',', ' ');

		Scanner scan = new Scanner(s);

		while (scan.hasNextInt()) {
			int index = scan.nextInt();
			bs.set(index);
		}

		return bs;
	}
        
        private static double calculateSimilarity(BitSet fp1, BitSet fp2) {
		
		double score = Double.MIN_VALUE;
		if (fp1 != null && fp2 != null) {
			try {
				score = Tanimoto.calculate(fp1, fp2);
			} catch (Exception e) {
				score = 0.0;
			}
		}
		if (score == Double.MIN_VALUE) score = 0.0;

		return score;
	} 
    
}
