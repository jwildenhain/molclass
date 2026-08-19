-- Unfiltered model-review lists order/page by updated_at DESC with
-- model_definition_id DESC as the deterministic tie-breaker.
ALTER TABLE model_definition
    ADD INDEX IF NOT EXISTS ix_model_definition_review_unfiltered
        (updated_at DESC, model_definition_id DESC);
