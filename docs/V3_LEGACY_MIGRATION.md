# MolClass v3 legacy migration

## Scope

`LegacyV3Migration` performs the one-time, read-only conversion from
`molclass_legacy` into the clean `molclass_v3` schema.

It migrates:

- Dataset metadata and legacy batch IDs.
- Canonical molecule rows and many-to-one legacy molecule ID mappings.
- Dataset molecule records with their original structures and source identifiers.
- Every legacy `sdftags` property into shared, typed wide-table columns.
- All legacy model configurations as model definitions awaiting a v3 rebuild.
- Durable stage, record, error, attempt, checksum, and legacy-to-v3 ID state.

It intentionally does not migrate:

- Legacy CDK descriptor values.
- Legacy fingerprint values.
- Legacy predictions.
- Serialized legacy Weka models or headers.

Those values were produced by older chemistry and machine-learning runtimes and must
not be presented as v3 artifacts. The legacy database and checked-in `models_v2`
remain the immutable comparison baseline.

## Safety contract

- The source and target schemas must differ.
- All source queries are schema-qualified and use a read-only JDBC connection.
- A new run refuses a target containing datasets, molecules, or model definitions.
- A database named lock prevents two migration processes from running together.
- The source schema fingerprint must match when a run is resumed.
- Each entity is wrapped in a JDBC savepoint. One malformed record is recorded and
  skipped without aborting later records.
- Successful records and their ID maps are committed every configured chunk.
- Stages are idempotent and resume from the durable `runstep`.
- No database password is accepted on the command line.

## Identifier selection

For each dataset, every property is analyzed over all member records. A property is
eligible as an identifier only when every value is non-blank, every value is unique,
and the longest value fits the 512-character source identifier contract.

Eligible ID-like names are preferred deterministically, followed by compound names
and then other unique properties. If corrupt or incomplete legacy data has no valid
SDF property, the migration uses the synthetic `__legacy_mol_id` property. The
fallback is explicit in `dataset.identifier_property_id`; it is never silent.

The legacy database does not retain original SDF record order. Therefore v3
`record_number` is deterministic legacy `mol_id` order within each dataset.

## Schema prerequisite

Apply migrations in order:

```bash
mysql --protocol=TCP -h 127.0.0.1 -P 3306 \
  -u "$MOLCLASS_DB_USER" -p < sql/v3/V1__molclass_v3_baseline.sql
mysql --protocol=TCP -h 127.0.0.1 -P 3306 \
  -u "$MOLCLASS_DB_USER" -p < sql/v3/V2__legacy_migration_tracking.sql
```

## CLI contract

Set credentials in the environment:

```bash
export MOLCLASS_JDBC_URL='jdbc:mariadb://127.0.0.1:3306/'
export MOLCLASS_DB_USER='molclass_migrator'
export MOLCLASS_DB_PASSWORD='use-a-secret-source'
./gradlew migrateLegacyV3
```

Non-secret arguments are passed through the Gradle property:

```bash
./gradlew migrateLegacyV3 \
  -PmigrationArgs="--source-schema molclass_legacy --target-schema molclass_v3 --chunk-size 500"
```

Pause after a stage:

```bash
./gradlew migrateLegacyV3 \
  -PmigrationArgs="--stop-after PROPERTY_ANALYSIS"
```

Resume the newest unfinished run:

```bash
./gradlew migrateLegacyV3 -PmigrationArgs="--resume-run latest"
```

Supported stages are:

1. `PROPERTIES`
2. `DATASETS`
3. `PROPERTY_ANALYSIS`
4. `MOLECULES`
5. `DATASET_MOLECULES`
6. `MODEL_DEFINITIONS`

## Operational inspection

Run state:

```sql
SELECT legacy_migration_run_id, status, runstep, error_code, error_message,
       started_at, finished_at
FROM molclass_v3.legacy_migration_run
ORDER BY legacy_migration_run_id DESC;
```

Failed records:

```sql
SELECT entity_type, source_key, legacy_id, legacy_parent_id, attempt_count,
       error_code, error_message
FROM molclass_v3.legacy_migration_record
WHERE legacy_migration_run_id = ? AND status = 'FAILED'
ORDER BY entity_type, legacy_migration_record_id;
```

Datasets with incomplete membership stay `MIGRATED_PARTIAL`, require explicit
acknowledgement, and are not model-eligible. Their model definitions are stored as
`BLOCKED_DATASET_REVIEW`; complete datasets receive `PENDING_REBUILD` definitions.

## Next pipeline boundary

After migration, the feature-generation pipeline must create versioned descriptor
and fingerprint generations with CDK 2.12. The model-rebuild pipeline then resolves
each pending feature profile, trains with Weka 3.8.7, records exact train/validation
membership, stores checksummed artifacts in MySQL, evaluates them, and waits for
manual approval before publication.
