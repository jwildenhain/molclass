USE molclass_v3;

CREATE TABLE model_build_supersession (
    model_build_id BIGINT UNSIGNED NOT NULL,
    superseded_by VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    supersession_reason VARCHAR(2048) NOT NULL,
    replacement_contract VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    superseded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (model_build_id),
    KEY ix_model_build_supersession_time (superseded_at, model_build_id),
    KEY ix_model_build_supersession_contract (replacement_contract, superseded_at),
    CONSTRAINT fk_model_build_supersession_build FOREIGN KEY (model_build_id)
        REFERENCES model_build (model_build_id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
