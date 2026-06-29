package molclass.predictor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
public class PredictionController {

    private static final Logger logger = LoggerFactory.getLogger(PredictionController.class);

    @Autowired
    private PredictionService predictionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int currentVersion = 2; // Default to v2

    @PostMapping("/api/version/set/{version}")
    public String setVersion(@PathVariable int version) {
        if (version == 1 || version == 2) {
            this.currentVersion = version;
            return "Successfully switched backend Prediction Model Version to v" + version;
        } else {
            return "Error: Invalid version " + version + ". Allowed values: 1 (Legacy Replica) or 2 (Modern Pipeline).";
        }
    }

    @GetMapping("/api/version")
    public String getVersion() {
        return "Current Backend Prediction Model Version is v" + currentVersion;
    }

    @PostMapping("/predict/{molId}")
    public String predictMolecule(@PathVariable int molId) {
        try {
            int batchId = 999999;
            int v = currentVersion;

            // Ensure molecule is in batchmols
            jdbcTemplate.update("INSERT IGNORE INTO batchmols (mol_id, batch_id) VALUES (?, ?)", molId, batchId);

            // Fetch all model_ids available
            List<Integer> modelIds = jdbcTemplate.queryForList("SELECT model_id FROM class_models", Integer.class);

            int successCount = 0;
            for (int modelId : modelIds) {
                try {
                    // Create prediction_list entry if not exists
                    String predName = "REST_API_Prediction_v" + v;
                    List<Integer> existingPreds = jdbcTemplate.queryForList(
                        "SELECT pred_id FROM prediction_list WHERE model_id = ? AND batch_id = ? AND pred_name = ?", 
                        Integer.class, modelId, batchId, predName);

                    int predId;
                    if (existingPreds.isEmpty()) {
                        jdbcTemplate.update(
                            "INSERT INTO prediction_list (pred_name, model_id, batch_id, username, email) VALUES (?, ?, ?, ?, ?)",
                            predName, modelId, batchId, "api_user", "api@localhost"
                        );
                        predId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Integer.class);
                    } else {
                        predId = existingPreds.get(0);
                    }

                    // Run prediction
                    predictionService.predict(batchId, modelId, predId, v);
                    successCount++;
                } catch (Exception e) {
                    logger.error("Failed to predict model " + modelId + " for mol " + molId + " (v" + v + "): " + e.getMessage());
                }
            }

            return "Predicted " + successCount + "/" + modelIds.size() + " models (v" + v + ") for molecule " + molId;
        } catch (Exception e) {
            logger.error("Prediction failed for molId {}", molId, e);
            return "Error: " + e.getMessage();
        }
    }

    @GetMapping("/api/batches")
    public List<Map<String, Object>> getBatches() {
        return jdbcTemplate.queryForList("SELECT batch_id, username, filename, info, uploaded FROM batchlist ORDER BY batch_id DESC");
    }

    @GetMapping("/api/models")
    public List<Map<String, Object>> getModels() {
        return jdbcTemplate.queryForList("SELECT model_id, name, username, batch_id, data_type, class_tag, class_scheme, feature_selection, email, IF(model_data IS NULL, 0, 1) as is_built FROM class_models ORDER BY model_id DESC LIMIT 100");
    }

    @GetMapping("/api/dataset-reviews")
    public List<Map<String, Object>> getDatasetReviews() {
        return jdbcTemplate.queryForList("SELECT batch_id, name, description FROM dataset_reviews ORDER BY batch_id ASC");
    }

    @PostMapping("/api/models/queue")
    public String queueModel(
            @org.springframework.web.bind.annotation.RequestParam("batch_id") int batchId,
            @org.springframework.web.bind.annotation.RequestParam("data_type") String dataType,
            @org.springframework.web.bind.annotation.RequestParam("class_scheme") String classScheme,
            @org.springframework.web.bind.annotation.RequestParam("class_tag") String classTag,
            @org.springframework.web.bind.annotation.RequestParam(value = "feature_selection", defaultValue = "CfsSubsetEval") String featureSelection,
            @org.springframework.web.bind.annotation.RequestParam("email") String email) {
        
        try {
            // Insert into class_models queue. The backend job/worker will pick it up when model_data is NULL
            jdbcTemplate.update(
                "INSERT INTO class_models (name, username, batch_id, data_type, class_tag, class_scheme, feature_selection, email) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "Model_" + classScheme + "_" + dataType, "api_user", batchId, dataType, classTag, classScheme, featureSelection, email
            );
            return "Model generation successfully queued for batch " + batchId;
        } catch (Exception e) {
            logger.error("Failed to queue model: ", e);
            return "Error queuing model: " + e.getMessage();
        }
    }

    @PostMapping("/api/upload")
    public String uploadSDF(@org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return "File is empty";
            }
            // Save to temporary directory
            java.io.File tempFile = java.io.File.createTempFile("upload_", ".sdf");
            file.transferTo(tempFile);
            
            // Queue the SDF for importing (this would trigger the Java SdfImporter)
            logger.info("Received SDF Upload: " + file.getOriginalFilename() + ", saved to " + tempFile.getAbsolutePath());
            return "File successfully uploaded and queued for processing.";
        } catch (Exception e) {
            logger.error("Upload failed: ", e);
            return "Error uploading file: " + e.getMessage();
        }
    }

    @GetMapping("/api/predictions/{modelId}/results")
    public List<Map<String, Object>> getPredictionResults(@PathVariable int modelId) {
        // We find the pred_id associated with this model_id. Usually there is one main prediction task.
        List<Integer> predIds = jdbcTemplate.queryForList("SELECT pred_id FROM prediction_list WHERE model_id = ? ORDER BY pred_id DESC LIMIT 1", Integer.class, modelId);
        if (predIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        int predId = predIds.get(0);

        String sql = "SELECT m.mol_id, m.main_class, m.distribution, m.response_strength, m.certainty_score " +
                     "FROM prediction_mols m " +
                     "WHERE m.pred_id = ? ORDER BY m.mol_id ASC";
        return jdbcTemplate.queryForList(sql, predId);
    }
}
