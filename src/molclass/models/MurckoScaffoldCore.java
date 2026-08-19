package molclass.models;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.BitSet;
import java.util.OptionalLong;

import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.aromaticity.Aromaticity;
import org.openscience.cdk.aromaticity.Kekulization;
import org.openscience.cdk.fingerprint.ExtendedFingerprinter;
import org.openscience.cdk.fragment.MurckoFragmenter;
import org.openscience.cdk.graph.Cycles;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IChemObjectBuilder;
import org.openscience.cdk.io.IChemObjectReader;
import org.openscience.cdk.io.ISimpleChemObjectReader;
import org.openscience.cdk.io.MDLV2000Reader;
import org.openscience.cdk.io.MDLV3000Reader;
import org.openscience.cdk.smiles.SmilesGenerator;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.tools.CDKHydrogenAdder;
import org.openscience.cdk.tools.manipulator.AtomContainerManipulator;

/**
 * Bemis-Murcko scaffold extraction and storage, shared between prediction-time applicability
 * scoring ({@code spring_boot_predictor}'s {@code MurckoScaffoldService}, which delegates here)
 * and training-time scaffold-stratified splitting ({@link V3ModelRebuilder}).
 * <p>
 * The legacy {@code molclass.fingerprints.MurckoFragments} tool was a stub: its own comments
 * say "MurckoFragmenter not available in current CDK version" and the fragmentation step was
 * never implemented, only stubbed out around the {@code murcko}/{@code murcko_mol} MyISAM
 * tables it was meant to populate. Those tables do hold real historical data (61k+ distinct
 * scaffold SMILES), so something else populated them at some point, but the checked-in Java
 * never did. CDK 2.12 (already a project dependency) does ship a working {@link
 * MurckoFragmenter}, so this reimplements the feature rather than resurrecting the stub,
 * writing into the v3 schema's {@code scaffold_definition}/{@code molecule_scaffold} tables
 * instead of the legacy pair.
 * <p>
 * This class is deliberately connection-per-call (never stores a {@link Connection} as a
 * field): it is used both by a long-lived Spring singleton juggling many short-lived request
 * connections, and by {@link V3ModelRebuilder}'s single-run {@code Worker}.
 */
public final class MurckoScaffoldCore {
    // Bumping this forces every scaffold to be recomputed and stored as a new row, the same
    // reproducibility contract fingerprint_definition.generation_version already uses. v2:
    // switched SmilesGenerator to .aromatic() output -- .unique() alone requires a fully
    // Kekulized structure (explicit alternating single/double bonds) and throws for some real
    // training molecules ("Cannot write Kekulé SMILES output due to aromatic bond with unset
    // bond order") where CDK's legacy aromaticity model marks bonds aromatic without a
    // consistent Kekulé resolution. Writing aromatic lowercase atoms directly sidesteps that
    // requirement entirely. v3: Kekulize the whole molecule right after reading it (see
    // readForScaffolding()), resolving MDL bond-type-4 "aromatic" bonds to a concrete order
    // before fragmentation instead of only after, which recovers many molecules that v2 still
    // failed on. v4: apply the same Kekulize-before-Aromaticity.apply() ordering fix to the
    // extracted fragment too (frameworkSmiles() had the same bug at the fragment level that v3
    // only fixed at the whole-molecule level).
    public static final String GENERATION_VERSION = "v3-cdk-2.12-murcko-v4";

    private static final IChemObjectBuilder CDK_BUILDER = DefaultChemObjectBuilder.getInstance();
    private static final SmilesGenerator CANONICAL_SMILES = SmilesGenerator.unique().aromatic();

    private final String schema;

    public MurckoScaffoldCore(String schema) {
        this.schema = schema;
    }

    private String t(String name) {
        return V3ModelRebuilder.qualifiedTable(schema, name);
    }

    /**
     * Returns the molecule's stored Murcko scaffold id, computing and storing it first if
     * necessary. Empty if the molecule has no ring system (Bemis-Murcko frameworks are
     * undefined for acyclic structures) or could not be parsed.
     */
    public OptionalLong ensureScaffold(Connection connection, long moleculeId) throws Exception {
        OptionalLong existing = existingScaffoldId(connection, moleculeId);
        if (existing.isPresent()) return existing;

        IAtomContainer molecule = loadMolecule(connection, moleculeId);
        if (molecule == null) return OptionalLong.empty();

        String scaffoldSmiles = frameworkSmiles(molecule);
        if (scaffoldSmiles == null) return OptionalLong.empty();

        long scaffoldId = upsertScaffoldDefinition(connection, scaffoldSmiles);
        linkMoleculeToScaffold(connection, moleculeId, scaffoldId);
        return OptionalLong.of(scaffoldId);
    }

    /** The Bemis-Murcko framework SMILES for an arbitrary structure, not tied to a stored molecule. */
    public String frameworkSmilesFor(IAtomContainer molecule) throws Exception {
        return frameworkSmiles(molecule);
    }

    /** A structural fingerprint of a Murcko framework, used for scaffold-space similarity. */
    public BitSet frameworkFingerprint(String scaffoldSmiles) throws Exception {
        IAtomContainer scaffold = configureForScaffolding(new SmilesParser(CDK_BUILDER).parseSmiles(scaffoldSmiles));
        return new ExtendedFingerprinter().getBitFingerprint(scaffold).asBitSet();
    }

    private String frameworkSmiles(IAtomContainer molecule) throws Exception {
        if (Cycles.mcb(molecule).numberOfCycles() == 0) return null;
        IAtomContainer scaffold = MurckoFragmenter.scaffold(molecule);
        if (scaffold == null || scaffold.getAtomCount() == 0) return null;
        // MurckoFragmenter.scaffold() returns a raw substructure copy: atoms that lost a
        // pruned substituent keep their original (now-wrong) implicit hydrogen count and
        // atom-type perception, e.g. an ipso carbon that had a branch attached looks
        // different from the same carbon in a molecule that never had one. Left alone, two
        // molecules sharing the identical ring system (say, toluene and ibuprofen, both a
        // bare benzene once side chains are stripped) canonicalize to *different* SMILES
        // purely from how many substituents used to hang off which atom -- which would
        // silently break scaffold deduplication and clustering, the entire point of storing
        // scaffold_sha256. Re-running the same atom-typing/hydrogen/Kekulization/aromaticity
        // pass used on the whole molecule in readForScaffolding() fixes the fragment the same
        // way -- and, same as there, Kekulization must run *before* Aromaticity.apply(), not
        // after: doing it after leaves some fragments with a ring bond flagged aromatic but
        // never given a concrete order, which fails much later with "Unsupported bond order:
        // UNSET" from the SMILES writer -- a confusing distance from the actual cause.
        scaffold = configureForScaffolding(scaffold);
        return CANONICAL_SMILES.create(scaffold);
    }

    private IAtomContainer loadMolecule(Connection connection, long moleculeId) throws Exception {
        String sql = "SELECT normalized_structure,canonical_smiles FROM " + t("molecule") + " WHERE molecule_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, moleculeId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                try {
                    return readForScaffolding(rows.getBytes(1), rows.getString(2));
                } catch (Exception unreadable) {
                    return null;
                }
            }
        }
    }

    /**
     * A dedicated reader for Murcko scaffolding, deliberately not sharing the prediction
     * pipeline's molecule loader. Registry structures stored as MDL molfiles often encode
     * aromatic rings with MDL bond type 4 ("aromatic"), which CDK's reader leaves with an
     * UNSET bond order; the prediction pipeline's own molecule configuration step only sets
     * aromaticity *flags* and never resolves that to a concrete Kekule form. Left alone, that
     * UNSET order survives into whatever ring atoms end up in the extracted Murcko fragment,
     * where it later breaks SMILES writing.
     * <p>
     * Resolving it here -- against the whole molecule, and specifically *before* re-perceiving
     * aromaticity -- recovers far more molecules than doing it only after fragmentation or after
     * aromaticity has already been (re)perceived: once side-chain atoms are pruned away,
     * Kekulization loses the very substituents that pin down which resonance structure is
     * correct, and once aromaticity has already been perceived on top of the unresolved bond
     * order, CDK's own Kekulization pass does not reliably revisit it. That ordering requirement
     * is specific enough to this feature that it is kept separate here rather than folded into
     * the shared prediction-pipeline molecule configuration step, which is also relied on by the
     * descriptor pipeline for already-validated models -- changing its aromaticity/bond-order
     * behavior there could shift live predictions, which is out of scope for a scaffold fix.
     * <p>
     * A minority of structures (e.g. some charged heterocycles) have no valid Kekule form at
     * all regardless of ordering -- that is a genuine property of the stored structure, not a
     * bug, so a failure here is swallowed and left for the caller to treat as unscaffoldable.
     */
    private IAtomContainer readForScaffolding(byte[] normalizedStructure, String canonicalSmiles) throws Exception {
        if (normalizedStructure != null && normalizedStructure.length > 0) {
            try {
                String molfile = new String(normalizedStructure, StandardCharsets.UTF_8);
                ISimpleChemObjectReader reader = molfile.contains("V3000")
                        ? new MDLV3000Reader(new java.io.StringReader(molfile), IChemObjectReader.Mode.RELAXED)
                        : new MDLV2000Reader(new java.io.StringReader(molfile), IChemObjectReader.Mode.RELAXED);
                try {
                    IAtomContainer molecule = reader.read(CDK_BUILDER.newInstance(IAtomContainer.class));
                    if (molecule != null && molecule.getAtomCount() > 0) return configureForScaffolding(molecule);
                } finally {
                    reader.close();
                }
            } catch (Exception ignored) {
                // fall through to the canonical SMILES below
            }
        }
        if (canonicalSmiles == null || canonicalSmiles.isBlank()) {
            throw new IllegalStateException("molecule has neither a readable structure nor canonical SMILES");
        }
        return configureForScaffolding(new SmilesParser(CDK_BUILDER).parseSmiles(canonicalSmiles));
    }

    private IAtomContainer configureForScaffolding(IAtomContainer molecule) throws Exception {
        AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(molecule);
        CDKHydrogenAdder.getInstance(CDK_BUILDER).addImplicitHydrogens(molecule);
        try {
            Kekulization.kekulize(molecule);
        } catch (Exception notKekulizable) {
            // some structures have no valid Kekule form at all; proceed with whatever bond
            // orders parsing produced and let aromaticity perception do what it can
        }
        Aromaticity.cdkLegacy().apply(molecule);
        return molecule;
    }

    private OptionalLong existingScaffoldId(Connection connection, long moleculeId) throws Exception {
        String sql = "SELECT ms.scaffold_id FROM " + t("molecule_scaffold") + " ms JOIN "
                + t("scaffold_definition") + " sd ON sd.scaffold_id=ms.scaffold_id "
                + "WHERE ms.molecule_id=? AND ms.primary_scaffold=1 AND sd.generation_version=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, moleculeId);
            statement.setString(2, GENERATION_VERSION);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? OptionalLong.of(rows.getLong(1)) : OptionalLong.empty();
            }
        }
    }

    private long upsertScaffoldDefinition(Connection connection, String scaffoldSmiles) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(scaffoldSmiles.getBytes(StandardCharsets.UTF_8));
        String selectSql = "SELECT scaffold_id FROM " + t("scaffold_definition")
                + " WHERE generation_version=? AND scaffold_sha256=?";
        try (PreparedStatement select = connection.prepareStatement(selectSql)) {
            select.setString(1, GENERATION_VERSION);
            select.setBytes(2, hash);
            try (ResultSet rows = select.executeQuery()) {
                if (rows.next()) return rows.getLong(1);
            }
        }
        String insertSql = "INSERT INTO " + t("scaffold_definition")
                + " (generation_version, scaffold_smiles, scaffold_sha256) VALUES (?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, GENERATION_VERSION);
            insert.setString(2, scaffoldSmiles);
            insert.setBytes(3, hash);
            try {
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (keys.next()) return keys.getLong(1);
                }
            } catch (SQLIntegrityConstraintViolationException raced) {
                // Another request computed and inserted the same scaffold first; read it back.
            }
        }
        try (PreparedStatement select = connection.prepareStatement(selectSql)) {
            select.setString(1, GENERATION_VERSION);
            select.setBytes(2, hash);
            try (ResultSet rows = select.executeQuery()) {
                if (rows.next()) return rows.getLong(1);
            }
        }
        throw new IllegalStateException("scaffold_definition insert raced but no row was found afterwards");
    }

    private void linkMoleculeToScaffold(Connection connection, long moleculeId, long scaffoldId) throws Exception {
        String sql = "INSERT IGNORE INTO " + t("molecule_scaffold")
                + " (molecule_id, scaffold_id, primary_scaffold) VALUES (?, ?, 1)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, moleculeId);
            statement.setLong(2, scaffoldId);
            statement.executeUpdate();
        }
    }
}
