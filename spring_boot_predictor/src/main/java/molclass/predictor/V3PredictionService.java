package molclass.predictor;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.BitSet;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.aromaticity.Aromaticity;
import org.openscience.cdk.fingerprint.SubstructureFingerprinter;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IChemObjectBuilder;
import org.openscience.cdk.isomorphism.VentoFoggia;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.tools.CDKHydrogenAdder;
import org.openscience.cdk.tools.manipulator.AtomContainerManipulator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import weka.classifiers.Classifier;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.SparseInstance;
import weka.core.Utils;

@Service
public class V3PredictionService {
    private static final String PRODUCTION_LABEL =
            "v3-cdk-2.12-weka-3.8.7-stratified-gzip-v1";
    private static final String ARTIFACT_FORMAT = "JAVA_SERIALIZATION_WEKA_3_8_7_GZIP";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern MANIFEST_NAME = Pattern.compile(
            "\\\"name\\\":\\\"((?:\\\\.|[^\\\"])*)\\\"");
    private static final Pattern INCHI_KEY = Pattern.compile("[A-Z]{14}-[A-Z]{10}-[A-Z]");
    private static final Pattern WILDCARD = Pattern.compile("[*?]");
    private static final IChemObjectBuilder CDK_BUILDER = DefaultChemObjectBuilder.getInstance();
    // Screening a fingerprint bit is O(1); the exact isomorphism check that follows is not.
    // This bounds worst-case request latency when a fragment is common enough to pass the
    // screen for many molecules but happens to verify true for very few (or none) of them.
    private static final int MAX_SUBSTRUCTURE_CANDIDATES_VERIFIED = 4000;

    private final DataSource dataSource;
    private final LinkedHashMap<Long, LoadedModel> cache = new LinkedHashMap<>(16, 0.75f, true);
    private volatile Long substructureFingerprintDefinitionId;

    @Value("${molclass.v3.schema:molclass_v3}")
    private String schema;

    @Value("${molclass.v3.model-cache-size:4}")
    private int cacheSize;

    @Value("${molclass.v3.max-compressed-artifact-bytes:134217728}")
    private long maxCompressedArtifactBytes;

    public V3PredictionService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void validateConfiguration() {
        if (!SAFE.matcher(schema).matches()) throw new IllegalStateException("unsafe v3 schema");
        if (cacheSize < 1 || cacheSize > 32) throw new IllegalStateException("model cache size must be 1..32");
        if (maxCompressedArtifactBytes < 1) throw new IllegalStateException("invalid artifact size limit");
    }

    public List<Map<String, Object>> searchModels(String query, int requestedLimit) throws Exception {
        if (query != null && query.length() > 255) {
            throw new IllegalArgumentException("model query must not exceed 255 characters");
        }
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        StringBuilder sql = new StringBuilder("SELECT md.model_definition_id,md.legacy_model_id,")
                .append("md.model_name,md.algorithm_code,fp.profile_code,mb.model_build_id,")
                .append("mb.training_count,mb.validation_count,mb.holdout_count,mb.excluded_count,")
                .append("mb.published_at,(SELECT me.metric_value FROM ").append(t("model_evaluation"))
                .append(" me WHERE me.model_build_id=mb.model_build_id AND me.evaluation_set='HOLDOUT'")
                .append(" AND me.metric_code='ACCURACY' LIMIT 1) holdout_accuracy FROM ")
                .append(t("model_definition")).append(" md JOIN ").append(t("model_build"))
                .append(" mb ON mb.model_build_id=md.published_model_build_id JOIN ")
                .append(t("feature_profile")).append(" fp ON fp.feature_profile_id=md.feature_profile_id")
                .append(" WHERE md.status='ACTIVE' AND mb.status='PUBLISHED'");
        boolean filtered = query != null && !query.isBlank();
        if (filtered) sql.append(" AND (md.model_name LIKE ? OR md.algorithm_code LIKE ? OR fp.profile_code LIKE ? OR CAST(md.legacy_model_id AS CHAR)=?)");
        sql.append(" ORDER BY md.model_definition_id LIMIT ?");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            if (filtered) {
                String like = "%" + query.trim() + "%";
                statement.setString(index++, like);statement.setString(index++, like);
                statement.setString(index++, like);statement.setString(index++, query.trim());
            }
            statement.setInt(index, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                while (rows.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("modelDefinitionId", rows.getLong(1));
                    row.put("legacyModelId", nullableLong(rows, 2));
                    row.put("name", rows.getString(3));row.put("algorithm", rows.getString(4));
                    row.put("featureProfile", rows.getString(5));row.put("modelBuildId", rows.getLong(6));
                    row.put("trainingCount", rows.getLong(7));row.put("validationCount", rows.getLong(8));
                    row.put("holdoutCount", rows.getLong(9));row.put("excludedCount", rows.getLong(10));
                    row.put("publishedAt", rows.getTimestamp(11));
                    double accuracy = rows.getDouble(12);row.put("holdoutAccuracy", rows.wasNull() ? null : accuracy);
                    result.add(row);
                }
                return result;
            }
        }
    }

    public List<Map<String, Object>> searchMolecules(String rawQuery, int requestedLimit)
            throws Exception {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) return List.of();
        if (query.length() > 512) {
            throw new IllegalArgumentException("molecule query must not exceed 512 characters");
        }
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        LinkedHashMap<Long, Map<String, Object>> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            if (WILDCARD.matcher(query).find()) {
                String likePattern = toLikePattern(query);
                addMoleculesByNamePattern(connection, result, likePattern, limit);
                if (result.size() < limit) {
                    addMoleculesByIdentifierPattern(connection, result, likePattern, limit);
                }
            } else if (query.chars().allMatch(Character::isDigit)) {
                addMolecules(connection, result, "m.molecule_id=?", query, limit);
            } else if (INCHI_KEY.matcher(query).matches()) {
                addMolecules(connection, result, "m.full_inchi_key=?", query, limit);
            } else {
                addMoleculesByIdentifier(connection, result, query, limit);
                if (result.size() < limit) {
                    addMolecules(connection, result, "m.canonical_smiles_sha256=UNHEX(SHA2(?,256))",
                            query, limit);
                }
                if (result.size() < limit) addMolecules(connection, result, "m.primary_name LIKE ?", query + "%", limit);
            }
        }
        return new ArrayList<>(result.values()).subList(0, Math.min(result.size(), limit));
    }

    /**
     * Translates the shell-style wildcards users actually type ({@code *} for any run of
     * characters, {@code ?} for exactly one) into a SQL {@code LIKE} pattern. Literal
     * {@code \}, {@code %}, and {@code _} in the query are escaped first so a query
     * containing a real percent sign or underscore is not misread as a wildcard.
     */
    private static String toLikePattern(String query) {
        StringBuilder pattern = new StringBuilder(query.length() + 8);
        for (int index = 0; index < query.length(); index++) {
            char character = query.charAt(index);
            switch (character) {
                case '\\', '%', '_' -> pattern.append('\\').append(character);
                case '*' -> pattern.append('%');
                case '?' -> pattern.append('_');
                default -> pattern.append(character);
            }
        }
        return pattern.toString();
    }

    /**
     * Substructure search: is the drawn/typed fragment present in each registry molecule?
     * <p>
     * The {@code molecule_fingerprint} table already carries a CDK {@code SubstructureFingerprinter}
     * (307-bit, fingerprint_code {@code SUB}) for every molecule {@link V3FeatureGenerator} has
     * processed. Those bits mark the presence of ~300 common substructure patterns and are a
     * necessary (not sufficient) condition for a true substructure match: if the query has a bit
     * set that a candidate does not, the candidate cannot contain the query fragment, and is
     * screened out without ever being parsed. Candidates that pass are then verified with a real
     * subgraph isomorphism test (CDK's VentoFoggia) against their stored structure, which is the
     * only step that can produce a false positive from screening alone.
     */
    public SubstructureSearchResult substructureSearch(String querySmiles, int requestedLimit) throws Exception {
        String smiles = querySmiles == null ? "" : querySmiles.trim();
        if (smiles.isEmpty()) return SubstructureSearchResult.empty();
        if (smiles.length() > 4096) {
            throw new IllegalArgumentException("query structure must not exceed 4096 characters");
        }
        int limit = Math.max(1, Math.min(requestedLimit, 100));

        IAtomContainer query;
        try {
            query = configureMolecule(new SmilesParser(CDK_BUILDER).parseSmiles(smiles));
        } catch (Exception parseFailure) {
            throw new IllegalArgumentException("could not parse the query structure: " + parseFailure.getMessage());
        }
        BitSet queryBits = new SubstructureFingerprinter().getBitFingerprint(query).asBitSet();
        org.openscience.cdk.isomorphism.Pattern queryPattern = VentoFoggia.findSubstructure(query);

        LinkedHashMap<Long, Map<String, Object>> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            Long definitionId = substructureFingerprintDefinitionId(connection);
            if (definitionId == null) return SubstructureSearchResult.empty();

            Coverage coverage = coverage(connection, definitionId);
            ScreenResult screen = screenByFingerprint(connection, definitionId, queryBits, limit);
            if (screen.ids().isEmpty()) {
                return new SubstructureSearchResult(List.of(), !screen.exhaustive(),
                        coverage.indexed(), coverage.total());
            }

            boolean verifiedEveryCandidate = true;
            String sql = "SELECT m.molecule_id,m.normalized_structure,m.canonical_smiles,m.full_inchi_key,"
                    + "m.primary_name,m.normalization_status FROM " + t("molecule")
                    + " m WHERE m.molecule_id IN ("
                    + String.join(",", screen.ids().stream().map(String::valueOf).toList()) + ")"
                    + " ORDER BY m.molecule_id";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                    ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() >= limit) {
                        verifiedEveryCandidate = false;
                        break;
                    }
                    long moleculeId = rows.getLong(1);
                    IAtomContainer candidate;
                    try {
                        candidate = loadCandidate(rows.getBytes(2), rows.getString(3));
                    } catch (Exception unreadable) {
                        continue;
                    }
                    if (!queryPattern.matches(candidate)) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("moleculeId", moleculeId);
                    row.put("inchiKey", rows.getString(4));
                    row.put("canonicalSmiles", rows.getString(3));
                    row.put("name", rows.getString(5));
                    row.put("normalizationStatus", rows.getString(6));
                    result.put(moleculeId, row);
                }
            }
            // Truncated whenever some indexed molecule was never verified: either the
            // candidate screen stopped early, or the page filled while candidates remained.
            boolean truncated = !screen.exhaustive() || !verifiedEveryCandidate;
            return new SubstructureSearchResult(new ArrayList<>(result.values()), truncated,
                    coverage.indexed(), coverage.total());
        }
    }

    /**
     * A page of substructure matches plus the honesty fields the UI needs: whether more
     * matches may exist beyond this page, and how much of the registry is actually indexed
     * for substructure search at all.
     */
    public record SubstructureSearchResult(
            List<Map<String, Object>> items, boolean truncated,
            long indexedMolecules, long totalMolecules) {
        static SubstructureSearchResult empty() {
            return new SubstructureSearchResult(List.of(), false, 0, 0);
        }
    }

    private record ScreenResult(List<Long> ids, boolean exhaustive) {}

    private record Coverage(long indexed, long total) {}

    private Coverage coverage(Connection connection, long definitionId) throws Exception {
        long indexed = 0;
        long total = 0;
        String indexedSql = "SELECT COUNT(*) FROM " + t("molecule_fingerprint")
                + " WHERE fingerprint_definition_id=? AND status='SUCCEEDED'";
        try (PreparedStatement statement = connection.prepareStatement(indexedSql)) {
            statement.setLong(1, definitionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) indexed = rows.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + t("molecule"));
                ResultSet rows = statement.executeQuery()) {
            if (rows.next()) total = rows.getLong(1);
        }
        return new Coverage(indexed, total);
    }

    private ScreenResult screenByFingerprint(Connection connection, long definitionId, BitSet queryBits, int limit)
            throws Exception {
        String sql = "SELECT molecule_id,fingerprint_bits FROM " + t("molecule_fingerprint")
                + " WHERE fingerprint_definition_id=? AND status='SUCCEEDED' ORDER BY molecule_id";
        // Collect more candidates than the page needs, because the fingerprint screen
        // over-selects: some candidates will fail the exact isomorphism check below.
        int candidateCap = Math.min(Math.max(limit * 20, 200), MAX_SUBSTRUCTURE_CANDIDATES_VERIFIED);
        List<Long> screened = new ArrayList<>();
        boolean exhaustive = true;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, definitionId);
            statement.setFetchSize(2000);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (screened.size() >= candidateCap) {
                        exhaustive = false;
                        break;
                    }
                    byte[] bits = rows.getBytes(2);
                    if (bits == null) continue;
                    if (isFingerprintSuperset(bits, queryBits)) screened.add(rows.getLong(1));
                }
            }
        }
        return new ScreenResult(screened, exhaustive);
    }

    /** True if every bit set in {@code query} is also set in {@code candidateBits}. */
    private static boolean isFingerprintSuperset(byte[] candidateBits, BitSet query) {
        for (int index = query.nextSetBit(0); index >= 0; index = query.nextSetBit(index + 1)) {
            if (!bit(candidateBits, index)) return false;
        }
        return true;
    }

    private Long substructureFingerprintDefinitionId(Connection connection) throws Exception {
        Long cached = substructureFingerprintDefinitionId;
        if (cached != null) return cached;
        String sql = "SELECT fingerprint_definition_id FROM " + t("fingerprint_definition")
                + " WHERE fingerprint_code='SUB' ORDER BY fingerprint_definition_id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) return null;
            long id = rows.getLong(1);
            substructureFingerprintDefinitionId = id;
            return id;
        }
    }

    private static IAtomContainer loadCandidate(byte[] normalizedStructure, String canonicalSmiles) throws Exception {
        if (normalizedStructure != null && normalizedStructure.length > 0) {
            try {
                String molfile = new String(normalizedStructure, StandardCharsets.UTF_8);
                org.openscience.cdk.io.ISimpleChemObjectReader reader = molfile.contains("V3000")
                        ? new org.openscience.cdk.io.MDLV3000Reader(new java.io.StringReader(molfile),
                                org.openscience.cdk.io.IChemObjectReader.Mode.RELAXED)
                        : new org.openscience.cdk.io.MDLV2000Reader(new java.io.StringReader(molfile),
                                org.openscience.cdk.io.IChemObjectReader.Mode.RELAXED);
                try {
                    IAtomContainer molecule = reader.read(CDK_BUILDER.newInstance(IAtomContainer.class));
                    if (molecule != null && molecule.getAtomCount() > 0) return configureMolecule(molecule);
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
        return configureMolecule(new SmilesParser(CDK_BUILDER).parseSmiles(canonicalSmiles));
    }

    /** Mirrors V3FeatureGenerator's molecule preparation so fingerprint bits stay comparable. */
    private static IAtomContainer configureMolecule(IAtomContainer molecule) throws Exception {
        AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(molecule);
        CDKHydrogenAdder.getInstance(CDK_BUILDER).addImplicitHydrogens(molecule);
        Aromaticity.cdkLegacy().apply(molecule);
        return molecule;
    }

    private void addMoleculesByNamePattern(Connection connection,
            LinkedHashMap<Long, Map<String, Object>> result, String likePattern, int limit) throws Exception {
        String sql = "SELECT m.molecule_id,m.full_inchi_key,LEFT(m.canonical_smiles,512),m.primary_name,"
                + "m.normalization_status,NULL source_identifier FROM " + t("molecule")
                + " m WHERE m.primary_name LIKE ? ESCAPE '\\\\' ORDER BY m.molecule_id LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, likePattern);statement.setInt(2, limit);collectMolecules(statement, result);
        }
    }

    private void addMoleculesByIdentifierPattern(Connection connection,
            LinkedHashMap<Long, Map<String, Object>> result, String likePattern, int limit) throws Exception {
        String sql = "SELECT m.molecule_id,m.full_inchi_key,LEFT(m.canonical_smiles,512),m.primary_name,"
                + "m.normalization_status,dm.source_identifier FROM " + t("dataset_molecule")
                + " dm JOIN " + t("molecule") + " m ON m.molecule_id=dm.molecule_id"
                + " WHERE dm.source_identifier LIKE ? ESCAPE '\\\\' ORDER BY dm.dataset_molecule_id LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, likePattern);statement.setInt(2, limit);collectMolecules(statement, result);
        }
    }

    public Prediction predict(long modelDefinitionId, long moleculeId) throws Exception {
        if (modelDefinitionId <= 0 || moleculeId <= 0) throw new IllegalArgumentException("positive IDs required");
        try (Connection connection = dataSource.getConnection()) {
            ModelReference reference = modelReference(connection, modelDefinitionId);
            LoadedModel loaded = loadedModel(connection, reference);
            Instance instance = featureInstance(connection, reference, moleculeId, loaded.header);
            double[] distribution;
            synchronized (loaded.classifier) {
                distribution = loaded.classifier.distributionForInstance(instance);
            }
            LinkedHashMap<String, Double> classes = new LinkedHashMap<>();
            int best = 0;
            for (int index = 0; index < distribution.length; index++) {
                classes.put(loaded.header.classAttribute().value(index), distribution[index]);
                if (distribution[index] > distribution[best]) best = index;
            }
            return new Prediction(modelDefinitionId, reference.buildId, moleculeId,
                    loaded.header.classAttribute().value(best), classes);
        }
    }

    private ModelReference modelReference(Connection connection, long definitionId) throws Exception {
        String sql = "SELECT md.published_model_build_id,md.feature_profile_id,mb.generation_label "
                + "FROM " + t("model_definition") + " md JOIN " + t("model_build")
                + " mb ON mb.model_build_id=md.published_model_build_id WHERE md.model_definition_id=?"
                + " AND md.status='ACTIVE' AND mb.status='PUBLISHED'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, definitionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new NoSuchElementException("model is not published: " + definitionId);
                if (!PRODUCTION_LABEL.equals(row.getString(3))) {
                    throw new IllegalStateException("published model uses unsupported generation contract");
                }
                return new ModelReference(definitionId, row.getLong(1), row.getLong(2));
            }
        }
    }

    private synchronized LoadedModel loadedModel(Connection connection, ModelReference reference)
            throws Exception {
        LoadedModel existing = cache.get(reference.buildId);
        if (existing != null) return existing;
        Object modelObject = null, headerObject = null;
        String sql = "SELECT artifact_kind,artifact_format,artifact_size,artifact_sha256,artifact_payload FROM "
                + t("model_artifact") + " WHERE model_build_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, reference.buildId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String kind = rows.getString(1);String format = rows.getString(2);
                    long declaredSize = rows.getLong(3);byte[] expected = rows.getBytes(4);
                    if (!ARTIFACT_FORMAT.equals(format)) {
                        throw new IllegalStateException("artifact integrity failure for " + kind);
                    }
                    Object artifact = deserializeArtifact(rows, declaredSize, expected,
                            maxCompressedArtifactBytes, kind);
                    if ("MODEL".equals(kind)) modelObject = artifact;
                    else if ("HEADER".equals(kind)) headerObject = artifact;
                    else throw new IllegalStateException("unexpected artifact kind " + kind);
                }
            }
        }
        if (modelObject == null || headerObject == null) {
            throw new IllegalStateException("MODEL and HEADER artifacts required");
        }
        if (!(modelObject instanceof Classifier classifier) || !(headerObject instanceof Instances instances)) {
            throw new IllegalStateException("artifact types do not match classifier/header contract");
        }
        if (instances.classIndex() < 0 || instances.classIndex() != instances.numAttributes() - 1) {
            throw new IllegalStateException("model header has invalid class index");
        }
        LoadedModel loaded = new LoadedModel(classifier, instances);
        cache.put(reference.buildId, loaded);
        while (cache.size() > cacheSize) cache.remove(cache.keySet().iterator().next());
        return loaded;
    }

    private Instance featureInstance(Connection connection, ModelReference reference, long moleculeId,
            Instances header) throws Exception {
        double[] values = new double[header.numAttributes()];int feature = 0;
        String componentSql = "SELECT c.descriptor_generation_id,c.fingerprint_definition_id,"
                + "fd.fingerprint_code,fd.bit_length,ds.descriptor_manifest_json FROM "
                + t("feature_profile_component") + " c LEFT JOIN " + t("fingerprint_definition")
                + " fd ON fd.fingerprint_definition_id=c.fingerprint_definition_id LEFT JOIN "
                + t("descriptor_schema") + " ds ON ds.descriptor_generation_id=c.descriptor_generation_id"
                + " WHERE c.feature_profile_id=? ORDER BY c.component_order";
        try (PreparedStatement componentStatement = connection.prepareStatement(componentSql)) {
            componentStatement.setLong(1, reference.featureProfileId);
            try (ResultSet components = componentStatement.executeQuery()) {
                while (components.next()) {
                    Long descriptorId = nullableLong(components, 1);
                    Long fingerprintId = nullableLong(components, 2);
                    if (descriptorId != null) {
                        List<String> names = manifestNames(components.getString(5));
                        String sql = "SELECT status,descriptor_values,missing_value_mask FROM "
                                + t("molecule_descriptor_vector")
                                + " WHERE descriptor_generation_id=? AND molecule_id=?";
                        try (PreparedStatement statement = connection.prepareStatement(sql)) {
                            statement.setLong(1, descriptorId);statement.setLong(2, moleculeId);
                            try (ResultSet row = statement.executeQuery()) {
                                if (!row.next() || !row.getString(1).startsWith("SUCCEEDED")) {
                                    throw new NoSuchElementException("molecule lacks ready descriptors");
                                }
                                byte[] vector = row.getBytes(2), mask = row.getBytes(3);
                                double[] decoded = decodeDoubles(vector, names.size());
                                for (int index = 0; index < decoded.length; index++) {
                                    requireAttribute(header, feature, names.get(index));
                                    values[feature++] = bit(mask, index) ? Utils.missingValue() : decoded[index];
                                }
                            }
                        }
                    } else if (fingerprintId != null) {
                        String code = components.getString(3);int length = components.getInt(4);
                        String sql = "SELECT status,fingerprint_bits FROM " + t("molecule_fingerprint")
                                + " WHERE fingerprint_definition_id=? AND molecule_id=?";
                        try (PreparedStatement statement = connection.prepareStatement(sql)) {
                            statement.setLong(1, fingerprintId);statement.setLong(2, moleculeId);
                            try (ResultSet row = statement.executeQuery()) {
                                if (!row.next() || !"SUCCEEDED".equals(row.getString(1))) {
                                    throw new NoSuchElementException("molecule lacks ready " + code + " fingerprint");
                                }
                                byte[] bits = row.getBytes(2);
                                for (int index = 0; index < length; index++) {
                                    requireAttribute(header, feature, code + "_" + index);
                                    values[feature++] = bit(bits, index) ? 1.0 : 0.0;
                                }
                            }
                        }
                    } else throw new IllegalStateException("empty feature profile component");
                }
            }
        }
        if (feature != header.numAttributes() - 1) throw new IllegalStateException("feature/header length mismatch");
        values[feature] = Utils.missingValue();
        Instance instance = new SparseInstance(1.0, values);instance.setDataset(header);return instance;
    }

    private void addMolecules(Connection connection, LinkedHashMap<Long, Map<String, Object>> result,
            String predicate, String value, int limit) throws Exception {
        String sql = "SELECT m.molecule_id,m.full_inchi_key,LEFT(m.canonical_smiles,512),m.primary_name,"
                + "m.normalization_status,NULL source_identifier FROM " + t("molecule")
                + " m WHERE " + predicate + " ORDER BY m.molecule_id LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (predicate.equals("m.molecule_id=?")) statement.setLong(1, Long.parseLong(value));
            else statement.setString(1, value);
            statement.setInt(2, limit);collectMolecules(statement, result);
        }
    }

    private void addMoleculesByIdentifier(Connection connection,
            LinkedHashMap<Long, Map<String, Object>> result, String identifier, int limit) throws Exception {
        String sql = "SELECT m.molecule_id,m.full_inchi_key,LEFT(m.canonical_smiles,512),m.primary_name,"
                + "m.normalization_status,dm.source_identifier FROM " + t("dataset_molecule")
                + " dm JOIN " + t("molecule") + " m ON m.molecule_id=dm.molecule_id"
                + " WHERE dm.source_identifier=? ORDER BY dm.dataset_molecule_id LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identifier);statement.setInt(2, limit);collectMolecules(statement, result);
        }
    }

    private static void collectMolecules(PreparedStatement statement,
            LinkedHashMap<Long, Map<String, Object>> result) throws Exception {
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong(1);Map<String, Object> row = result.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
                row.put("moleculeId", id);row.put("inchiKey", rows.getString(2));row.put("canonicalSmiles", rows.getString(3));
                row.put("name", rows.getString(4));row.put("normalizationStatus", rows.getString(5));
                if (rows.getString(6) != null) row.put("sourceIdentifier", rows.getString(6));
            }
        }
    }

    private static void requireAttribute(Instances header, int index, String expected) {
        if (index >= header.numAttributes() - 1 || !expected.equals(header.attribute(index).name())) {
            throw new IllegalStateException("feature header mismatch at " + index + ": expected " + expected);
        }
    }

    static Object deserializeArtifact(ResultSet row, long declaredSize, byte[] expectedSha256,
            long maximumSize, String kind) throws Exception {
        if (declaredSize < 1 || declaredSize > maximumSize || expectedSha256 == null
                || expectedSha256.length != 32) {
            throw new IllegalStateException("artifact integrity failure for " + kind);
        }
        InputStream jdbcStream = row.getBinaryStream(5);
        if (jdbcStream == null) {
            throw new IllegalStateException("artifact integrity failure for " + kind);
        }
        try (jdbcStream) {
            ArtifactInputStream artifactStream = new ArtifactInputStream(
                    jdbcStream, declaredSize, MessageDigest.getInstance("SHA-256"));
            Object artifact = null;
            Exception decodeFailure = null;
            try {
                artifact = deserialize(artifactStream);
            } catch (Exception exception) {
                decodeFailure = exception;
            }

            byte[] buffer = new byte[8192];
            while (artifactStream.read(buffer) != -1) {
                // Consume the complete declared payload so size and digest cover every byte.
            }
            boolean hasTrailingBytes = jdbcStream.read() != -1;
            if (artifactStream.bytesRead() != declaredSize || hasTrailingBytes
                    || !MessageDigest.isEqual(expectedSha256, artifactStream.digest())) {
                throw new IllegalStateException("artifact integrity failure for " + kind);
            }
            if (decodeFailure != null) throw decodeFailure;
            return artifact;
        }
    }

    private static Object deserialize(InputStream payload) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(
                new GZIPInputStream(payload))) {
            input.setObjectInputFilter(info -> {
                if (info.depth() > 256 || info.references() > 2_000_000
                        || info.streamBytes() > 1_073_741_824L || info.arrayLength() > 100_000_000L) {
                    return ObjectInputFilter.Status.REJECTED;
                }
                Class<?> type = info.serialClass();
                if (type == null) return ObjectInputFilter.Status.UNDECIDED;
                while (type.isArray()) type = type.getComponentType();
                if (type.isPrimitive()) return ObjectInputFilter.Status.ALLOWED;
                String name = type.getName();
                return name.startsWith("weka.") || name.startsWith("libsvm.")
                        || name.startsWith("java.lang.") || name.startsWith("java.util.")
                        || name.startsWith("java.math.") || name.startsWith("java.time.")
                        || name.equals("java.io.File") || name.startsWith("no.uib.cipr.matrix.")
                        || name.startsWith("Jama.") ? ObjectInputFilter.Status.ALLOWED
                                : ObjectInputFilter.Status.REJECTED;
            });
            return input.readObject();
        }
    }

    private String t(String name) {
        if (!SAFE.matcher(name).matches()) throw new IllegalArgumentException("unsafe table name");
        return "`" + schema + "`.`" + name + "`";
    }

    private static List<String> manifestNames(String json) {
        List<String> names = new ArrayList<>();Matcher matcher = MANIFEST_NAME.matcher(json);
        while (matcher.find()) names.add(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        return names;
    }

    private static double[] decodeDoubles(byte[] bytes, int count) throws Exception {
        if (bytes == null || bytes.length != count * 8) throw new IllegalStateException("descriptor vector length mismatch");
        double[] values = new double[count];
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            for (int index = 0; index < count; index++) values[index] = input.readDouble();
        }
        return values;
    }

    private static boolean bit(byte[] bytes, int index) {
        return bytes != null && (bytes[index >>> 3] & (1 << (index & 7))) != 0;
    }

    private static final class ArtifactInputStream extends InputStream {
        private final InputStream delegate;
        private final long limit;
        private final MessageDigest digest;
        private long bytesRead;

        private ArtifactInputStream(InputStream delegate, long limit, MessageDigest digest) {
            this.delegate = delegate;
            this.limit = limit;
            this.digest = digest;
        }

        @Override
        public int read() throws java.io.IOException {
            if (bytesRead == limit) return -1;
            int value = delegate.read();
            if (value != -1) {
                digest.update((byte) value);
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
            if (length == 0) return 0;
            if (bytesRead == limit) return -1;
            int boundedLength = (int) Math.min(length, limit - bytesRead);
            int count = delegate.read(bytes, offset, boundedLength);
            if (count > 0) {
                digest.update(bytes, offset, count);
                bytesRead += count;
            }
            return count;
        }

        private long bytesRead() {
            return bytesRead;
        }

        private byte[] digest() {
            return digest.digest();
        }
    }

    private static Long nullableLong(ResultSet rows, int index) throws Exception {
        long value = rows.getLong(index);return rows.wasNull() ? null : value;
    }

    private record ModelReference(long definitionId, long buildId, long featureProfileId) { }
    private record LoadedModel(Classifier classifier, Instances header) { }
    public record Prediction(long modelDefinitionId, long modelBuildId, long moleculeId,
            String predictedClass, Map<String, Double> distribution) { }
}
