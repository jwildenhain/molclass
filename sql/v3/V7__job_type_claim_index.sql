-- Covers worker claim predicates by job type and status before the availability range.
ALTER TABLE job
    DROP INDEX ix_job_claim,
    ADD INDEX ix_job_claim (job_type, status, available_at, priority, job_id);
