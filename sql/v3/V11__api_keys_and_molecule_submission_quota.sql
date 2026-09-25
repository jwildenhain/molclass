USE molclass_v3;

-- Trusted external callers of the ad-hoc molecule-predict endpoint (e.g. chemgrid.org).
-- Keys are never stored in plaintext: callers send the raw key in a header, the API hashes
-- it and looks up the hash here.
CREATE TABLE api_key (
    api_key_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    key_hash BINARY(32) NOT NULL,
    label VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (api_key_id),
    UNIQUE KEY uq_api_key_hash (key_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One row per POST /api/v1/molecules/predict request, independent of whether the job
-- ultimately succeeds. Exists to enforce the per-IP daily submission quota (anonymous
-- callers) and to audit which caller (api_key_id, if any) submitted what and when.
-- Requests carrying a valid, unrevoked API key are exempt from the IP quota -- see
-- app/v3_molecules.py.
CREATE TABLE molecule_submission (
    molecule_submission_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    job_id BIGINT UNSIGNED NOT NULL,
    api_key_id BIGINT UNSIGNED NULL,
    client_ip VARBINARY(16) NOT NULL,
    requested_model_definition_id BIGINT UNSIGNED NULL,
    predict_all_missing TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (molecule_submission_id),
    UNIQUE KEY uq_molecule_submission_job (job_id),
    KEY ix_molecule_submission_ip_time (client_ip, created_at),
    KEY ix_molecule_submission_api_key (api_key_id, created_at),
    CONSTRAINT fk_molecule_submission_job FOREIGN KEY (job_id) REFERENCES job (job_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_molecule_submission_api_key FOREIGN KEY (api_key_id) REFERENCES api_key (api_key_id)
        ON UPDATE RESTRICT ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
