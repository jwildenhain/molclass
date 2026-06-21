package molclass.predictor;

import java.io.File;
import java.io.FilenameFilter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import weka.classifiers.Classifier;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.SerializationHelper;
import weka.core.Utils;
import weka.experiment.InstanceQuery;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.NumericToNominal;
import molclass.BlobToBits;

@Service
public class PredictionService {

    private static final Logger logger = LoggerFactory.getLogger(PredictionService.class);
    private static final DecimalFormat df = new DecimalFormat("#.########");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${spring.datasource.url}")
    private String databaseUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    // Outer map is version (1 or 2), inner map is modelId -> Classifier
    private Map<Integer, Map<Integer, Classifier>> versionedClassifiers = new HashMap<>();
    private Map<Integer, Map<Integer, Instances>> versionedHeaders = new HashMap<>();
    private Map<Integer, ModelMeta> metadata = new HashMap<>();

    private static class ModelMeta {
        String dataType;
        String classTag;
    }

    @PostConstruct
    public void init() throws Exception {
        logger.info("Initializing PredictionService... Loading models from disk");

        versionedClassifiers.put(1, new HashMap<>());
        versionedClassifiers.put(2, new HashMap<>());
        versionedHeaders.put(1, new HashMap<>());
        versionedHeaders.put(2, new HashMap<>());

        // Load metadata from database
        jdbcTemplate.query("SELECT model_id, data_type, class_tag FROM class_models", rs -> {
            ModelMeta meta = new ModelMeta();
            meta.dataType = rs.getString("data_type");
            meta.classTag = rs.getString("class_tag");
            metadata.put(rs.getInt("model_id"), meta);
        });

        loadVersionModels(1, "models_v1");
        loadVersionModels(2, "models_v2");
    }

    private void loadVersionModels(int version, String dirName) {
        String modelsDir = "/home/jw/repos/wdc_gitlab/molclass/spring_boot_predictor/src/main/resources/" + dirName;
        File dir = new File(modelsDir);
        File[] modelFiles = dir.listFiles((d, name) -> name.startsWith("model_") && name.endsWith(".model"));

        if (modelFiles != null) {
            for (File mf : modelFiles) {
                String name = mf.getName();
                int modelId = Integer.parseInt(name.replace("model_", "").replace(".model", ""));
                
                File hf = new File(dir, "header_" + modelId + ".obj");
                if (hf.exists()) {
                    try {
                        Classifier classifier = (Classifier) SerializationHelper.read(mf.getAbsolutePath());
                        Instances header = (Instances) SerializationHelper.read(hf.getAbsolutePath());
                        
                        versionedClassifiers.get(version).put(modelId, classifier);
                        versionedHeaders.get(version).put(modelId, header);
                        logger.info("Successfully loaded v" + version + " model " + modelId);
                    } catch (Exception e) {
                        logger.error("Failed to load v" + version + " model " + modelId + ": " + e.getMessage());
                    }
                }
            }
        }
        logger.info("Loaded " + versionedClassifiers.get(version).size() + " models for version " + version + ".");
    }

    private String getColumnNames(Connection conn, String cdktable) throws Exception {
        String select_query = "select * from " + cdktable + " limit 1";
        try (PreparedStatement pstmt = conn.prepareStatement(select_query)) {
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    java.sql.ResultSetMetaData rsMetaData = rs.getMetaData();
                    int numberOfColumns = rsMetaData.getColumnCount();
                    String header = rsMetaData.getTableName(2) + "." + rsMetaData.getColumnName(2);
                    for (int i = 3; i < numberOfColumns + 1; i++) {
                        header += "," + rsMetaData.getTableName(i) + "." + rsMetaData.getColumnName(i);
                    }
                    return header;
                }
            }
        }
        return "";
    }

    private double logIt(double p, double offset) {
        return Math.log((p + offset) / (1 + offset - p));
    }

    public void predict(int batchId, int modelId, int predId, int version) throws Exception {
        Map<Integer, Classifier> classifiers = versionedClassifiers.get(version);
        Map<Integer, Instances> headers = versionedHeaders.get(version);

        if (classifiers == null || !classifiers.containsKey(modelId)) {
            throw new Exception("v" + version + " Model " + modelId + " not loaded in memory.");
        }

        Classifier classifier = classifiers.get(modelId);
        Instances header = headers.get(modelId);
        ModelMeta meta = metadata.get(modelId);

        String data_type = meta.dataType;
        String class_tag = meta.classTag;

        InstanceQuery query = new InstanceQuery();
        query.setUsername(dbUsername);
        query.setPassword(dbPassword);
        query.setDatabaseURL(databaseUrl);

        Instances unlabeled = null;
        String select_query = null;
        String[] options = null;

        BlobToBits blobToBits = new BlobToBits();
        NumericToNominal numericToNominal = new NumericToNominal();

        String cdktable = "cdk_descriptors";
        String fptable = "fingerprints";
        String batchmoltable = "batchmols";
        String predmoltable = "prediction_mols";
        String predtable = "prediction_list";

        Connection conn = dataSource.getConnection();

        // Query logic adapted from Predictor.java
        if (data_type.equals("CDK")) {
            select_query = "SELECT " + cdktable + ".* FROM " + cdktable + ", " + batchmoltable 
                + " WHERE " + cdktable + ".MW IS NOT NULL AND " + batchmoltable + ".mol_id = " 
                + cdktable + ".mol_id AND " + batchmoltable + ".batch_id =" + batchId;
            query.setQuery(select_query);
            unlabeled = query.retrieveInstances();
        } else if (data_type.equals("MACCS")) {
            select_query = "SELECT " + fptable + ".mol_id, " + fptable + ".MACCS FROM " + fptable 
                + ", " + batchmoltable + " WHERE " + fptable + ".MACCS IS NOT NULL AND " 
                + batchmoltable + ".mol_id = " + fptable + ".mol_id AND " + batchmoltable + ".batch_id =" + batchId;
            query.setQuery(select_query);
            unlabeled = query.retrieveInstances();

            options = new String[]{"-R", "2", "-C", "200"};
            blobToBits.setOptions(options);
            blobToBits.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, blobToBits);

            options = new String[]{"-R", unlabeled.attribute("MACCS_0").index() + "-last"};
            numericToNominal.setOptions(options);
            numericToNominal.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, numericToNominal);
        } else if (data_type.equals("PubChem")) {
            select_query = "SELECT " + fptable + ".mol_id, " + fptable + ".PubChem FROM " + fptable 
                + ", " + batchmoltable + " WHERE " + fptable + ".PubChem IS NOT NULL AND " 
                + batchmoltable + ".mol_id = " + fptable + ".mol_id AND " + batchmoltable + ".batch_id =" + batchId;
            query.setQuery(select_query);
            unlabeled = query.retrieveInstances();

            options = new String[]{"-R", "2", "-C", "1000"};
            blobToBits.setOptions(options);
            blobToBits.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, blobToBits);

            options = new String[]{"-R", unlabeled.attribute("PubChem_0").index() + "-last"};
            numericToNominal.setOptions(options);
            numericToNominal.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, numericToNominal);
        } else if (data_type.equals("EXT")) {
            select_query = "SELECT " + fptable + ".mol_id, " + fptable + ".EXT FROM " + fptable 
                + ", " + batchmoltable + " WHERE " + fptable + ".EXT IS NOT NULL AND " 
                + batchmoltable + ".mol_id = " + fptable + ".mol_id AND " + batchmoltable + ".batch_id =" + batchId;
            query.setQuery(select_query);
            unlabeled = query.retrieveInstances();

            options = new String[]{"-R", "2", "-C", "1100"};
            blobToBits.setOptions(options);
            blobToBits.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, blobToBits);

            options = new String[]{"-R", unlabeled.attribute("EXT_0").index() + "-last"};
            numericToNominal.setOptions(options);
            numericToNominal.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, numericToNominal);
        } else if (data_type.equals("SUB")) {
            select_query = "SELECT " + fptable + ".mol_id, " + fptable + ".SUB FROM " + fptable 
                + ", " + batchmoltable + " WHERE " + fptable + ".SUB IS NOT NULL AND " 
                + batchmoltable + ".mol_id = " + fptable + ".mol_id AND " + batchmoltable + ".batch_id =" + batchId;
            query.setQuery(select_query);
            unlabeled = query.retrieveInstances();

            options = new String[]{"-R", "2", "-C", "310"};
            blobToBits.setOptions(options);
            blobToBits.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, blobToBits);

            options = new String[]{"-R", unlabeled.attribute("SUB_0").index() + "-last"};
            numericToNominal.setOptions(options);
            numericToNominal.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, numericToNominal);
        } else if (data_type.equals("ALL")) {
            String cdk_table_header = getColumnNames(conn, cdktable);
            select_query = "SELECT " + fptable + ".mol_id, " + fptable + ".MACCS, " + fptable + ".PubChem, " 
                + fptable + ".EXT, " + fptable + ".SUB, " + cdk_table_header + " FROM " + fptable + ", " + cdktable 
                + ", " + batchmoltable + " WHERE " + cdktable + ".mol_id = " + fptable + ".mol_id AND " 
                + cdktable + ".MW IS NOT NULL AND " + fptable + ".MACCS IS NOT NULL AND " + cdktable + ".mol_id = " 
                + batchmoltable + ".mol_id AND " + batchmoltable + ".batch_id =" + batchId;
            query.setQuery(select_query);
            unlabeled = query.retrieveInstances();

            options = new String[]{"-R", "2", "-C", "200"};
            blobToBits.setOptions(options);
            blobToBits.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, blobToBits);
            options = new String[]{"-R", unlabeled.attribute("MACCS_0").index() + "-last"};
            numericToNominal.setOptions(options);
            numericToNominal.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, numericToNominal);

            options = new String[]{"-R", "3", "-C", "1000"};
            blobToBits.setOptions(options);
            blobToBits.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, blobToBits);
            options = new String[]{"-R", unlabeled.attribute("PubChem_0").index() + "-last"};
            numericToNominal.setOptions(options);
            numericToNominal.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, numericToNominal);

            options = new String[]{"-R", "4", "-C", "1100"};
            blobToBits.setOptions(options);
            blobToBits.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, blobToBits);
            options = new String[]{"-R", unlabeled.attribute("EXT_0").index() + "-last"};
            numericToNominal.setOptions(options);
            numericToNominal.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, numericToNominal);

            options = new String[]{"-R", "5", "-C", "310"};
            blobToBits.setOptions(options);
            blobToBits.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, blobToBits);
            options = new String[]{"-R", unlabeled.attribute("SUB_0").index() + "-last"};
            numericToNominal.setOptions(options);
            numericToNominal.setInputFormat(unlabeled);
            unlabeled = Filter.useFilter(unlabeled, numericToNominal);
        } else {
            throw new Exception("Unsupported data_type: " + data_type);
        }

        unlabeled.insertAttributeAt(header.classAttribute(), unlabeled.numAttributes());

        StringBuilder text = new StringBuilder();
        text.append("=== Predicted values (v").append(version).append(") ===\n\n");
        text.append(classifier.toString()).append("\n");
        text.append("Classification Categories\n");
        for (int j = 0; j < header.classAttribute().numValues(); j++) {
            char ch = (char) ('a' + j);
            text.append(ch).append(" - ").append(header.classAttribute().value(j)).append("\n");
        }
        text.append("\n");

        text.append(Utils.padRight("   mol_id", 15)).append(Utils.padRight("   Predicted Classification", 27)).append('\t');
        for (int j = 0; j < header.classAttribute().numValues(); j++) {
            char ch = (char) ('a' + j);
            text.append(" ").append(ch).append("\t");
        }
        text.append("\n");

        String insertSQL = "INSERT INTO " + predmoltable + "(mol_id, pred_id, main_class, distribution, lhood) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE main_class=VALUES(main_class), distribution=VALUES(distribution), lhood=VALUES(lhood)";
        
        conn.setAutoCommit(false);
        try (PreparedStatement pstmtInsert = conn.prepareStatement(insertSQL)) {
            for (int i = 0; i < unlabeled.numInstances(); i++) {
                Instance curr = unlabeled.instance(i);
                Instance inst = new DenseInstance(header.numAttributes());
                inst.setDataset(header);
                
                for (int n = 0; n < header.numAttributes(); n++) {
                    Attribute att = unlabeled.attribute(header.attribute(n).name());
                    if (att != null) {
                        if (att.isNominal()) {
                            if ((header.attribute(n).numValues() > 0) && (att.numValues() > 0)) {
                                String label = curr.stringValue(att);
                                int index = header.attribute(n).indexOfValue(label);
                                if (index != -1) inst.setValue(n, index);
                            }
                        } else if (att.isNumeric()) {
                            inst.setValue(n, curr.value(att));
                        }
                    }
                }

                unlabeled.setClass(unlabeled.attribute(class_tag));

                double pred = classifier.classifyInstance(inst);
                double idVal = unlabeled.instance(i).value(unlabeled.attribute("mol_id"));
                String mol_id = String.valueOf((int) idVal);
                String pred_class = unlabeled.classAttribute().value((int) pred);
                
                String mol_col = Utils.padRight(mol_id, 15);
                String class_col = Utils.padRight(pred_class, 27);
                text.append(mol_col).append(class_col).append('\t');

                double[] dist = classifier.distributionForInstance(inst);
                StringBuilder mol_dist = new StringBuilder();

                for (int x = 0; x < dist.length; x++) {
                    double d = dist[x];
                    text.append(df.format(d));
                    mol_dist.append(df.format(d));
                    if (x == (int) pred) text.append('*');
                    text.append('\t');
                    mol_dist.append('\t');
                }
                text.append('\n');

                double llhood = logIt(dist[0], 0.001);

                pstmtInsert.setInt(1, Integer.parseInt(mol_id));
                pstmtInsert.setInt(2, predId);
                pstmtInsert.setString(3, pred_class);
                pstmtInsert.setString(4, mol_dist.toString().trim());
                pstmtInsert.setDouble(5, llhood);
                pstmtInsert.addBatch();
            }
            pstmtInsert.executeBatch();
        }

        String updateSQL = "UPDATE " + predtable + " SET printout = ? WHERE pred_id = ?";
        try (PreparedStatement pstmtUpdate = conn.prepareStatement(updateSQL)) {
            pstmtUpdate.setString(1, text.toString());
            pstmtUpdate.setInt(2, predId);
            pstmtUpdate.executeUpdate();
        }

        conn.commit();
        conn.close();
    }
}
