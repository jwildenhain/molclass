-- Immutable human decisions and an indexed pointer to the active published build.
ALTER TABLE model_approval
    ADD UNIQUE INDEX IF NOT EXISTS uq_model_approval_build (model_build_id);

ALTER TABLE model_definition
    ADD COLUMN IF NOT EXISTS published_model_build_id BIGINT UNSIGNED NULL AFTER status,
    ADD UNIQUE INDEX IF NOT EXISTS uq_model_definition_published_build (published_model_build_id),
    ADD INDEX IF NOT EXISTS ix_model_definition_status_published (status, published_model_build_id),
    ADD CONSTRAINT fk_model_definition_published_build
        FOREIGN KEY (published_model_build_id) REFERENCES model_build (model_build_id);
