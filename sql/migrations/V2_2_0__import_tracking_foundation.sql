-- MolClass 2.2.0 import tracking foundation.
--
-- This migration introduces only new InnoDB tables. Foreign keys to legacy batch
-- and molecule tables are deferred until those tables are converted to InnoDB and
-- their existing data has passed integrity checks.

CREATE TABLE upload_artifact (
    upload_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    storage_key VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    original_filename VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_length BIGINT UNSIGNED NOT NULL,
    owner_subject VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    media_type VARCHAR(127) CHARACTER SET ascii COLLATE ascii_general_ci NULL,
    analysis_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    analysis_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    analysis_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    analysis_error_message VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
    retention_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    analyzed_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (upload_id),
    UNIQUE KEY uq_upload_storage_key (storage_key),
    KEY ix_upload_owner_created (owner_subject, created_at),
    KEY ix_upload_digest (content_sha256),
    KEY ix_upload_retention (retention_until, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE property_definition (
    property_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    original_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    canonical_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    physical_column_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sql_type_family VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sql_type_ddl VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    is_nullable TINYINT(1) NOT NULL DEFAULT 1,
    max_length INT UNSIGNED NULL,
    numeric_precision_value INT UNSIGNED NULL,
    numeric_scale_value INT UNSIGNED NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (property_id),
    UNIQUE KEY uq_property_canonical_name (canonical_name),
    UNIQUE KEY uq_property_physical_column (physical_column_name),
    KEY ix_property_original_name (original_name),
    KEY ix_property_active (active, property_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE import_run (
    import_run_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    upload_id BIGINT UNSIGNED NOT NULL,
    batch_id BIGINT NULL,
    owner_subject VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    runstep VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    identifier_property_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    selected_properties_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    analysis_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lease_owner VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    lease_expires_at DATETIME(6) NULL,
    heartbeat_at DATETIME(6) NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    total_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    success_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    failed_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    skipped_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    cancel_requested_at DATETIME(6) NULL,
    PRIMARY KEY (import_run_id),
    UNIQUE KEY uq_import_manifest (upload_id, manifest_sha256),
    KEY ix_import_queue (status, lease_expires_at, created_at),
    KEY ix_import_owner_created (owner_subject, created_at),
    KEY ix_import_batch (batch_id, import_run_id),
    CONSTRAINT fk_import_run_upload
        FOREIGN KEY (upload_id) REFERENCES upload_artifact (upload_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE import_run_property (
    import_run_id BIGINT UNSIGNED NOT NULL,
    property_id BIGINT UNSIGNED NOT NULL,
    source_property_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    selected TINYINT(1) NOT NULL DEFAULT 1,
    identifier_property TINYINT(1) NOT NULL DEFAULT 0,
    inferred_sql_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resolved_sql_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    present_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    blank_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    distinct_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (import_run_id, property_id),
    UNIQUE KEY uq_import_source_property (import_run_id, source_property_name),
    KEY ix_import_selected_property (import_run_id, selected, property_id),
    CONSTRAINT fk_import_property_run
        FOREIGN KEY (import_run_id) REFERENCES import_run (import_run_id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_import_property_definition
        FOREIGN KEY (property_id) REFERENCES property_definition (property_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE import_record (
    import_record_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    import_run_id BIGINT UNSIGNED NOT NULL,
    record_number BIGINT UNSIGNED NOT NULL,
    source_identifier VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    mol_id BIGINT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    runstep VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (import_record_id),
    UNIQUE KEY uq_import_record_number (import_run_id, record_number),
    UNIQUE KEY uq_import_record_source (import_run_id, source_identifier),
    KEY ix_import_record_status (import_run_id, status, record_number),
    KEY ix_import_record_molecule (mol_id, import_run_id),
    CONSTRAINT fk_import_record_run
        FOREIGN KEY (import_run_id) REFERENCES import_run (import_run_id)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE dataset_molecule (
    batch_id BIGINT NOT NULL,
    mol_id BIGINT NOT NULL,
    import_run_id BIGINT UNSIGNED NOT NULL,
    import_record_id BIGINT UNSIGNED NOT NULL,
    record_number BIGINT UNSIGNED NOT NULL,
    source_identifier VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (batch_id, mol_id),
    UNIQUE KEY uq_dataset_source_identifier (batch_id, source_identifier),
    UNIQUE KEY uq_dataset_import_record (import_record_id),
    KEY ix_dataset_molecule_reverse (mol_id, batch_id),
    KEY ix_dataset_import_run (import_run_id, record_number),
    CONSTRAINT fk_dataset_import_run
        FOREIGN KEY (import_run_id) REFERENCES import_run (import_run_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_dataset_import_record
        FOREIGN KEY (import_record_id) REFERENCES import_record (import_record_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE import_event (
    import_event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    import_run_id BIGINT UNSIGNED NOT NULL,
    import_record_id BIGINT UNSIGNED NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    runstep VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_message VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
    event_details_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (import_event_id),
    KEY ix_import_event_run (import_run_id, import_event_id),
    KEY ix_import_event_record (import_record_id, import_event_id),
    CONSTRAINT fk_import_event_run
        FOREIGN KEY (import_run_id) REFERENCES import_run (import_run_id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_import_event_record
        FOREIGN KEY (import_record_id) REFERENCES import_record (import_record_id)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Foreign keys from dataset_molecule(batch_id, mol_id) and import_record(mol_id)
-- to legacy MolClass tables must be added in a later migration after engine and
-- data-type reconciliation. Do not add application-side cascading deletes as a
-- substitute for those future constraints.
