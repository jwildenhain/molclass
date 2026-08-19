package molclass.importer;

import org.openscience.cdk.aromaticity.Aromaticity;
import org.openscience.cdk.inchi.InChIGenerator;
import org.openscience.cdk.inchi.InChIGeneratorFactory;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.io.MDLV2000Writer;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesGenerator;
import org.openscience.cdk.tools.CDKHydrogenAdder;
import org.openscience.cdk.tools.manipulator.AtomContainerManipulator;

import java.io.BufferedReader;
import java.security.DigestInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class V3SdfImporter {
    private static final String JOB_TYPE = "SDF_IMPORT";
    private static final String NORMALIZATION_VERSION = "molclass-v3-cdk-2.12-normalization-1";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern PROPERTY_HEADER = Pattern.compile("^>\\s*<([^>]+)>.*$");
    private static final Pattern INCHI_KEY = Pattern.compile("[A-Z]{14}-[A-Z]{10}-[A-Z]");
    private static final Pattern TYPE = Pattern.compile(
            "^(INT|BIGINT|DOUBLE|TEXT|CHAR\\(([1-9][0-9]{0,3})\\)|"
                    + "VARCHAR\\(([1-9][0-9]{0,4})\\)|DECIMAL\\(([1-9][0-9]?),([0-9]{1,2})\\))$");
    private static final org.openscience.cdk.interfaces.IChemObjectBuilder BUILDER = SilentChemObjectBuilder.getInstance();
    private static final int MAX_RECORD_CHARS = 16 * 1024 * 1024;

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String schema;
    private final Path uploadRoot;
    private final String workerId;
    private final int leaseSeconds;

    private V3SdfImporter(
            String jdbcUrl,
            String user,
            String password,
            String schema,
            Path uploadRoot,
            String workerId,
            int leaseSeconds) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.schema = schema;
        this.uploadRoot = uploadRoot;
        this.workerId = workerId;
        this.leaseSeconds = leaseSeconds;
    }

    static boolean runOne(
            String jdbcUrl,
            String user,
            String password,
            String schema,
            Path uploadRoot,
            String workerId,
            int leaseSeconds) throws Exception {
        V3SdfImporter importer = new V3SdfImporter(
                jdbcUrl, user, password, schema, uploadRoot, workerId, leaseSeconds);
        ImportClaim claim = importer.claim();
        if (claim == null) return false;
        importer.process(claim);
        return true;
    }

    static void recoverExpired(
            String jdbcUrl, String user, String password, String schema) throws SQLException {
        V3SdfImporter importer = new V3SdfImporter(
                jdbcUrl, user, password, schema, Path.of("."), "recovery", 120);
        try (Connection connection = importer.connection()) {
            connection.setAutoCommit(false);
            String sql = "SELECT j.job_id,ir.import_run_id,ir.dataset_id,"
                    + "j.attempt_count,j.maximum_attempts FROM " + importer.t("job") + " j "
                    + "JOIN " + importer.t("import_run") + " ir ON ir.job_id=j.job_id "
                    + "WHERE j.job_type=? AND j.status IN ('LEASED','RUNNING') "
                    + "AND j.lease_expires_at<UTC_TIMESTAMP(6) FOR UPDATE";
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                select.setString(1, JOB_TYPE);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        boolean retry = rows.getInt(4) < rows.getInt(5);
                        importer.recoverOne(
                                connection,
                                rows.getLong(1),
                                rows.getLong(2),
                                rows.getLong(3),
                                retry);
                    }
                }
            }
            connection.commit();
        }
    }

    private void recoverOne(
            Connection connection,
            long jobId,
            long importRunId,
            long datasetId,
            boolean retry) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + t("job") + " SET status=?,runstep=?,lease_owner=NULL,"
                        + "lease_expires_at=NULL,heartbeat_at=NULL,available_at=UTC_TIMESTAMP(6),"
                        + "error_code=?,error_message=?,finished_at="
                        + (retry ? "NULL" : "UTC_TIMESTAMP(6)") + " WHERE job_id=?")) {
            update.setString(1, retry ? "QUEUED" : "FAILED");
            update.setString(2, retry ? "RECOVERED" : "ATTEMPTS_EXHAUSTED");
            update.setString(3, retry ? "WORKER_LEASE_EXPIRED" : "MAXIMUM_ATTEMPTS_EXHAUSTED");
            update.setString(4, "expired import worker lease");
            update.setLong(5, jobId);
            update.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + t("import_run") + " SET status=?,runstep=?,error_code=?,"
                        + "error_message=?,finished_at="
                        + (retry ? "NULL" : "UTC_TIMESTAMP(6)") + " WHERE import_run_id=?")) {
            update.setString(1, retry ? "QUEUED" : "FAILED");
            update.setString(2, retry ? "RECOVERED" : "FAILED");
            update.setString(3, retry ? "WORKER_LEASE_EXPIRED" : "MAXIMUM_ATTEMPTS_EXHAUSTED");
            update.setString(4, "expired import worker lease");
            update.setLong(5, importRunId);
            update.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + t("dataset") + " SET status=? WHERE dataset_id=?")) {
            update.setString(1, retry ? "IMPORT_QUEUED" : "IMPORT_FAILED");
            update.setLong(2, datasetId);
            update.executeUpdate();
        }
        event(connection, jobId, retry ? "LEASE_RECOVERED" : "JOB_FAILED",
                retry ? "RECOVERED" : "FAILED", "expired import lease recovered");
    }

    private ImportClaim claim() throws SQLException {
        try (Connection connection = connection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            String sql = "SELECT j.job_id,ir.import_run_id,ir.upload_id,ir.dataset_id,"
                    + "ir.identifier_property_name,ir.total_records,ir.success_records,"
                    + "ir.failed_records,ir.not_processed_records,u.storage_key,"
                    + "u.content_sha256,u.content_length FROM " + t("job") + " j "
                    + "JOIN " + t("import_run") + " ir ON ir.job_id=j.job_id "
                    + "JOIN " + t("upload_artifact") + " u ON u.upload_id=ir.upload_id "
                    + "WHERE j.job_type=? AND j.status='QUEUED' "
                    + "AND j.available_at<=UTC_TIMESTAMP(6) "
                    + "ORDER BY j.priority DESC,j.job_id LIMIT 1 FOR UPDATE SKIP LOCKED";
            ImportClaim claim = null;
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                select.setString(1, JOB_TYPE);
                try (ResultSet row = select.executeQuery()) {
                    if (row.next()) {
                        claim = new ImportClaim(
                                row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4),
                                row.getString(5), row.getLong(6), row.getLong(7),
                                row.getLong(8), row.getLong(9), row.getString(10),
                                row.getBytes(11), row.getLong(12));
                    }
                }
            }
            if (claim == null) {
                connection.rollback();
                return null;
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("job") + " SET status='RUNNING',runstep='VERIFY_UPLOAD',"
                            + "lease_owner=?,lease_expires_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),"
                            + "heartbeat_at=UTC_TIMESTAMP(6),attempt_count=attempt_count+1,"
                            + "started_at=COALESCE(started_at,UTC_TIMESTAMP(6)),"
                            + "error_code=NULL,error_message=NULL WHERE job_id=?")) {
                update.setString(1, workerId);
                update.setInt(2, leaseSeconds);
                update.setLong(3, claim.jobId);
                update.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("import_run") + " SET status='RUNNING',"
                            + "runstep='VERIFY_UPLOAD',started_at=COALESCE(started_at,UTC_TIMESTAMP(6)),"
                            + "error_code=NULL,error_message=NULL WHERE import_run_id=?")) {
                update.setLong(1, claim.importRunId);
                update.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("dataset") + " SET status='IMPORTING' WHERE dataset_id=?")) {
                update.setLong(1, claim.datasetId);
                update.executeUpdate();
            }
            event(connection, claim.jobId, "JOB_STARTED", "VERIFY_UPLOAD",
                    "import claimed by " + workerId);
            connection.commit();
            return claim;
        }
    }

    private void process(ImportClaim claim) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sdf-import-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        AtomicBoolean leaseLost = new AtomicBoolean();
        long period = Math.max(5, leaseSeconds / 3L);
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        if (!heartbeat(claim.jobId)) leaseLost.set(true);
                    } catch (Exception exception) {
                        System.err.println("Import heartbeat failed: " + safeMessage(exception));
                    }
                }, period, period, TimeUnit.SECONDS);
        try {
            Path source = resolveStoragePath(claim.storageKey);
            verifyUpload(source, claim.contentLength, claim.contentSha256);
            transition(claim, "PREFLIGHT_PROPERTIES");
            List<PropertyBinding> properties = prepareProperties(claim);
            transition(claim, "IMPORT_RECORDS");
            importRecords(source, claim, properties, leaseLost);
            if (leaseLost.get()) throw new SQLException("worker lease was lost");
            finish(claim);
            System.out.println("Imported dataset " + claim.datasetId + " from upload " + claim.uploadId);
        } catch (Exception exception) {
            try {
                failJob(claim, exception);
            } catch (Exception persistenceFailure) {
                System.err.println("Could not persist import failure: " + safeMessage(persistenceFailure));
            }
        } finally {
            heartbeat.cancel(true);
            scheduler.shutdownNow();
        }
    }

    private List<PropertyBinding> prepareProperties(ImportClaim claim) throws Exception {
        try (Connection ddl = connection()) {
            if (!namedLock(ddl, "molclass_v3_property_schema", 30)) {
                throw new SQLException("property schema lock is unavailable");
            }
            try {
                List<AnalyzedProperty> analyzed = analyzedProperties(ddl, claim.importRunId);
                if (analyzed.isEmpty()) throw new IllegalStateException("selected property manifest is empty");
                List<PropertyBinding> bindings = new ArrayList<>();
                for (AnalyzedProperty property : analyzed) {
                    bindings.add(resolveProperty(ddl, claim, property));
                }
                persistPropertyManifest(ddl, claim, bindings);
                return bindings;
            } finally {
                releaseNamedLock(ddl, "molclass_v3_property_schema");
            }
        }
    }

    private List<AnalyzedProperty> analyzedProperties(Connection connection, long importRunId)
            throws SQLException {
        List<String> selected = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT selected.property_name FROM " + t("import_run") + " ir "
                        + "JOIN JSON_TABLE(ir.selected_properties_json,'$[*]' "
                        + "COLUMNS(property_name VARCHAR(255) PATH '$')) selected ON TRUE "
                        + "WHERE ir.import_run_id=?")) {
            statement.setLong(1, importRunId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) selected.add(rows.getString(1));
            }
        }
        Map<String, AnalyzedProperty> available = new LinkedHashMap<>();
        String sql = "SELECT analyzed.property_name,analyzed.inferred_type,"
                + "analyzed.storage_recommendation,analyzed.present_count,"
                + "analyzed.blank_count,analyzed.distinct_count FROM " + t("import_run") + " ir "
                + "JOIN " + t("upload_artifact") + " u ON u.upload_id=ir.upload_id "
                + "JOIN JSON_TABLE(u.analysis_json,'$.properties[*]' COLUMNS("
                + "property_name VARCHAR(255) PATH '$.name',"
                + "inferred_type VARCHAR(64) PATH '$.inferredSqlType',"
                + "storage_recommendation VARCHAR(16) PATH '$.storageRecommendation',"
                + "present_count BIGINT PATH '$.presentCount',"
                + "blank_count BIGINT PATH '$.blankCount',"
                + "distinct_count BIGINT PATH '$.distinctCount')) analyzed ON TRUE "
                + "WHERE ir.import_run_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, importRunId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    AnalyzedProperty property = new AnalyzedProperty(
                            rows.getString(1), SqlType.parse(rows.getString(2)),
                            rows.getString(3), rows.getLong(4), rows.getLong(5), rows.getLong(6));
                    available.put(property.name, property);
                }
            }
        }
        List<AnalyzedProperty> result = new ArrayList<>();
        for (String name : selected) {
            AnalyzedProperty property = available.get(name);
            if (property == null) throw new IllegalStateException("selected property missing: " + name);
            result.add(property);
        }
        return result;
    }

    private PropertyBinding resolveProperty(
            Connection connection, ImportClaim claim, AnalyzedProperty analyzed) throws Exception {
        String selectSql = "SELECT property_id,physical_column_name,storage_mode,sql_type_ddl "
                + "FROM " + t("property_definition") + " WHERE original_name=?";
        try (PreparedStatement select = connection.prepareStatement(selectSql)) {
            select.setString(1, analyzed.name);
            try (ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    long propertyId = row.getLong(1);
                    String column = row.getString(2);
                    String storage = row.getString(3);
                    SqlType current = SqlType.parse(row.getString(4));
                    SqlType resolved = SqlType.widen(current, analyzed.type);
                    if (!resolved.ddl.equals(current.ddl)) {
                        String ddl = null;
                        if ("WIDE".equals(storage)) {
                            ddl = "ALTER TABLE " + t("dataset_molecule_properties")
                                    + " MODIFY COLUMN `" + column + "` " + resolved.ddl + " NULL";
                            executeDdl(connection, ddl);
                        }
                        updatePropertyType(connection, propertyId, resolved);
                        schemaChange(connection, claim.importRunId, propertyId,
                                "WIDEN", current.ddl, resolved.ddl,
                                ddl == null ? "REGISTRY " + resolved.ddl : ddl);
                    }
                    return new PropertyBinding(
                            propertyId, analyzed.name, column, storage, resolved,
                            analyzed.present, analyzed.blank, analyzed.distinct);
                }
            }
        }

        String storage = "OVERFLOW".equals(analyzed.storageRecommendation) ? "OVERFLOW" : "WIDE";
        String column = physicalColumn(connection, analyzed.name);
        String ddl = null;
        if ("WIDE".equals(storage) && !columnExists(connection, column)) {
            ddl = "ALTER TABLE " + t("dataset_molecule_properties")
                    + " ADD COLUMN `" + column + "` " + analyzed.type.ddl + " NULL";
            executeDdl(connection, ddl);
        }
        long propertyId;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + t("property_definition")
                        + " (original_name,physical_column_name,storage_mode,sql_type_family,"
                        + "sql_type_ddl,nullable_value,maximum_length,numeric_precision_value,"
                        + "numeric_scale_value,active) VALUES (?,?,?,?,?,1,?,?,?,1)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, analyzed.name);
            insert.setString(2, column);
            insert.setString(3, storage);
            insert.setString(4, analyzed.type.family);
            insert.setString(5, analyzed.type.ddl);
            setNullableInt(insert, 6, analyzed.type.maximumLength());
            setNullableInt(insert, 7, analyzed.type.precision());
            setNullableInt(insert, 8, analyzed.type.scale());
            insert.executeUpdate();
            propertyId = generatedKey(insert);
        }
        schemaChange(connection, claim.importRunId, propertyId,
                "ADD", null, analyzed.type.ddl,
                ddl == null ? "OVERFLOW " + analyzed.type.ddl : ddl);
        return new PropertyBinding(
                propertyId, analyzed.name, column, storage, analyzed.type,
                analyzed.present, analyzed.blank, analyzed.distinct);
    }

    private void persistPropertyManifest(
            Connection connection, ImportClaim claim, List<PropertyBinding> bindings)
            throws SQLException {
        connection.setAutoCommit(false);
        String datasetSql = "INSERT INTO " + t("dataset_property")
                + " (dataset_id,property_id,selected_for_import,identifier_property,"
                + "model_target_allowed,searchable,present_count,blank_count,distinct_count,"
                + "inferred_sql_type,resolved_sql_type) VALUES (?,?,1,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE selected_for_import=1,"
                + "identifier_property=VALUES(identifier_property),"
                + "model_target_allowed=VALUES(model_target_allowed),"
                + "searchable=VALUES(searchable),present_count=VALUES(present_count),"
                + "blank_count=VALUES(blank_count),distinct_count=VALUES(distinct_count),"
                + "inferred_sql_type=VALUES(inferred_sql_type),"
                + "resolved_sql_type=VALUES(resolved_sql_type)";
        String runSql = "INSERT INTO " + t("import_run_property")
                + " (import_run_id,property_id,source_property_name,selected_for_import,"
                + "identifier_property,inferred_sql_type,resolved_sql_type) "
                + "VALUES (?,?,?,1,?,?,?) ON DUPLICATE KEY UPDATE "
                + "selected_for_import=1,identifier_property=VALUES(identifier_property),"
                + "inferred_sql_type=VALUES(inferred_sql_type),"
                + "resolved_sql_type=VALUES(resolved_sql_type)";
        long identifierId = 0;
        try (PreparedStatement dataset = connection.prepareStatement(datasetSql);
             PreparedStatement run = connection.prepareStatement(runSql)) {
            for (PropertyBinding binding : bindings) {
                boolean identifier = binding.name.equals(claim.identifierProperty);
                boolean target = !identifier
                        && binding.present == claim.totalRecords
                        && binding.blank == 0
                        && binding.distinct >= 2
                        && binding.distinct <= 100;
                dataset.setLong(1, claim.datasetId);
                dataset.setLong(2, binding.propertyId);
                dataset.setBoolean(3, identifier);
                dataset.setBoolean(4, target);
                dataset.setBoolean(5, identifier);
                dataset.setLong(6, binding.present);
                dataset.setLong(7, binding.blank);
                dataset.setLong(8, binding.distinct);
                dataset.setString(9, binding.type.ddl);
                dataset.setString(10, binding.type.ddl);
                dataset.addBatch();

                run.setLong(1, claim.importRunId);
                run.setLong(2, binding.propertyId);
                run.setString(3, binding.name);
                run.setBoolean(4, identifier);
                run.setString(5, binding.type.ddl);
                run.setString(6, binding.type.ddl);
                run.addBatch();
                if (identifier) identifierId = binding.propertyId;
            }
            dataset.executeBatch();
            run.executeBatch();
        }
        if (identifierId == 0) throw new IllegalStateException("identifier property was not selected");
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + t("dataset") + " SET identifier_property_id=? WHERE dataset_id=?")) {
            update.setLong(1, identifierId);
            update.setLong(2, claim.datasetId);
            update.executeUpdate();
        }
        connection.commit();
        connection.setAutoCommit(true);
    }

    private void importRecords(
            Path source,
            ImportClaim claim,
            List<PropertyBinding> bindings,
            AtomicBoolean leaseLost) throws Exception {
        long failed = claim.failedRecords;
        boolean cutoff = failed * 20 > claim.totalRecords;
        try (RecordReader records = new RecordReader(source)) {
            SdfRecord record;
            while ((record = records.next()) != null) {
                if (leaseLost.get()) throw new SQLException("worker lease was lost");
                Map<String, String> values = record.oversized
                        ? Map.of() : parseProperties(record.text);
                String identifier = trimmed(values.get(claim.identifierProperty));
                if (cutoff) {
                    markNotProcessed(claim, record.number, identifier);
                    continue;
                }
                StartRecord start = startRecord(claim, record.number, identifier);
                if (start.terminal) continue;
                try {
                    if (record.oversized) {
                        throw new RecordException("RECORD_TOO_LARGE", "record exceeds 16 MiB");
                    }
                    if (identifier == null) {
                        throw new RecordException(
                                "IDENTIFIER_MISSING", "identifier property is missing or blank");
                    }
                    if (identifier.length() > 512) {
                        throw new RecordException(
                                "IDENTIFIER_TOO_LONG", "identifier exceeds 512 characters");
                    }
                    IAtomContainer molecule = SdfAnalyzer.readMolecule(record.text);
                    completeRecord(claim, start.importRecordId, record, identifier,
                            molecule, values, bindings);
                } catch (SQLException exception) {
                    if (infrastructureFailure(exception)) throw exception;
                    failRecord(claim, start.importRecordId, "DATABASE_RECORD_REJECTED",
                            safeMessage(exception));
                    failed++;
                } catch (RecordException exception) {
                    failRecord(claim, start.importRecordId, exception.code, exception.getMessage());
                    failed++;
                } catch (Exception exception) {
                    failRecord(claim, start.importRecordId, "MOLECULE_IMPORT_FAILED",
                            safeMessage(exception));
                    failed++;
                }
                if (failed * 20 > claim.totalRecords) cutoff = true;
                if (record.number % 25 == 0) transition(claim, "IMPORT_RECORDS");
            }
            if (records.count != claim.totalRecords) {
                throw new PermanentImportException(
                        "record count changed after analysis: expected " + claim.totalRecords
                                + " but found " + records.count);
            }
        }
    }

    private StartRecord startRecord(
            ImportClaim claim, long recordNumber, String identifier) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT import_record_id,status FROM " + t("import_record")
                            + " WHERE import_run_id=? AND record_number=? FOR UPDATE")) {
                select.setLong(1, claim.importRunId);
                select.setLong(2, recordNumber);
                try (ResultSet row = select.executeQuery()) {
                    if (row.next()) {
                        long id = row.getLong(1);
                        String status = row.getString(2);
                        if (Set.of("SUCCEEDED", "FAILED", "NOT_PROCESSED", "SKIPPED")
                                .contains(status)) {
                            connection.rollback();
                            return new StartRecord(id, true);
                        }
                        try (PreparedStatement update = connection.prepareStatement(
                                "UPDATE " + t("import_record")
                                        + " SET source_identifier=?,status='RUNNING',"
                                        + "runstep='PARSE',attempt_count=attempt_count+1,"
                                        + "started_at=UTC_TIMESTAMP(6),finished_at=NULL,"
                                        + "error_code=NULL,error_message=NULL "
                                        + "WHERE import_record_id=?")) {
                            setNullableString(update, 1, identifier);
                            update.setLong(2, id);
                            update.executeUpdate();
                        }
                        connection.commit();
                        return new StartRecord(id, false);
                    }
                }
            }
            if (identifier != null && identifierAlreadyUsed(
                    connection, claim.importRunId, recordNumber, identifier)) {
                long id = insertTracker(connection, claim.importRunId, recordNumber, null);
                connection.commit();
                failRecord(claim, id, "DUPLICATE_IDENTIFIER",
                        "source identifier is duplicated in this dataset");
                return new StartRecord(id, true);
            }
            long id = insertTracker(
                    connection, claim.importRunId, recordNumber, identifier);
            connection.commit();
            return new StartRecord(id, false);
        }
    }

    private boolean identifierAlreadyUsed(
            Connection connection, long runId, long recordNumber, String identifier)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + t("import_record")
                        + " WHERE import_run_id=? AND source_identifier=? AND record_number<>? LIMIT 1")) {
            statement.setLong(1, runId);
            statement.setString(2, identifier);
            statement.setLong(3, recordNumber);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private long insertTracker(
            Connection connection, long runId, long recordNumber, String identifier)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + t("import_record")
                        + " (import_run_id,record_number,source_identifier,status,runstep,"
                        + "attempt_count,started_at) VALUES (?,?,?,'RUNNING','PARSE',1,UTC_TIMESTAMP(6))",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, runId);
            insert.setLong(2, recordNumber);
            setNullableString(insert, 3, identifier);
            insert.executeUpdate();
            return generatedKey(insert);
        }
    }

    private void completeRecord(
            ImportClaim claim,
            long importRecordId,
            SdfRecord record,
            String identifier,
            IAtomContainer molecule,
            Map<String, String> values,
            List<PropertyBinding> bindings) throws Exception {
        byte[] sourceStructure = molBlock(record.text).getBytes(StandardCharsets.UTF_8);
        byte[] sourceHash = sha256(sourceStructure);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            MoleculeIdentity identity = identity(
                    molecule, record.text, identifier, claim.datasetId, record.number);
            long moleculeId = findOrInsertMolecule(connection, identity, identifier);
            long datasetMoleculeId;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + t("dataset_molecule")
                            + " (dataset_id,molecule_id,import_record_id,record_number,"
                            + "source_identifier,source_structure,source_structure_sha256,"
                            + "source_structure_format) VALUES (?,?,?,?,?,?,?,'MOLFILE')",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, claim.datasetId);
                insert.setLong(2, moleculeId);
                insert.setLong(3, importRecordId);
                insert.setLong(4, record.number);
                insert.setString(5, identifier);
                insert.setBytes(6, sourceStructure);
                insert.setBytes(7, sourceHash);
                insert.executeUpdate();
                datasetMoleculeId = generatedKey(insert);
            }
            insertProperties(connection, datasetMoleculeId, values, bindings);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("import_record")
                            + " SET molecule_id=?,status='SUCCEEDED',runstep='COMPLETE',"
                            + "normalization_version=?,finished_at=UTC_TIMESTAMP(6),"
                            + "error_code=NULL,error_message=NULL WHERE import_record_id=?")) {
                update.setLong(1, moleculeId);
                update.setString(2, NORMALIZATION_VERSION);
                update.setLong(3, importRecordId);
                update.executeUpdate();
            }
            incrementCounters(connection, claim, 1, 0, 0);
            connection.commit();
        }
    }

    private MoleculeIdentity identity(
            IAtomContainer molecule,
            String sourceRecord,
            String identifier,
            long datasetId,
            long recordNumber) {
        try {
            AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(molecule);
            CDKHydrogenAdder.getInstance(BUILDER).addImplicitHydrogens(molecule);
            Aromaticity.cdkLegacy().apply(molecule);
            String smiles = SmilesGenerator.unique().create(molecule);
            InChIGenerator generator =
                    InChIGeneratorFactory.getInstance().getInChIGenerator(molecule);
            String inchi = trimmed(generator.getInchi());
            String inchiKey = trimmed(generator.getInchiKey());
            if (inchi == null || inchiKey == null || !INCHI_KEY.matcher(inchiKey).matches()) {
                throw new IllegalArgumentException("CDK did not produce a full InChI identity");
            }
            byte[] normalized = writeMolfile(molecule);
            return new MoleculeIdentity(
                    "IMPORTED_CDK_2_12", normalized, sha256(normalized),
                    inchi, inchiKey, smiles, null);
        } catch (Exception exception) {
            byte[] normalized = molBlock(SdfAnalyzer.padV2000ForValidation(sourceRecord))
                    .getBytes(StandardCharsets.UTF_8);
            byte[] salted = sha256((HexFormat.of().formatHex(sha256(normalized))
                    + "\u0000UNMERGED\u0000" + datasetId + "\u0000" + recordNumber)
                    .getBytes(StandardCharsets.UTF_8));
            return new MoleculeIdentity(
                    "IMPORTED_UNMERGED", normalized, salted,
                    null, null, null, truncate(safeMessage(exception), 2048));
        }
    }

    private long findOrInsertMolecule(
            Connection connection, MoleculeIdentity identity, String primaryName) throws Exception {
        if (identity.inchiKey != null) {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT molecule_id,standard_inchi FROM " + t("molecule")
                            + " WHERE full_inchi_key=?")) {
                select.setString(1, identity.inchiKey);
                try (ResultSet row = select.executeQuery()) {
                    if (row.next()) {
                        if (identity.inchi.equals(row.getString(2))) return row.getLong(1);
                        identity = identity.withIdentityConflict(
                                "full InChIKey matched but standard InChI differed");
                    }
                }
            }
        }
        if (identity.inchiKey != null) {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT molecule_id,standard_inchi FROM " + t("molecule")
                            + " WHERE normalization_version=? "
                            + "AND normalized_structure_sha256=?")) {
                select.setString(1, NORMALIZATION_VERSION);
                select.setBytes(2, identity.normalizedHash);
                try (ResultSet row = select.executeQuery()) {
                    if (row.next() && identity.inchi.equals(row.getString(2))) {
                        return row.getLong(1);
                    }
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + t("molecule")
                        + " (normalization_version,normalization_status,normalized_structure,"
                        + "normalized_structure_sha256,standard_inchi,full_inchi_key,"
                        + "canonical_smiles,canonical_smiles_sha256,primary_name,"
                        + "canonicalization_error) VALUES (?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, NORMALIZATION_VERSION);
            insert.setString(2, identity.status);
            insert.setBytes(3, identity.normalized);
            insert.setBytes(4, identity.normalizedHash);
            setNullableString(insert, 5, identity.inchi);
            setNullableString(insert, 6, identity.inchiKey);
            setNullableString(insert, 7, identity.smiles);
            if (identity.smiles == null) insert.setNull(8, Types.BINARY);
            else insert.setBytes(8, sha256(identity.smiles.getBytes(StandardCharsets.UTF_8)));
            setNullableString(insert, 9, truncate(primaryName, 512));
            setNullableString(insert, 10, identity.error);
            insert.executeUpdate();
            return generatedKey(insert);
        }
    }

    private void insertProperties(
            Connection connection,
            long datasetMoleculeId,
            Map<String, String> values,
            List<PropertyBinding> bindings) throws Exception {
        List<PropertyBinding> wide = bindings.stream()
                .filter(binding -> "WIDE".equals(binding.storage)).toList();
        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(t("dataset_molecule_properties"))
                .append(" (dataset_molecule_id");
        for (PropertyBinding binding : wide) {
            sql.append(",`").append(binding.column).append('`');
        }
        sql.append(") VALUES (?");
        sql.append(",?".repeat(wide.size())).append(')');
        try (PreparedStatement insert = connection.prepareStatement(sql.toString())) {
            insert.setLong(1, datasetMoleculeId);
            int index = 2;
            for (PropertyBinding binding : wide) {
                binding.type.bind(insert, index++, trimmed(values.get(binding.name)));
            }
            insert.executeUpdate();
        }

        String overflowSql = "INSERT INTO " + t("property_value_overflow")
                + " (dataset_molecule_id,property_id,integer_value,decimal_value,"
                + "double_value,text_value,value_sha256) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement insert = connection.prepareStatement(overflowSql)) {
            for (PropertyBinding binding : bindings) {
                if (!"OVERFLOW".equals(binding.storage)) continue;
                String value = trimmed(values.get(binding.name));
                if (value == null) continue;
                insert.setLong(1, datasetMoleculeId);
                insert.setLong(2, binding.propertyId);
                insert.setNull(3, Types.BIGINT);
                insert.setNull(4, Types.DECIMAL);
                insert.setNull(5, Types.DOUBLE);
                insert.setNull(6, Types.LONGVARCHAR);
                switch (binding.type.family) {
                    case "INT", "BIGINT" -> insert.setLong(3, Long.parseLong(value));
                    case "DECIMAL" -> insert.setBigDecimal(4, new BigDecimal(value));
                    case "DOUBLE" -> insert.setDouble(5, Double.parseDouble(value));
                    default -> insert.setString(6, value);
                }
                insert.setBytes(7, sha256(value.getBytes(StandardCharsets.UTF_8)));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void failRecord(
            ImportClaim claim, long importRecordId, String code, String message) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("import_record")
                            + " SET status='FAILED',runstep='FAILED',error_code=?,error_message=?,"
                            + "finished_at=UTC_TIMESTAMP(6) WHERE import_record_id=? "
                            + "AND status='RUNNING'")) {
                update.setString(1, code);
                update.setString(2, truncate(message, 2048));
                update.setLong(3, importRecordId);
                if (update.executeUpdate() == 1) {
                    incrementCounters(connection, claim, 0, 1, 0);
                }
            }
            connection.commit();
        }
    }

    private void markNotProcessed(
            ImportClaim claim, long recordNumber, String identifier) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT status FROM " + t("import_record")
                            + " WHERE import_run_id=? AND record_number=?")) {
                select.setLong(1, claim.importRunId);
                select.setLong(2, recordNumber);
                try (ResultSet row = select.executeQuery()) {
                    if (row.next()) {
                        connection.rollback();
                        return;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + t("import_record")
                            + " (import_run_id,record_number,source_identifier,status,runstep,"
                            + "attempt_count,error_code,error_message,finished_at) "
                            + "VALUES (?,?,?,'NOT_PROCESSED','CUTOFF',0,"
                            + "'FAILURE_THRESHOLD_EXCEEDED',"
                            + "'record not processed after failure threshold',UTC_TIMESTAMP(6))")) {
                insert.setLong(1, claim.importRunId);
                insert.setLong(2, recordNumber);
                setNullableString(insert, 3, identifier);
                try {
                    insert.executeUpdate();
                } catch (SQLException duplicateIdentifier) {
                    insert.setNull(3, Types.VARCHAR);
                    insert.executeUpdate();
                }
            }
            incrementCounters(connection, claim, 0, 0, 1);
            connection.commit();
        }
    }

    private void incrementCounters(
            Connection connection, ImportClaim claim, int success, int failed, int notProcessed)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + t("import_run")
                        + " SET success_records=success_records+?,failed_records=failed_records+?,"
                        + "not_processed_records=not_processed_records+? WHERE import_run_id=?")) {
            update.setInt(1, success);
            update.setInt(2, failed);
            update.setInt(3, notProcessed);
            update.setLong(4, claim.importRunId);
            update.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + t("dataset")
                        + " SET imported_records=imported_records+?,failed_records=failed_records+?,"
                        + "not_processed_records=not_processed_records+? WHERE dataset_id=?")) {
            update.setInt(1, success);
            update.setInt(2, failed);
            update.setInt(3, notProcessed);
            update.setLong(4, claim.datasetId);
            update.executeUpdate();
        }
    }

    private void finish(ImportClaim claim) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            long success;
            long failed;
            long notProcessed;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT success_records,failed_records,not_processed_records "
                            + "FROM " + t("import_run") + " WHERE import_run_id=? FOR UPDATE")) {
                select.setLong(1, claim.importRunId);
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    success = row.getLong(1);
                    failed = row.getLong(2);
                    notProcessed = row.getLong(3);
                }
            }
            boolean partial = failed > 0 || notProcessed > 0;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("import_run") + " SET status=?,runstep='COMPLETE',"
                            + "finished_at=UTC_TIMESTAMP(6),error_code=NULL,error_message=NULL "
                            + "WHERE import_run_id=?")) {
                update.setString(1, partial ? "PARTIAL" : "SUCCEEDED");
                update.setLong(2, claim.importRunId);
                update.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("dataset") + " SET status=?,"
                            + "partial_acknowledgement_required=?,model_eligible=? "
                            + "WHERE dataset_id=?")) {
                update.setString(1, partial ? "PARTIAL" : "READY");
                update.setBoolean(2, partial);
                update.setBoolean(3, !partial && success > 0);
                update.setLong(4, claim.datasetId);
                update.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("job") + " SET status='SUCCEEDED',runstep='COMPLETE',"
                            + "lease_owner=NULL,lease_expires_at=NULL,"
                            + "heartbeat_at=UTC_TIMESTAMP(6),finished_at=UTC_TIMESTAMP(6),"
                            + "error_code=NULL,error_message=NULL WHERE job_id=? "
                            + "AND status='RUNNING' AND lease_owner=?")) {
                update.setLong(1, claim.jobId);
                update.setString(2, workerId);
                if (update.executeUpdate() != 1) throw new SQLException("worker lease was lost");
            }
            event(connection, claim.jobId, "JOB_SUCCEEDED", "COMPLETE",
                    String.format(Locale.ROOT, "success=%d failed=%d not_processed=%d",
                            success, failed, notProcessed));
            connection.commit();
        }
    }

    private void failJob(ImportClaim claim, Exception exception) throws SQLException {
        String message = safeMessage(exception);
        boolean permanent = exception instanceof PermanentImportException;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            int attempt;
            int maximum;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT attempt_count,maximum_attempts FROM " + t("job")
                            + " WHERE job_id=? FOR UPDATE")) {
                select.setLong(1, claim.jobId);
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    attempt = row.getInt(1);
                    maximum = row.getInt(2);
                }
            }
            boolean retry = !permanent && attempt < maximum;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("job") + " SET status=?,runstep=?,lease_owner=NULL,"
                            + "lease_expires_at=NULL,heartbeat_at=NULL,available_at="
                            + (retry ? "TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6))" : "available_at")
                            + ",error_code='IMPORT_FAILED',error_message=?,finished_at="
                            + (retry ? "NULL" : "UTC_TIMESTAMP(6)")
                            + " WHERE job_id=? AND lease_owner=?")) {
                update.setString(1, retry ? "QUEUED" : "FAILED");
                update.setString(2, retry ? "RETRY_WAIT" : "FAILED");
                update.setString(3, message);
                update.setLong(4, claim.jobId);
                update.setString(5, workerId);
                update.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("import_run") + " SET status=?,runstep=?,"
                            + "error_code='IMPORT_FAILED',error_message=?,finished_at="
                            + (retry ? "NULL" : "UTC_TIMESTAMP(6)") + " WHERE import_run_id=?")) {
                update.setString(1, retry ? "QUEUED" : "FAILED");
                update.setString(2, retry ? "RETRY_WAIT" : "FAILED");
                update.setString(3, message);
                update.setLong(4, claim.importRunId);
                update.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("dataset") + " SET status=? WHERE dataset_id=?")) {
                update.setString(1, retry ? "IMPORT_QUEUED" : "IMPORT_FAILED");
                update.setLong(2, claim.datasetId);
                update.executeUpdate();
            }
            event(connection, claim.jobId, retry ? "JOB_RETRY_SCHEDULED" : "JOB_FAILED",
                    retry ? "RETRY_WAIT" : "FAILED", message);
            connection.commit();
        }
    }

    private void transition(ImportClaim claim, String runstep) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("job") + " SET runstep=?,heartbeat_at=UTC_TIMESTAMP(6),"
                            + "lease_expires_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)) "
                            + "WHERE job_id=? AND status='RUNNING' AND lease_owner=?")) {
                update.setString(1, runstep);
                update.setInt(2, leaseSeconds);
                update.setLong(3, claim.jobId);
                update.setString(4, workerId);
                if (update.executeUpdate() != 1) throw new SQLException("worker lease was lost");
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + t("import_run") + " SET runstep=? WHERE import_run_id=?")) {
                update.setString(1, runstep);
                update.setLong(2, claim.importRunId);
                update.executeUpdate();
            }
            connection.commit();
        }
    }

    private boolean heartbeat(long jobId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE " + t("job") + " SET heartbeat_at=UTC_TIMESTAMP(6),"
                             + "lease_expires_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)) "
                             + "WHERE job_id=? AND status='RUNNING' AND lease_owner=?")) {
            update.setInt(1, leaseSeconds);
            update.setLong(2, jobId);
            update.setString(3, workerId);
            return update.executeUpdate() == 1;
        }
    }

    private Path resolveStoragePath(String storageKey) throws Exception {
        if (storageKey == null || storageKey.isBlank() || storageKey.contains("/")
                || storageKey.contains("\\") || storageKey.contains("..")) {
            throw new PermanentImportException("unsafe upload storage key");
        }
        Path root = uploadRoot.toRealPath();
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) throw new PermanentImportException("upload path escapes root");
        return path;
    }

    private void verifyUpload(Path source, long expectedLength, byte[] expectedHash)
            throws Exception {
        if (!Files.isRegularFile(source) || Files.size(source) != expectedLength) {
            throw new PermanentImportException("upload file length does not match analysis");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(Files.newInputStream(source), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (!MessageDigest.isEqual(expectedHash, digest.digest())) {
            throw new PermanentImportException("upload checksum does not match analysis");
        }
    }

    private Map<String, String> parseProperties(String record) throws Exception {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(record))) {
            boolean afterEnd = false;
            String name = null;
            StringBuilder value = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!afterEnd) {
                    if (line.startsWith("M  END")) afterEnd = true;
                    continue;
                }
                Matcher header = PROPERTY_HEADER.matcher(line);
                if (header.matches()) {
                    if (name != null) putProperty(values, duplicates, name, value.toString());
                    name = header.group(1).trim();
                    value = new StringBuilder();
                } else if (name != null && line.isEmpty()) {
                    putProperty(values, duplicates, name, value.toString());
                    name = null;
                    value = null;
                } else if (name != null) {
                    if (value.length() > 0) value.append('\n');
                    value.append(line);
                }
            }
            if (name != null) putProperty(values, duplicates, name, value.toString());
        }
        if (!duplicates.isEmpty()) {
            throw new RecordException("DUPLICATE_PROPERTY_TAG",
                    "record repeats property tags: " + duplicates);
        }
        return values;
    }

    private static void putProperty(
            Map<String, String> values, Set<String> duplicates, String name, String value) {
        if (name.isEmpty()) return;
        if (values.put(name, value) != null) duplicates.add(name);
    }

    private static String molBlock(String record) {
        int end = record.indexOf("M  END");
        if (end < 0) return record;
        int newline = record.indexOf('\n', end);
        return newline < 0 ? record.substring(0, end + 6) + "\n"
                : record.substring(0, newline + 1);
    }

    private static byte[] writeMolfile(IAtomContainer molecule) throws Exception {
        StringWriter output = new StringWriter();
        try (MDLV2000Writer writer = new MDLV2000Writer(output)) {
            writer.write(molecule);
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String physicalColumn(Connection connection, String name) throws Exception {
        String hex = HexFormat.of().formatHex(sha256(name.getBytes(StandardCharsets.UTF_8)));
        for (int length = 24; length <= 60; length += 4) {
            String candidate = "p_" + hex.substring(0, Math.min(length, hex.length()));
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT original_name FROM " + t("property_definition")
                            + " WHERE physical_column_name=?")) {
                select.setString(1, candidate);
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next() || name.equals(row.getString(1))) return candidate;
                }
            }
        }
        throw new IllegalStateException("cannot allocate property column name");
    }

    private boolean columnExists(Connection connection, String column) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE table_schema=? "
                        + "AND table_name='dataset_molecule_properties' AND column_name=?")) {
            select.setString(1, schema);
            select.setString(2, column);
            try (ResultSet row = select.executeQuery()) {
                return row.next();
            }
        }
    }

    private void executeDdl(Connection connection, String ddl) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    private void updatePropertyType(Connection connection, long propertyId, SqlType type)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + t("property_definition")
                        + " SET sql_type_family=?,sql_type_ddl=?,maximum_length=?,"
                        + "numeric_precision_value=?,numeric_scale_value=? WHERE property_id=?")) {
            update.setString(1, type.family);
            update.setString(2, type.ddl);
            setNullableInt(update, 3, type.maximumLength());
            setNullableInt(update, 4, type.precision());
            setNullableInt(update, 5, type.scale());
            update.setLong(6, propertyId);
            update.executeUpdate();
        }
    }

    private void schemaChange(
            Connection connection,
            long importRunId,
            long propertyId,
            String change,
            String previous,
            String next,
            String ddl) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + t("property_schema_change")
                        + " (import_run_id,property_id,change_type,previous_sql_type,"
                        + "new_sql_type,ddl_sha256) VALUES (?,?,?,?,?,?)")) {
            insert.setLong(1, importRunId);
            insert.setLong(2, propertyId);
            insert.setString(3, change);
            setNullableString(insert, 4, previous);
            insert.setString(5, next);
            insert.setBytes(6, sha256(ddl.getBytes(StandardCharsets.UTF_8)));
            insert.executeUpdate();
        }
    }

    private boolean namedLock(Connection connection, String name, int timeout) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?,?)")) {
            statement.setString(1, name);
            statement.setInt(2, timeout);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && row.getInt(1) == 1;
            }
        }
    }

    private void releaseNamedLock(Connection connection, String name) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, name);
            statement.executeQuery().close();
        } catch (SQLException ignored) {
        }
    }

    private void event(
            Connection connection, long jobId, String type, String runstep, String message)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + t("job_event")
                        + " (job_id,event_type,runstep,event_message,event_details_json) "
                        + "VALUES (?,?,?,?,NULL)")) {
            insert.setLong(1, jobId);
            insert.setString(2, type);
            insert.setString(3, runstep);
            insert.setString(4, truncate(message, 2048));
            insert.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET time_zone = '+00:00'");
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    private String t(String table) {
        if (!SAFE.matcher(schema).matches() || !SAFE.matcher(table).matches()) {
            throw new IllegalStateException("unsafe SQL identifier");
        }
        return "`" + schema + "`.`" + table + "`";
    }

    private static boolean infrastructureFailure(SQLException exception) {
        String state = exception.getSQLState();
        return exception instanceof SQLTransientException
                || state == null
                || state.startsWith("08")
                || "40001".equals(state)
                || exception.getErrorCode() == 1205
                || exception.getErrorCode() == 1213;
    }

    private static long generatedKey(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) throw new SQLException("generated key is unavailable");
            return keys.getLong(1);
        }
    }

    private static void setNullableString(
            PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void setNullableInt(
            PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER);
        else statement.setInt(index, value);
    }

    private static String trimmed(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) message = throwable.getClass().getSimpleName();
        return truncate(message.replace('\n', ' ').replace('\r', ' '), 2048);
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit);
    }

    private record ImportClaim(
            long jobId,
            long importRunId,
            long uploadId,
            long datasetId,
            String identifierProperty,
            long totalRecords,
            long successRecords,
            long failedRecords,
            long notProcessedRecords,
            String storageKey,
            byte[] contentSha256,
            long contentLength) {}

    private record AnalyzedProperty(
            String name,
            SqlType type,
            String storageRecommendation,
            long present,
            long blank,
            long distinct) {}

    private record PropertyBinding(
            long propertyId,
            String name,
            String column,
            String storage,
            SqlType type,
            long present,
            long blank,
            long distinct) {}

    private record StartRecord(long importRecordId, boolean terminal) {}

    private record SdfRecord(long number, String text, boolean oversized) {}

    private record MoleculeIdentity(
            String status,
            byte[] normalized,
            byte[] normalizedHash,
            String inchi,
            String inchiKey,
            String smiles,
            String error) {
        MoleculeIdentity withIdentityConflict(String message) {
            byte[] salted = sha256((HexFormat.of().formatHex(normalizedHash)
                    + "\u0000IDENTITY_CONFLICT\u0000" + message)
                    .getBytes(StandardCharsets.UTF_8));
            return new MoleculeIdentity(
                    "IMPORTED_IDENTITY_CONFLICT", normalized, salted,
                    null, null, smiles, message);
        }
    }

    private static final class RecordReader implements AutoCloseable {
        private final BufferedReader reader;
        private long count;
        private boolean finished;

        RecordReader(Path source) throws Exception {
            reader = new BufferedReader(new InputStreamReader(
                    Files.newInputStream(source),
                    StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)),
                    64 * 1024);
        }

        SdfRecord next() throws Exception {
            if (finished) return null;
            StringBuilder record = new StringBuilder();
            boolean oversized = false;
            boolean nonBlank = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if ("$$$$".equals(line)) {
                    count++;
                    return new SdfRecord(count, record.toString(), oversized);
                }
                if (!line.isBlank()) nonBlank = true;
                if (!oversized) {
                    if ((long) record.length() + line.length() + 1 > MAX_RECORD_CHARS) {
                        oversized = true;
                        record.setLength(0);
                    } else {
                        record.append(line).append('\n');
                    }
                }
            }
            finished = true;
            if (oversized || nonBlank) {
                count++;
                return new SdfRecord(count, record.toString(), oversized);
            }
            return null;
        }

        @Override
        public void close() throws Exception {
            reader.close();
        }
    }

    private static final class SqlType {
        final String family;
        final String ddl;
        final int first;
        final int second;

        SqlType(String family, String ddl, int first, int second) {
            this.family = family;
            this.ddl = ddl;
            this.first = first;
            this.second = second;
        }

        static SqlType parse(String ddl) {
            if (ddl == null) throw new IllegalArgumentException("SQL type is null");
            String normalized = ddl.trim().toUpperCase(Locale.ROOT);
            Matcher match = TYPE.matcher(normalized);
            if (!match.matches()) throw new IllegalArgumentException("unsupported SQL type: " + ddl);
            if ("INT".equals(normalized) || "BIGINT".equals(normalized)
                    || "DOUBLE".equals(normalized) || "TEXT".equals(normalized)) {
                return new SqlType(normalized, normalized, 0, 0);
            }
            if (normalized.startsWith("CHAR(")) {
                int length = Integer.parseInt(match.group(2));
                if (length > 255) throw new IllegalArgumentException("CHAR exceeds 255");
                return new SqlType("CHAR", "CHAR(" + length + ")", length, 0);
            }
            if (normalized.startsWith("VARCHAR(")) {
                int length = Integer.parseInt(match.group(3));
                if (length > 2048) throw new IllegalArgumentException("VARCHAR exceeds 2048");
                return new SqlType("VARCHAR", "VARCHAR(" + length + ")", length, 0);
            }
            int precision = Integer.parseInt(match.group(4));
            int scale = Integer.parseInt(match.group(5));
            if (precision > 65 || scale > 30 || scale >= precision) {
                throw new IllegalArgumentException("invalid DECIMAL type: " + ddl);
            }
            return new SqlType(
                    "DECIMAL", "DECIMAL(" + precision + "," + scale + ")", precision, scale);
        }

        static SqlType widen(SqlType current, SqlType proposed) {
            if (current.ddl.equals(proposed.ddl)) return current;
            if ("TEXT".equals(current.family) || "TEXT".equals(proposed.family)) {
                return parse("TEXT");
            }
            boolean currentText = Set.of("CHAR", "VARCHAR").contains(current.family);
            boolean proposedText = Set.of("CHAR", "VARCHAR").contains(proposed.family);
            if (currentText || proposedText) {
                int length = Math.max(
                        currentText ? current.first : 64,
                        proposedText ? proposed.first : 64);
                return length <= 2048 ? parse("VARCHAR(" + length + ")") : parse("TEXT");
            }
            int currentRank = numericRank(current.family);
            int proposedRank = numericRank(proposed.family);
            String family = currentRank >= proposedRank ? current.family : proposed.family;
            if ("DOUBLE".equals(family)) return parse("DOUBLE");
            if ("DECIMAL".equals(family)) {
                int scale = Math.max(
                        "DECIMAL".equals(current.family) ? current.second : 0,
                        "DECIMAL".equals(proposed.family) ? proposed.second : 0);
                int integer = Math.max(integerDigits(current), integerDigits(proposed));
                if (integer + scale > 65 || scale > 30) return parse("DOUBLE");
                return parse("DECIMAL(" + (integer + scale) + "," + scale + ")");
            }
            return parse(family);
        }

        private static int numericRank(String family) {
            return switch (family) {
                case "INT" -> 1;
                case "BIGINT" -> 2;
                case "DECIMAL" -> 3;
                case "DOUBLE" -> 4;
                default -> 5;
            };
        }

        private static int integerDigits(SqlType type) {
            return switch (type.family) {
                case "INT" -> 10;
                case "BIGINT" -> 19;
                case "DECIMAL" -> type.first - type.second;
                default -> 0;
            };
        }

        Integer maximumLength() {
            return Set.of("CHAR", "VARCHAR").contains(family) ? first : null;
        }

        Integer precision() {
            return "DECIMAL".equals(family) ? first : null;
        }

        Integer scale() {
            return "DECIMAL".equals(family) ? second : null;
        }

        void bind(PreparedStatement statement, int index, String value) throws Exception {
            if (value == null) {
                statement.setNull(index, Types.NULL);
                return;
            }
            switch (family) {
                case "INT" -> statement.setInt(index, Integer.parseInt(value));
                case "BIGINT" -> statement.setLong(index, Long.parseLong(value));
                case "DECIMAL" -> statement.setBigDecimal(index, new BigDecimal(value));
                case "DOUBLE" -> statement.setDouble(index, Double.parseDouble(value));
                default -> statement.setString(index, value);
            }
        }
    }

    private static class RecordException extends Exception {
        final String code;

        RecordException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class PermanentImportException extends Exception {
        PermanentImportException(String message) {
            super(message);
        }
    }
}
