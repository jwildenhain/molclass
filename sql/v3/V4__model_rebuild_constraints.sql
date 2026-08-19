USE molclass_v3;

ALTER TABLE model_build
    ADD UNIQUE INDEX IF NOT EXISTS uq_model_build_generation
        (model_definition_id, generation_label, generation_number);

ALTER TABLE model_artifact
    ADD UNIQUE INDEX IF NOT EXISTS uq_model_artifact_kind (model_build_id, artifact_kind);

ALTER TABLE model_evaluation
    ADD INDEX IF NOT EXISTS ix_model_evaluation_metric
        (model_build_id, evaluation_set, metric_code, class_label);
