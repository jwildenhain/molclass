USE molclass_v3;

-- Canonical molecules may legitimately represent more than one legacy molecule ID.
ALTER TABLE legacy_id_map
    DROP INDEX uq_legacy_map_v3,
    ADD INDEX ix_legacy_map_v3 (legacy_migration_run_id, entity_type, v3_id);

CREATE TABLE legacy_migration_record (
    legacy_migration_record_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    legacy_migration_run_id BIGINT UNSIGNED NOT NULL,
    entity_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legacy_id BIGINT NULL,
    legacy_parent_id BIGINT NULL,
    v3_id BIGINT UNSIGNED NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    runstep VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    source_sha256 BINARY(32) NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (legacy_migration_record_id),
    UNIQUE KEY uq_legacy_migration_record
        (legacy_migration_run_id, entity_type, source_key),
    KEY ix_legacy_migration_record_status
        (legacy_migration_run_id, status, entity_type, legacy_migration_record_id),
    KEY ix_legacy_migration_record_parent
        (legacy_migration_run_id, entity_type, legacy_parent_id, status),
    CONSTRAINT fk_legacy_migration_record_run
        FOREIGN KEY (legacy_migration_run_id)
        REFERENCES legacy_migration_run (legacy_migration_run_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Preserve the declared class contract and model provenance before any rebuild exists.
ALTER TABLE model_definition
    ADD COLUMN declared_class_labels_json LONGTEXT
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        CHECK (JSON_VALID(declared_class_labels_json))
        AFTER positive_class_label,
    ADD COLUMN created_by VARCHAR(255)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        AFTER status,
    ADD COLUMN definition_metadata_json LONGTEXT
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        CHECK (JSON_VALID(definition_metadata_json))
        AFTER created_by;
