package molclass.importer;

import org.openscience.cdk.AtomContainer;
import org.openscience.cdk.io.IChemObjectReader;
import org.openscience.cdk.io.ISimpleChemObjectReader;
import org.openscience.cdk.io.MDLV2000Reader;
import org.openscience.cdk.io.MDLV3000Reader;
import org.openscience.cdk.interfaces.IAtomContainer;

import java.io.BufferedReader;
import java.security.DigestInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SdfAnalyzer {
    public static final String ANALYSIS_VERSION = "v3-sdf-analyzer-1";
    private static final long DEFAULT_MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int DEFAULT_MAX_RECORD_CHARS = 16 * 1024 * 1024;
    private static final Pattern PROPERTY_HEADER =
            Pattern.compile("^>\\s*<([^>]+)>.*$");
    private static final Pattern INTEGER =
            Pattern.compile("[+-]?(?:0|[1-9][0-9]*)");
    private static final Pattern DECIMAL =
            Pattern.compile("[+-]?(?:0|[1-9][0-9]*)\\.[0-9]+");
    private static final Pattern SCIENTIFIC =
            Pattern.compile("[+-]?(?:(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?|\\.[0-9]+)[eE][+-]?[0-9]+");
    private static final BigInteger INT_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private final Path sdfPath;
    private final long maxFileBytes;
    private final int maxRecordChars;
    private final List<String> identifierPriority;
    private final Analysis analysis = new Analysis();
    private int nextPropertyId = 1;

    private SdfAnalyzer(
            Path sdfPath, long maxFileBytes, int maxRecordChars, List<String> identifierPriority) {
        this.sdfPath = sdfPath;
        this.maxFileBytes = maxFileBytes;
        this.maxRecordChars = maxRecordChars;
        this.identifierPriority = List.copyOf(identifierPriority);
    }

    public static void analyze(Path sdf, Path output) throws Exception {
        new SdfAnalyzer(
                sdf,
                DEFAULT_MAX_FILE_BYTES,
                DEFAULT_MAX_RECORD_CHARS,
                List.of("identifier", "compound_id", "Compound_ID", "ID", "MOL_ID"))
                .write(output);
    }

    public static void main(String[] args) {
        try {
            Cli cli = Cli.parse(args);
            SdfAnalyzer analyzer = new SdfAnalyzer(
                    cli.sdf(), cli.maxFileBytes(), cli.maxRecordChars(), cli.identifierPriority());
            analyzer.write(cli.output());
            System.out.printf(
                    Locale.ROOT,
                    "Analyzed %,d records: %,d valid, %,d malformed; output=%s%n",
                    analyzer.analysis.totalRecords,
                    analyzer.analysis.validRecords,
                    analyzer.analysis.malformedRecords,
                    cli.output().toAbsolutePath());
        } catch (IllegalArgumentException exception) {
            System.err.println("Configuration error: " + exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("SDF analysis failed: " + safeMessage(exception));
            System.exit(3);
        }
    }

    private void write(Path output) throws Exception {
        Path absoluteInput = sdfPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absoluteInput) || !Files.isReadable(absoluteInput)) {
            throw new IllegalArgumentException("SDF file is not readable: " + absoluteInput);
        }
        long fileSize = Files.size(absoluteInput);
        if (fileSize > maxFileBytes) {
            throw new IllegalArgumentException(
                    "SDF file exceeds maximum size of " + maxFileBytes + " bytes");
        }
        analysis.originalFilename = absoluteInput.getFileName().toString();
        analysis.contentLength = fileSize;
        analysis.analyzedAt = Instant.now().toString();

        Path outputAbsolute = output.toAbsolutePath().normalize();
        Path outputParent = outputAbsolute.getParent();
        if (outputParent == null) {
            throw new IllegalArgumentException("output path must have a parent directory");
        }
        Files.createDirectories(outputParent);

        MessageDigest fileDigest = sha256();
        try (DistinctStore distinctStore = new DistinctStore();
             DigestInputStream digestInput =
                     new DigestInputStream(Files.newInputStream(absoluteInput), fileDigest);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(
                             digestInput,
                             StandardCharsets.UTF_8.newDecoder()
                                     .onMalformedInput(CodingErrorAction.REPORT)
                                     .onUnmappableCharacter(CodingErrorAction.REPORT)),
                     64 * 1024)) {
            streamRecords(reader, distinctStore);
        }
        analysis.contentSha256 = HexFormat.of().formatHex(fileDigest.digest());
        chooseIdentifier();

        String json = analysisJson();
        Path temporary = Files.createTempFile(outputParent, ".sdf-analysis-", ".json");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        outputAbsolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, outputAbsolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void streamRecords(BufferedReader reader, DistinctStore distinctStore) throws Exception {
        StringBuilder record = new StringBuilder();
        boolean oversized = false;
        boolean hasNonWhitespace = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if ("$$$$".equals(line)) {
                processRecord(record.toString(), oversized, false, distinctStore);
                record.setLength(0);
                oversized = false;
                hasNonWhitespace = false;
                continue;
            }
            if (!line.isBlank()) hasNonWhitespace = true;
            if (!oversized) {
                if ((long) record.length() + line.length() + 1 > maxRecordChars) {
                    oversized = true;
                    record.setLength(0);
                } else {
                    record.append(line).append('\n');
                }
            }
        }
        if (oversized || hasNonWhitespace) {
            analysis.missingFinalDelimiter = true;
            processRecord(record.toString(), oversized, true, distinctStore);
        }
    }

    private void processRecord(
            String record,
            boolean oversized,
            boolean missingDelimiter,
            DistinctStore distinctStore) throws Exception {
        long recordNumber = ++analysis.totalRecords;
        if (oversized) {
            malformed(recordNumber, "RECORD_TOO_LARGE",
                    "record exceeds " + maxRecordChars + " characters");
            return;
        }
        if (record.isBlank()) {
            malformed(recordNumber, "EMPTY_RECORD", "record contains no molecule data");
            return;
        }

        try {
            IAtomContainer molecule = readMolecule(record);
            if (molecule == null || molecule.getAtomCount() == 0) {
                malformed(recordNumber, "NO_ATOMS", "record contains no atoms");
                return;
            }
        } catch (Exception exception) {
            malformed(recordNumber, "MOLFILE_PARSE_FAILED", safeMessage(exception));
            return;
        }

        ParsedProperties properties = parseProperties(record);
        analysis.validRecords++;
        for (Map.Entry<String, String> entry : properties.values.entrySet()) {
            PropertyStats stats = analysis.properties.get(entry.getKey());
            if (stats == null) {
                stats = new PropertyStats(nextPropertyId++, entry.getKey());
                analysis.properties.put(entry.getKey(), stats);
            }
            boolean duplicateTag = properties.duplicateNames.contains(entry.getKey());
            stats.observe(entry.getValue(), duplicateTag, distinctStore);
        }
        distinctStore.commitRecord();

        if (missingDelimiter) {
            analysis.warnings.add("The final SDF record did not end with $$$$ and was accepted.");
        }
    }

    private void malformed(long recordNumber, String code, String message) {
        analysis.malformedRecords++;
        if (analysis.malformedExamples.size() < 10) {
            analysis.malformedExamples.add(
                    new MalformedExample(recordNumber, code, truncate(message, 240)));
        }
    }

    static IAtomContainer readMolecule(String record) throws Exception {
        String validationRecord = padV2000ForValidation(record);
        ISimpleChemObjectReader reader = validationRecord.contains("V3000")
                ? new MDLV3000Reader(new StringReader(validationRecord), IChemObjectReader.Mode.RELAXED)
                : new MDLV2000Reader(new StringReader(validationRecord), IChemObjectReader.Mode.RELAXED);
        try {
            return reader.read(new AtomContainer());
        } finally {
            reader.close();
        }
    }

    static String padV2000ForValidation(String record) {
        if (record.contains("V3000")) return record;
        String[] lines = record.split("\\n", -1);
        if (lines.length < 5) return record;
        int atomCount = fixedWidthInteger(lines[3], 0, 3);
        int bondCount = fixedWidthInteger(lines[3], 3, 6);
        if (atomCount < 0 || bondCount < 0 || 4L + atomCount + bondCount > lines.length) {
            return record;
        }
        for (int index = 4; index < 4 + atomCount; index++) {
            lines[index] = completeMdlFields(lines[index], 34, 36, 69);
        }
        for (int index = 4 + atomCount; index < 4 + atomCount + bondCount; index++) {
            lines[index] = completeMdlFields(lines[index], 9, 9, 21);
        }
        return String.join("\n", lines);
    }

    private static int fixedWidthInteger(String line, int start, int end) {
        if (line.length() < end) return -1;
        try {
            return Integer.parseInt(line.substring(start, end).trim());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static String completeMdlFields(
            String line, int minimumCoreLength, int firstOptionalBoundary, int maximumBoundary) {
        int effectiveLength = line.length();
        while (effectiveLength > 0 && line.charAt(effectiveLength - 1) == ' ') {
            effectiveLength--;
        }
        if (effectiveLength <= minimumCoreLength || effectiveLength > maximumBoundary) return line;
        int target = firstOptionalBoundary;
        while (target < effectiveLength && target < maximumBoundary) target += 3;
        if (target == effectiveLength || target > maximumBoundary) return line;
        return line.substring(0, effectiveLength)
                + " ".repeat(target - effectiveLength - 1) + "0";
    }

    private ParsedProperties parseProperties(String record) throws IOException {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        LinkedHashSet<String> duplicateNames = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(record))) {
            boolean afterMolEnd = false;
            String currentName = null;
            StringBuilder currentValue = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!afterMolEnd) {
                    if (line.startsWith("M  END")) afterMolEnd = true;
                    continue;
                }
                Matcher header = PROPERTY_HEADER.matcher(line);
                if (header.matches()) {
                    if (currentName != null) {
                        storeProperty(values, duplicateNames, currentName, currentValue.toString());
                    }
                    currentName = header.group(1).trim();
                    currentValue = new StringBuilder();
                    continue;
                }
                if (currentName == null) continue;
                if (line.isEmpty()) {
                    storeProperty(values, duplicateNames, currentName, currentValue.toString());
                    currentName = null;
                    currentValue = null;
                    continue;
                }
                if (currentValue.length() > 0) currentValue.append('\n');
                currentValue.append(line);
            }
            if (currentName != null) {
                storeProperty(values, duplicateNames, currentName, currentValue.toString());
            }
        }
        return new ParsedProperties(values, duplicateNames);
    }

    private static void storeProperty(
            Map<String, String> values,
            Set<String> duplicateNames,
            String name,
            String value) {
        if (name.isEmpty()) return;
        if (values.put(name, value) != null) duplicateNames.add(name);
    }

    private void chooseIdentifier() {
        List<PropertyStats> eligible = analysis.properties.values().stream()
                .filter(property -> property.identifierEligible(analysis.validRecords))
                .toList();
        if (eligible.isEmpty()) {
            analysis.warnings.add(
                    "No property is non-blank and unique across every valid molecule.");
            return;
        }
        analysis.autoSelectedIdentifier = eligible.stream()
                .min(Comparator
                        .comparingInt(this::identifierPriorityRank)
                        .thenComparingLong(property -> property.maximumLength)
                        .thenComparing(property -> property.name))
                .orElseThrow()
                .name;
    }

    private int identifierPriorityRank(PropertyStats property) {
        if ("Identifiers".equals(property.name)) return 0;
        int configured = identifierPriority.indexOf(property.name);
        return configured < 0 ? Integer.MAX_VALUE : configured + 1;
    }

    private String analysisJson() {
        StringBuilder json = new StringBuilder(16 * 1024);
        json.append("{\n");
        field(json, "analysisVersion", ANALYSIS_VERSION, true, 1);
        field(json, "analyzedAt", analysis.analyzedAt, true, 1);
        field(json, "originalFilename", analysis.originalFilename, true, 1);
        field(json, "contentSha256", analysis.contentSha256, true, 1);
        numberField(json, "contentLength", analysis.contentLength, true, 1);
        numberField(json, "totalRecords", analysis.totalRecords, true, 1);
        numberField(json, "validRecords", analysis.validRecords, true, 1);
        numberField(json, "malformedRecords", analysis.malformedRecords, true, 1);
        booleanField(json, "missingFinalDelimiter", analysis.missingFinalDelimiter, true, 1);
        nullableField(json, "autoSelectedIdentifier", analysis.autoSelectedIdentifier, true, 1);
        booleanField(json, "identifierConfirmationRequired", true, true, 1);

        indent(json, 1).append("\"identifierPriority\": ");
        stringArray(json, identifierPriority);
        json.append(",\n");

        indent(json, 1).append("\"properties\": [\n");
        List<PropertyStats> properties = new ArrayList<>(analysis.properties.values());
        properties.sort(Comparator.comparing(property -> property.name));
        for (int index = 0; index < properties.size(); index++) {
            propertyJson(json, properties.get(index), analysis.validRecords, 2);
            if (index + 1 < properties.size()) json.append(',');
            json.append('\n');
        }
        indent(json, 1).append("],\n");

        indent(json, 1).append("\"malformedExamples\": [");
        if (!analysis.malformedExamples.isEmpty()) json.append('\n');
        for (int index = 0; index < analysis.malformedExamples.size(); index++) {
            MalformedExample example = analysis.malformedExamples.get(index);
            indent(json, 2).append('{');
            json.append("\"recordNumber\":").append(example.recordNumber).append(',');
            json.append("\"code\":").append(quote(example.code)).append(',');
            json.append("\"message\":").append(quote(example.message)).append('}');
            if (index + 1 < analysis.malformedExamples.size()) json.append(',');
            json.append('\n');
        }
        if (!analysis.malformedExamples.isEmpty()) indent(json, 1);
        json.append("],\n");

        indent(json, 1).append("\"warnings\": ");
        stringArray(json, analysis.warnings);
        json.append('\n');
        json.append("}\n");
        return json.toString();
    }

    private static void propertyJson(
            StringBuilder json, PropertyStats property, long validRecords, int level) {
        indent(json, level).append("{\n");
        field(json, "name", property.name, true, level + 1);
        numberField(json, "presentCount", property.presentCount, true, level + 1);
        numberField(json, "missingCount", validRecords - property.presentCount, true, level + 1);
        numberField(json, "blankCount", property.blankCount, true, level + 1);
        numberField(json, "distinctCount", property.distinctCount, true, level + 1);
        numberField(json, "duplicateTagRecords", property.duplicateTagRecords, true, level + 1);
        numberField(json, "minimumLength",
                property.minimumLength == Long.MAX_VALUE ? 0 : property.minimumLength, true, level + 1);
        numberField(json, "maximumLength", property.maximumLength, true, level + 1);
        field(json, "inferredSqlType", property.inferredSqlType(), true, level + 1);
        field(json, "storageRecommendation", property.storageRecommendation(), true, level + 1);
        booleanField(json, "identifierEligible",
                property.identifierEligible(validRecords), true, level + 1);
        indent(json, level + 1).append("\"examples\": ");
        stringArray(json, property.examples);
        json.append(",\n");
        indent(json, level + 1).append("\"duplicateExamples\": ");
        stringArray(json, property.duplicateExamples);
        json.append('\n');
        indent(json, level).append('}');
    }

    private static void field(
            StringBuilder json, String name, String value, boolean comma, int level) {
        indent(json, level).append(quote(name)).append(": ").append(quote(value));
        if (comma) json.append(',');
        json.append('\n');
    }

    private static void nullableField(
            StringBuilder json, String name, String value, boolean comma, int level) {
        indent(json, level).append(quote(name)).append(": ")
                .append(value == null ? "null" : quote(value));
        if (comma) json.append(',');
        json.append('\n');
    }

    private static void numberField(
            StringBuilder json, String name, long value, boolean comma, int level) {
        indent(json, level).append(quote(name)).append(": ").append(value);
        if (comma) json.append(',');
        json.append('\n');
    }

    private static void booleanField(
            StringBuilder json, String name, boolean value, boolean comma, int level) {
        indent(json, level).append(quote(name)).append(": ").append(value);
        if (comma) json.append(',');
        json.append('\n');
    }

    private static void stringArray(StringBuilder json, List<String> values) {
        json.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) json.append(',');
            json.append(quote(values.get(index)));
        }
        json.append(']');
    }

    private static StringBuilder indent(StringBuilder json, int level) {
        return json.append("  ".repeat(level));
    }

    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] sha256(String value) {
        return sha256().digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) message = throwable.getClass().getSimpleName();
        return truncate(message.replace('\n', ' ').replace('\r', ' '), 512);
    }

    private static String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        return value.substring(0, maximum);
    }

    private static final class Analysis {
        String analyzedAt;
        String originalFilename;
        String contentSha256;
        long contentLength;
        long totalRecords;
        long validRecords;
        long malformedRecords;
        boolean missingFinalDelimiter;
        String autoSelectedIdentifier;
        final Map<String, PropertyStats> properties = new TreeMap<>();
        final List<MalformedExample> malformedExamples = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
    }

    private enum ValueType {
        NONE,
        INT,
        BIGINT,
        DECIMAL,
        DOUBLE,
        TEXT;

        static ValueType promote(ValueType left, ValueType right) {
            return left.ordinal() >= right.ordinal() ? left : right;
        }
    }

    private static final class PropertyStats {
        final int propertyId;
        final String name;
        long presentCount;
        long blankCount;
        long distinctCount;
        long duplicateTagRecords;
        long minimumLength = Long.MAX_VALUE;
        long maximumLength;
        long firstLength = -1;
        boolean constantLength = true;
        ValueType valueType = ValueType.NONE;
        int maximumIntegerDigits = 1;
        int maximumScale;
        final List<String> examples = new ArrayList<>();
        final List<String> duplicateExamples = new ArrayList<>();

        PropertyStats(int propertyId, String name) {
            this.propertyId = propertyId;
            this.name = name;
        }

        void observe(String rawValue, boolean duplicateTag, DistinctStore distinctStore)
                throws SQLException {
            presentCount++;
            if (duplicateTag) duplicateTagRecords++;
            String value = rawValue == null ? "" : rawValue.strip();
            if (value.isEmpty()) {
                blankCount++;
                return;
            }
            int length = value.codePointCount(0, value.length());
            minimumLength = Math.min(minimumLength, length);
            maximumLength = Math.max(maximumLength, length);
            if (firstLength < 0) firstLength = length;
            else if (firstLength != length) constantLength = false;
            if (examples.size() < 3) examples.add(truncate(value, 160));

            observeType(value);
            boolean newValue = distinctStore.add(propertyId, value);
            if (newValue) {
                distinctCount++;
            } else if (duplicateExamples.size() < 3) {
                duplicateExamples.add(truncate(value, 160));
            }
        }

        private void observeType(String value) {
            ValueType observed;
            if (INTEGER.matcher(value).matches()) {
                BigInteger integer = new BigInteger(value);
                int digits = integer.abs().toString().length();
                maximumIntegerDigits = Math.max(maximumIntegerDigits, digits);
                if (integer.compareTo(INT_MIN) >= 0 && integer.compareTo(INT_MAX) <= 0) {
                    observed = ValueType.INT;
                } else if (integer.compareTo(LONG_MIN) >= 0 && integer.compareTo(LONG_MAX) <= 0) {
                    observed = ValueType.BIGINT;
                } else if (digits <= 65) {
                    observed = ValueType.DECIMAL;
                } else {
                    observed = ValueType.DOUBLE;
                }
            } else if (DECIMAL.matcher(value).matches()) {
                BigDecimal decimal = new BigDecimal(value);
                int scale = Math.max(0, decimal.scale());
                int integerDigits = Math.max(1, decimal.precision() - scale);
                maximumIntegerDigits = Math.max(maximumIntegerDigits, integerDigits);
                maximumScale = Math.max(maximumScale, scale);
                observed = maximumScale <= 30
                                && maximumIntegerDigits + maximumScale <= 65
                        ? ValueType.DECIMAL : ValueType.DOUBLE;
            } else if (SCIENTIFIC.matcher(value).matches()) {
                observed = ValueType.DOUBLE;
            } else {
                observed = ValueType.TEXT;
            }
            valueType = ValueType.promote(valueType, observed);
        }

        String inferredSqlType() {
            return switch (valueType) {
                case NONE -> "VARCHAR(1)";
                case INT -> "INT";
                case BIGINT -> "BIGINT";
                case DECIMAL -> "DECIMAL(" + Math.min(65,
                        Math.max(1, maximumIntegerDigits + maximumScale)) + "," + maximumScale + ")";
                case DOUBLE -> "DOUBLE";
                case TEXT -> {
                    long safeLength = Math.max(1, maximumLength);
                    if (constantLength && safeLength <= 255) yield "CHAR(" + safeLength + ")";
                    if (safeLength <= 2048) yield "VARCHAR(" + safeLength + ")";
                    yield "TEXT";
                }
            };
        }

        String storageRecommendation() {
            String type = inferredSqlType();
            if ("TEXT".equals(type)) return "OVERFLOW";
            if (type.startsWith("VARCHAR(") && maximumLength > 1024) return "OVERFLOW";
            return "WIDE";
        }

        boolean identifierEligible(long validRecords) {
            return validRecords > 0
                    && presentCount == validRecords
                    && blankCount == 0
                    && distinctCount == validRecords
                    && duplicateTagRecords == 0;
        }
    }

    private static final class DistinctStore implements AutoCloseable {
        private final Path basePath;
        private final Connection connection;
        private final PreparedStatement select;
        private final PreparedStatement insert;

        DistinctStore() throws Exception {
            Path placeholder = Files.createTempFile("molclass-sdf-distinct-", "");
            Files.deleteIfExists(placeholder);
            basePath = placeholder.toAbsolutePath();
            String url = "jdbc:h2:file:" + basePath
                    + ";DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_UPPER=FALSE;LOCK_TIMEOUT=10000";
            connection = DriverManager.getConnection(url);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE distinct_value (
                            property_id INT NOT NULL,
                            value_hash BINARY(32) NOT NULL,
                            collision_ordinal INT NOT NULL,
                            value_text CLOB NOT NULL,
                            PRIMARY KEY (property_id, value_hash, collision_ordinal)
                        )
                        """);
            }
            connection.commit();
            select = connection.prepareStatement(
                    "SELECT collision_ordinal,value_text FROM distinct_value "
                            + "WHERE property_id=? AND value_hash=? ORDER BY collision_ordinal");
            insert = connection.prepareStatement(
                    "INSERT INTO distinct_value "
                            + "(property_id,value_hash,collision_ordinal,value_text) VALUES (?,?,?,?)");
        }

        boolean add(int propertyId, String value) throws SQLException {
            byte[] hash = sha256(value);
            select.setInt(1, propertyId);
            select.setBytes(2, hash);
            int nextOrdinal = 0;
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    nextOrdinal = Math.max(nextOrdinal, rows.getInt(1) + 1);
                    if (value.equals(rows.getString(2))) return false;
                }
            }
            insert.setInt(1, propertyId);
            insert.setBytes(2, hash);
            insert.setInt(3, nextOrdinal);
            insert.setString(4, value);
            insert.executeUpdate();
            return true;
        }

        void commitRecord() throws SQLException {
            connection.commit();
        }

        @Override
        public void close() throws Exception {
            try {
                select.close();
                insert.close();
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP ALL OBJECTS DELETE FILES");
                }
            } finally {
                connection.close();
                Files.deleteIfExists(Path.of(basePath + ".mv.db"));
                Files.deleteIfExists(Path.of(basePath + ".trace.db"));
            }
        }
    }

    private record ParsedProperties(
            LinkedHashMap<String, String> values, LinkedHashSet<String> duplicateNames) {}

    private record MalformedExample(long recordNumber, String code, String message) {}

    private record Cli(
            Path sdf,
            Path output,
            long maxFileBytes,
            int maxRecordChars,
            List<String> identifierPriority) {
        static Cli parse(String[] args) {
            if (args.length == 0 || !"analyze".equals(args[0])) {
                throw new IllegalArgumentException(
                        "usage: analyze --sdf <path> --output <path> "
                                + "[--max-file-bytes <n>] [--max-record-chars <n>] "
                                + "[--identifier-priority <name1,name2,...>]");
            }
            Path sdf = null;
            Path output = null;
            long maxFileBytes = DEFAULT_MAX_FILE_BYTES;
            int maxRecordChars = DEFAULT_MAX_RECORD_CHARS;
            List<String> priority = List.of(
                    "identifier", "compound_id", "Compound_ID", "ID", "MOL_ID");
            for (int index = 1; index < args.length; index++) {
                String option = args[index];
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                String value = args[++index];
                switch (option) {
                    case "--sdf" -> sdf = Path.of(value);
                    case "--output" -> output = Path.of(value);
                    case "--max-file-bytes" -> maxFileBytes = positiveLong(option, value);
                    case "--max-record-chars" -> {
                        long parsed = positiveLong(option, value);
                        if (parsed > Integer.MAX_VALUE) {
                            throw new IllegalArgumentException(option + " exceeds integer range");
                        }
                        maxRecordChars = (int) parsed;
                    }
                    case "--identifier-priority" -> {
                        LinkedHashSet<String> names = new LinkedHashSet<>();
                        for (String name : value.split(",", -1)) {
                            String trimmed = name.trim();
                            if (!trimmed.isEmpty() && !"Identifiers".equals(trimmed)) {
                                names.add(trimmed);
                            }
                        }
                        priority = List.copyOf(names);
                    }
                    default -> throw new IllegalArgumentException("unknown option: " + option);
                }
            }
            if (sdf == null) throw new IllegalArgumentException("--sdf is required");
            if (output == null) throw new IllegalArgumentException("--output is required");
            return new Cli(sdf, output, maxFileBytes, maxRecordChars, priority);
        }

        private static long positiveLong(String option, String value) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed <= 0) throw new NumberFormatException();
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(option + " requires a positive integer");
            }
        }
    }
}
