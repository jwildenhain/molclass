package molclass;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.mail.*;
import javax.mail.internet.*;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.depict.DepictionGenerator;
import org.openscience.cdk.silent.SilentChemObjectBuilder;

public class SdfImporter {

    static class FieldMapping {
        String sdfLabel;
        String mysqlLabel;
        String mysqlType;
    }

    private static final Pattern SAFE_COLUMN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public static void main(String[] args) {
        if (args.length < 7) {
            System.err.println("Usage: SdfImporter <sdf_target> <username> <email> <mol_type> <pmid> <info> <id>");
            System.exit(1);
        }

        String sdfTarget = args[0];
        String username = args[1];
        String email = args[2];
        String molType = args[3];
        String pmid = args[4];
        String info = args[5];
        String id = args[6];

        File targetFile = new File(sdfTarget);
        String parent = targetFile.getParent();
        if (parent == null) {
            parent = ".";
        }
        String name = targetFile.getName();
        File defFile = new File(parent, "sdf2moldb_" + name + ".def");

        if (!defFile.exists()) {
            System.out.println("Definition file not found: " + defFile.getAbsolutePath());
            try {
                ensureDefFile(targetFile, defFile);
            } catch (Exception e) {
                System.out.println("Could not auto-create .def: " + e.getMessage() + ". Continuing in auto-tag discovery mode.");
            }
        }

        System.out.println("SDF Target: " + sdfTarget);
        System.out.println("Def File: " + defFile.getAbsolutePath());

        Map<String, String> configMap = new HashMap<>();
        List<FieldMapping> mappings = new ArrayList<>();
        boolean foundMolName = false;

        if (defFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(defFile))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#") || line.isEmpty()) {
                        continue;
                    }
                    if (line.contains("=")) {
                        String[] parts = line.split("=", 2);
                        configMap.put(parts[0].trim(), parts[1].trim());
                    } else if (line.contains(":")) {
                        String[] parts = line.split(":", 3);
                        if (parts.length >= 3) {
                            String sdfLabel = parts[0].trim();
                            String mysqlLabel = parts[1].trim();
                            String mysqlType = parts[2].trim();
                            int commentIndex = mysqlType.indexOf(":");
                            if (commentIndex >= 0) {
                                mysqlType = mysqlType.substring(0, commentIndex).trim();
                            }

                            if (sdfLabel.isEmpty() || mysqlLabel.isEmpty() || mysqlType.isEmpty()) {
                                continue;
                            }

                            if (!isValidColumnName(mysqlLabel)) {
                                System.out.println("Skipping invalid mysql column name in def: " + mysqlLabel);
                                continue;
                            }
                            if ("mol_id".equalsIgnoreCase(mysqlLabel)) {
                                System.out.println("Skipping reserved mapping name: mol_id");
                                continue;
                            }

                            if ("mol_name".equals(mysqlLabel)) {
                                foundMolName = true;
                            }

                            FieldMapping m = new FieldMapping();
                            m.sdfLabel = sdfLabel;
                            m.mysqlLabel = mysqlLabel;
                            m.mysqlType = mysqlType;
                            mappings.add(m);
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Error reading def file: " + e.getMessage());
            }
        } else {
            System.out.println("Definition file not found: " + defFile.getAbsolutePath() + ", running in auto-tag discovery mode.");
        }

        // Remove duplicate mysql labels, keep first occurrence only
        List<FieldMapping> dedupedMappings = new ArrayList<>();
        Set<String> mappedColumns = new HashSet<>();
        for (FieldMapping m : mappings) {
            String key = m.mysqlLabel.toLowerCase();
            if (mappedColumns.contains(key)) {
                System.out.println("Skipping duplicate mapping for column: " + m.mysqlLabel);
                continue;
            }
            mappedColumns.add(key);
            dedupedMappings.add(m);
        }
        mappings = dedupedMappings;

        List<FieldMapping> customMappings = new ArrayList<>();
        List<FieldMapping> plateMappings = new ArrayList<>();
        FieldMapping nameMapping = null;

        Set<String> plateColumns = new HashSet<>(Arrays.asList(
            "plate_number", "plate_row", "plate_column", "plate_row_char", "library", "sublibrary", "chemgrid"
        ));

        for (FieldMapping m : mappings) {
            if ("mol_name".equals(m.mysqlLabel)) {
                nameMapping = m;
            } else if (plateColumns.contains(m.mysqlLabel)) {
                plateMappings.add(m);
            } else {
                customMappings.add(m);
            }
        }

        String host = XMLReader.getTag("hostname");
        String database = XMLReader.getTag("database");
        String user = XMLReader.getTag("rw_user");
        String password = XMLReader.getTag("rw_password");
        String databaseURL = "jdbc:mysql://" + host + "/" + database;

        try (Connection conn = DriverManager.getConnection(databaseURL, user, password)) {
            // 1. Get existing columns in sdftags
            Set<String> existingColumns = new HashSet<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW COLUMNS FROM sdftags")) {
                while (rs.next()) {
                    existingColumns.add(rs.getString(1).toLowerCase());
                }
            }

            // 2. Add missing columns
            for (FieldMapping m : customMappings) {
                if (!existingColumns.contains(m.mysqlLabel.toLowerCase())) {
                    String sql = "ALTER TABLE sdftags ADD `" + m.mysqlLabel + "` " + m.mysqlType;
                    System.out.println("Altering table sdftags: " + sql);
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate(sql);
                    }
                }
            }

            // 3. Register batch
            StringBuilder tagsBuilder = new StringBuilder();
            for (FieldMapping m : customMappings) {
                tagsBuilder.append(m.mysqlLabel).append(" ");
            }
            String tagsStr = tagsBuilder.toString().trim();

            int batchId = -1;
            String insertBatchSql = "INSERT INTO batchlist (username, filename, tags, mol_type, pmid, info, uploaded) VALUES (?, ?, ?, ?, ?, ?, '0')";
            try (PreparedStatement pstmtBatch = conn.prepareStatement(insertBatchSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmtBatch.setString(1, username);
                pstmtBatch.setString(2, targetFile.getName());
                pstmtBatch.setString(3, tagsStr);
                pstmtBatch.setString(4, molType);
                pstmtBatch.setString(5, pmid);
                pstmtBatch.setString(6, info);
                pstmtBatch.executeUpdate();

                try (ResultSet generatedKeys = pstmtBatch.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        batchId = generatedKeys.getInt(1);
                    }
                }
            }
            if (batchId == -1) {
                throw new SQLException("Failed to retrieve generated batch_id.");
            }
            System.out.println("Registered batch ID: " + batchId);

            // 4. Get next mol_id
            int nextMolId = 1;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(mol_id), 0) FROM moldb_moldata")) {
                if (rs.next()) {
                    nextMolId = rs.getInt(1) + 1;
                }
            }
            System.out.println("Starting mol_id: " + nextMolId);

            // Disable memory-based tables
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE moldb_meta SET memstatus = 0 WHERE db_id = 1");
            }

            // 5. Parse SDF records
            List<String> records = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(sdfTarget))) {
                StringBuilder recordBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    recordBuilder.append(line).append("\n");
                    if (line.trim().equals("$$$$")) {
                        records.add(recordBuilder.toString());
                        recordBuilder = new StringBuilder();
                    }
                }
                if (recordBuilder.length() > 0 && recordBuilder.toString().trim().length() > 0) {
                    records.add(recordBuilder.toString());
                }
            }

            // Prepare connection for transaction
            conn.setAutoCommit(false);

            // Prepared Statements for insertion
            String insertStrucSql = "INSERT INTO moldb_molstruc (mol_id, struc) VALUES (?, ?)";
            String insertDataSql = "INSERT INTO moldb_moldata (mol_id, mol_name) VALUES (?, ?)";
            String insertDescSql = "INSERT INTO cdk_descriptors (mol_id) VALUES (?)";
            String insertFpSql = "INSERT INTO fingerprints (mol_id) VALUES (?)";
            String insertInchiSql = "INSERT INTO inchi_key (mol_id, inchi_key, mol_type) VALUES (?, '', ?)";
            String insertBatchMolSql = "INSERT INTO batchmols (batch_id, mol_id) VALUES (?, ?)";
            String insertPicSql = "INSERT INTO moldb_pic2d (mol_id, type, status, svg) VALUES (?, 1, 3, ?)";

            // Dynamic query for sdftags
            boolean hasIdentifierMapping = false;
            for (FieldMapping m : customMappings) {
                if (m.mysqlLabel.equalsIgnoreCase("identifier")) {
                    hasIdentifierMapping = true;
                }
            }
            
            StringBuilder sbSdf = new StringBuilder("INSERT INTO sdftags (mol_id");
            if (!hasIdentifierMapping) {
                sbSdf.append(", `identifier`");
            }
            for (FieldMapping m : customMappings) {
                sbSdf.append(", `").append(m.mysqlLabel).append("`");
            }
            sbSdf.append(") VALUES (?");
            if (!hasIdentifierMapping) {
                sbSdf.append(", ?");
            }
            for (int i = 0; i < customMappings.size(); i++) {
                sbSdf.append(", ?");
            }
            sbSdf.append(")");
            String insertSdfTagsSql = sbSdf.toString();

            // Dynamic query for plate_info
            StringBuilder sbPlate = new StringBuilder("INSERT INTO plate_info (mol_id");
            for (FieldMapping m : plateMappings) {
                sbPlate.append(", `").append(m.mysqlLabel).append("`");
            }
            sbPlate.append(") VALUES (?");
            for (int i = 0; i < plateMappings.size(); i++) {
                sbPlate.append(", ?");
            }
            sbPlate.append(")");
            String insertPlateSql = sbPlate.toString();

            try (PreparedStatement pstmtStruc = conn.prepareStatement(insertStrucSql);
                 PreparedStatement pstmtData = conn.prepareStatement(insertDataSql);
                 PreparedStatement pstmtDesc = conn.prepareStatement(insertDescSql);
                 PreparedStatement pstmtFp = conn.prepareStatement(insertFpSql);
                 PreparedStatement pstmtInchi = conn.prepareStatement(insertInchiSql);
                 PreparedStatement pstmtBatchMol = conn.prepareStatement(insertBatchMolSql);
                 PreparedStatement pstmtPic = conn.prepareStatement(insertPicSql);
                 PreparedStatement pstmtSdfTags = conn.prepareStatement(insertSdfTagsSql);
                 PreparedStatement pstmtPlate = conn.prepareStatement(insertPlateSql)) {

                boolean chkzero = !"dummy@example.com".equals(email);
                int counter = 0;
                int badmols = 0;

                molclass.SDFReader sr = new molclass.SDFReader();

                for (String record : records) {
                    // Split record into raw Molfile string and property tag lines
                    String[] lines = record.split("\n");
                    StringBuilder molfileBuilder = new StringBuilder();
                    boolean inProperties = false;
                    for (String l : lines) {
                        if (l.trim().startsWith(">")) {
                            inProperties = true;
                        }
                        if (!inProperties) {
                            molfileBuilder.append(l).append("\n");
                        }
                    }
                    String rawMolfile = molfileBuilder.toString();
                    if (!rawMolfile.contains("M  END")) {
                        rawMolfile += "M  END\n";
                    }

                    // Parse properties using our simple robust parser
                    Map<String, String> properties = new HashMap<>();
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();
                        if (line.startsWith(">") && line.contains("<") && line.contains(">")) {
                            int start = line.indexOf("<") + 1;
                            int end = line.indexOf(">", start);
                            if (start > 0 && end > start) {
                                String tag = line.substring(start, end);
                                StringBuilder valBuilder = new StringBuilder();
                                int j = i + 1;
                                while (j < lines.length) {
                                    String nextLine = lines[j].trim();
                                    if (nextLine.isEmpty() || nextLine.startsWith(">") || nextLine.equals("$$$$")) {
                                        break;
                                    }
                                    if (valBuilder.length() > 0) {
                                        valBuilder.append("\n");
                                    }
                                    valBuilder.append(lines[j]);
                                    j++;
                                }
                                properties.put(tag, valBuilder.toString().trim());
                                i = j - 1;
                            }
                        }
                    }

                    // Validate molecule coordinates
                    boolean valid = true;
                    IAtomContainer mol = null;
                    try {
                        mol = sr.read(record);
                        if (chkzero) {
                            int zeroCoords = 0;
                            for (int k = 0; k < mol.getAtomCount(); k++) {
                                org.openscience.cdk.interfaces.IAtom atom = mol.getAtom(k);
                                javax.vecmath.Point3d p3d = atom.getPoint3d();
                                if (p3d != null) {
                                    if (Math.abs(p3d.x) < 1e-5 && Math.abs(p3d.y) < 1e-5 && Math.abs(p3d.z) < 1e-5) {
                                        zeroCoords++;
                                    }
                                } else {
                                    javax.vecmath.Point2d p2d = atom.getPoint2d();
                                    if (p2d != null) {
                                        if (Math.abs(p2d.x) < 1e-5 && Math.abs(p2d.y) < 1e-5) {
                                            zeroCoords++;
                                        }
                                    }
                                }
                            }
                            if (zeroCoords > 1) {
                                valid = false;
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Validation warning: " + e.getMessage());
                        valid = false;
                    }

                    if (!valid) {
                        badmols++;
                        continue;
                    }

                    counter++;
                    int molId = nextMolId++;

                    // 1. Insert into moldb_molstruc
                    pstmtStruc.setInt(1, molId);
                    pstmtStruc.setBytes(2, rawMolfile.getBytes(StandardCharsets.UTF_8));
                    pstmtStruc.executeUpdate();

                    // 2. Insert into moldb_moldata
                    String molName = null;
                    if (nameMapping != null) {
                        molName = properties.get(nameMapping.sdfLabel);
                    }
                    if (molName == null || molName.trim().isEmpty()) {
                        String[] commonIdTags = {"ID", "MOL_ID", "Molecule_ID", "Compound_ID", "PUBCHEM_COMPOUND_CID", "ZINC_ID", "CHEMBL_ID", "ChEMBL_ID"};
                        for (String tag : commonIdTags) {
                            if (properties.containsKey(tag) && !properties.get(tag).trim().isEmpty()) {
                                molName = properties.get(tag).trim();
                                break;
                            }
                        }
                    }
                    if (molName == null || molName.trim().isEmpty()) {
                        if (lines.length > 0 && !lines[0].trim().isEmpty() && !lines[0].trim().startsWith(">")) {
                            molName = lines[0].trim();
                        } else {
                            molName = "Mol_" + molId;
                        }
                    }
                    
                    // Enforce uniqueness
                    try (PreparedStatement checkStmt = conn.prepareStatement("SELECT 1 FROM moldb_moldata WHERE mol_name = ? LIMIT 1")) {
                        checkStmt.setString(1, molName);
                        try (ResultSet rs = checkStmt.executeQuery()) {
                            if (rs.next()) {
                                molName = molName + "_" + molId;
                            }
                        }
                    }

                    pstmtData.setInt(1, molId);
                    pstmtData.setString(2, molName);
                    pstmtData.executeUpdate();

                    // 3. Insert into sdftags
                    pstmtSdfTags.setInt(1, molId);
                    int sdfParamIndex = 2;
                    
                    if (!hasIdentifierMapping) {
                        pstmtSdfTags.setString(sdfParamIndex++, molName);
                    }
                    
                    for (int i = 0; i < customMappings.size(); i++) {
                        FieldMapping m = customMappings.get(i);
                        String val = properties.get(m.sdfLabel);
                        if (val == null) {
                            val = "";
                        }
                        pstmtSdfTags.setString(sdfParamIndex++, val);
                    }
                    pstmtSdfTags.executeUpdate();

                    // 4. Insert into plate_info
                    pstmtPlate.setInt(1, molId);
                    for (int i = 0; i < plateMappings.size(); i++) {
                        FieldMapping m = plateMappings.get(i);
                        String val = properties.get(m.sdfLabel);
                        pstmtPlate.setString(i + 2, val);
                    }
                    pstmtPlate.executeUpdate();

                    // 5. Insert descriptor rows
                    pstmtDesc.setInt(1, molId);
                    pstmtDesc.executeUpdate();

                    pstmtFp.setInt(1, molId);
                    pstmtFp.executeUpdate();

                    pstmtInchi.setInt(1, molId);
                    pstmtInchi.setString(2, molType);
                    pstmtInchi.executeUpdate();

                    pstmtBatchMol.setInt(1, batchId);
                    pstmtBatchMol.setInt(2, molId);
                    pstmtBatchMol.executeUpdate();

                    // 6. Generate SVG natively via CDK
                    String svg = "";
                    try {
                        if (mol != null) {
                            DepictionGenerator dg = new DepictionGenerator().withSize(300, 300);
                            svg = dg.depict(mol).toSvgStr();
                        }
                    } catch (Exception e) {
                        System.err.println("SVG generation warning: " + e.getMessage());
                    }
                    pstmtPic.setInt(1, molId);
                    pstmtPic.setBytes(2, svg.getBytes(StandardCharsets.UTF_8));
                    pstmtPic.executeUpdate();
                }

                conn.commit();
                System.out.println("Successfully imported " + counter + " molecules. (" + badmols + " rejected)");

                // 6. Post-import calculation pipeline
                runStep("molclass.fingerprints.Fingerprinter", batchId, "fingerprinter");
                runStep("molclass.descriptors.AutomaticCalcDriver", batchId, "descriptors");
                runStep("molclass.fingerprints.InChiGenerator", batchId, "InChiGenerator");

                // Update batchlist as uploaded
                try (PreparedStatement pstmtUpd = conn.prepareStatement("UPDATE batchlist SET uploaded = '1' WHERE batch_id = ?")) {
                    pstmtUpd.setInt(1, batchId);
                    pstmtUpd.executeUpdate();
                }

                // Notify finish of upload
                sendEmailNotification(email, "SDF Upload for batch " + batchId + " complete",
                    "Upload of file " + targetFile.getName() + " is complete. Batch ID: " + batchId);

                // If not dummy, run Weka predictions, Murcko fragments and Similarity
                if (!"dummy@example.com".equals(email)) {
                    List<Integer> modelIds = new ArrayList<>();
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT model_id FROM class_models")) {
                        while (rs.next()) {
                            modelIds.add(rs.getInt(1));
                        }
                    }

                    for (int modelId : modelIds) {
                        int predId = -1;
                        String insertPredSql = "INSERT INTO prediction_list (username, batch_id, model_id, pred_name, email) VALUES (?, ?, ?, '', 'dummy@example.com')";
                        try (PreparedStatement pstmtPred = conn.prepareStatement(insertPredSql, Statement.RETURN_GENERATED_KEYS)) {
                            pstmtPred.setString(1, username);
                            pstmtPred.setInt(2, batchId);
                            pstmtPred.setInt(3, modelId);
                            pstmtPred.executeUpdate();
                            try (ResultSet generatedKeys = pstmtPred.getGeneratedKeys()) {
                                if (generatedKeys.next()) {
                                    predId = generatedKeys.getInt(1);
                                }
                            }
                        }

                        if (predId != -1) {
                            runStep("molclass.Predictor", predId, "predictors_sdf2moldb");
                        }
                    }

                    sendEmailNotification(email, "Model prediction for batch " + batchId + " complete",
                        "Prediction against all existing models in the database is complete. Batch ID: " + batchId);

                    // Murcko fragments
                    runStep("molclass.fingerprints.MurckoFragments", batchId, "Murcko");

                    // Similarity
                    runStep("molclass.fingerprints.Similarity", batchId, "Similarity");

                    sendEmailNotification(email, "Similarity calculus for batch " + batchId + " against database is complete",
                        "Similarity checks completed. Batch ID: " + batchId);
                }

                conn.commit();
            }
        } catch (Exception e) {
            System.err.println("Fatal error during import: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runStep(String className, int batchId, String logPrefix) {
        try {
            System.out.println("Running " + className + " for batch " + batchId + "...");
            ProcessBuilder pb = new ProcessBuilder(
                "./deploy.sh", className, String.valueOf(batchId)
            );
            pb.redirectOutput(new File("./log/output_" + logPrefix + ".log"));
            pb.redirectError(new File("./log/error_" + logPrefix + ".log"));
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                System.err.println("Warning: " + className + " exited with code " + exitCode);
            }
        } catch (Exception e) {
            System.err.println("Error running " + className + ": " + e.getMessage());
        }
    }

    private static void ensureDefFile(File sdfFile, File defFile) throws Exception {
        if (defFile.exists()) {
            return;
        }

        File script = new File("tools/sdftools/sdfcheck.pl");
        if (!script.exists()) {
            throw new Exception("sdfcheck.pl not found at " + script.getAbsolutePath());
        }

        ProcessBuilder pb = new ProcessBuilder("perl", script.getAbsolutePath(), sdfFile.getAbsolutePath());
        pb.directory(new File("."));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = readAll(proc.getInputStream());
        int exitCode = proc.waitFor();
        if (exitCode != 0 || !defFile.exists()) {
            throw new Exception("sdfcheck.pl exited with " + exitCode + "; output: " + output);
        }
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        StringBuilder out = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append("\n");
            }
        }
        return out.toString();
    }

    private static boolean isValidColumnName(String name) {
        return name != null && SAFE_COLUMN_NAME.matcher(name).matches();
    }

    private static void sendEmailNotification(String to, String subject, String body) {
        if (to == null || to.equals("dummy@example.com") || to.trim().isEmpty()) {
            return;
        }
        try {
            String from = XMLReader.getTag("molclassemail");
            if (from == null) {
                from = "molclass@localhost";
            }
            Properties props = new Properties();
            props.put("mail.smtp.host", "localhost");
            Session session = Session.getDefaultInstance(props, null);
            Message msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(from));
            msg.setRecipients(Message.RecipientType.TO, new InternetAddress[]{new InternetAddress(to)});
            msg.setSubject(subject);
            msg.setContent(body, "text/plain");
            Transport.send(msg);
            System.out.println("Sent email notification to: " + to);
        } catch (Exception e) {
            System.err.println("Email notification warning: " + e.getMessage());
        }
    }
}
