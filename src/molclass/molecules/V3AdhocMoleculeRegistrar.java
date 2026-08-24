package molclass.molecules;

import org.openscience.cdk.aromaticity.Aromaticity;
import org.openscience.cdk.inchi.InChIGenerator;
import org.openscience.cdk.inchi.InChIGeneratorFactory;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IChemObjectBuilder;
import org.openscience.cdk.io.MDLV2000Writer;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesGenerator;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.tools.CDKHydrogenAdder;
import org.openscience.cdk.tools.manipulator.AtomContainerManipulator;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.regex.Pattern;

/**
 * Registers one ad-hoc molecule from a bare SMILES string, for the single-molecule
 * prediction pipeline (a caller has a structure that isn't in any dataset yet, and
 * wants it registered and predicted on, not imported as part of a batch).
 *
 * Deliberately not a general-purpose reuse of V3SdfImporter: that class exists to
 * import many records from one SDF as a dataset, and its identity-computation
 * fallback (a salted hash keyed by dataset/record position, for when CDK
 * normalization fails) only makes sense in that batch context. Here, a SMILES CDK
 * cannot normalize is a client error to report clearly, not something to paper over
 * with a placeholder molecule that could never gain real descriptors anyway.
 */
public final class V3AdhocMoleculeRegistrar {
    private static final String NORMALIZATION_VERSION = "molclass-v3-cdk-2.12-normalization-1";
    private static final Pattern INCHI_KEY = Pattern.compile("[A-Z]{14}-[A-Z]{10}-[A-Z]");
    private static final IChemObjectBuilder BUILDER = SilentChemObjectBuilder.getInstance();

    private V3AdhocMoleculeRegistrar() {
    }

    public record Registered(long moleculeId, byte[] normalizedStructure, String canonicalSmiles) {
    }

    public static Registered register(Connection connection, String schema, String smiles) throws Exception {
        String trimmed = smiles == null ? null : smiles.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new IllegalArgumentException("smiles must not be blank");
        }
        IAtomContainer molecule;
        try {
            molecule = new SmilesParser(BUILDER).parseSmiles(trimmed);
            AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(molecule);
            CDKHydrogenAdder.getInstance(BUILDER).addImplicitHydrogens(molecule);
            Aromaticity.cdkLegacy().apply(molecule);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "CDK could not parse or normalize this SMILES: " + safeMessage(exception), exception);
        }
        String canonicalSmiles = SmilesGenerator.unique().create(molecule);
        InChIGenerator generator = InChIGeneratorFactory.getInstance().getInChIGenerator(molecule);
        String inchi = trim(generator.getInchi());
        String inchiKey = trim(generator.getInchiKey());
        if (inchi == null || inchiKey == null || !INCHI_KEY.matcher(inchiKey).matches()) {
            throw new IllegalArgumentException("CDK did not produce a full InChI identity for this SMILES");
        }
        byte[] normalized = writeMolfile(molecule);
        byte[] normalizedHash = sha256(normalized);

        long moleculeId = findOrInsert(
                connection, schema, inchi, inchiKey, normalizedHash, normalized,
                canonicalSmiles, trimmed);
        return new Registered(moleculeId, normalized, canonicalSmiles);
    }

    private static long findOrInsert(
            Connection connection, String schema, String inchi, String inchiKey,
            byte[] normalizedHash, byte[] normalized, String canonicalSmiles, String primaryName)
            throws Exception {
        Long existing = lookup(connection, schema, inchi, inchiKey, normalizedHash);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + t(schema, "molecule")
                        + " (normalization_version,normalization_status,normalized_structure,"
                        + "normalized_structure_sha256,standard_inchi,full_inchi_key,"
                        + "canonical_smiles,canonical_smiles_sha256,primary_name,"
                        + "canonicalization_error) VALUES (?,'IMPORTED_CDK_2_12',?,?,?,?,?,?,?,NULL)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, NORMALIZATION_VERSION);
            insert.setBytes(2, normalized);
            insert.setBytes(3, normalizedHash);
            insert.setString(4, inchi);
            insert.setString(5, inchiKey);
            insert.setString(6, canonicalSmiles);
            insert.setBytes(7, sha256(canonicalSmiles.getBytes(StandardCharsets.UTF_8)));
            insert.setString(8, truncate(primaryName, 512));
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException duplicate) {
            // Concurrent submission of the same structure: the unique keys on
            // full_inchi_key / (normalization_version, normalized_structure_sha256) reject
            // the second insert. Re-select rather than fail -- the other request already
            // registered the molecule we wanted.
            if (!isDuplicateKey(duplicate)) {
                throw duplicate;
            }
            Long resolved = lookup(connection, schema, inchi, inchiKey, normalizedHash);
            if (resolved == null) {
                throw duplicate;
            }
            return resolved;
        }
    }

    private static Long lookup(
            Connection connection, String schema, String inchi, String inchiKey, byte[] normalizedHash)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT molecule_id,standard_inchi FROM " + t(schema, "molecule")
                        + " WHERE full_inchi_key=?")) {
            select.setString(1, inchiKey);
            try (ResultSet row = select.executeQuery()) {
                if (row.next() && inchi.equals(row.getString(2))) {
                    return row.getLong(1);
                }
            }
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT molecule_id,standard_inchi FROM " + t(schema, "molecule")
                        + " WHERE normalization_version=? AND normalized_structure_sha256=?")) {
            select.setString(1, NORMALIZATION_VERSION);
            select.setBytes(2, normalizedHash);
            try (ResultSet row = select.executeQuery()) {
                if (row.next() && inchi.equals(row.getString(2))) {
                    return row.getLong(1);
                }
            }
        }
        return null;
    }

    private static boolean isDuplicateKey(SQLException exception) {
        // MariaDB/MySQL: SQLState 23000 ("integrity constraint violation"), vendor code 1062
        // (ER_DUP_ENTRY) specifically. Broader than just checking the message text, which
        // varies by driver/locale.
        return "23000".equals(exception.getSQLState()) || exception.getErrorCode() == 1062;
    }

    private static byte[] writeMolfile(IAtomContainer molecule) throws Exception {
        StringWriter output = new StringWriter();
        try (MDLV2000Writer writer = new MDLV2000Writer(output)) {
            writer.write(molecule);
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String t(String schema, String table) {
        return "`" + schema + "`.`" + table + "`";
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int maximum) {
        if (value == null) return null;
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
