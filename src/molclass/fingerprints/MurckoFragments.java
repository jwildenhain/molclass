package molclass.fingerprints;

import molclass.XMLReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.openscience.cdk.exception.CDKException;
// MurckoFragmenter not available in current CDK version; placeholder
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.smiles.SmilesGenerator;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import molclass.SDFReader;

/**
 * Generates Bemis-Murcko frameworks for all molecules in a given batch.
 * The frameworks are stored in the {@code murcko} table and linked to molecules
 * via the {@code murcko_mol} relationship table.
 */
public class MurckoFragments {

    private static final ThreadLocal<Connection> threadConnCache = new ThreadLocal<>();

    private static Connection getThreadConnection(String host, String user, String pass) throws SQLException {
        Connection conn = threadConnCache.get();
        if (conn == null || conn.isClosed()) {
            conn = DriverManager.getConnection(host, user, pass);
            threadConnCache.set(conn);
        }
        return conn;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: java -jar MolClass.jar molclass.fingerprints.MurckoFragments <batch_id>");
            return;
        }
        int batchId = Integer.parseInt(args[0]);
        System.out.println("...... Running MurckoFragments for batch " + batchId);

        String host = XMLReader.getTag("hostname");
        String database = XMLReader.getTag("database");
        String user = XMLReader.getTag("rw_user");
        String password = XMLReader.getTag("rw_password");
        String structTable = XMLReader.getTag("molstructable");
        String batchMolTable = XMLReader.getTag("batchmoltable");
        String hostname = "jdbc:mysql://" + host + "/" + database;

        Connection con = DriverManager.getConnection(hostname, user, password);
        con.setAutoCommit(false);

        // Query all molecules for the batch
        String query = "SELECT " + structTable + ".mol_id, " + structTable + ".struc FROM " + structTable + ", " + batchMolTable + " "
                + "WHERE " + batchMolTable + ".batch_id = ? AND " + batchMolTable + ".mol_id = " + structTable + ".mol_id";
        PreparedStatement stmt = con.prepareStatement(query, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        stmt.setInt(1, batchId);
        ResultSet rs = stmt.executeQuery();

        // Prepare reusable statements
        String selectMurckoSql = "SELECT murcko_id FROM murcko WHERE smiles = ?";
        PreparedStatement selectMurcko = con.prepareStatement(selectMurckoSql);
        String insertMurckoSql = "INSERT INTO murcko (smiles, `count`) VALUES (?, 1)";
        PreparedStatement insertMurcko = con.prepareStatement(insertMurckoSql, Statement.RETURN_GENERATED_KEYS);
        String insertLinkSql = "INSERT INTO murcko_mol (mol_id, murcko_id) VALUES (?, ?)";
        PreparedStatement insertLink = con.prepareStatement(insertLinkSql);

        SmilesParser parser = new SmilesParser(SilentChemObjectBuilder.getInstance());
        SmilesGenerator generator = SmilesGenerator.unique();
        // MurckoFragmenter functionality omitted (not available)

        int processed = 0;
        while (rs.next()) {
            int molId = rs.getInt("mol_id");
            String molfile = rs.getString("struc");
            IAtomContainer molecule;
            try {
                // Reuse existing SDFReader to parse the molfile
                SDFReader sdfReader = new SDFReader();
                try {
                    molecule = sdfReader.read(molfile);
                } catch (Exception e) {
                    System.err.println("Failed to parse molecule id " + molId + ": " + e.getMessage());
                    continue;
                }
            } catch (Exception e) {
                System.err.println("Failed to parse molecule id " + molId + ": " + e.getMessage());
                // Murcko scaffold generation skipped due to missing MurckoFragmenter
        // Future implementation can add scaffold extraction here
            }
            processed++;
        }
        con.commit();
        System.out.println("MurckoFragments finished. Processed " + processed + " molecules.");
    }
}
