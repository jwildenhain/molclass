-- MolClass v3 clean database baseline.
-- Target: MariaDB 10.11+
-- All domain tables are empty after this migration.

CREATE DATABASE IF NOT EXISTS molclass_v3
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE molclass_v3;
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE property_definition (
    property_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    original_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    physical_column_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sql_type_family VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sql_type_ddl VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nullable_value TINYINT(1) NOT NULL DEFAULT 1,
    maximum_length INT UNSIGNED NULL,
    numeric_precision_value SMALLINT UNSIGNED NULL,
    numeric_scale_value SMALLINT UNSIGNED NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (property_id),
    UNIQUE KEY uq_property_original_name (original_name),
    UNIQUE KEY uq_property_physical_column (physical_column_name),
    KEY ix_property_active (active, property_id),
    CONSTRAINT ck_property_storage_mode CHECK (storage_mode IN ('WIDE', 'OVERFLOW')),
    CONSTRAINT ck_property_type_family CHECK (sql_type_family IN
        ('INT', 'BIGINT', 'DECIMAL', 'DOUBLE', 'CHAR', 'VARCHAR', 'TEXT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE job (
    job_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    job_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    runstep VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    priority SMALLINT NOT NULL DEFAULT 0,
    payload_json JSON NOT NULL,
    lease_owner VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    lease_expires_at DATETIME(6) NULL,
    heartbeat_at DATETIME(6) NULL,
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    maximum_attempts INT UNSIGNED NOT NULL DEFAULT 1,
    cancel_requested_at DATETIME(6) NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (job_id),
    -- Equality predicates lead, then the worker's queue order. available_at is
    -- deliberately last so MariaDB can avoid sorting by priority DESC, job_id ASC.
    KEY ix_job_claim (job_type, status, priority DESC, job_id ASC, available_at),
    KEY ix_job_lease (status, lease_expires_at),
    KEY ix_job_type_created (job_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE job_event (
    job_event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    job_id BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    runstep VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_message VARCHAR(2048) NULL,
    event_details_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (job_event_id),
    KEY ix_job_event_job (job_id, job_event_id),
    CONSTRAINT fk_job_event_job FOREIGN KEY (job_id) REFERENCES job (job_id)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE worker_lock (
    lock_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lease_owner VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    lease_expires_at DATETIME(6) NULL,
    heartbeat_at DATETIME(6) NULL,
    fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (lock_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE upload_artifact (
    upload_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    storage_key VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    original_filename VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    content_sha256 BINARY(32) NOT NULL,
    content_length BIGINT UNSIGNED NOT NULL,
    media_type VARCHAR(127) CHARACTER SET ascii COLLATE ascii_general_ci NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    analysis_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    analysis_json JSON NULL,
    analysis_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    analysis_error_message VARCHAR(2048) NULL,
    retention_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    analyzed_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (upload_id),
    UNIQUE KEY uq_upload_storage_key (storage_key),
    KEY ix_upload_digest (content_sha256),
    KEY ix_upload_retention (retention_until, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE dataset (
    dataset_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    legacy_batch_id BIGINT NULL,
    upload_id BIGINT UNSIGNED NULL,
    name VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    description TEXT NULL,
    publication_reference VARCHAR(255) NULL,
    molecule_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    identifier_property_id BIGINT UNSIGNED NULL,
    total_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    imported_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    failed_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    not_processed_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    partial_acknowledgement_required TINYINT(1) NOT NULL DEFAULT 0,
    model_eligible TINYINT(1) NOT NULL DEFAULT 0,
    created_by VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (dataset_id),
    UNIQUE KEY uq_dataset_legacy_batch (legacy_batch_id),
    KEY ix_dataset_status_created (status, created_at),
    CONSTRAINT fk_dataset_upload FOREIGN KEY (upload_id) REFERENCES upload_artifact (upload_id)
        ON UPDATE RESTRICT ON DELETE SET NULL,
    CONSTRAINT fk_dataset_identifier_property FOREIGN KEY (identifier_property_id)
        REFERENCES property_definition (property_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE dataset_property (
    dataset_id BIGINT UNSIGNED NOT NULL,
    property_id BIGINT UNSIGNED NOT NULL,
    selected_for_import TINYINT(1) NOT NULL DEFAULT 1,
    identifier_property TINYINT(1) NOT NULL DEFAULT 0,
    model_target_allowed TINYINT(1) NOT NULL DEFAULT 0,
    searchable TINYINT(1) NOT NULL DEFAULT 0,
    present_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    blank_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    distinct_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    inferred_sql_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resolved_sql_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (dataset_id, property_id),
    KEY ix_dataset_property_target (dataset_id, model_target_allowed, property_id),
    KEY ix_dataset_property_search (property_id, searchable, dataset_id),
    CONSTRAINT fk_dataset_property_dataset FOREIGN KEY (dataset_id) REFERENCES dataset (dataset_id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_dataset_property_definition FOREIGN KEY (property_id)
        REFERENCES property_definition (property_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE molecule (
    molecule_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    normalization_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalization_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalized_structure MEDIUMBLOB NOT NULL,
    normalized_structure_sha256 BINARY(32) NOT NULL,
    standard_inchi MEDIUMTEXT NULL,
    full_inchi_key CHAR(27) CHARACTER SET ascii COLLATE ascii_bin NULL,
    canonical_smiles MEDIUMTEXT NULL,
    canonical_smiles_sha256 BINARY(32) NULL,
    primary_name VARCHAR(512) NULL,
    canonicalization_error VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (molecule_id),
    UNIQUE KEY uq_molecule_inchi_key (full_inchi_key),
    UNIQUE KEY uq_molecule_normalized_hash
        (normalization_version, normalized_structure_sha256),
    KEY ix_molecule_smiles_hash (canonical_smiles_sha256),
    KEY ix_molecule_status (normalization_status, molecule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE import_run (
    import_run_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    job_id BIGINT UNSIGNED NOT NULL,
    upload_id BIGINT UNSIGNED NOT NULL,
    dataset_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    runstep VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    identifier_property_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    analysis_sha256 BINARY(32) NOT NULL,
    manifest_sha256 BINARY(32) NOT NULL,
    selected_properties_json JSON NOT NULL,
    failure_threshold_numerator INT UNSIGNED NOT NULL DEFAULT 1,
    failure_threshold_denominator INT UNSIGNED NOT NULL DEFAULT 20,
    total_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    success_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    failed_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    not_processed_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (import_run_id),
    UNIQUE KEY uq_import_job (job_id),
    UNIQUE KEY uq_import_manifest (upload_id, manifest_sha256),
    KEY ix_import_dataset (dataset_id, import_run_id),
    KEY ix_import_status (status, created_at),
    CONSTRAINT fk_import_job FOREIGN KEY (job_id) REFERENCES job (job_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_import_upload FOREIGN KEY (upload_id) REFERENCES upload_artifact (upload_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_import_dataset FOREIGN KEY (dataset_id) REFERENCES dataset (dataset_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_import_failure_threshold CHECK (failure_threshold_numerator > 0
        AND failure_threshold_denominator > failure_threshold_numerator)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE import_run_property (
    import_run_id BIGINT UNSIGNED NOT NULL,
    property_id BIGINT UNSIGNED NOT NULL,
    source_property_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    selected_for_import TINYINT(1) NOT NULL DEFAULT 1,
    identifier_property TINYINT(1) NOT NULL DEFAULT 0,
    inferred_sql_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resolved_sql_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (import_run_id, property_id),
    UNIQUE KEY uq_import_source_property (import_run_id, source_property_name),
    CONSTRAINT fk_import_run_property_run FOREIGN KEY (import_run_id)
        REFERENCES import_run (import_run_id) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_import_run_property_definition FOREIGN KEY (property_id)
        REFERENCES property_definition (property_id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE import_record (
    import_record_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    import_run_id BIGINT UNSIGNED NOT NULL,
    record_number BIGINT UNSIGNED NOT NULL,
    source_identifier VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    molecule_id BIGINT UNSIGNED NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    runstep VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalization_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (import_record_id),
    UNIQUE KEY uq_import_record_number (import_run_id, record_number),
    UNIQUE KEY uq_import_record_identifier (import_run_id, source_identifier),
    KEY ix_import_record_status (import_run_id, status, record_number),
    KEY ix_import_record_molecule (molecule_id, import_run_id),
    CONSTRAINT fk_import_record_run FOREIGN KEY (import_run_id) REFERENCES import_run (import_run_id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_import_record_molecule FOREIGN KEY (molecule_id) REFERENCES molecule (molecule_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE dataset_molecule (
    dataset_molecule_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    dataset_id BIGINT UNSIGNED NOT NULL,
    molecule_id BIGINT UNSIGNED NOT NULL,
    import_record_id BIGINT UNSIGNED NULL,
    record_number BIGINT UNSIGNED NOT NULL,
    source_identifier VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_structure MEDIUMBLOB NOT NULL,
    source_structure_sha256 BINARY(32) NOT NULL,
    source_structure_format VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (dataset_molecule_id),
    UNIQUE KEY uq_dataset_record (dataset_id, record_number),
    UNIQUE KEY uq_dataset_source_identifier (dataset_id, source_identifier),
    UNIQUE KEY uq_dataset_import_record (import_record_id),
    KEY ix_dataset_molecule (dataset_id, molecule_id),
    KEY ix_molecule_dataset (molecule_id, dataset_id),
    CONSTRAINT fk_dataset_molecule_dataset FOREIGN KEY (dataset_id) REFERENCES dataset (dataset_id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_dataset_molecule_molecule FOREIGN KEY (molecule_id) REFERENCES molecule (molecule_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_dataset_molecule_import_record FOREIGN KEY (import_record_id)
        REFERENCES import_record (import_record_id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Java may add only allowlisted registry-backed property columns to this table.
CREATE TABLE dataset_molecule_properties (
    dataset_molecule_id BIGINT UNSIGNED NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (dataset_molecule_id),
    CONSTRAINT fk_wide_property_dataset_molecule FOREIGN KEY (dataset_molecule_id)
        REFERENCES dataset_molecule (dataset_molecule_id)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE property_value_overflow (
    dataset_molecule_id BIGINT UNSIGNED NOT NULL,
    property_id BIGINT UNSIGNED NOT NULL,
    integer_value BIGINT NULL,
    decimal_value DECIMAL(65,30) NULL,
    double_value DOUBLE NULL,
    text_value LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    value_sha256 BINARY(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (dataset_molecule_id, property_id),
    KEY ix_overflow_property_hash (property_id, value_sha256),
    CONSTRAINT fk_overflow_dataset_molecule FOREIGN KEY (dataset_molecule_id)
        REFERENCES dataset_molecule (dataset_molecule_id) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_overflow_property FOREIGN KEY (property_id)
        REFERENCES property_definition (property_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_overflow_one_value CHECK ((integer_value IS NOT NULL)
        + (decimal_value IS NOT NULL) + (double_value IS NOT NULL)
        + (text_value IS NOT NULL) = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE property_schema_change (
    property_schema_change_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    import_run_id BIGINT UNSIGNED NULL,
    property_id BIGINT UNSIGNED NOT NULL,
    change_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_sql_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    new_sql_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ddl_sha256 BINARY(32) NOT NULL,
    applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (property_schema_change_id),
    KEY ix_property_change_property (property_id, applied_at),
    CONSTRAINT fk_property_change_import FOREIGN KEY (import_run_id)
        REFERENCES import_run (import_run_id) ON UPDATE RESTRICT ON DELETE SET NULL,
    CONSTRAINT fk_property_change_definition FOREIGN KEY (property_id)
        REFERENCES property_definition (property_id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE dataset_acknowledgement (
    dataset_acknowledgement_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    dataset_id BIGINT UNSIGNED NOT NULL,
    import_run_id BIGINT UNSIGNED NULL,
    acknowledgement_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    acknowledged_by VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    acknowledgement_note VARCHAR(2048) NULL,
    acknowledged_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (dataset_acknowledgement_id),
    KEY ix_dataset_acknowledgement (dataset_id, acknowledgement_type, acknowledged_at),
    CONSTRAINT fk_acknowledgement_dataset FOREIGN KEY (dataset_id) REFERENCES dataset (dataset_id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_acknowledgement_import FOREIGN KEY (import_run_id)
        REFERENCES import_run (import_run_id) ON UPDATE RESTRICT ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE descriptor_generation (
    descriptor_generation_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    generation_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cdk_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    java_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalization_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    implementation_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    configuration_json JSON NOT NULL,
    configuration_sha256 BINARY(32) NOT NULL,
    vector_format VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    PRIMARY KEY (descriptor_generation_id),
    UNIQUE KEY uq_descriptor_generation_name (generation_name),
    UNIQUE KEY uq_descriptor_generation_config (configuration_sha256),
    KEY ix_descriptor_generation_status (status, descriptor_generation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE descriptor_schema (
    descriptor_generation_id BIGINT UNSIGNED NOT NULL,
    descriptor_count INT UNSIGNED NOT NULL,
    descriptor_manifest_json JSON NOT NULL,
    manifest_sha256 BINARY(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (descriptor_generation_id),
    UNIQUE KEY uq_descriptor_manifest_hash (manifest_sha256),
    CONSTRAINT fk_descriptor_schema_generation FOREIGN KEY (descriptor_generation_id)
        REFERENCES descriptor_generation (descriptor_generation_id)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE molecule_descriptor_vector (
    descriptor_generation_id BIGINT UNSIGNED NOT NULL,
    molecule_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    descriptor_values LONGBLOB NULL,
    missing_value_mask BLOB NULL,
    values_sha256 BINARY(32) NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(2048) NULL,
    calculated_at DATETIME(6) NULL,
    PRIMARY KEY (descriptor_generation_id, molecule_id),
    KEY ix_descriptor_molecule (molecule_id, descriptor_generation_id),
    KEY ix_descriptor_status (descriptor_generation_id, status, molecule_id),
    CONSTRAINT fk_descriptor_vector_generation FOREIGN KEY (descriptor_generation_id)
        REFERENCES descriptor_generation (descriptor_generation_id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_descriptor_vector_molecule FOREIGN KEY (molecule_id)
        REFERENCES molecule (molecule_id) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE fingerprint_definition (
    fingerprint_definition_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    fingerprint_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    implementation_class VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cdk_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalization_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bit_length INT UNSIGNED NOT NULL,
    configuration_json JSON NOT NULL,
    configuration_sha256 BINARY(32) NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    PRIMARY KEY (fingerprint_definition_id),
    UNIQUE KEY uq_fingerprint_code_version (fingerprint_code, generation_version),
    UNIQUE KEY uq_fingerprint_config (configuration_sha256),
    KEY ix_fingerprint_definition_status (status, fingerprint_definition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE molecule_fingerprint (
    fingerprint_definition_id BIGINT UNSIGNED NOT NULL,
    molecule_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint_bits BLOB NULL,
    bit_count INT UNSIGNED NULL,
    bits_sha256 BINARY(32) NULL,
    fallback_used TINYINT(1) NOT NULL DEFAULT 0,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(2048) NULL,
    calculated_at DATETIME(6) NULL,
    PRIMARY KEY (fingerprint_definition_id, molecule_id),
    KEY ix_fingerprint_molecule (molecule_id, fingerprint_definition_id),
    KEY ix_fingerprint_status (fingerprint_definition_id, status, molecule_id),
    CONSTRAINT fk_molecule_fingerprint_definition FOREIGN KEY (fingerprint_definition_id)
        REFERENCES fingerprint_definition (fingerprint_definition_id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_molecule_fingerprint_molecule FOREIGN KEY (molecule_id)
        REFERENCES molecule (molecule_id) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE feature_profile (
    feature_profile_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    profile_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    profile_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    description VARCHAR(2048) NULL,
    configuration_json JSON NOT NULL,
    configuration_sha256 BINARY(32) NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (feature_profile_id),
    UNIQUE KEY uq_feature_profile_code_version (profile_code, profile_version),
    UNIQUE KEY uq_feature_profile_config (configuration_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE feature_profile_component (
    feature_profile_id BIGINT UNSIGNED NOT NULL,
    component_order SMALLINT UNSIGNED NOT NULL,
    descriptor_generation_id BIGINT UNSIGNED NULL,
    fingerprint_definition_id BIGINT UNSIGNED NULL,
    transformation_json JSON NULL,
    PRIMARY KEY (feature_profile_id, component_order),
    KEY ix_feature_component_descriptor (descriptor_generation_id),
    KEY ix_feature_component_fingerprint (fingerprint_definition_id),
    CONSTRAINT fk_feature_component_profile FOREIGN KEY (feature_profile_id)
        REFERENCES feature_profile (feature_profile_id) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_feature_component_descriptor FOREIGN KEY (descriptor_generation_id)
        REFERENCES descriptor_generation (descriptor_generation_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_feature_component_fingerprint FOREIGN KEY (fingerprint_definition_id)
        REFERENCES fingerprint_definition (fingerprint_definition_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_feature_component_one_source CHECK ((descriptor_generation_id IS NOT NULL)
        + (fingerprint_definition_id IS NOT NULL) = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE model_definition (
    model_definition_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    legacy_model_id BIGINT NULL,
    dataset_id BIGINT UNSIGNED NOT NULL,
    target_property_id BIGINT UNSIGNED NOT NULL,
    feature_profile_id BIGINT UNSIGNED NOT NULL,
    model_name VARCHAR(255) NULL,
    algorithm_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    algorithm_options_json JSON NOT NULL,
    feature_selection_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    feature_selection_options_json JSON NOT NULL,
    positive_class_label VARCHAR(255) NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (model_definition_id),
    UNIQUE KEY uq_model_definition_legacy (legacy_model_id),
    KEY ix_model_definition_dataset (dataset_id, model_definition_id),
    KEY ix_model_definition_status (status, model_definition_id),
    -- Supports review lists filtered by status and ordered/paged by update time
    -- with model_definition_id as the deterministic tie-breaker.
    KEY ix_model_definition_review (status, updated_at, model_definition_id),
    -- Supports unfiltered review lists in deterministic newest-first order.
    KEY ix_model_definition_review_unfiltered (updated_at DESC, model_definition_id DESC),
    CONSTRAINT fk_model_definition_dataset FOREIGN KEY (dataset_id) REFERENCES dataset (dataset_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_model_definition_target FOREIGN KEY (target_property_id)
        REFERENCES property_definition (property_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_model_definition_feature_profile FOREIGN KEY (feature_profile_id)
        REFERENCES feature_profile (feature_profile_id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE model_build (
    model_build_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    model_definition_id BIGINT UNSIGNED NOT NULL,
    parent_model_build_id BIGINT UNSIGNED NULL,
    job_id BIGINT UNSIGNED NULL,
    generation_label VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation_number INT UNSIGNED NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    runstep VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    java_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cdk_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    weka_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    code_revision VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    database_schema_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    random_seed BIGINT NOT NULL,
    split_strategy VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    split_configuration_json JSON NOT NULL,
    training_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    validation_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    holdout_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    excluded_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    build_manifest_json JSON NULL,
    manifest_sha256 BINARY(32) NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (model_build_id),
    UNIQUE KEY uq_model_generation (model_definition_id, generation_number),
    UNIQUE KEY uq_model_build_job (job_id),
    KEY ix_model_build_status (status, created_at),
    KEY ix_model_build_published (model_definition_id, published_at),
    CONSTRAINT fk_model_build_definition FOREIGN KEY (model_definition_id)
        REFERENCES model_definition (model_definition_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_model_build_parent FOREIGN KEY (parent_model_build_id)
        REFERENCES model_build (model_build_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_model_build_job FOREIGN KEY (job_id) REFERENCES job (job_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE model_training_member (
    model_build_id BIGINT UNSIGNED NOT NULL,
    dataset_molecule_id BIGINT UNSIGNED NOT NULL,
    partition_name VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fold_number SMALLINT NULL,
    exclusion_reason VARCHAR(255) NULL,
    PRIMARY KEY (model_build_id, dataset_molecule_id),
    KEY ix_training_member_partition
        (model_build_id, partition_name, fold_number, dataset_molecule_id),
    KEY ix_training_member_molecule (dataset_molecule_id, model_build_id),
    CONSTRAINT fk_training_member_build FOREIGN KEY (model_build_id)
        REFERENCES model_build (model_build_id) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_training_member_dataset_molecule FOREIGN KEY (dataset_molecule_id)
        REFERENCES dataset_molecule (dataset_molecule_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE model_class (
    model_build_id BIGINT UNSIGNED NOT NULL,
    class_order SMALLINT UNSIGNED NOT NULL,
    class_label VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    support_count BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (model_build_id, class_order),
    UNIQUE KEY uq_model_class_label (model_build_id, class_label),
    CONSTRAINT fk_model_class_build FOREIGN KEY (model_build_id)
        REFERENCES model_build (model_build_id) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE model_evaluation (
    model_evaluation_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    model_build_id BIGINT UNSIGNED NOT NULL,
    evaluation_set VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fold_number SMALLINT NULL,
    class_label VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    metric_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    metric_value DOUBLE NULL,
    support_count BIGINT UNSIGNED NULL,
    metric_details_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (model_evaluation_id),
    KEY ix_model_evaluation_build
        (model_build_id, evaluation_set, metric_code, class_label),
    CONSTRAINT fk_model_evaluation_build FOREIGN KEY (model_build_id)
        REFERENCES model_build (model_build_id) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE model_artifact (
    model_artifact_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    model_build_id BIGINT UNSIGNED NOT NULL,
    artifact_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    artifact_format VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    media_type VARCHAR(127) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    artifact_size BIGINT UNSIGNED NOT NULL,
    artifact_sha256 BINARY(32) NOT NULL,
    artifact_payload LONGBLOB NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (model_artifact_id),
    UNIQUE KEY uq_model_artifact_kind (model_build_id, artifact_kind),
    UNIQUE KEY uq_model_artifact_hash (model_build_id, artifact_sha256),
    CONSTRAINT fk_model_artifact_build FOREIGN KEY (model_build_id)
        REFERENCES model_build (model_build_id) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT ck_model_artifact_size CHECK (artifact_size = OCTET_LENGTH(artifact_payload))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE model_approval (
    model_approval_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    model_build_id BIGINT UNSIGNED NOT NULL,
    approval_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approved_by VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    approval_note VARCHAR(2048) NULL,
    approved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (model_approval_id),
    KEY ix_model_approval_build (model_build_id, approved_at),
    CONSTRAINT fk_model_approval_build FOREIGN KEY (model_build_id)
        REFERENCES model_build (model_build_id) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prediction_job (
    prediction_job_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    job_id BIGINT UNSIGNED NOT NULL,
    model_build_id BIGINT UNSIGNED NOT NULL,
    dataset_id BIGINT UNSIGNED NULL,
    prediction_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (prediction_job_id),
    UNIQUE KEY uq_prediction_job_job (job_id),
    KEY ix_prediction_model (model_build_id, prediction_job_id),
    KEY ix_prediction_dataset (dataset_id, prediction_job_id),
    CONSTRAINT fk_prediction_job_job FOREIGN KEY (job_id) REFERENCES job (job_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_prediction_job_model_build FOREIGN KEY (model_build_id)
        REFERENCES model_build (model_build_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_prediction_job_dataset FOREIGN KEY (dataset_id) REFERENCES dataset (dataset_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prediction_result (
    prediction_job_id BIGINT UNSIGNED NOT NULL,
    molecule_id BIGINT UNSIGNED NOT NULL,
    dataset_molecule_id BIGINT UNSIGNED NULL,
    predicted_class VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    distribution_json JSON NOT NULL,
    confidence_score DOUBLE NULL,
    applicability_score DOUBLE NULL,
    in_applicability_domain TINYINT(1) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (prediction_job_id, molecule_id),
    KEY ix_prediction_result_molecule (molecule_id, prediction_job_id),
    KEY ix_prediction_result_dataset_molecule (dataset_molecule_id, prediction_job_id),
    CONSTRAINT fk_prediction_result_job FOREIGN KEY (prediction_job_id)
        REFERENCES prediction_job (prediction_job_id) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_prediction_result_molecule FOREIGN KEY (molecule_id)
        REFERENCES molecule (molecule_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_prediction_result_dataset_molecule FOREIGN KEY (dataset_molecule_id)
        REFERENCES dataset_molecule (dataset_molecule_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scaffold_definition (
    scaffold_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    generation_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scaffold_smiles MEDIUMTEXT NOT NULL,
    scaffold_sha256 BINARY(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (scaffold_id),
    UNIQUE KEY uq_scaffold_generation_hash (generation_version, scaffold_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE molecule_scaffold (
    molecule_id BIGINT UNSIGNED NOT NULL,
    scaffold_id BIGINT UNSIGNED NOT NULL,
    primary_scaffold TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (molecule_id, scaffold_id),
    KEY ix_scaffold_molecules (scaffold_id, molecule_id),
    CONSTRAINT fk_molecule_scaffold_molecule FOREIGN KEY (molecule_id)
        REFERENCES molecule (molecule_id) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_molecule_scaffold_definition FOREIGN KEY (scaffold_id)
        REFERENCES scaffold_definition (scaffold_id) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE search_index_generation (
    search_index_generation_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    index_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    implementation VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    implementation_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint_definition_id BIGINT UNSIGNED NULL,
    configuration_json JSON NOT NULL,
    configuration_sha256 BINARY(32) NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    indexed_molecule_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (search_index_generation_id),
    UNIQUE KEY uq_search_index_config (configuration_sha256),
    KEY ix_search_index_status (status, index_type),
    CONSTRAINT fk_search_index_fingerprint FOREIGN KEY (fingerprint_definition_id)
        REFERENCES fingerprint_definition (fingerprint_definition_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE audit_event (
    audit_event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    action_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_details_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (audit_event_id),
    KEY ix_audit_entity (entity_type, entity_id, audit_event_id),
    KEY ix_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE legacy_migration_run (
    legacy_migration_run_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_schema VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_schema_fingerprint BINARY(32) NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    runstep VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    configuration_json JSON NOT NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (legacy_migration_run_id),
    KEY ix_legacy_migration_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE legacy_id_map (
    legacy_migration_run_id BIGINT UNSIGNED NOT NULL,
    entity_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legacy_id BIGINT NOT NULL,
    v3_id BIGINT UNSIGNED NOT NULL,
    source_sha256 BINARY(32) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (legacy_migration_run_id, entity_type, legacy_id),
    UNIQUE KEY uq_legacy_map_v3 (legacy_migration_run_id, entity_type, v3_id),
    CONSTRAINT fk_legacy_id_map_run FOREIGN KEY (legacy_migration_run_id)
        REFERENCES legacy_migration_run (legacy_migration_run_id)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
