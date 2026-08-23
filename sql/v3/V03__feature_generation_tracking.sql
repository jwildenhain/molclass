USE molclass_v3;

ALTER TABLE molecule_descriptor_vector
    ADD COLUMN last_job_id BIGINT UNSIGNED NULL AFTER status,
    ADD COLUMN attempt_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER last_job_id,
    ADD KEY ix_descriptor_last_job (last_job_id),
    ADD CONSTRAINT fk_descriptor_last_job
        FOREIGN KEY (last_job_id) REFERENCES job (job_id) ON DELETE SET NULL;

ALTER TABLE molecule_fingerprint
    ADD COLUMN last_job_id BIGINT UNSIGNED NULL AFTER status,
    ADD COLUMN attempt_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER last_job_id,
    ADD KEY ix_fingerprint_last_job (last_job_id),
    ADD CONSTRAINT fk_fingerprint_last_job
        FOREIGN KEY (last_job_id) REFERENCES job (job_id) ON DELETE SET NULL;
