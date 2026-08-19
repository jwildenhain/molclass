# MolClass v3 production index preflight

Preflight date: 2026-08-17  
Target schema: `molclass_v3`  
Observed server: MariaDB `10.11.14-MariaDB-0ubuntu0.24.04.1`  
Prepared migrations: V8 and V9  
Preflight outcome: ready for a controlled maintenance-window apply after the active model build finishes; no migration was applied during this preflight.

## Scope and safety boundary

This preflight covers the indexes prepared in:

- `sql/v3/V8__worker_claim_and_model_review_indexes.sql`
- `sql/v3/V9__unfiltered_model_review_index.sql`

It maps those indexes to the exact query shapes in:

- `src/molclass/importer/V3SdfWorker.java`
- `src/molclass/importer/V3SdfImporter.java`
- `html/molclass/api/app/v3_model_reviews.py`

The live inspection used only `SELECT`, `SHOW`, and `EXPLAIN`. It did not execute DDL, DML, a migration, a build, a model approval, or a source-control command. Model definition 19 remained active throughout the initial database inspection.

## Executive finding

V8 and V9 are not present in the inspected database. The current `job.ix_job_claim` has `available_at` before the ordering columns, and `model_definition` has neither proposed review index.

All four baseline plans report a filesort on the targeted path:

- The SDF analysis claim uses `ix_job_lease|ix_job_type_created` through MariaDB's `ref|filter` access and reports `Using filesort`.
- The SDF import claim uses the same `ref|filter` combination and reports `Using filesort`.
- The status-filtered model-review query starts with `feature_profile`, uses a temporary result, and reports `Using filesort`.
- The unfiltered model-review query has the same join order, temporary result, and filesort.

The prepared indexes match the predicates and ordering in the application source. Applying them is reasonable, but the expected plan improvements below are inference until the post-apply `EXPLAIN` commands confirm them. Current tables are small, so MariaDB may continue to prefer a different join order even when the new indexes are available.

## Live database snapshot

The following values were observed through `SELECT` from `information_schema` and grouped row counts. InnoDB `table_rows` values are optimizer estimates, not exact counts.

| Table | Engine | Estimated rows | Data bytes | Index bytes |
| --- | --- | ---: | ---: | ---: |
| `dataset` | InnoDB | 88 | 49,152 | 65,536 |
| `feature_profile` | InnoDB | 9 | 16,384 | 32,768 |
| `import_run` | InnoDB | 1 | 16,384 | 65,536 |
| `job` | InnoDB | 142 | 49,152 | 49,152 |
| `model_approval` | InnoDB | 2 | 16,384 | 32,768 |
| `model_build` | InnoDB | 135 | 131,072 | 98,304 |
| `model_definition` | InnoDB | 118 | 98,304 | 114,688 |
| `property_definition` | InnoDB | 91 | 16,384 | 49,152 |
| `upload_artifact` | InnoDB | 0 | 16,384 | 49,152 |

At inspection time, `job` contained one `MODEL_REBUILD/RUNNING` row and no queued SDF job. `model_definition` contained 115 `AWAITING_APPROVAL`, one `PENDING_REBUILD`, and two `REBUILD_FAILED` rows. The lack of queued SDF work and the low table cardinalities limit the predictive value of current row estimates.

## Current and proposed index definitions

Index direction below comes from `SHOW INDEX`: `A` is ascending and `D` is descending.

| Table and index | Current live definition | Proposed definition | Source | State |
| --- | --- | --- | --- | --- |
| `job.ix_job_claim` | `(job_type A, status A, available_at A, priority A, job_id A)` | `(job_type A, status A, priority D, job_id A, available_at A)` | V8 | Replacement not applied |
| `model_definition.ix_model_definition_review` | Absent | `(status A, updated_at A, model_definition_id A)` | V8 | Not applied |
| `model_definition.ix_model_definition_review_unfiltered` | Absent | `(updated_at D, model_definition_id D)` | V9 | Not applied |

The existing related `model_definition` indexes are:

| Index | Live definition |
| --- | --- |
| `PRIMARY` | `(model_definition_id A)` |
| `uq_model_definition_legacy` | `(legacy_model_id A)` |
| `uq_model_definition_published_build` | `(published_model_build_id A)` |
| `ix_model_definition_dataset` | `(dataset_id A, model_definition_id A)` |
| `ix_model_definition_status` | `(status A, model_definition_id A)` |
| `fk_model_definition_target` | `(target_property_id A)` |
| `fk_model_definition_feature_profile` | `(feature_profile_id A)` |
| `ix_model_definition_status_published` | `(status A, published_model_build_id A)` |

V8 deliberately replaces `ix_job_claim`; it does not add a second claim index. V8 and V9 add review indexes without dropping the existing status indexes. Retain those existing indexes until production workload evidence demonstrates that they are redundant for all other query paths.

## Exact application query shapes

Java table-name helpers qualify the configured schema. The SQL below renders that helper as `molclass_v3` and retains the application's placeholders.

### SDF analysis worker claim

Source: `V3SdfWorker.java`, with `JOB_TYPE = 'SDF_ANALYZE'`.

```sql
SELECT j.job_id,
       u.upload_id,
       u.storage_key,
       u.content_sha256,
       u.content_length
FROM molclass_v3.job j
JOIN molclass_v3.upload_artifact u
  ON u.upload_id = CAST(
       JSON_UNQUOTE(JSON_EXTRACT(j.payload_json, '$.uploadId')) AS UNSIGNED
     )
WHERE j.job_type = ?
  AND j.status = 'QUEUED'
  AND j.available_at <= UTC_TIMESTAMP(6)
ORDER BY j.priority DESC, j.job_id
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

### SDF import worker claim

Source: `V3SdfImporter.java`, with `JOB_TYPE = 'SDF_IMPORT'`.

```sql
SELECT j.job_id,
       ir.import_run_id,
       ir.upload_id,
       ir.dataset_id,
       ir.identifier_property_name,
       ir.total_records,
       ir.success_records,
       ir.failed_records,
       ir.not_processed_records,
       u.storage_key,
       u.content_sha256,
       u.content_length
FROM molclass_v3.job j
JOIN molclass_v3.import_run ir
  ON ir.job_id = j.job_id
JOIN molclass_v3.upload_artifact u
  ON u.upload_id = ir.upload_id
WHERE j.job_type = ?
  AND j.status = 'QUEUED'
  AND j.available_at <= UTC_TIMESTAMP(6)
ORDER BY j.priority DESC, j.job_id
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

Both worker queries therefore have the same index contract:

```text
equality: job_type, status
ordering: priority DESC, job_id ASC
eligibility filter: available_at <= current UTC time
```

### Model-review list

Source: `v3_model_reviews.py`. The endpoint conditionally inserts the marked status predicate and binds `limit`, `offset`, and, when present, `status`.

```sql
SELECT md.model_definition_id,
       md.model_name,
       md.status AS definition_status,
       md.algorithm_code,
       md.feature_selection_code,
       md.positive_class_label,
       md.created_by,
       md.created_at,
       md.updated_at,
       d.dataset_id,
       d.name AS dataset_name,
       p.original_name AS target_property,
       fp.profile_code,
       mb.model_build_id,
       mb.status AS build_status,
       mb.runstep,
       mb.generation_number,
       mb.finished_at,
       mb.published_at,
       ma.approval_status,
       ma.approved_by,
       ma.approved_at
FROM molclass_v3.model_definition md
JOIN molclass_v3.dataset d
  ON d.dataset_id = md.dataset_id
JOIN molclass_v3.property_definition p
  ON p.property_id = md.target_property_id
JOIN molclass_v3.feature_profile fp
  ON fp.feature_profile_id = md.feature_profile_id
LEFT JOIN molclass_v3.model_build mb
  ON mb.model_build_id = (
       SELECT mb_latest.model_build_id
       FROM molclass_v3.model_build mb_latest
       WHERE mb_latest.model_definition_id = md.model_definition_id
       ORDER BY mb_latest.generation_number DESC
       LIMIT 1
     )
LEFT JOIN molclass_v3.model_approval ma
  ON ma.model_build_id = mb.model_build_id
/* Filtered variant only: WHERE md.status = :status */
ORDER BY md.updated_at DESC, md.model_definition_id DESC
LIMIT :limit OFFSET :offset;
```

The latest-build dependent subquery already uses `uq_model_generation` in the baseline plan and reports `Using index`. V8/V9 target the outer model-definition list, not that subquery.

## Baseline EXPLAIN evidence

Traditional `EXPLAIN` was used to avoid executing the locking reads. Representative bound values were `SDF_ANALYZE`, `SDF_IMPORT`, `AWAITING_APPROVAL`, `LIMIT 100`, and `OFFSET 0`.

### Worker claims

| Query | Table | Access | Chosen key | Estimated rows | Extra |
| --- | --- | --- | --- | ---: | --- |
| SDF analysis claim | `j` | `ref|filter` | `ix_job_lease|ix_job_type_created` | `1 (1%)` | `Using index condition; Using where; Using filesort; Using rowid filter` |
| SDF analysis claim | `u` | `eq_ref` | `PRIMARY` | 1 | `Using where` |
| SDF import claim | `j` | `ref|filter` | `ix_job_lease|ix_job_type_created` | `1 (1%)` | `Using index condition; Using where; Using filesort; Using rowid filter` |
| SDF import claim | `ir` | `eq_ref` | `uq_import_job` | 1 | none reported |
| SDF import claim | `u` | `eq_ref` | `PRIMARY` | 1 | none reported |

The current claim index was listed among `possible_keys` but was not selected. The plan combines filtering from existing indexes and then sorts eligible candidates.

### Filtered model-review list

| Select | Table | Access | Chosen key | Estimated rows | Extra |
| --- | --- | --- | --- | ---: | --- |
| Primary | `fp` | `index` | `uq_feature_profile_code_version` | 9 | `Using index; Using temporary; Using filesort` |
| Primary | `md` | `ref` | `fk_model_definition_feature_profile` | 6 | `Using where` |
| Primary | `d` | `eq_ref` | `PRIMARY` | 1 | none reported |
| Primary | `p` | `eq_ref` | `PRIMARY` | 1 | none reported |
| Primary | `mb` | `eq_ref` | `PRIMARY` | 1 | `Using where` |
| Primary | `ma` | `eq_ref` | `uq_model_approval_build` | 1 | `Using where` |
| Dependent subquery | `mb_latest` | `ref` | `uq_model_generation` | 1 | `Using where; Using index` |

### Unfiltered model-review list

The unfiltered plan is the same except that the `md` row has no `Using where` entry. It starts from `fp`, reaches `md` through `fk_model_definition_feature_profile`, and reports `Using temporary; Using filesort` on `fp`. The latest-build subquery again uses `uq_model_generation`.

## Expected improvement

Everything in this section is inference, not observed post-migration evidence.

### V8 worker-claim replacement

**Inference:** `(job_type, status, priority DESC, job_id ASC, available_at)` lets MariaDB seek to one job-type/status queue and walk rows in claim order. That should remove the filesort and reduce work before `LIMIT 1`, especially as historical jobs and queued jobs accumulate.

Placing `available_at` after the ordering columns preserves index order, but it also means `available_at <= UTC_TIMESTAMP(6)` is not a contiguous range immediately after the equality prefix. MariaDB can evaluate it while scanning, but a queue dominated by high-priority future jobs could require scanning several entries before finding an eligible job. This is the intentional tradeoff in V8: efficient priority order rather than an `available_at` range followed by a filesort.

### V8 filtered review index

**Inference:** `(status, updated_at, model_definition_id)` matches status equality followed by the deterministic two-column order. MariaDB can traverse a B-tree in reverse when both ordered columns have the same requested direction, so the all-ascending physical definition can support both `DESC` terms. It should permit an ordered, limited scan for one status and avoid the temporary/filesort path when the optimizer starts with `model_definition`.

### V9 unfiltered review index

**Inference:** `(updated_at DESC, model_definition_id DESC)` matches the unfiltered order and stable tie-breaker directly. It should permit an ordered `LIMIT/OFFSET` scan and avoid sorting the complete joined candidate set.

The current endpoint permits offsets and therefore still has linear skip cost for deep pages. These indexes improve ordering access but do not convert offset pagination to keyset pagination.

### Why a plan change is not guaranteed locally

The inspected schema has only 118 model definitions, nine feature profiles, and a page limit of 100. At that scale, MariaDB may calculate that the current join order is cheaper even after the review indexes exist. Production acceptance must compare `EXPLAIN` after apply and measure endpoint latency under representative cardinality; index presence alone is not proof of use or improvement.

## Idempotence and partial-failure behavior

### V8

V8 is not fully idempotent under every starting state:

- `DROP INDEX ix_job_claim` has no `IF EXISTS`. It succeeds against the observed current state, but fails if that index is absent.
- The drop and replacement add are one `ALTER TABLE job` statement. On the observed MariaDB/InnoDB version this should be treated as one DDL operation, but operators must still inspect the resulting definition after any client disconnect or server failure.
- `ADD INDEX IF NOT EXISTS ix_model_definition_review` is name-idempotent. It does not prove that an existing index with that name has the intended columns or directions.
- If the first V8 statement succeeds and the second fails, rerunning V8 replaces `ix_job_claim` again and retries the review index. If the job statement does not complete and the index is absent, the unguarded drop can prevent a blind rerun.

Do not repeatedly apply V8 as a generic startup migration without first checking `SHOW INDEX` and the migration-history mechanism.

### V9

V9 uses `ADD INDEX IF NOT EXISTS`, so an index-name collision produces a warning instead of creating a duplicate. This is also only name-idempotent. If the name exists with the wrong column sequence or direction, V9 does not repair it.

### Schema selection and transaction behavior

The prepared files use unqualified table names. The client must select `molclass_v3`; applying against the wrong default database would target the wrong tables or fail.

Each `ALTER TABLE` is DDL and must be treated as an implicit transaction boundary. Do not rely on wrapping V8 and V9 in one transaction for an all-or-nothing rollback. Apply V8, verify it, then apply V9 and verify it.

## MariaDB online-DDL and locking considerations

The observed tables use InnoDB. Adding or dropping ordinary secondary indexes is normally eligible for InnoDB's online/in-place DDL capabilities on MariaDB 10.11, but the prepared migrations do not specify `ALGORITHM` or `LOCK`. The server is therefore allowed to choose its supported algorithm and lock level.

An online index operation is not lock-free:

- `ALTER TABLE` must acquire a metadata lock at the beginning and end.
- A long transaction that has touched the table can delay the metadata lock.
- A waiting DDL metadata lock can queue later application statements behind it.
- Index construction consumes CPU, storage bandwidth, temporary space, redo/undo capacity, and buffer-pool bandwidth.
- `LOCK=NONE` permits concurrent DML only when the selected operation and server version support it; requesting it is useful as a fail-closed deployment policy, but it must be tested on a staging copy before changing the prepared migration.

The local tables are currently tiny, but that does not remove the production requirement to inspect actual cardinality, free disk space, active transactions, replication lag, and application load immediately before apply.

MariaDB references:

- [ALTER TABLE](https://mariadb.com/kb/en/alter-table/)
- [InnoDB online DDL with ALGORITHM=INPLACE](https://mariadb.com/kb/en/innodb-online-ddl-operations-with-the-inplace-alter-algorithm/)
- [Metadata locking](https://mariadb.com/kb/en/metadata-locking/)

## Credential-safe client contract

Use a client option file provisioned by the deployment secret manager. It should be readable only by the operator account and contain the `[client]` host, port, user, password, protocol, and database settings. Do not put a password in shell history, a command argument, this document, a migration file, or a log.

```bash
export MOLCLASS_DB_DEFAULTS_FILE=/secure/runtime/molclass-v3-client.cnf
: "${MOLCLASS_DB_DEFAULTS_FILE:?MOLCLASS_DB_DEFAULTS_FILE is required}"
test -r "$MOLCLASS_DB_DEFAULTS_FILE"
test "$(stat -c '%a' "$MOLCLASS_DB_DEFAULTS_FILE")" = 600
```

For the MariaDB client, `--defaults-extra-file` must be supplied before the other client options:

```bash
mariadb --defaults-extra-file="$MOLCLASS_DB_DEFAULTS_FILE" \
  --database=molclass_v3 \
  --batch --raw
```

## Safe apply procedure

This is an operator procedure. None of these apply commands was executed during the preflight.

1. Wait for model definition 19 and every other model builder to finish.
2. Drain SDF analysis/import workers and mutation endpoints.
3. Confirm no long InnoDB transactions, metadata-lock waits, backup operation, schema migration, or replication problem is active.
4. Record the current index definitions and current `EXPLAIN` output.
5. Confirm the target database is `molclass_v3` in the credential file and command.
6. Apply V8 alone, inspect warnings, and verify all two V8 definitions.
7. Apply V9 alone, inspect warnings, and verify its definition.
8. Run the post-apply plans before restoring worker traffic.
9. Resume traffic gradually and monitor lock waits, query latency, CPU, disk throughput, temporary space, and replication lag.

Pre-apply read-only checks:

```bash
mariadb --defaults-extra-file="$MOLCLASS_DB_DEFAULTS_FILE" \
  --database=molclass_v3 --table <<'SQL'
SELECT trx_id, trx_started, trx_state, trx_rows_locked, trx_rows_modified
FROM information_schema.innodb_trx
ORDER BY trx_started;

SHOW FULL PROCESSLIST;
SHOW INDEX FROM job;
SHOW INDEX FROM model_definition;
SQL
```

Apply the exact prepared files from the repository root:

```bash
set -euo pipefail
: "${MOLCLASS_DB_DEFAULTS_FILE:?MOLCLASS_DB_DEFAULTS_FILE is required}"

mariadb --defaults-extra-file="$MOLCLASS_DB_DEFAULTS_FILE" \
  --database=molclass_v3 --show-warnings \
  < sql/v3/V8__worker_claim_and_model_review_indexes.sql

mariadb --defaults-extra-file="$MOLCLASS_DB_DEFAULTS_FILE" \
  --database=molclass_v3 --show-warnings \
  < sql/v3/V9__unfiltered_model_review_index.sql
```

Do not run these commands concurrently. Do not apply them while definition 19 is active. If V8 reports an error, stop and inspect the live index definition before deciding whether a retry or a corrective migration is appropriate.

## Rollback procedure

Rollback is another online schema change, not an instantaneous switch. Use it only when post-apply plans or production measurements show a regression and the same lock/load gates are satisfied.

The following restores the observed preflight index state:

```bash
mariadb --defaults-extra-file="$MOLCLASS_DB_DEFAULTS_FILE" \
  --database=molclass_v3 --show-warnings <<'SQL'
ALTER TABLE job
    DROP INDEX ix_job_claim,
    ADD INDEX ix_job_claim
        (job_type, status, available_at, priority, job_id);

ALTER TABLE model_definition
    DROP INDEX IF EXISTS ix_model_definition_review,
    DROP INDEX IF EXISTS ix_model_definition_review_unfiltered;
SQL
```

After rollback, rerun `SHOW INDEX` and every baseline `EXPLAIN`. If another deployment has intentionally changed any of these indexes since this preflight, do not use the static rollback block; generate a rollback from the newly captured authoritative state.

## Post-apply verification

### Verify exact index definitions

```bash
mariadb --defaults-extra-file="$MOLCLASS_DB_DEFAULTS_FILE" \
  --database=molclass_v3 --table <<'SQL'
SELECT table_name,
       index_name,
       seq_in_index,
       column_name,
       collation,
       non_unique
FROM information_schema.statistics
WHERE table_schema = 'molclass_v3'
  AND (
       (table_name = 'job' AND index_name = 'ix_job_claim')
    OR (table_name = 'model_definition' AND index_name IN (
         'ix_model_definition_review',
         'ix_model_definition_review_unfiltered'
       ))
  )
ORDER BY table_name, index_name, seq_in_index;
SQL
```

Expected rows:

```text
job              ix_job_claim                               1 job_type             A
job              ix_job_claim                               2 status               A
job              ix_job_claim                               3 priority             D
job              ix_job_claim                               4 job_id               A
job              ix_job_claim                               5 available_at          A
model_definition ix_model_definition_review                 1 status               A
model_definition ix_model_definition_review                 2 updated_at           A
model_definition ix_model_definition_review                 3 model_definition_id  A
model_definition ix_model_definition_review_unfiltered      1 updated_at           D
model_definition ix_model_definition_review_unfiltered      2 model_definition_id  D
```

### Re-run the four exact plans

Use the SQL in the "Exact application query shapes" section, replacing bind placeholders only with representative literals:

- Analysis worker: `job_type = 'SDF_ANALYZE'`.
- Import worker: `job_type = 'SDF_IMPORT'`.
- Filtered review: `status = 'AWAITING_APPROVAL'`, `LIMIT 100 OFFSET 0`.
- Unfiltered review: `LIMIT 100 OFFSET 0`.

Acceptance criteria:

- The intended new index appears in `possible_keys` for its target query.
- Preferably the intended index is selected in `key`.
- Worker claim plans no longer report `Using filesort` on `j`.
- Review plans no longer report `Using temporary; Using filesort` for outer-list ordering.
- The latest-build subquery continues to use `uq_model_generation` or an equivalently selective index.
- Plans do not introduce a large full scan or unexpectedly high row estimate.

Index selection is not a hard acceptance requirement on the current tiny tables. If MariaDB retains the old plan, collect `EXPLAIN FORMAT=JSON`, representative production cardinalities, and endpoint latency before forcing an index or dropping the new one.

### Verify runtime behavior

After workers resume, confirm:

- Multiple workers still claim distinct jobs under `FOR UPDATE SKIP LOCKED`.
- Priority and `job_id` tie-breaking remain deterministic.
- Future `available_at` jobs are not claimed early.
- Filtered and unfiltered review pages retain stable order when timestamps tie.
- P50/P95/P99 claim and review latency do not regress.
- Lock waits, deadlocks, temporary-table usage, disk pressure, and replication lag remain within the deployment baseline.

## Read-only command ledger

The live preflight executed these database statement classes only:

- `SELECT VERSION(), @@version_comment`.
- `SELECT` table estimates from `information_schema.tables`.
- `SELECT ... GROUP BY` job type/status counts.
- `SELECT ... GROUP BY` model-definition status counts.
- `SHOW INDEX FROM job FROM molclass_v3`.
- `SHOW INDEX FROM model_definition FROM molclass_v3`.
- `EXPLAIN` for the exact SDF analysis worker claim with `SDF_ANALYZE`.
- `EXPLAIN` for the exact SDF import worker claim with `SDF_IMPORT`.
- `EXPLAIN` for the status-filtered model-review list with `AWAITING_APPROVAL` and a 100-row first page.
- `EXPLAIN` for the unfiltered model-review list with a 100-row first page.

An initial analysis-worker `EXPLAIN` used a non-application literal, `SDF_ANALYSIS`. It produced the same plan, but it is not used as evidence in this document. The command was rerun with the exact `SDF_ANALYZE` constant and that corrected output is the baseline above.

No result contained a database password, and no credential was passed as a command-line argument.

## Consistency checklist

- V8 job definition in this document is exactly `(job_type, status, priority DESC, job_id ASC, available_at)`.
- V8 filtered-review definition in this document is exactly `(status, updated_at, model_definition_id)`.
- V9 unfiltered-review definition in this document is exactly `(updated_at DESC, model_definition_id DESC)`.
- Both Java claim queries use equality on `job_type` and `status`, an `available_at` eligibility predicate, the same priority/id order, `LIMIT 1`, and `FOR UPDATE SKIP LOCKED`.
- The Python endpoint uses optional status equality and always orders by `updated_at DESC, model_definition_id DESC`.
- Current-state claims in this document come from live `SHOW INDEX` and baseline `EXPLAIN`, not from the migration comments.
- Expected improvements are explicitly marked as inference and require post-apply confirmation.
