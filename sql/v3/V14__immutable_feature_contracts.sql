USE molclass_v3;

-- Seal feature profile composition, expose profile-specific readiness, and
-- snapshot the resolved feature contract into every new model build. Existing
-- builds are deliberately left as LEGACY_NOT_CAPTURED: reconstructing a
-- historical contract from today's formerly-mutable profile would be false
-- provenance.
ALTER TABLE feature_profile
    ADD COLUMN contract_manifest_json JSON NULL AFTER configuration_sha256,
    ADD COLUMN contract_sha256 BINARY(32) NULL AFTER contract_manifest_json,
    ADD COLUMN raw_feature_count INT UNSIGNED NULL AFTER contract_sha256,
    ADD COLUMN required_molecule_count BIGINT UNSIGNED NULL AFTER raw_feature_count,
    ADD COLUMN ready_molecule_count BIGINT UNSIGNED NULL AFTER required_molecule_count,
    ADD COLUMN failed_molecule_count BIGINT UNSIGNED NULL AFTER ready_molecule_count,
    ADD COLUMN missing_molecule_count BIGINT UNSIGNED NULL AFTER failed_molecule_count,
    ADD COLUMN readiness_checked_at DATETIME(6) NULL AFTER missing_molecule_count,
    ADD COLUMN superseded_by_feature_profile_id BIGINT UNSIGNED NULL AFTER readiness_checked_at,
    ADD UNIQUE KEY uq_feature_profile_contract (profile_code, contract_sha256),
    ADD KEY ix_feature_profile_active (profile_code, superseded_by_feature_profile_id, status),
    ADD CONSTRAINT fk_feature_profile_superseded_by FOREIGN KEY (superseded_by_feature_profile_id)
        REFERENCES feature_profile (feature_profile_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT chk_feature_profile_contract_seal CHECK (
        (contract_manifest_json IS NULL AND contract_sha256 IS NULL AND raw_feature_count IS NULL)
        OR (contract_manifest_json IS NOT NULL AND contract_sha256 IS NOT NULL
            AND raw_feature_count IS NOT NULL)
    ),
    ADD CONSTRAINT chk_feature_profile_coverage CHECK (
        required_molecule_count IS NULL
        OR required_molecule_count = COALESCE(ready_molecule_count, 0)
            + COALESCE(failed_molecule_count, 0) + COALESCE(missing_molecule_count, 0)
    );

UPDATE feature_profile
   SET description = CASE profile_code
       WHEN 'CDK' THEN 'CDK molecular descriptors; 454 raw features before model-specific feature selection.'
       WHEN 'MACCS' THEN 'MACCS structural keys; 166 raw features before model-specific feature selection.'
       WHEN 'PubChem' THEN 'PubChem structural fingerprint; 881 raw features before model-specific feature selection.'
       WHEN 'EXT' THEN 'CDK extended fingerprint; 1024 raw features before model-specific feature selection.'
       WHEN 'KR' THEN 'Klekota-Roth structural keys; 4860 raw features before model-specific feature selection.'
       WHEN 'EXTGO' THEN 'Extended and graph-only fingerprints; 2048 raw features before model-specific feature selection.'
       WHEN 'ALL' THEN 'Descriptors plus MACCS, PubChem, extended, substructure and ECFP4 fingerprints; 3856 raw features before model-specific feature selection.'
       WHEN 'MCAT' THEN 'Descriptors plus MACCS, PubChem, substructure and Klekota-Roth fingerprints; 6668 raw features before model-specific feature selection.'
       WHEN 'JUMBO' THEN 'Descriptors plus MACCS, PubChem, extended, substructure, Klekota-Roth and ECFP4 fingerprints; 8716 raw features before model-specific feature selection.'
       ELSE description
   END;

CREATE TEMPORARY TABLE feature_profile_contract_backfill (
    feature_profile_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    component_json JSON NOT NULL,
    raw_feature_count INT UNSIGNED NOT NULL
) ENGINE=InnoDB;

INSERT INTO feature_profile_contract_backfill
    (feature_profile_id, component_json, raw_feature_count)
SELECT fpc.feature_profile_id,
       CAST(CONCAT('[', GROUP_CONCAT(
           JSON_OBJECT(
               'order', fpc.component_order,
               'sourceType', IF(fpc.descriptor_generation_id IS NULL,
                                'FINGERPRINT', 'DESCRIPTOR'),
               'sourceCode', COALESCE(fd.fingerprint_code, 'CDK'),
               'sourceVersion', COALESCE(fd.generation_version, dg.implementation_version),
               'configurationSha256', COALESCE(HEX(fd.configuration_sha256), HEX(ds.manifest_sha256)),
               'width', COALESCE(fd.bit_length, ds.descriptor_count),
               'transformation', fpc.transformation_json
           ) ORDER BY fpc.component_order SEPARATOR ','
       ), ']') AS JSON),
       SUM(COALESCE(fd.bit_length, ds.descriptor_count))
  FROM feature_profile_component fpc
  LEFT JOIN descriptor_generation dg
    ON dg.descriptor_generation_id=fpc.descriptor_generation_id
  LEFT JOIN descriptor_schema ds
    ON ds.descriptor_generation_id=fpc.descriptor_generation_id
  LEFT JOIN fingerprint_definition fd
    ON fd.fingerprint_definition_id=fpc.fingerprint_definition_id
 GROUP BY fpc.feature_profile_id;

UPDATE feature_profile fp
JOIN feature_profile_contract_backfill b
  ON b.feature_profile_id=fp.feature_profile_id
   SET fp.contract_manifest_json=JSON_OBJECT(
           'contractFormat','MOLCLASS_FEATURE_PROFILE_V1_MIGRATED',
           'profileCode',fp.profile_code,
           'components',JSON_EXTRACT(b.component_json,'$'),
           'rawFeatureCount',b.raw_feature_count),
       fp.raw_feature_count=b.raw_feature_count;

UPDATE feature_profile
   SET contract_sha256=UNHEX(SHA2(CAST(contract_manifest_json AS CHAR CHARACTER SET utf8mb4),256))
 WHERE contract_manifest_json IS NOT NULL;

DROP TEMPORARY TABLE feature_profile_contract_backfill;

ALTER TABLE model_build
    ADD COLUMN feature_profile_id_at_build BIGINT UNSIGNED NULL AFTER database_schema_version,
    ADD COLUMN feature_contract_json JSON NULL AFTER feature_profile_id_at_build,
    ADD COLUMN feature_contract_sha256 BINARY(32) NULL AFTER feature_contract_json,
    ADD COLUMN raw_feature_count INT UNSIGNED NULL AFTER feature_contract_sha256,
    ADD COLUMN feature_contract_capture VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'LEGACY_NOT_CAPTURED' AFTER raw_feature_count,
    ADD KEY ix_model_build_feature_contract (feature_contract_sha256, model_build_id),
    ADD CONSTRAINT fk_model_build_feature_profile_snapshot FOREIGN KEY (feature_profile_id_at_build)
        REFERENCES feature_profile (feature_profile_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT chk_model_build_feature_contract_capture CHECK (
        (feature_contract_capture='LEGACY_NOT_CAPTURED'
         AND feature_profile_id_at_build IS NULL AND feature_contract_json IS NULL
         AND feature_contract_sha256 IS NULL AND raw_feature_count IS NULL)
        OR (feature_contract_capture='CAPTURED_AT_BUILD'
            AND feature_profile_id_at_build IS NOT NULL AND feature_contract_json IS NOT NULL
            AND feature_contract_sha256 IS NOT NULL AND raw_feature_count IS NOT NULL)
    );

DELIMITER $$

CREATE TRIGGER bi_model_definition_feature_contract
BEFORE INSERT ON model_definition
FOR EACH ROW
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM feature_profile fp
         WHERE fp.feature_profile_id=NEW.feature_profile_id
           AND fp.contract_sha256 IS NOT NULL
           AND fp.superseded_by_feature_profile_id IS NULL
           AND fp.status LIKE 'READY%'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='model definition requires an active sealed READY feature profile';
    END IF;
END$$

CREATE TRIGGER bu_model_definition_feature_contract
BEFORE UPDATE ON model_definition
FOR EACH ROW
BEGIN
    IF NOT (NEW.feature_profile_id <=> OLD.feature_profile_id) AND NOT EXISTS (
        SELECT 1 FROM feature_profile fp
         WHERE fp.feature_profile_id=NEW.feature_profile_id
           AND fp.contract_sha256 IS NOT NULL
           AND fp.superseded_by_feature_profile_id IS NULL
           AND fp.status LIKE 'READY%'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='model definition requires an active sealed READY feature profile';
    END IF;
END$$

CREATE TRIGGER bi_model_build_feature_contract
BEFORE INSERT ON model_build
FOR EACH ROW
BEGIN
    DECLARE v_profile_id BIGINT UNSIGNED;
    DECLARE v_manifest JSON;
    DECLARE v_hash BINARY(32);
    DECLARE v_count INT UNSIGNED;

    SELECT fp.feature_profile_id,fp.contract_manifest_json,fp.contract_sha256,fp.raw_feature_count
      INTO v_profile_id,v_manifest,v_hash,v_count
      FROM model_definition md
      JOIN feature_profile fp ON fp.feature_profile_id=md.feature_profile_id
     WHERE md.model_definition_id=NEW.model_definition_id;

    IF v_hash IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='model build requires a sealed feature contract';
    END IF;
    SET NEW.feature_profile_id_at_build=v_profile_id;
    SET NEW.feature_contract_json=v_manifest;
    SET NEW.feature_contract_sha256=v_hash;
    SET NEW.raw_feature_count=v_count;
    SET NEW.feature_contract_capture='CAPTURED_AT_BUILD';
END$$

CREATE TRIGGER bu_model_build_feature_contract
BEFORE UPDATE ON model_build
FOR EACH ROW
BEGIN
    IF NOT (NEW.feature_profile_id_at_build <=> OLD.feature_profile_id_at_build)
       OR NOT (NEW.feature_contract_sha256 <=> OLD.feature_contract_sha256)
       OR NOT (NEW.feature_contract_json <=> OLD.feature_contract_json)
       OR NOT (NEW.raw_feature_count <=> OLD.raw_feature_count)
       OR NOT (NEW.feature_contract_capture <=> OLD.feature_contract_capture) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT='captured model-build feature contract is immutable';
    END IF;
    IF NEW.build_manifest_json IS NOT NULL
       AND NEW.feature_contract_capture='CAPTURED_AT_BUILD' THEN
        SET NEW.build_manifest_json=JSON_SET(
            NEW.build_manifest_json,
            '$.featureContract',NEW.feature_contract_json,
            '$.featureContractSha256',HEX(NEW.feature_contract_sha256),
            '$.rawFeatureCount',NEW.raw_feature_count);
        SET NEW.manifest_sha256=UNHEX(SHA2(
            CAST(NEW.build_manifest_json AS CHAR CHARACTER SET utf8mb4),256));
    END IF;
END$$

CREATE TRIGGER bi_feature_profile_component_sealed
BEFORE INSERT ON feature_profile_component
FOR EACH ROW
BEGIN
    IF EXISTS (SELECT 1 FROM feature_profile fp
                WHERE fp.feature_profile_id=NEW.feature_profile_id
                  AND fp.contract_sha256 IS NOT NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='sealed feature profile is immutable';
    END IF;
END$$

CREATE TRIGGER bu_feature_profile_component_sealed
BEFORE UPDATE ON feature_profile_component
FOR EACH ROW
BEGIN
    IF EXISTS (SELECT 1 FROM feature_profile fp
                WHERE fp.feature_profile_id=OLD.feature_profile_id
                  AND fp.contract_sha256 IS NOT NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='sealed feature profile is immutable';
    END IF;
END$$

CREATE TRIGGER bd_feature_profile_component_sealed
BEFORE DELETE ON feature_profile_component
FOR EACH ROW
BEGIN
    IF EXISTS (SELECT 1 FROM feature_profile fp
                WHERE fp.feature_profile_id=OLD.feature_profile_id
                  AND fp.contract_sha256 IS NOT NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='sealed feature profile is immutable';
    END IF;
END$$

CREATE TRIGGER bu_feature_profile_contract_sealed
BEFORE UPDATE ON feature_profile
FOR EACH ROW
BEGIN
    IF OLD.contract_sha256 IS NOT NULL AND (
       NOT (NEW.profile_code <=> OLD.profile_code)
       OR NOT (NEW.profile_version <=> OLD.profile_version)
       OR NOT (NEW.configuration_json <=> OLD.configuration_json)
       OR NOT (NEW.configuration_sha256 <=> OLD.configuration_sha256)
       OR NOT (NEW.contract_manifest_json <=> OLD.contract_manifest_json)
       OR NOT (NEW.contract_sha256 <=> OLD.contract_sha256)
       OR NOT (NEW.raw_feature_count <=> OLD.raw_feature_count)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='sealed feature profile contract is immutable';
    END IF;
END$$

CREATE TRIGGER bd_feature_profile_contract_sealed
BEFORE DELETE ON feature_profile
FOR EACH ROW
BEGIN
    IF OLD.contract_sha256 IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='sealed feature profile cannot be deleted';
    END IF;
END$$

DELIMITER ;

-- The two FDA DICTrank Ensemble definitions exhausted the former generic
-- two-hour ceiling. Preserve their classifier/feature contracts and grant a
-- reviewed six-hour deadline instead of silently changing the models.
UPDATE model_definition
   SET build_timeout_seconds=21600
 WHERE model_definition_id IN (201,202)
   AND algorithm_code='Ensemble';

INSERT INTO audit_event
    (actor,action_code,entity_type,entity_id,event_details_json,created_at)
SELECT 'schema-migration-v14','MODEL_TIMEOUT_POLICY_UPDATED','MODEL_DEFINITION',
       CAST(md.model_definition_id AS CHAR),
       JSON_OBJECT('previousSeconds',7200,'newSeconds',21600,
                   'reason','Reviewed retry after BUILD_TIMEOUT; model contract unchanged'),
       NOW(6)
  FROM model_definition md
 WHERE md.model_definition_id IN (201,202)
   AND md.algorithm_code='Ensemble';
