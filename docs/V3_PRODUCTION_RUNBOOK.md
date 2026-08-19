# MolClass v3 Production Operations Runbook

Status: production candidate  
Last reconciled: 2026-08-17

## 1. Purpose and authority

This is the production operations authority for the implemented MolClass v3
pipeline:

```text
SDF upload -> queued analysis -> human import confirmation -> queued import
-> CDK feature generation -> Weka model rebuild -> read-only audit
-> human review -> explicit human approval -> Spring prediction
```

The component documents under `docs/` remain useful implementation references.
Where an older operations note conflicts with this file, this file controls the
production procedure. In particular, the older statement that a fresh database
applies only V1 through V6 is stale. The complete current migration chain is V1
through V8.

The binding model decision is the superseding D-11R/D-20R decision in
`docs/IMPLEMENTATION_DECISIONS.md`: v3 models are rebuilt side by side with the
immutable legacy baseline and are never activated automatically.

### Current migration warning

As recorded on 2026-08-17, V7 has been applied to the existing local v3 database,
but `sql/v3/V8__worker_claim_and_model_review_indexes.sql` has not been applied.
Applying and recording V8 is a production-release prerequisite. Recheck actual
database state at deployment time rather than relying only on this note.

## 2. Non-negotiable safety rules

- Automation may upload, analyze, import, generate features, rebuild models, and
  run read-only audits.
- Automation must never approve, publish, repoint, or silently activate a model.
- `approveV3Model` is a human-only command run with a separate operator database
  role after evidence review.
- Rebuild success means `AWAITING_APPROVAL`, not production availability.
- Keep normal FastAPI queries read-only. Enable the guarded decision bridge only with a separate approval role and review token.
- Do not expose Java or Gradle directly. Route human decisions through the guarded FastAPI bridge.
  worker, CI, a scheduler, or a deployment hook.
- Never edit model status, publication pointers, approval rows, checksums, or
  artifacts directly in SQL.
- Preserve failed, interrupted, excluded, rejected, and superseded records. They
  are production evidence, not cleanup candidates.
- Run only one SDF importer and one model-definition rebuild at a time. Database
  advisory locks enforce these ownership boundaries; do not bypass them.
- Keep MariaDB, FastAPI, Spring, and frontend host bindings on loopback for the
  initial local-only release.
- Keep the legacy schema and legacy model artifacts read-only.
- Use UTC for the database server, process environment, logs, and operator records.

## 3. Prerequisites

### 3.1 Platform

- Linux host with 32 CPU cores and 128 GiB RAM.
- MariaDB 10.11 or a compatible tested version with InnoDB enabled.
- Java 17 and the checked-in Gradle wrapper.
- Docker Engine with Docker Compose for the recommended deployment path.
- Python and Node runtimes matching the repository containers when running
  services outside Compose.
- CDK 2.12 and Weka 3.8.7 resolved by Gradle, not copied from a legacy runtime.
- Enough database, upload, temporary, log, and backup storage for the planned
  import plus model artifacts.
- A second storage location for database and upload backups.
- A release revision that is immutable and reproducible.
- A host clock synchronized through NTP and configured for UTC service timestamps.

### 3.2 Network boundary

Expected host listeners are:

| Service | Default host endpoint | Exposure |
| --- | --- | --- |
| Frontend | `127.0.0.1:3000` | Browser-facing local origin |
| FastAPI | `127.0.0.1:8000` | Local control API |
| Spring predictor | `127.0.0.1:8082` | Local prediction API |
| MariaDB | Compose network only | No host publication by default |

Any LAN, VPN, reverse-proxy, shared-workstation, cloud, or public exposure requires
authentication and authorization work before deployment. The local-only release
does not treat loopback access as a substitute for authorization on a network.

## 4. Secrets and environment configuration

### 4.1 Secret handling

- Supply passwords through a process supervisor, container secret, or root-readable
  environment file with mode `0600`.
- Keep `.env` untracked and replace every placeholder before startup.
- Do not place passwords in Gradle properties, command arguments, shell history,
  logs, documentation, or source-controlled XML/properties files.
- Use a MariaDB client option file with mode `0600` for backup and migration client
  commands.
- Rotate a credential immediately if it appears in command output or a tracked
  file.

### 4.2 Database roles

Use separate least-privilege accounts where the deployment mechanism permits it.

| Role | Required capability | Prohibited capability |
| --- | --- | --- |
| Migration operator | DDL and migration-history writes | Routine application use |
| FastAPI control service | Upload/job/import-control reads and writes | Model approval/publication |
| SDF worker | Job claims, import writes, allowlisted property DDL | Model approval/publication |
| Feature/model worker | Feature and build records/artifacts | Model approval/publication |
| Review/audit user | `SELECT` on v3 evidence | All writes |
| Approval operator | Minimum model decision/publication/audit writes | Schema and import administration |
| Spring predictor | Published model, molecule, and feature reads | All writes |

The `--actor` value supplied to the approval CLI is an audit identity, not an
authentication mechanism. Restrict approval credentials and the shell that can use
them to authenticated human operators.

### 4.3 Core variables

Use placeholders backed by the deployment secret source. Do not use the literal
values below.

```bash
export TZ='UTC'
export MOLCLASS_JDBC_URL='jdbc:mysql://127.0.0.1:3306/molclass_v3'
export MOLCLASS_V3_SCHEMA='molclass_v3'
export MOLCLASS_DB_USER='<role-specific-user>'
export MOLCLASS_DB_PASSWORD='<secret-source>'
export MOLCLASS_UPLOAD_ROOT='<private-absolute-upload-directory>'
export MOLCLASS_CODE_REVISION='<immutable-release-revision>'
```

The Spring predictor uses its tested MariaDB URL form:

```bash
export MOLCLASS_JDBC_URL='jdbc:mariadb://127.0.0.1:3306/molclass_v3'
export MOLCLASS_PREDICTOR_ADDRESS='127.0.0.1'
export MOLCLASS_PREDICTOR_PORT='8082'
```

Important Compose controls are:

```text
MOLCLASS_DB_ROOT_PASSWORD
MOLCLASS_DB_USER
MOLCLASS_DB_PASSWORD
MOLCLASS_INNODB_BUFFER_POOL
MOLCLASS_API_PORT
MOLCLASS_API_MEMORY
MOLCLASS_SDF_WORKER_MEMORY
MOLCLASS_PREDICTOR_PORT
MOLCLASS_PREDICTOR_MEMORY
MOLCLASS_FRONTEND_PORT
MOLCLASS_FRONTEND_MEMORY
MOLCLASS_MODEL_WORKER_MEMORY
MOLCLASS_MODEL_THREADS
MOLCLASS_MODEL_POLL_SECONDS
MOLCLASS_FEATURE_TIMEOUT_MINUTES
MOLCLASS_MODEL_TIMEOUT_MINUTES
MOLCLASS_CODE_REVISION
```

Set `MOLCLASS_ALLOWED_ORIGINS` to the exact local frontend origins when FastAPI is
run outside Compose. Do not use a wildcard origin.

## 5. Host resource budget

### 5.1 Aggregate limit

The operator-approved MolClass ceiling is 75 percent of 128 GiB, or 96 GiB. This is
a hard aggregate ceiling, not a target allocation. Reserve at least 32 GiB for the
kernel, filesystem cache, host services, diagnostics, and unexpected native-memory
use.

A safe initial build-window budget is:

| Component | Initial limit or budget |
| --- | ---: |
| InnoDB buffer pool | 16 GiB |
| Model/feature worker container or cgroup | 56 GiB |
| Spring predictor | 6 GiB |
| SDF worker | 4 GiB |
| FastAPI | 1 GiB |
| Frontend | 1 GiB |
| MariaDB non-buffer and MolClass overhead allowance | 8 GiB |
| Planned MolClass total | 92 GiB |

Keep at least 4 GiB of margin below the 96 GiB ceiling. A Java heap limit is not an
aggregate process limit because direct buffers, native chemistry libraries, thread
stacks, the Gradle daemon, and compression also consume memory. Enforce the worker
limit with the container runtime or service-manager cgroup.

### 5.2 CPU and concurrency

- Set `MOLCLASS_MODEL_THREADS=24` on this 32-core host as the initial maximum.
- Leave at least eight cores available for MariaDB, serving, compression, and the
  operating system.
- The model queue remains sequential at the model-definition level.
- RandomForest may use bounded internal parallelism; other Weka algorithms may not
  scale with the thread count.
- Do not start ad hoc parallel `rebuildV3Models` processes. Use only the
  explicit-ID bounded launcher in section 11.5, and only after its
  per-definition database lease prerequisite is satisfied.
- Do not overlap bulk SDF import, full feature generation, and model training unless
  measured cgroup totals remain below 96 GiB and database latency remains healthy.
- Read-only audit and service health checks may run while a build is active, but
  artifact digest verification adds database I/O.

### 5.3 Database memory

Start with `MOLCLASS_INNODB_BUFFER_POOL=16G` on this mixed-use host. Keep
`innodb_flush_log_at_trx_commit=1`, size redo capacity near 4 GiB initially, retain
a slow-query log with a one-second threshold during rollout, and keep
`max_allowed_packet` above the 128 MiB compressed-artifact ceiling. Change server
memory through managed configuration and a controlled restart, never through
ad-hoc SQL during an import or build.

## 6. Database migration and baseline procedure

### 6.1 Migration order

Apply each version exactly once and in this order:

| Version | File | Purpose |
| --- | --- | --- |
| V1 | `V1__molclass_v3_baseline.sql` | Empty InnoDB v3 entity and relationship baseline |
| V2 | `V2__legacy_migration_tracking.sql` | Resumable legacy migration records and provenance |
| V3 | `V3__feature_generation_tracking.sql` | Feature checksums, attempts, jobs, and fallback tracking |
| V4 | `V4__model_rebuild_constraints.sql` | Rebuild integrity constraints |
| V5 | `V5__model_approval_integrity.sql` | Immutable decisions and publication pointer integrity |
| V6 | `V6__molecule_search_indexes.sql` | Global identifier and molecule search indexes |
| V7 | `V7__job_type_claim_index.sql` | Earlier job-type claim index |
| V8 | `V8__worker_claim_and_model_review_indexes.sql` | Final claim-order index and model-review index |

V8 intentionally replaces the V7 `ix_job_claim` column order. The current V1 clean
baseline already contains the final index definitions for a newly created schema,
but the complete version chain must still finish at V8 so migration history and an
upgraded schema converge on the same final shape.

The SQL filenames are Flyway-compatible. The repository does not currently define
a Gradle Flyway task, so production must use the managed Flyway CLI supplied by the
deployment environment or the documented MariaDB-client fallback. Do not claim a
manual migration was run by Flyway.

### 6.2 Pre-migration gate

1. Stop `sdf-worker` and `model-worker` so no job can claim or mutate data.
2. Keep browser traffic out of writable FastAPI routes during the maintenance window.
3. Confirm the target schema name and current migration state.
4. Capture `flyway info` when Flyway history exists.
5. Take a consistent database backup and upload-root backup.
6. Record SHA-256 checksums for the backup and all `sql/v3/V*.sql` files.
7. Confirm free disk space for the backup, temporary DDL space, and binary logs.
8. Confirm no long-running import, feature, model, or approval transaction remains.
9. Abort on checksum drift, an unknown schema state, or a failed backup.

### 6.3 Fresh empty schema with Flyway

Use Flyway environment variables so secrets do not appear in process arguments:

```bash
export FLYWAY_URL='jdbc:mariadb://127.0.0.1:3306/molclass_v3'
export FLYWAY_USER='<migration-user>'
export FLYWAY_PASSWORD='<secret-source>'
export FLYWAY_LOCATIONS='filesystem:sql/v3'
export FLYWAY_BASELINE_ON_MIGRATE='false'

flyway info
flyway migrate
flyway validate
flyway info
```

An empty schema must not be baselined. Flyway must execute V1 through V8.

### 6.4 Existing schema already tracked by Flyway

If `flyway_schema_history` exists and reports success through V7:

```bash
flyway validate
flyway info
flyway migrate
flyway validate
flyway info
```

The only new migration should be V8. Stop if validation reports an edited checksum,
a failed migration, an out-of-order version, or a version other than the expected
state. Never run `repair` merely to make validation green; investigate and record
the cause first.

### 6.5 Existing V1-V7 schema without Flyway history

This is the current local deployment pattern. Baseline only after a human verifies
that V1 through V7 are actually present and the database backup is usable:

```bash
export FLYWAY_BASELINE_VERSION='7'
export FLYWAY_BASELINE_DESCRIPTION='MolClass v3 through V7 verified'

flyway info
flyway baseline
flyway validate
flyway migrate
flyway validate
flyway info
```

Do not use automatic `baselineOnMigrate=true` against an unverified non-empty
schema. A baseline records an assertion; it does not inspect whether V1 through V7
were applied correctly.

If the existing schema is already at V8 but has no Flyway history, verify both V8
indexes and baseline at V8 instead. Do not rerun V8.

### 6.6 MariaDB-client fallback

Use this only when managed Flyway is unavailable. Record the migration filename,
SHA-256, operator, start/end timestamps, target schema, and result in the deployment
record.

```bash
sha256sum sql/v3/V8__worker_claim_and_model_review_indexes.sql
mariadb --defaults-extra-file=/etc/molclass/db-client.cnf molclass_v3 \
  < sql/v3/V8__worker_claim_and_model_review_indexes.sql
```

Do not rerun V8 after a partial or uncertain result. Inspect the two target indexes
and resolve the exact state first.

### 6.7 Empty Compose volume

On the first startup of a truly empty database volume, MariaDB executes the mounted
`sql/v3` scripts in filename order. The current chain is V1 through V8. Docker
entrypoint initialization never migrates an existing volume. An established volume
therefore requires Flyway or the explicit client procedure above before application
containers are replaced.

### 6.8 V8 verification

After V8, verify index column sequence from `information_schema.STATISTICS`:

```sql
SELECT TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, COLLATION
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'molclass_v3'
  AND (
    (TABLE_NAME = 'job' AND INDEX_NAME = 'ix_job_claim')
    OR
    (TABLE_NAME = 'model_definition'
      AND INDEX_NAME = 'ix_model_definition_review')
  )
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;
```

Expected order is:

```text
job.ix_job_claim:
  job_type, status, priority DESC, job_id ASC, available_at

model_definition.ix_model_definition_review:
  status, updated_at, model_definition_id
```

Refresh optimizer statistics after migration:

```sql
ANALYZE TABLE job, model_definition;
```

Capture `EXPLAIN` for the actual worker-claim and model-review list queries. Treat
the index shape as necessary but not proof that MariaDB will always avoid a
filesort; query predicates and sort direction still control plan selection.

## 7. Backup before data or publication changes

The database backup includes v3 model/header BLOBs, manifests, decisions, queue
state, runsteps, and per-record import evidence. It does not include the filesystem
upload root.

```bash
mariadb-dump --defaults-extra-file=/etc/molclass/db-client.cnf \
  --single-transaction --routines --triggers --hex-blob molclass_v3 \
  | gzip > "molclass_v3-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"

sha256sum molclass_v3-*.sql.gz
```

Back up `MOLCLASS_UPLOAD_ROOT` separately while no upload/import job is writing to
it. Record a file manifest and checksums. Store database and upload backups away
from the database volume.

Take a backup before:

- V8 or any later migration.
- A bulk legacy migration.
- A large SDF import when recovery cost is significant.
- Human model approval/publication.
- Database-server tuning that requires restart.

## 8. Service startup and checks

### 8.1 Startup order

1. Validate the untracked environment file and secret permissions.
2. Start MariaDB only.
3. Wait for the MariaDB health check.
4. Apply and verify all migrations through V8.
5. Start FastAPI and Spring.
6. Run API and predictor readiness checks.
7. Start the frontend and verify browser routes.
8. Run the read-only production audit.
9. Start the SDF worker.
10. Start the model worker only when queued model work is intended.

List actual Compose service names before selective startup:

```bash
docker compose --env-file .env config --services
docker compose --env-file .env config --quiet
```

The standard all-service command is:

```bash
docker compose --env-file .env up -d --build
docker compose --env-file .env ps
```

Use selective startup during migration so workers cannot claim jobs early.

### 8.2 FastAPI check

```bash
curl --fail --silent --show-error \
  http://127.0.0.1:8000/api/v1/health/readiness
curl --fail --silent --show-error \
  'http://127.0.0.1:8000/api/v1/model-reviews?limit=1'
```

Readiness must fail when required database access or schema objects are missing.
The model-review request must not return artifact payloads and must expose no write
method.

When run outside Compose, start the authoritative API from
`html/molclass/api/run.sh` under a supervisor with loopback binding, the v3 schema,
the exact frontend CORS origins, and a role that cannot approve models.

### 8.3 Spring predictor check

```bash
curl --fail --silent --show-error \
  http://127.0.0.1:8082/actuator/health/liveness
curl --fail --silent --show-error \
  http://127.0.0.1:8082/actuator/health/readiness
curl --fail --silent --show-error \
  'http://127.0.0.1:8082/api/v3/models?query=&limit=1'
```

Spring readiness validates the database and serving schema. Zero published models
is valid and produces an empty catalogue. The predictor may load only a build
selected by `published_model_build_id` whose status is `PUBLISHED` and whose
artifacts pass format, size, and SHA-256 validation.

The source-start sequence is:

```bash
./gradlew :spring_boot_predictor:bootJar
java -jar spring_boot_predictor/build/libs/spring_boot_predictor-3.0.0.jar
```

Run the JAR under a supervisor using predictor-only database credentials.

### 8.4 Frontend check

```bash
curl --fail --silent --show-error http://127.0.0.1:3000/
curl --fail --silent --show-error http://127.0.0.1:3000/upload
curl --fail --silent --show-error http://127.0.0.1:3000/model-review
curl --fail --silent --show-error http://127.0.0.1:3000/structure-search
```

Verify in a browser that upload analysis, property selection, import progress,
model review, and molecule search call only the v3 endpoints. Legacy PHP/Perl and
legacy Spring routes are not production fallbacks.

### 8.5 Listener check

```bash
ss -ltnp
```

Confirm ports 3000, 8000, and 8082 are published only on `127.0.0.1`. MariaDB
should remain on the Compose network unless a separately reviewed local
administration binding is required.

## 9. Queued SDF analysis and import

### 9.1 Upload and analysis

The supported operator path is the frontend `/upload` page or the corresponding v3
FastAPI endpoints:

```text
POST /api/v1/uploads
GET  /api/v1/uploads/{uploadId}
GET  /api/v1/jobs/{jobId}
```

`POST /api/v1/uploads` stores an opaque upload, length, and SHA-256, then queues an
`SDF_ANALYZE` job. The API does not parse the full SDF in the request process.

The SDF worker is the sole analyzer/import executor. A bounded maintenance run is:

```bash
./gradlew runV3SdfWorker \
  -PimportArgs='--once --lease-seconds 120'
```

The worker verifies source length and SHA-256 before use. A database advisory lock
prevents concurrent upload analysis/import workers. Analysis streams one record at
a time and uses file-backed distinct-value state rather than retaining the whole SDF
in memory.

### 9.2 Property and identifier confirmation

Analysis must complete before import is queued. The UI behavior is:

- Analyze every chemically valid record before proposing schema types.
- Select all discovered properties by default.
- Let the operator explicitly unselect properties that should not be imported.
- Reuse the same physical data column when an exact, trimmed, case-sensitive
  property name already exists.
- Preview allowlisted new-column creation, safe widening, or overflow storage for a
  new property.
- Never narrow, truncate, or accept a client-supplied SQL identifier or DDL type.
- Auto-propose an identifier only when it is non-blank and unique in every valid
  molecule record and is not repeated within a record.
- Prefer exact property `Identifiers`, then configured identifier-like names, then
  deterministic eligible alternatives.
- Require explicit human confirmation even when an identifier is auto-selected.
- Keep the identifier property selected for import.

The source identifier is unique within the dataset. It is not the internal MariaDB
`molecule_id` and is not assumed globally unique. Canonically identical structures
may share one internal molecule while retaining separate dataset records, source
identifiers, properties, and original mol blocks.

### 9.3 Queue import

After the operator confirms the identifier and selected property manifest:

```text
POST /api/v1/uploads/{uploadId}/imports
GET  /api/v1/imports/{importRunId}
GET  /api/v1/jobs/{jobId}
```

The API queues `SDF_IMPORT`; it does not import records in the HTTP transaction.
Before record processing, the worker acquires the property-schema lock, resolves
the exact property registry, applies only allowlisted add/widen DDL, and stores the
resolved manifest.

### 9.4 Per-record transaction contract

- Each SDF record has one durable `import_record` tracker.
- One molecule is one InnoDB domain transaction.
- Successful canonical identity, molecule, dataset membership, selected property
  values, counters, and terminal record status commit together.
- A molecule or property error rolls back that record's domain writes.
- The worker records the failure and counters in a separate short transaction.
- A bad molecule does not abort later valid molecules.
- The worker never merges structures by name, partial InChIKey, or source identifier.
- A failed or conflicting canonicalization receives a salted unmerged identity.
- Successful records remain committed if the final dataset is partial.

The worker stops submitting new molecules when:

```text
failed_records * 20 > total_records
```

This is strictly more than five percent. Remaining records become
`NOT_PROCESSED`, the run and dataset become `PARTIAL`, and model creation remains
blocked until an explicit partial-data acknowledgement is recorded. If no supported
UI/API acknowledgement control is available, keep the dataset blocked; do not edit
acknowledgement or model-eligibility fields directly.

### 9.5 Resumability and recovery

- Job ownership uses a lease, heartbeat, runstep, attempt count, and append-only
  events.
- The default queued import has three maximum attempts.
- Restart the same SDF worker after a process or host failure.
- Let an expired lease return through the normal queue recovery path.
- Do not queue a duplicate upload or import merely because a worker disappeared.
- A database transaction open at process death rolls back.
- Terminal successful records are not repeated on resume.
- Terminal molecule failures remain recorded while later records continue.
- Database unavailability, exhausted storage, lease loss, upload checksum mismatch,
  or parser-stream failure stops the run rather than pretending to skip one molecule.
- Preserve the upload file and analysis JSON until the import is terminal and the
  retention policy permits cleanup.
- Never clear advisory locks, leases, counters, runsteps, or failed records manually
  without an incident-level database review.

## 10. Feature generation

Feature generation uses CDK 2.12 and never reads legacy descriptor or fingerprint
values. The default `MODEL` scope processes molecules required by model definitions;
`ALL` processes every canonical molecule.

### 10.1 Preflight

1. Confirm migration V3 and all later migrations are applied.
2. Confirm the target datasets are complete or explicitly acknowledged partial data.
3. Confirm no bulk import is changing the same dataset.
4. Confirm worker memory and thread limits.
5. Confirm enough disk, redo, and binary-log capacity.
6. Record `MOLCLASS_CODE_REVISION` and the expected CDK version.

### 10.2 Commands

Start with a bounded smoke batch:

```bash
./gradlew generateV3Features \
  -PfeatureArgs='--scope MODEL --threads 8 --batch-size 100 --limit 100'
```

Run model-required coverage on this host with bounded parallelism:

```bash
./gradlew generateV3Features \
  -PfeatureArgs='--scope MODEL --threads 24 --batch-size 200'
```

Run full canonical coverage only when needed:

```bash
./gradlew generateV3Features \
  -PfeatureArgs='--scope ALL --threads 24 --batch-size 200'
```

### 10.3 Resume behavior

- Resume is implicit: rerun the same scope after the failure is understood.
- Successful descriptor/fingerprint rows are checksummed and not recalculated
  merely because the process restarted.
- Missing and failed components are selected for retry.
- CDK calculators are thread-local and database writes use a bounded transaction
  owner and bounded batches.
- The explicitly authorized `SUB` fallback is recorded per generated feature; it is
  never silent provenance.
- `READY_WITH_EXCLUSIONS` requires review of excluded molecules before training.
- Run `ANALYZE TABLE` for large feature tables after bulk generation and before a
  major rebuild window.

## 11. Bounded model rebuild

### 11.1 Preconditions

- All schema migrations through V8 are applied.
- The model definition points to a ready feature profile.
- Dataset partial-state acknowledgement, when required, is present.
- The release revision is immutable and supplied as `MOLCLASS_CODE_REVISION`.
- The worker container/cgroup is limited to 56 GiB for this host profile.
- No other model builder owns the database named lock.
- A current backup exists before a large rebuild campaign.

### 11.2 Recovery-only startup

After an unclean worker stop, run recovery before claiming another definition:

```bash
./gradlew rebuildV3Models -PmodelArgs='--limit 0 --threads 24'
```

The normal startup path records an orphaned build/job as interrupted. Do not delete
or rewrite the old attempt.

### 11.3 One-definition command

Use an external wall-clock deadline and process one definition per invocation:

```bash
timeout --signal=TERM --kill-after=30s 120m \
  env MOLCLASS_CODE_REVISION='<immutable-release-revision>' \
      MOLCLASS_MODEL_THREADS='24' \
  ./gradlew rebuildV3Models \
    -PmodelArgs='--limit 1 --threads 24'
```

For an explicitly reviewed retry:

```bash
timeout --signal=TERM --kill-after=30s 120m \
  env MOLCLASS_CODE_REVISION='<immutable-release-revision>' \
      MOLCLASS_MODEL_THREADS='24' \
  ./gradlew rebuildV3Models \
    -PmodelArgs='--model-id <definition-id> --threads 24'
```

Never remove the deadline. Increase it for a known large definition only after
reviewing its previous attempt, peak memory, CPU time, and database pressure.

### 11.4 Build behavior

- Definitions are processed sequentially.
- Feature loading is sparse and bounded by the selected dataset/profile.
- Splits are deterministic 80/10/10 train, validation, and holdout membership.
- Every included and excluded dataset record is persisted.
- Each artifact and manifest is checksummed.
- Successful builds stop at `AWAITING_APPROVAL`.
- `Ensemble2` remains `UNSUPPORTED_CONFIGURATION`; no substitute classifier is used.
- The standalone KNN path tests odd K values from 1 through the largest safe odd
  value no greater than 25 or the smallest cross-validation training fold; tiny
  datasets use K=1.
- A timeout or classifier failure is evidence, not permission to alter the model
  contract silently.

### 11.5 Parallel explicit-ID launcher

The production launcher is `tools/rebuild_v3_models_parallel.sh`. It accepts
only explicit, unique model definition IDs and starts an independent
`./gradlew --no-daemon rebuildV3Models` process for each ID. It never invokes
the approval task.

Export the existing database connection variables. Do not pass credentials as
command-line arguments:

```bash
export MOLCLASS_JDBC_URL='jdbc:mysql://127.0.0.1:3306/'
export MOLCLASS_DB_USER='<model-worker-user>'
export MOLCLASS_DB_PASSWORD='<secret-from-secret-store>'

tools/rebuild_v3_models_parallel.sh \
  --model-id 101 \
  --model-id 102 \
  --model-id 103 \
  --model-id 104
```

Defaults are four concurrent lanes, eight Weka threads per lane, and a 24 GiB
JavaExec heap per lane. The launcher rejects a configuration above 96 GiB of
aggregate configured heap or 32 aggregate Weka threads. These ceilings include
configured lanes even when fewer IDs are supplied, which prevents a misleading
under-budget configuration. Override them deliberately when the host budget
changes:

```bash
tools/rebuild_v3_models_parallel.sh \
  --lanes 3 \
  --threads-per-lane 8 \
  --heap-per-lane 24g \
  --max-aggregate-threads 32 \
  --max-aggregate-heap 96g \
  --model-id 105 --model-id 106 --model-id 107
```

Use `--validate-only` to check IDs, required environment, executable
prerequisites, and resource arithmetic without creating a log directory or
starting Gradle. Every live invocation creates a new log directory containing
`model-<id>.log` for each started definition and `summary.tsv` with the exact
child exit code. A zero launcher exit means every model process exited zero;
otherwise inspect the summary and per-model logs. `TERM` and `INT` terminate
the complete Gradle/Java process groups, wait for the configured grace period,
and record interrupted work without approving or publishing it.

#### Required per-definition lease contract

Parallel correctness depends on the database enforcing one exclusive lease per
model definition for the complete build transaction. Different definitions may
run simultaneously; duplicate or competing work for the same definition must
be rejected or recovered through that lease. Resource limits and distinct IDs
in the launcher are not a substitute for database ownership.

The current `V3ModelRebuilder` release still acquires one schema-wide advisory
lock (`molclass-v3-model-rebuild:<schema>`). Therefore, this launcher is staged
but **must not be used with more than one lane in production until that lock is
replaced by the reviewed per-definition lease contract**. The launcher does not
bypass the lock: with the current implementation, competing lanes will exit
nonzero and appear accurately in `summary.tsv` rather than build in parallel.

## 12. Read-only production audit

Run the audit with a database account that has `SELECT` only:

```bash
./gradlew auditV3Production \
  -PauditArgs='--verify-artifact-digests'
```

Digest verification is more I/O intensive but must run before approval and after a
restore. The audit checks counters, leases, manifests, artifact metadata/digests,
evaluation contracts, split membership, immutable rejected decisions, and
publication invariants. It performs no approval or publication.

Run the audit:

- After migration and before workers start.
- After a feature/model rebuild batch.
- Before every human approval.
- After every approval or rejection.
- After database restore.
- On a scheduled read-only production health cycle.

Any violation blocks approval and production rollout. Preserve the complete audit
output with the release or approval record. Do not make SQL edits merely to silence
an audit finding.

## 13. Review, approval, rejection, and supersession

Use the `/model-review` interface for routine human decisions.

The page enables controls only when its API reports that the guarded bridge is configured and the selected latest build is `AWAITING_APPROVAL` without an existing decision. Approval publishes the build through the canonical Java transaction. Rejection records an immutable human decision without publication.

Configure the API service with:

- `MOLCLASS_MODEL_APPROVAL_ENABLED=true`
- `MOLCLASS_MODEL_REVIEW_TOKEN=<high-entropy-secret>`
- `MOLCLASS_APPROVAL_DB_USER=<dedicated-approval-user>`
- `MOLCLASS_APPROVAL_DB_PASSWORD=<secret>`
- `MOLCLASS_REPO_ROOT=/mnt/wdc_store/gitlab/molclass`
- Optional `MOLCLASS_MODEL_APPROVAL_TIMEOUT_SECONDS=120`

Restart the FastAPI service after changing these variables. Confirm that the normal API connection still uses its read-only credentials.

The guarded endpoint is `POST /api/v1/model-builds/{model_build_id}/decision`. It requires the review token header, reviewer identity, and decision note. It invokes `:approveV3Model` with `--no-daemon` and without a shell.

Use `supersedeV3Model` only for a technical replacement where no human approval or rejection exists. `SUPERSEDED` is a lifecycle state, not an approval decision.

For loopback troubleshooting, the Gradle approval task remains the emergency fallback. Do not make the raw task remotely callable.

## 14. Monitoring

### 14.1 Service health

Monitor:

- FastAPI readiness.
- Spring liveness and readiness.
- Frontend route availability.
- Compose/container restart count and exit reason.
- Unexpected non-loopback listeners.
- Database connection-pool exhaustion.

### 14.2 Job and pipeline health

Use read-only queries or the v3 status endpoints. A compact database view is:

```sql
SELECT job_type, status, runstep, COUNT(*) AS jobs
FROM job
GROUP BY job_type, status, runstep
ORDER BY job_type, status, runstep;

SELECT status, runstep, COUNT(*) AS runs
FROM import_run
GROUP BY status, runstep
ORDER BY status, runstep;

SELECT status, COUNT(*) AS definitions
FROM model_definition
GROUP BY status
ORDER BY status;

SELECT status, runstep, COUNT(*) AS builds
FROM model_build
GROUP BY status, runstep
ORDER BY status, runstep;
```

Investigate:

- A `RUNNING` job whose heartbeat is older than its lease.
- Repeated attempts approaching `maximum_attempts`.
- Import failure ratio approaching the strict five-percent boundary.
- Any `NOT_PROCESSED`, `PARTIAL`, `REBUILD_FAILED`, `INTERRUPTED`, or unsupported state.
- A growing `PENDING_REBUILD` backlog with no active worker.
- An `AWAITING_APPROVAL` build without recorded human review.
- Any audit violation or publication-pointer inconsistency.

### 14.3 Host and MariaDB health

Monitor:

- Cgroup and host RSS against the 96 GiB aggregate ceiling.
- Swap activity and kernel OOM events.
- CPU saturation, load, and run queue.
- Database, upload, temporary, log, and backup free space and inodes.
- InnoDB buffer-pool hit ratio.
- Row-lock waits, deadlocks, redo/checkpoint waits, and history-list growth.
- Temporary tables written to disk.
- Slow-query log entries over one second.
- Binary-log and model-artifact growth.
- Backup age and last restore-exercise result.

Do not increase worker threads or memory in response to a slow query until the query
plan, lock waits, and I/O pressure have been measured.

### 14.4 Logs and incident evidence

```bash
docker compose --env-file .env ps
docker compose --env-file .env logs --since=30m api predictor sdf-worker model-worker
```

Preserve:

- Worker command, immutable revision, environment limits, and timestamps.
- Job, job-event, runstep, heartbeat, and attempt rows.
- Import run and per-record transaction rows.
- Feature status, checksum, attempt, and fallback rows.
- Model build, membership, evaluation, manifest, artifact, decision, and audit rows.
- API, Spring, worker, MariaDB, kernel, cgroup, and storage evidence.

## 15. Rollback and recovery

### 15.1 General rule

Prefer forward recovery using durable job state. Do not erase evidence or manually
force terminal statuses. Stop mutating services, capture evidence, take a backup of
the failed state, then recover through the supported command path.

### 15.2 Migration recovery

- V8 is index-only and application rollback normally leaves it in place.
- There is no supported automatic down migration.
- If V8 fails or leaves an uncertain state, stop workers and inspect both indexes
  before any retry.
- Do not rerun the whole migration chain on a non-empty schema.
- Restore the pre-migration backup into a new schema when database recovery is
  required; never overwrite the only working schema first.
- Point an isolated service instance at the restored schema and run readiness plus
  the full digest audit before cutover.

### 15.3 Import recovery

- Restart the SDF worker and let lease recovery resume the same queued job.
- Do not create duplicate upload/import rows.
- Preserve successful per-record commits and recorded failures.
- Keep a partial dataset blocked until acknowledged through the supported workflow.
- Never delete canonical molecules directly to undo one dataset import because they
  may be shared by other datasets.
- For a logically incorrect import, quarantine it from downstream model work and
  use a reviewed dataset-removal/reimport procedure rather than ad-hoc cascading SQL.

### 15.4 Feature recovery

- Fix the environmental or chemistry failure, then rerun the same scope.
- Let checksums and successful component rows prevent unnecessary recalculation.
- Do not relabel a failed generation or copy legacy feature values into v3.
- Preserve `SUB` fallback and exclusion provenance.

### 15.5 Model rebuild recovery

- After an unclean stop, run `--limit 0` recovery first.
- Review the interrupted/failed attempt before retrying the explicit model ID.
- Keep an external timeout and the same immutable revision unless the new attempt is
  intentionally a new code generation.
- Never substitute a classifier or silently alter options to make a build succeed.
- Never approve a build merely because an earlier attempt failed.

### 15.6 Publication recovery

There is no direct "unapprove" operation. Approval decisions and artifacts are
immutable.

- Stop or isolate the predictor immediately if a published model is unsafe.
- Preserve the bad publication, request, prediction, and audit evidence.
- Build or select a corrected candidate through the supported model-definition
  workflow.
- Run audit and human review again.
- Publish only through a new explicit human approval transaction.
- Do not repoint the publication foreign key manually to an older build.
- Use a full database restore into a new schema only when the required recovery is
  broader than one model publication.

### 15.7 Restore exercise

Restore into a new schema, never over the only working database:

```bash
mariadb --defaults-extra-file=/etc/molclass/db-client.cnf \
  -e 'CREATE DATABASE molclass_v3_restore CHARACTER SET utf8mb4 COLLATE utf8mb4_bin'

gzip -dc <backup.sql.gz> | mariadb \
  --defaults-extra-file=/etc/molclass/db-client.cnf molclass_v3_restore
```

Restore the matching upload-root backup to a private test path. Start isolated API
and predictor instances on different loopback ports, verify readiness, run the
digest audit, inspect model catalogue counts, and perform representative molecule
searches. Record the exercise before removing the restore environment.

## 16. Production change checklist

### Before change

- Approved maintenance/change record exists.
- Human operator and rollback owner are identified.
- Immutable release revision is recorded.
- Secrets are supplied from protected sources.
- Database and upload backups completed with SHA-256.
- Restore space is available.
- Workers are stopped and no mutation transaction remains.
- Current Flyway/manual migration state is recorded.
- V8 is applied and verified before the production candidate is declared ready.
- Aggregate MolClass memory limits total no more than 96 GiB.

### After migration/startup

- Flyway validation or manual migration record is successful through V8.
- V8 index order is verified.
- MariaDB statistics are refreshed.
- FastAPI readiness passes.
- Spring liveness/readiness passes.
- Frontend core routes respond.
- Host listeners are loopback-only.
- Read-only digest audit reports no violations.
- Worker ownership and queue state are understood before workers start.

### Before model publication

- Build is `AWAITING_APPROVAL`.
- Human evidence review is complete.
- Exact split, metric, exclusion, manifest, and artifact evidence is recorded.
- Read-only digest audit reports no violations.
- Approval database role is separate from all automation roles.
- Only one human approval process is active for the definition.
- Approval command and operator identity are recorded.

### After model publication

- Read-only digest audit reports no violations.
- Spring catalogue exposes the intended definition/build only.
- Representative predictions succeed with the approved feature contract.
- Previous publication is retained as immutable/superseded evidence.
- Monitoring and incident contacts are active.

## 17. Reconciled component references

- `docs/DATABASE_MODEL_V3.md`
- `docs/MYSQL_V3_PRODUCTION_TUNING.md`
- `docs/V3_SDF_ANALYZER.md`
- `docs/V3_FEATURE_GENERATION.md`
- `docs/V3_MODEL_QUEUE.md`
- `docs/V3_MODEL_REBUILD.md`
- `docs/V3_KNN_TUNING.md`
- `docs/V3_MODEL_REVIEW.md`
- `docs/V3_MODEL_APPROVAL.md`
- `docs/V3_PREDICTION_API.md`
- `docs/V3_LEGACY_MIGRATION.md`
- `docs/adr/0006-model-rebuild-restart-semantics.md`
- `docs/adr/0008-utc-runtime-timestamps.md`
