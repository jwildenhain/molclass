USE molclass_v3;

-- Persist evaluation and fail-safe execution intent with the immutable model
-- definition so web-created models and CLI-created models use the same worker
-- contract. NULL retains the existing automatic cross-validation policy.
ALTER TABLE model_definition
    ADD COLUMN cross_validation_folds TINYINT UNSIGNED NULL
        AFTER feature_selection_options_json,
    ADD COLUMN build_timeout_seconds INT UNSIGNED NOT NULL DEFAULT 7200
        AFTER cross_validation_folds,
    ADD CONSTRAINT chk_model_definition_cv_folds
        CHECK (cross_validation_folds IS NULL OR cross_validation_folds BETWEEN 2 AND 10),
    ADD CONSTRAINT chk_model_definition_build_timeout
        CHECK (build_timeout_seconds BETWEEN 60 AND 86400);
