package molclass;

import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import molclass.XMLReader;
import molclass.fingerprints.Fingerprinter;
import molclass.fingerprints.Similarity;

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
        conn.setAutoCommit(true);
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

        int batchId = 84;
        int totalMolecules = queryScalarInt(
                "SELECT COUNT(*) FROM batchmols WHERE batch_id = " + batchId);
        assertEquals("Expected batch size should be 20", 20, totalMolecules);

        // 1. Reset fingerprints for batch
        String resetSQL = "UPDATE fingerprints SET SUB = NULL WHERE mol_id IN (SELECT mol_id FROM batchmols WHERE batch_id = " + batchId + ")";
        try (Statement resetStmt = conn.createStatement()) {
            resetStmt.executeUpdate(resetSQL);
        }

        int resetNullCount = queryScalarInt(
                "SELECT COUNT(*) FROM fingerprints WHERE mol_id IN (SELECT mol_id FROM batchmols WHERE batch_id = " + batchId + ") AND SUB IS NULL");
        assertEquals("All selected molecules should be reset with SUB=NULL", totalMolecules, resetNullCount);

        // 2. Run multithreaded Fingerprinter
        Fingerprinter.main(new String[]{String.valueOf(batchId)});

        // 3. Verify all molecules now have computed fingerprints (SUB is not null)
        int computedCount = queryScalarInt(
                "SELECT COUNT(*) FROM fingerprints WHERE mol_id IN (SELECT mol_id FROM batchmols WHERE batch_id = " + batchId + ") AND SUB IS NOT NULL");

        assertEquals("All 20 molecules should have generated fingerprints concurrently", totalMolecules, computedCount);
    }

    @Test
    public void testMultithreadedSimilarity() throws Exception {
        System.out.println("\n=== MULTITHREADED SIMILARITY TEST ===");

        int batchId = 84;
        // 1. Reset similarity records for batch
        String resetSQL = "DELETE FROM tanimoto WHERE mol_id1 IN (SELECT mol_id FROM batchmols WHERE batch_id = " + batchId + ")";
        try (Statement resetStmt = conn.createStatement()) {
            resetStmt.executeUpdate(resetSQL);
        }

        // 2. Run multithreaded Similarity
        Similarity.main(new String[]{String.valueOf(batchId)});

        // 3. Verify that tanimoto entries were successfully populated
        String verifySQL = "SELECT COUNT(*) FROM tanimoto WHERE mol_id1 IN (SELECT mol_id FROM batchmols WHERE batch_id = " + batchId + ")";
        int count = queryScalarInt(verifySQL);
        System.out.println("Generated " + count + " similarity relations in tanimoto table.");
        assertTrue("Should have calculated similarities and created rows in tanimoto table", count > 0);
    }

    private static int queryScalarInt(String sql) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (!rs.next()) {
                return 0;
            }
            return rs.getInt(1);
        }
    }
}
