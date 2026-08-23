-- Worker claims use equality predicates on job_type and status, followed by
-- ORDER BY priority DESC, job_id ASC. Keeping available_at after those ordering
-- columns lets MariaDB walk jobs in claim order without a filesort; the
-- available_at <= current-time predicate remains available for index filtering.
ALTER TABLE job
    DROP INDEX ix_job_claim,
    ADD INDEX ix_job_claim
        (job_type, status, priority DESC, job_id ASC, available_at);

-- Model-review lists filter on status and order/page by updated_at with
-- model_definition_id as a stable tie-breaker. MariaDB can scan this index in
-- either direction when both ORDER BY columns use the same direction.
ALTER TABLE model_definition
    ADD INDEX IF NOT EXISTS ix_model_definition_review
        (status, updated_at, model_definition_id);
