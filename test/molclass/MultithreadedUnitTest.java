package molclass;

import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import fingerprints.XMLReader;
import fingerprints.Fingerprinter;
import fingerprints.Similarity;

public class MultithreadedUnitTest {

    private static Connection conn;
    private static String databaseURL;
    private static String rwUser;
    private static String rwPassword;

    @BeforeClass
    public static void setUp() throws Exception {
        String host = XMLReader.getTag("hostname");
        String database = XMLReader.getTag("database");
        rwUser = XMLReader.getTag("rw_user");
        rwPassword = XMLReader.getTag("rw_password");
        databaseURL = "jdbc:mysql://" + host + "/" + database;
        conn = DriverManager.getConnection(databaseURL, rwUser, rwPassword);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @Test
    public void testConfigurableThreads() throws Exception {
        String threadsVal = XMLReader.getTag("numThreads");
        assertNotNull("numThreads config tag should not be null", threadsVal);
        int numThreads = Integer.parseInt(threadsVal.trim());
        assertEquals("numThreads default should be 16", 16, numThreads);
    }

    @Test
    public void testMultithreadedFingerprinter() throws Exception {
        System.out.println("\n=== MULTITHREADED FINGERPRINTER TEST ===");
        
        // 1. Reset fingerprints for batch 84
        String resetSQL = "UPDATE fingerprints SET SUB = NULL WHERE mol_id IN (SELECT mol_id FROM batchmols WHERE batch_id = 84)";
        Statement resetStmt = conn.createStatement();
        resetStmt.executeUpdate(resetSQL);
        resetStmt.close();
        
        // Verify reset succeeded
        String checkSQL = "SELECT COUNT(*) FROM fingerprints WHERE mol_id IN (SELECT mol_id FROM batchmols WHERE batch_id = 84) AND SUB IS NULL";
        Statement checkStmt = conn.createStatement();
        ResultSet checkRs = checkStmt.executeQuery(checkSQL);
        assertTrue(checkRs.next());
        assertEquals("All 20 molecules should be reset with SUB=NULL", 20, checkRs.getInt(1));
        checkRs.close();
        checkStmt.close();

        // 2. Run multithreaded Fingerprinter
        Fingerprinter.main(new String[]{"84"});

        // 3. Verify all molecules now have computed fingerprints (SUB is not null)
        String verifySQL = "SELECT COUNT(*) FROM fingerprints WHERE mol_id IN (SELECT mol_id FROM batchmols WHERE batch_id = 84) AND SUB IS NOT NULL";
        Statement verifyStmt = conn.createStatement();
        ResultSet verifyRs = verifyStmt.executeQuery(verifySQL);
        assertTrue(verifyRs.next());
        assertEquals("All 20 molecules should have generated fingerprints concurrently", 20, verifyRs.getInt(1));
        verifyRs.close();
        verifyStmt.close();
    }

    @Test
    public void testMultithreadedSimilarity() throws Exception {
        System.out.println("\n=== MULTITHREADED SIMILARITY TEST ===");
        
        // 1. Reset similarity records for batch 84
        String resetSQL = "DELETE FROM tanimoto WHERE mol_id1 IN (SELECT mol_id FROM batchmols WHERE batch_id = 84)";
        Statement resetStmt = conn.createStatement();
        resetStmt.executeUpdate(resetSQL);
        resetStmt.close();

        // 2. Run multithreaded Similarity
        Similarity.main(new String[]{"84"});

        // 3. Verify that tanimoto entries were successfully populated
        String verifySQL = "SELECT COUNT(*) FROM tanimoto WHERE mol_id1 IN (SELECT mol_id FROM batchmols WHERE batch_id = 84)";
        Statement verifyStmt = conn.createStatement();
        ResultSet verifyRs = verifyStmt.executeQuery(verifySQL);
        assertTrue(verifyRs.next());
        int count = verifyRs.getInt(1);
        System.out.println("Generated " + count + " similarity relations in tanimoto table.");
        assertTrue("Should have calculated similarities and created rows in tanimoto table", count > 0);
        verifyRs.close();
        verifyStmt.close();
    }
}
