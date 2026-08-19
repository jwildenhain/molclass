package molclass;

import weka.core.Instances;
import weka.core.Instance;
import weka.core.DenseInstance;
import weka.experiment.InstanceQuery;
import weka.classifiers.Evaluation;
import weka.classifiers.Classifier;
import weka.classifiers.functions.SMO; // LibSVM substitute or actual LibSVM
import weka.classifiers.functions.LibSVM;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.rules.DTNB;
import weka.core.SerializationHelper;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.NumericToNominal;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.attributeSelection.CfsSubsetEval;
import weka.attributeSelection.GreedyStepwise;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.io.File;
import java.util.Random;
import weka.core.Utils;

public class RetrainModels {

    public static void main(String[] args) throws Exception {
        System.out.println("Starting RetrainModels with High-Fidelity Legacy Configurations...");
        
        // Connecting to the legacy database to get all instances
        String url = "jdbc:mysql://127.0.0.1:3306/molclass_legacy?useSSL=false&allowPublicKeyRetrieval=true";
        Connection conn = DriverManager.getConnection(url, "molclass_unit_user", "unittestPassword");

        String outputDir = "spring_boot_predictor/src/main/resources/models_modern/";
        new File(outputDir).mkdirs();

        String query = "SELECT model_id, data_type, class_tag, class_scheme FROM class_models WHERE model_id IN (4, 20, 21, 22)";
        PreparedStatement stmt = conn.prepareStatement(query);
        ResultSet rs = stmt.executeQuery();

        System.out.println("| Model ID | Scheme | Data Type | F1 Score | Kappa | ROC Area |");
        System.out.println("|---|---|---|---|---|---|");

        while (rs.next()) {
            int modelId = rs.getInt("model_id");
            String dataType = rs.getString("data_type");
            String classTag = rs.getString("class_tag");
            String classScheme = rs.getString("class_scheme");

            try {
                System.out.println("Processing model " + modelId + " (" + classScheme + " - " + dataType + ")...");
                
                // Set up InstanceQuery
                InstanceQuery iq = new InstanceQuery();
                iq.setDatabaseURL(url);
                iq.setUsername("molclass_unit_user");
                iq.setPassword("unittestPassword");
                
                String selectSql = "";
                // To mirror ModelBuilder perfectly we select all batch_ids if we can, or specific ones.
                // For molclass_legacy, ModelBuilder used `batch_id = batch_id`. Since we want all available data for this model_id,
                // we should join with the original batch_id from class_models.
                String getBatch = "SELECT batch_id FROM class_models WHERE model_id = " + modelId;
                ResultSet rsBatch = conn.createStatement().executeQuery(getBatch);
                rsBatch.next();
                int batchId = rsBatch.getInt("batch_id");

                if (dataType.equals("CDK")) {
                    selectSql = "SELECT sdftags." + classTag + ", cdk_descriptors.* FROM sdftags, cdk_descriptors, batchmols " +
                                "WHERE sdftags." + classTag + " != '' AND cdk_descriptors.MW IS NOT NULL AND " +
                                "sdftags.mol_id = cdk_descriptors.mol_id AND sdftags.mol_id = batchmols.mol_id AND " +
                                "batchmols.batch_id = " + batchId;
                } else {
                    selectSql = "SELECT sdftags." + classTag + ", fingerprints." + dataType + " FROM sdftags, fingerprints, batchmols " +
                                "WHERE sdftags." + classTag + " != '' AND fingerprints." + dataType + " IS NOT NULL AND " +
                                "sdftags.mol_id = fingerprints.mol_id AND sdftags.mol_id = batchmols.mol_id AND " +
                                "batchmols.batch_id = " + batchId;
                }

                iq.setQuery(selectSql);
                Instances data = iq.retrieveInstances();
                System.out.println("Loaded " + data.numInstances() + " instances.");
                
                // Pre-processing identical to ModelBuilder
                weka.filters.unsupervised.attribute.Remove remove = new weka.filters.unsupervised.attribute.Remove();
                weka.filters.unsupervised.attribute.RemoveUseless removeUseless = new weka.filters.unsupervised.attribute.RemoveUseless();
                NumericToNominal numericToNominal = new NumericToNominal();
                BlobToBits blobToBits = new BlobToBits();

                if (!dataType.equals("CDK")) {
                    int c = 0;
                    if (dataType.equals("MACCS")) c = 200;
                    else if (dataType.equals("SUB")) c = 310;
                    else if (dataType.equals("GO")) c = 1110;
                    else if (dataType.equals("KR")) c = 5100;
                    else if (dataType.equals("PubChem")) c = 1000;
                    else if (dataType.equals("EXT")) c = 1100;
                    
                    blobToBits.setOptions(new String[]{"-R", "2", "-C", String.valueOf(c)});
                    blobToBits.setInputFormat(data);
                    data = Filter.useFilter(data, blobToBits);
                    
                    int maccInd = data.attribute(dataType + "_0").index();
                    numericToNominal.setOptions(new String[]{"-R", (maccInd + 1) + "-last"});
                    numericToNominal.setInputFormat(data);
                    data = Filter.useFilter(data, numericToNominal);

                    remove.setOptions(new String[]{"-R", "2"});
                    remove.setInputFormat(data);
                    data = Filter.useFilter(data, remove);
                } else {
                    // if CDK, remove mol_id (usually attribute index 1 if it exists)
                    // but we didn't select mol_id explicitly except in cdk_descriptors.*, which brings mol_id.
                    int molIdIndex = data.attribute("mol_id").index();
                    if (molIdIndex != -1) {
                        remove.setOptions(new String[]{"-R", String.valueOf(molIdIndex + 1)});
                        remove.setInputFormat(data);
                        data = Filter.useFilter(data, remove);
                    }
                }

                removeUseless.setOptions(new String[]{"-M", "99.0"});
                removeUseless.setInputFormat(data);
                data = Filter.useFilter(data, removeUseless);

                // Set class attribute
                data.setClass(data.attribute(classTag));

                // -----------------------------------------------------
                // LEGACY ATTRIBUTE SELECTION
                // -----------------------------------------------------
                AttributeSelection filter = new AttributeSelection();
                CfsSubsetEval attreval = new CfsSubsetEval();
                GreedyStepwise search = new GreedyStepwise();
                search.setSearchBackwards(false);
                filter.setEvaluator(attreval);
                filter.setSearch(search);
                filter.setInputFormat(data);
                data = Filter.useFilter(data, filter);
                System.out.println("Features after AttributeSelection: " + data.numAttributes());

                // -----------------------------------------------------
                // CLASSIFIER INSTANTIATION
                // -----------------------------------------------------
                Classifier classifier;
                if (classScheme.equals("LibSVM")) {
                    LibSVM svm = new LibSVM();
                    // Original options for Model 20 LibSVM: -S 0 -K 2 -D 3 -G 0.0 -R 0.0 -N 0.5 -M 40.0 -C 2.0 -E 0.001 -P 0.1 -Z -B
                    svm.setOptions(Utils.splitOptions("-S 0 -K 2 -D 3 -G 0.0 -R 0.0 -N 0.5 -M 40.0 -C 2.0 -E 0.001 -P 0.1 -Z -B"));
                    classifier = svm;
                } else if (classScheme.equals("NaiveBayes")) {
                    classifier = new NaiveBayes();
                } else if (classScheme.equals("DecisionTreeNaiveBayes") || classScheme.equals("DTNB")) {
                    DTNB dtnb = new DTNB();
                    dtnb.setOptions(Utils.splitOptions("-X 1"));
                    classifier = dtnb;
                } else {
                    classifier = new weka.classifiers.trees.RandomForest();
                }

                // Cross validate for metrics
                System.out.println("Evaluating...");
                Evaluation eval = new Evaluation(data);
                eval.crossValidateModel(classifier, data, 10, new Random(1));

                // Train final model
                System.out.println("Building final classifier...");
                classifier.buildClassifier(data);

                // Save model and header
                Instances header = new Instances(data, 0);
                SerializationHelper.write(outputDir + "model_" + modelId + ".model", classifier);
                SerializationHelper.write(outputDir + "header_" + modelId + ".obj", header);

                // Print metrics
                double f1 = eval.fMeasure(1);
                double kappa = eval.kappa();
                double roc = eval.areaUnderROC(1);

                System.out.printf("| %d | %s | %s | %.4f | %.4f | %.4f |\n", modelId, classScheme, dataType, f1, kappa, roc);

            } catch (Exception e) {
                System.out.println("Error processing model " + modelId + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        conn.close();
        System.out.println("Retraining complete.");
    }
}
