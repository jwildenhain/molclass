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

                // get max molecule number
             
		String nstmtdb = "SELECT max(mol_id) mol_id FROM batchmols";
		//System.out.println(nstmt);
		PreparedStatement stmtdb = con.prepareStatement(nstmtdb,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                ResultSet rsdb = stmtdb.executeQuery();
                
                int maxMolID = 1;
                int taniBatchSize = 1000;            
                
                rsdb.next();   
                maxMolID = rsdb.getInt("mol_id");
                rsdb.close();
                
                if (maxMolID < taniBatchSize) { taniBatchSize = maxMolID; }
                            
                double taniBatchId = Math.ceil(maxMolID/taniBatchSize) +1;
                
		//get all molecules with new batch_id .
		String nstmt = "SELECT " + fptablename + ".mol_id, " + fptablename + ".EXT, " + fptablename + ".KR FROM " + fptablename + ", " + batchmoltable + " WHERE " + batchmoltable + ".mol_id = " + fptablename + ".mol_id AND " + batchmoltable
                     + ".batch_id = ?";
		//System.out.println(nstmt);
		PreparedStatement stmt = con.prepareStatement(nstmt,
				ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
		stmt.setInt(1, batch_id);
		ResultSet rs = stmt.executeQuery();

		int x = 0;

		while (rs.next()) {
                       
                       int batchMinBound = 1;
                       int taniBatchTemp = 1;
                       int batchMaxBound = taniBatchTemp * taniBatchSize;
                            
         
                        while (taniBatchId > taniBatchTemp) {
                            
                            //System.out.println("Current: " + rs.getInt("mol_id") + " Max:" + maxMolID + " compute:" + taniBatchId + " between " + batchMinBound + " and " +batchMaxBound);

                            // read all molecules from batch 
                            String nstmtint = new String("SELECT " + fptablename + ".mol_id, " + fptablename + ".EXT, " + fptablename + ".KR FROM " + fptablename + ", " + batchmoltable + " WHERE " + batchmoltable + ".mol_id = " + fptablename + ".mol_id AND " + batchmoltable + ".batch_id <= " + batch_id + " AND " + batchmoltable
				+ ".mol_id between " + batchMinBound + " and " + batchMaxBound );
                            //System.out.println(nstmtint);
                            PreparedStatement stmtint = con.prepareStatement(nstmtint,
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                            //stmtint.setInt(1, batchMinBound);
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
                                            //String sqladdsmile = "insert into `tanimoto` (`mol_id1`,`mol_id2`,`ext`,`kr`) VALUES (" + rs.getString("mol_id") + ",'" + rsint.getString("mol_id") + "'," + extscore +", " + krscore +" ) ON DUPLICATE KEY UPDATE ext=" + extscore + ", kr =" + krscore; 
                                            //System.out.println(sqladdsmile);
                                            String sqladdsmile = "insert ignore into `tanimoto` (`mol_id1`,`mol_id2`,`ext`,`kr`) VALUES (" + rs.getString("mol_id") + ",'" + rsint.getString("mol_id") + "'," + extscore +", " + krscore +" )"; 
                                            int updateCount = sqlstmt.executeUpdate(sqladdsmile);
                                            // is symmetric... after A, B do B, A
                                            sqlstmt = con.createStatement();
                                            sqladdsmile = "insert ignore into `tanimoto` (`mol_id1`,`mol_id2`,`ext`,`kr`) VALUES (" + rsint.getString("mol_id") + ",'" + rs.getString("mol_id") + "'," + extscore +", " + krscore +" )"; 
                                            updateCount = sqlstmt.executeUpdate(sqladdsmile);
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
                            stmtint.close();
                            rsint.close();
                            batchMinBound = ( taniBatchTemp * taniBatchSize ) +1;
                            taniBatchTemp++;
                            batchMaxBound = taniBatchTemp * taniBatchSize;
                        }
		}
                stmt.close();
                rs.close();
                con.close();
                

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
