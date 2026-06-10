/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
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
import java.util.List;
import java.util.ArrayList;
import org.openscience.cdk.similarity.Tanimoto;


/**
 *
 * @author zahir
 */
public class Similarity {

    private static class MolRecord {
        String molId;
        String ext;
        String kr;
        MolRecord(String molId, String ext, String kr) {
            this.molId = molId;
            this.ext = ext;
            this.kr = kr;
        }
    }
    
    
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
                Double TanimotoCutoff = 0.85;

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
		
		List<MolRecord> targetMols = new ArrayList<>();
		while (rs.next()) {
			targetMols.add(new MolRecord(rs.getString("mol_id"), rs.getString("EXT"), rs.getString("KR")));
		}
		rs.close();
		stmt.close();

		//get all batch_ids in MolClass
		String nstmtdb = new String("SELECT batch_id FROM batchlist");
		PreparedStatement stmtdb = con.prepareStatement(nstmtdb,
				ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
		ResultSet rsdb = stmtdb.executeQuery();
		List<Integer> batchIds = new ArrayList<>();
		while (rsdb.next()) {
			batchIds.add(rsdb.getInt("batch_id"));
		}
		rsdb.close();
		stmtdb.close();
		
		int numThreads = 16;
		try {
			String threadsVal = XMLReader.getTag("numThreads");
			if (threadsVal != null) {
				numThreads = Integer.parseInt(threadsVal.trim());
			}
		} catch (Exception e) {
			// fallback to 16
		}
		System.out.println("...... Running Similarity with thread pool size = " + numThreads);

		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(numThreads);
		final String threadHost = hostname;
		final String threadUser = user;
		final String threadPassword = password;
		final String threadFpTable = fptablename;
		final String threadBatchTable = batchmoltable;
		final Double threadCutoff = TanimotoCutoff;
		final List<Integer> finalBatchIds = batchIds;

		int x = 0;
		for (final MolRecord mol1 : targetMols) {
			pool.submit(new Runnable() {
				public void run() {
					try (Connection threadConn = DriverManager.getConnection(threadHost, threadUser, threadPassword)) {
						for (Integer compBatchId : finalBatchIds) {
							String nstmtint = "SELECT " + threadFpTable + ".mol_id, " + threadFpTable + ".EXT, " + threadFpTable + ".KR FROM " + threadFpTable + ", " + threadBatchTable + " WHERE " + threadBatchTable + ".mol_id = " + threadFpTable + ".mol_id AND " + threadBatchTable + ".batch_id = ?";
							try (PreparedStatement stmtint = threadConn.prepareStatement(nstmtint)) {
								stmtint.setInt(1, compBatchId);
								try (ResultSet rsint = stmtint.executeQuery()) {
									while (rsint.next()) {
										String mol2Id = rsint.getString("mol_id");
										String cmp2e = rsint.getString("EXT");
										String cmp2kr = rsint.getString("KR");
										
										if (mol1.ext == null || mol1.kr == null || cmp2e == null || cmp2kr == null) {
											continue;
										}
										
										BitSet bs1 = bsFromString(mol1.ext);
										BitSet bs1k = bsFromString(mol1.kr);
										BitSet bs2 = bsFromString(cmp2e);
										BitSet bs2k = bsFromString(cmp2kr);
										
										double extscore = calculateSimilarity(bs1, bs2);
										double krscore = calculateSimilarity(bs1k, bs2k);
										
										if ((extscore >= threadCutoff || krscore >= threadCutoff) && extscore < 1) {
											if (!mol1.molId.equals(mol2Id)) {
												String sqladdsmile = "insert into `tanimoto` (`mol_id1`,`mol_id2`,`ext`,`kr`) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE ext=?, kr=?";
												try (PreparedStatement insertStmt = threadConn.prepareStatement(sqladdsmile)) {
													insertStmt.setString(1, mol1.molId);
													insertStmt.setString(2, mol2Id);
													insertStmt.setDouble(3, extscore);
													insertStmt.setDouble(4, krscore);
													insertStmt.setDouble(5, extscore);
													insertStmt.setDouble(6, krscore);
													insertStmt.executeUpdate();
												}
											}
										}
									}
								}
							}
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
		System.out.println("Similarity finished. Submitted " + x + " tasks.");

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
