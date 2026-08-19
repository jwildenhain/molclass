# MolClass Production Implementation Plan

Status: In progress

Last updated: 2026-08-17

Scope: SDF upload and import, database reliability, model building and description,
prediction, molecule and structure search, frontend behavior, memory use, security,
operations, testing, and documentation.

## Current implementation status (2026-08-17)

This status register records implementation evidence accumulated after the original
plan was written. The historical phase descriptions, acceptance criteria, release
gates, open questions, and initial next steps remain below unchanged. A phase is not
complete merely because one of its deliverables appears here.

Status vocabulary used in this register:

| Status | Meaning |
| --- | --- |
| `COMPLETE` | Implemented and supported by the evidence stated here |
| `IN_PROGRESS` | Work has advanced but the complete gate has not passed |
| `PREPARED_NOT_APPLIED` | Repository change exists but has not been applied to the live database |
| `BLOCKED_BY_OPERATOR` | Automation must stop until a human makes the required decision |
| `PENDING` | Required work has not yet been completed |
| `FAILED` | An attempted operation did not complete successfully |
| `UNSUPPORTED` | The requested legacy configuration has no supported v3 implementation |

### Session implementation evidence

| Area | Status | Evidence and boundary |
| --- | --- | --- |
| Adaptive KNN tuning contract | `COMPLETE` | KNN now derives a fail-safe odd-valued search range from the available training rows: 10 folds where possible, a maximum K bounded by the smallest cross-validation training fold and 25, and direct K=1 handling for tiny datasets. Focused range and Weka-option tests passed. |
| Model definition 39 | `COMPLETE` build, approval pending | Definition 39 rebuilt successfully with the adaptive KNN contract and produced the required metrics and checksum-addressed artifacts. This records a successful build only; it does not approve or publish the model. |
| Read-only model review API | `COMPLETE` | FastAPI provides list and definition-detail review endpoints without mutating routes. Query ordering and filtered SQL were tightened, Python 3.9-incompatible union annotations were replaced, five focused mocked-database tests passed, and a fresh API smoke run returned successful OpenAPI, readiness, list, and detail responses. |
| Model review frontend | `COMPLETE` for implemented scope | The Next.js review list/detail interface, desktop navigation, and mobile navigation are implemented. A production build generated all 14 pages, including `/model-review` and `/structure-search`. The review workflow remains intentionally read-only. |
| Spring schema readiness | `COMPLETE` | The predictor health check now validates `descriptor_schema` and `molecule_descriptor_vector`, rather than the nonexistent `descriptor_definition`. Focused mocked-JDBC regression tests and Spring compilation passed. |
| Spring artifact loading | `COMPLETE` for code and focused tests | Predictor artifacts are read through bounded streams and checked for declared size, trailing bytes, SHA-256, GZIP format, and deserialization filtering. Five focused stream/integrity tests passed. A fresh predictor started and reported healthy, but an actual published artifact could not be loaded because no model was published. |
| Approval transaction hardening | `COMPLETE` for code and focused tests | Approval locks the model definition before the selected build, protects against publication races, requires the mandatory aggregate metric set and matching support, verifies all split membership counts and totals, and has focused regression coverage. No approval command was run. |
| Approval artifact verification | `COMPLETE` | Approval verifies model and header payloads through an 8 KiB bounded streaming path, including nonempty payload, exact byte count, and SHA-256 checks. Focused empty, bounded-read, size-mismatch, and digest-mismatch tests passed. |
| Model JDBC timezone initialization | `COMPLETE` | Rebuild and approval CLI connections set the MariaDB session timezone to UTC immediately after connection creation while preserving existing transaction behavior. Focused tests passed. |
| Production audit | `COMPLETE` for implemented checks | The audit CLI checks job/import counters and leases, model manifests, artifacts, evaluations, split membership, and publication invariants. Rejected builds require a matching immutable `REJECT` decision but not the publishable 18-metric contract. Focused tests and a live read-only audit passed at the time run; the audit must be repeated after rebuild and migration work. |
| V8 worker/review indexes | `PREPARED_NOT_APPLIED` | V8 prepares queue-claim and status-filtered model-review indexes and the clean V1 baseline contains their final definitions. Compatibility was checked against MariaDB 10.11 and current plans were inspected read-only. V8 has not been applied to the live database and post-apply plans have not been measured. |
| V9 unfiltered review index | `PREPARED_NOT_APPLIED` | V9 prepares `(updated_at DESC, model_definition_id DESC)` for the unfiltered review list and updates the clean baseline. It has not been applied to the live database. |
| Frontend end-to-end suite | `COMPLETE` for the implemented routes | The Playwright suite was rewritten against the v3 interface. The former legacy-parity spec asserted retired PHP markup for routes that are now redirect stubs, and the default playwright.dev example spec required internet access; both were removed. The replacement covers navigation across all seven routes and both viewports, the legacy redirect contract, the published-model and prediction flow, dataset review, and model review, with both backends stubbed at the network layer so the suite is deterministic without a database. 99 tests pass across Chromium, Firefox, and WebKit, and pass repeated three times. |
| Frontend static gates | `COMPLETE` | `eslint`, `tsc --noEmit`, and a new `tsconfig.tests.json` pass. The test suite was previously excluded from typechecking; `npm run typecheck:tests` now covers it, and the workflow runs lint and both typechecks before Playwright. |
| Production operations documentation | `COMPLETE` for the delivered documents | `docs/V3_PRODUCTION_RUNBOOK.md` documents migrations, resource limits, resumable operations, rebuild/audit/approval flow, startup, monitoring, backup, and recovery. `docs/V3_LEGACY_DEPRECATION.md` maps legacy replacements and defines disablement, retention, rollback, and removal gates. This does not complete the full documentation gate in Section 17. |

Additional evidenced frontend fixes in this session include sending `query=` for an
empty structure-search browse request and correcting the upload safety-callout
contrast. The repository-wide frontend lint run now reports no errors and no
warnings, so the previously `PENDING` frontend lint gate is `COMPLETE`. The
Playwright workflow has been moved to the repository-root `.github/workflows/`
directory that GitHub actually reads, running from `molclass-frontend` via
`defaults.run.working-directory`, so the lint, typecheck, and end-to-end gates are
wired to run on push and pull request. The first CI execution has not been observed
yet, so the gate is wired but not yet demonstrated green on GitHub.

### Model rebuild status

The rebuild sweep has reached model definition 100. This is an execution frontier,
not a claim that every definition through 100 is successful, approved, or
publishable.

| Definition or range | Status | Recorded outcome / next action |
| --- | --- | --- |
| 39 | `COMPLETE` build, approval pending | Adaptive KNN build succeeded; retain for human review. |
| 19 | `FAILED` | The large legacy Ensemble build exceeded the 60-minute watchdog and remains `REBUILD_FAILED`/interrupted. An explicit retry, redesign, or retirement decision is required. |
| 47 | `UNSUPPORTED` | Legacy `Ensemble2` has no supported v3 implementation. No substitute was silently used. An implementation, validated remapping, or deprecation decision is required. |
| 1-100 sweep frontier | `IN_PROGRESS` | Processing has advanced through definition 100. Database build state and the final audit remain authoritative for each individual definition; traversal alone is not completion evidence. |
| 98 | `COMPLETE` build, approval pending | Already in `AWAITING_APPROVAL`; do not rebuild merely to advance the queue unless review rejects it or provenance validation fails. |
| 101-117 | `PENDING` | Continue the rebuild sweep and record each success, exclusion, failure, or unsupported configuration independently. |
| 118 | `COMPLETE` build, approval pending | The uncoupler model is already in `AWAITING_APPROVAL`; it is outside the current sequential frontier and must not be auto-approved. |
| Final rebuild reconciliation | `PENDING` | After the queue is exhausted, reconcile every active definition with exactly one current terminal build state and rerun the production audit. |

The approved execution envelope is 32 CPU cores and at most 96 GiB aggregate
memory, representing 75 percent of the 128 GiB host. Independent application work
may proceed in parallel. Model-build concurrency must remain bounded by measured
per-build heap/RSS and database pressure; the resource envelope is a ceiling, not a
requirement to allocate 96 GiB to one JVM. Upload jobs remain serialized by the
queue policy.

### Human model approval gate

Status: `BLOCKED_BY_OPERATOR`

Model rebuilding, auditing, and read-only review may continue, but promotion may
not cross this gate automatically. A human operator must review each
`AWAITING_APPROVAL` build, including definitions 39, 98, and 118, verify its target,
algorithm contract, split evidence, metrics, provenance, artifact sizes, and
checksums, then record an explicit approve or reject decision using an authenticated
actor. No model was approved or published by the implementation work recorded in
this session. Batch or inferred approval remains prohibited.

### Remaining explicit decisions and actions

1. Decide whether definition 19 is retried with a longer bounded watchdog and a
   measured resource profile, redesigned, or retired. A timeout must not be treated
   as a successful build.
2. Decide whether to implement legacy `Ensemble2` semantics for definition 47,
   provide a scientifically validated remapping, or mark the definition deprecated.
   Silent substitution is prohibited.
3. Complete definitions 101-117, then reconcile all definition/build states and
   rerun the read-only production audit.
4. Schedule and authorize V8 and V9 on staging first, capture before/after query
   plans and timings, assess write/queue-order tradeoffs, and only then apply them
   to production. Both migrations remain unapplied.
5. Select the authenticated identity source for approval actors. The CLI preserves
   actor recording but does not itself establish human identity.
6. Perform predictor artifact-load and prediction-equivalence testing after a human
   approves a suitable model; current startup evidence covers an empty published
   model list only.
7. Done. The Playwright workflow now lives at root `.github/workflows/playwright.yml`
   with `defaults.run.working-directory: molclass-frontend` and an artifact path of
   `molclass-frontend/playwright-report/`, so the frontend lint, typecheck, and
   end-to-end gates execute on push and pull request rather than only locally. The
   suite is green at 99 tests across Chromium, Firefox, and WebKit. This closes the
   root-CI portion of PR-25; first CI run on a pull request still needs observing.
8. Execute the separate human approval gate build by build. This operator action
   must remain distinct from rebuild completion and automated audit success.
9. Continue the broader security, authorization, benchmark, backup/restore,
   migration-rehearsal, and cutover gates below; the focused work in this register
   does not imply those production gates are complete.

## 1. Objective

Deliver a production-ready MolClass release that can safely execute this workflow:

1. A user uploads an SDF file.
2. MolClass analyzes every SDF record and every property.
3. The user chooses the properties to import.
4. A non-null property with unique values is automatically proposed as the compound
   identifier.
5. A queued Java worker imports the dataset, preserving the source identifier and
   continuing past individual invalid molecules.
6. The database records the state and outcome of every import step and every SDF
   record.
7. The user selects an imported property as a classification target and queues a
   model build.
8. MolClass builds, evaluates, versions, describes, and serves the model.
9. Users can find molecules by identifier, property, text, similarity,
   substructure, and scaffold where those capabilities are explicitly supported.
10. The service remains within defined database, CPU, and memory budgets and can
    be monitored, backed up, restored, and upgraded.

## 2. Target architecture

The implementation plan assumes the following architecture. Record the final
decision in an Architecture Decision Record before implementation begins.

| Component | Proposed implementation | Responsibility |
| --- | --- | --- |
| Web frontend | Next.js in `molclass-frontend` | Upload, property selection, job status, model configuration, model cards, molecule search, and prediction views |
| Control API | One FastAPI application | Authentication, validation, REST contracts, job creation, job status, search orchestration, and presentation DTOs |
| Compute worker | Java application built with Gradle | SDF analysis/import, standardization, fingerprints, descriptors, InChI, model building, and batch prediction |
| Queue | Durable MariaDB job tables initially | Leased jobs, heartbeats, retries, cancellation, and resumability |
| Database | MariaDB with InnoDB tables | Authoritative application state, dataset metadata, molecule metadata, job state, and indexed search fields |
| Model storage | Filesystem for initial release, object storage-ready abstraction | Immutable model artifacts, manifests, checksums, and evaluation reports |
| Reverse proxy | One public origin | TLS, request-size limits, API routing, and static frontend delivery |

The Spring predictor, the second FastAPI upload application, PHP pages, Perl upload
scripts, and duplicate prediction implementations must not remain independently
active. During migration they may exist behind feature flags, but one code path must
be authoritative for each operation.

## 3. Engineering principles

1. Use an internal auto-generated `mol_id` as the database primary key.
2. Store the SDF compound identifier separately as `source_identifier`.
3. Enforce uniqueness at the dataset boundary with
   `UNIQUE(batch_id, source_identifier)`.
4. Do not use `MAX(id) + 1` to allocate identifiers.
5. Use InnoDB for every table participating in an application transaction.
6. Never concatenate user-provided values, column names, or SQL types into SQL.
7. Analyze the complete SDF before creating or altering property columns.
8. Stream SDF records; do not retain the complete file or complete batch in heap.
9. Commit or roll back one molecule as a unit.
10. Record a failed molecule in a separate transaction, then continue the import.
11. Make every worker operation idempotent and resumable.
12. Bound queues, result sets, caches, subprocesses, and request sizes.
13. Reject unsupported model and search options rather than silently approximating
    them.
14. Treat model provenance and evaluation as part of the model artifact.
15. Make schema changes through migrations, not startup side effects.

## 4. Delivery strategy

Implement the work in the order below. A phase may be developed in parallel only
when its dependencies and entry gates are complete.

| Phase | Outcome | Dependency | Indicative duration |
| --- | --- | --- | --- |
| 0 | Architecture and security containment | None | 1 week |
| 1 | Versioned, transaction-safe schema | Phase 0 | 1-2 weeks |
| 2 | Java SDF analyzer and strict CLI | Phase 1 | 1-2 weeks |
| 3 | Failsafe Java importer and durable queue | Phase 2 | 2-3 weeks |
| 4 | Complete upload frontend and API | Phase 3 | 1-2 weeks |
| 5 | Reliable model jobs and model descriptions | Phases 1 and 3 | 2-3 weeks |
| 6 | Correct prediction and bounded model serving | Phase 5 | 1-2 weeks |
| 7 | Molecule and structure search | Phases 1 and 3 | 2-3 weeks |
| 8 | Memory and database performance hardening | Phases 3, 6, and 7 | 1-2 weeks |
| 9 | Security, observability, and operations | All runtime phases | 1-2 weeks |
| 10 | Migration, release validation, and cutover | All phases | 1-2 weeks |

Durations are planning ranges, not commitments. Benchmark data and migration
complexity can change them.

## 5. Phase 0: Architecture and security containment

### 5.1 Select the authoritative runtime path

1. Create `docs/adr/0001-runtime-architecture.md`.
2. Confirm one public API implementation.
3. Confirm Java is the only SDF analyzer/importer and compute worker.
4. Confirm Next.js is the only production web interface.
5. Define deprecation dates for Spring prediction endpoints, the secondary upload
   API, legacy PHP, and Perl upload scripts.
6. Add feature flags for any legacy path needed during data reconciliation.
7. Update `docker-compose.yml` so only authoritative services are public.

Acceptance criteria:

- Every public frontend route maps to one documented API operation.
- No operation has two independently writable implementations.
- The architecture and deprecation decisions are approved and recorded.

### 5.2 Contain immediate security risks

1. Remove all `bash -c` construction involving request data.
2. Replace shell execution with a typed Java process invocation or an internal job
   row.
3. Block public access to `html/molclass/web` and legacy PHP endpoints.
4. Disable the unauthenticated delete, model creation, upload, and single-prediction
   endpoints until authorization is implemented.
5. Replace wildcard CORS with an explicit deployment-origin allowlist.
6. Add maximum upload size, request duration, and request-rate policies at the
   reverse proxy and API.
7. Move database passwords to environment-injected secrets.
8. Rotate every credential currently committed to configuration or compose files.
9. Classify the large historical database seed as sensitive.
10. Remove the historical seed from the current tree and repository history after
    an approved backup and retention decision.
11. Create a sanitized, empty schema seed for development and CI.

Acceptance criteria:

- No request field reaches a shell interpreter.
- No production credential is present in tracked files.
- Legacy pages are not reachable from the production network.
- The repository contains no unsanitized user or authentication data.

### 5.3 Establish version and configuration ownership

1. Select one release version source.
2. Generate API, frontend, and build version displays from that source.
3. Define `development`, `test`, `staging`, and `production` configuration profiles.
4. Fail startup when required secrets or incompatible schema versions are missing.
5. Remove hardcoded usernames and displayed identities from the frontend.

Phase 0 exit gate:

- Security containment is deployed before functional expansion begins.
- An approved ADR identifies the production API, worker, frontend, and database.

## 6. Phase 1: Transaction-safe database baseline

### 6.1 Introduce schema migrations

1. Select Flyway or Liquibase and use it from one controlled migration process.
2. Create a baseline migration matching the actual production schema.
3. Create a clean migration path for an empty database.
4. Add a `schema_history` mechanism through the selected migration tool.
5. Stop Java, Python, PHP, and Perl code from making unversioned DDL changes.
6. Add a startup schema compatibility check to the API and workers.

Deliverables:

- `sql/migrations/` containing ordered, repeatable migration scripts.
- `sql/molclass_data_model.sql` generated or maintained as the empty current model.
- `docs/DATABASE_MODEL.md` updated from the same schema version.

### 6.2 Convert operational tables to InnoDB

Convert at least these tables:

| Table | Required action |
| --- | --- |
| `batchlist` | Convert to InnoDB and reconcile status fields |
| `batchmols` | Convert to InnoDB and replace ambiguous key order |
| `class_models` | Convert to InnoDB and reconcile fields used by Java and API |
| `fingerprints` | Convert to InnoDB before transactional status changes |
| `inchi_key` | Convert to InnoDB and define exact-lookup constraints |
| `prediction_list` | Convert to InnoDB and add job state |
| `prediction_mols` | Convert to InnoDB and reconcile response fields |
| `sdftags` | Convert to InnoDB and define null/default behavior |

Implementation steps:

1. Measure table sizes and current write volume.
2. Create and test migration scripts against a production-sized clone.
3. Check for duplicate rows that would violate proposed unique constraints.
4. Repair or quarantine duplicates through an auditable migration report.
5. Convert tables during a maintenance window or use an online schema-change tool.
6. Validate row counts and checksums before and after conversion.
7. Retain a rollback artifact until the release gate passes.

### 6.3 Add import tracking tables

Create `import_run` with these minimum fields:

| Field | Purpose |
| --- | --- |
| `import_run_id` | Internal primary key |
| `batch_id` | Dataset being created or resumed |
| `upload_id` | Reference to the uploaded artifact |
| `status` | `QUEUED`, `LEASED`, `RUNNING`, `PARTIAL`, `SUCCEEDED`, `FAILED`, `CANCELLED` |
| `runstep` | Current durable workflow step |
| `lease_owner` | Worker holding the run |
| `lease_expires_at` | Recovery of abandoned work |
| `heartbeat_at` | Worker health indicator |
| `identifier_property` | Exact SDF property selected as identifier |
| `selected_properties_json` | Immutable selected-property manifest |
| `analysis_hash` | Links import to the analyzed file and manifest |
| `total_records` | Analyzer record count |
| `success_records` | Successfully imported record count |
| `failed_records` | Failed record count |
| `skipped_records` | Idempotently skipped record count |
| `error_code` | Stable run-level error code |
| `error_message` | Sanitized run-level error summary |
| `created_at`, `started_at`, `finished_at` | Lifecycle timestamps |

Create `import_record` with these minimum fields:

| Field | Purpose |
| --- | --- |
| `import_record_id` | Internal primary key |
| `import_run_id` | Parent run |
| `record_number` | One-based position in the SDF |
| `source_identifier` | Original selected SDF value |
| `mol_id` | Internal molecule ID after successful insertion |
| `status` | `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `SKIPPED` |
| `runstep` | Record-level failure location |
| `attempt_count` | Idempotent retry tracking |
| `error_code` | Stable machine-readable failure code |
| `error_message` | Bounded sanitized diagnostic |
| `created_at`, `started_at`, `finished_at` | Lifecycle timestamps |

Required constraints:

```sql
UNIQUE (import_run_id, record_number)
UNIQUE (import_run_id, source_identifier)
INDEX (import_run_id, status, record_number)
INDEX (status, lease_expires_at)
```

### 6.4 Separate internal and source identity

1. Make `mol_id` auto-generated by the database.
2. Add or create a dataset membership table with `batch_id`, `mol_id`,
   `source_identifier`, `record_number`, and import status.
3. Enforce `UNIQUE(batch_id, source_identifier)`.
4. Preserve identifiers byte-for-byte after an explicit normalization policy.
5. Do not replace underscores, slashes, whitespace, or punctuation implicitly.
6. Define whether one physical molecule can have multiple source identifiers across
   datasets.
7. Document duplicate policies before migration.

Recommended membership indexes:

```sql
UNIQUE KEY uq_dataset_source (batch_id, source_identifier)
UNIQUE KEY uq_dataset_molecule (batch_id, mol_id)
KEY ix_molecule_datasets (mol_id, batch_id)
```

### 6.5 Add a property registry

Create `property_definition` to map original SDF labels to controlled database
columns:

| Field | Purpose |
| --- | --- |
| `property_id` | Internal key |
| `original_name` | Exact property label from the SDF |
| `canonical_name` | Stable normalized comparison name |
| `column_name` | Quoted, server-generated physical column name |
| `sql_type` | Server-selected type from an allowlist |
| `nullable` | Null policy |
| `max_length`, `precision_value`, `scale_value` | Inference evidence |
| `created_at` | Audit timestamp |

Rules:

1. Match an existing property by canonical registry identity, not by raw SQL text.
2. Store the original property label for display and export.
3. Generate physical column names on the server.
4. Allow only `INT`, `BIGINT`, `DECIMAL(p,s)`, `DOUBLE`, `CHAR(n)`,
   `VARCHAR(n)`, and `TEXT` according to a strict parser.
5. Apply DDL once, before record import, while holding a schema migration lock.
6. Widen existing compatible columns; never narrow a column automatically.
7. Use `NULL` for missing properties, never an empty string in numeric columns.
8. Route very long values and excessive property counts to a typed value table if
   the wide table would exceed MariaDB row-size or column-count limits.

Phase 1 exit gate:

- Empty-database migration succeeds.
- Upgrade migration succeeds on a production-sized clone.
- Rollback procedure is documented and tested.
- All importer writes can participate in real InnoDB transactions.
- Active Java and API queries match the migrated schema exactly.

## 7. Phase 2: Java SDF analyzer and CLI contract

### 7.1 Create one Gradle application entry point

Build an executable importer artifact through the existing Gradle wrapper.

Proposed commands:

```text
molclass-importer analyze \
  --sdf /path/input.sdf \
  --output /path/analysis.json

molclass-importer import \
  --run-id 123 \
  --sdf /path/input.sdf \
  --manifest /path/selection.json \
  --resume

molclass-importer validate-config
molclass-importer version
```

CLI rules:

1. Every option has one meaning and a documented type.
2. Paths and labels containing spaces retain argument boundaries.
3. The importer receives an `import_run_id`, not free-form batch metadata.
4. The identifier property is read from the immutable selection manifest.
5. Configuration precedence is CLI, environment, profile file, then safe default.
6. Secrets never appear in process arguments or logs.
7. Standard output emits structured progress events or concise human output.
8. Standard error contains diagnostics.
9. Exit codes are stable.

Proposed exit codes:

| Code | Meaning |
| --- | --- |
| `0` | Operation succeeded |
| `2` | Invalid CLI or configuration |
| `3` | SDF analysis failed before import |
| `4` | Database unavailable or schema incompatible |
| `5` | Import completed with failed records |
| `6` | Run-level import failure |
| `7` | Lease lost or operation cancelled |

### 7.2 Stream and analyze every record

1. Read one SDF record at a time from a bounded buffered stream.
2. Compute a SHA-256 digest of the uploaded file while reading.
3. Reject a missing final delimiter only according to a documented policy.
4. Detect malformed mol blocks and property syntax without terminating analysis of
   subsequent parseable records.
5. Maintain aggregate property statistics only; do not retain all values.
6. Use an exact distinct-value strategy for identifier candidates in the first
   release.
7. Store distinct values in a temporary disk-backed structure when memory limits
   would be exceeded.
8. Report malformed records independently from property statistics.

For each property collect:

| Statistic | Use |
| --- | --- |
| Total record count | Completeness denominator |
| Present count | Property coverage |
| Null/blank count | Identifier eligibility and nullable flag |
| Distinct count | Identifier eligibility |
| Duplicate examples | User diagnostics |
| Minimum/maximum text length | Character type sizing |
| Integer minimum/maximum | Integer sizing |
| Decimal precision/scale | Exact numeric sizing |
| Parse failures by candidate type | Type promotion evidence |
| Example values | Frontend preview, with redaction limits |

### 7.3 Implement deterministic type inference

Use a promotion lattice rather than first-value inference:

```text
INT -> BIGINT -> DECIMAL -> DOUBLE -> CHAR/VARCHAR -> TEXT
```

Rules:

1. Ignore missing values when identifying the value type.
2. A property with only missing values becomes nullable `VARCHAR(1)` or is rejected
   according to product policy.
3. Use `INT` only when all values are strict base-10 integers in range.
4. Use `BIGINT` when integer values exceed `INT` but fit signed `BIGINT`.
5. Use `DECIMAL(p,s)` when all values are exact decimals and MariaDB limits permit.
6. Use `DOUBLE` for scientific notation or numeric ranges unsuitable for exact
   decimal storage.
7. Use `CHAR(n)` only when all non-null values have the same bounded length.
8. Use `VARCHAR(n)` for varying text up to the configured safe width.
9. Use `TEXT` for longer values or when row-size calculations reject `VARCHAR`.
10. Promote an existing property type when new data cannot fit; never coerce or
    truncate silently.

### 7.4 Select identifier candidates

A property is an identifier candidate only when:

```text
present_count == total_valid_records
blank_count == 0
distinct_count == total_valid_records
```

Selection behavior:

1. Auto-select the strongest candidate deterministically.
2. Prefer names matching a configurable exact-name priority list such as
   `identifier`, `compound_id`, or a deployment-specific registry.
3. Prefer shorter values and stable character types when priorities tie.
4. Show all eligible candidates to the user.
5. Require explicit user confirmation when no candidate exists or candidates tie
   without a configured preference.
6. Do not allow import until one valid identifier property is selected.
7. Revalidate uniqueness during import to protect against file or manifest changes.

### 7.5 Define analysis output

The analysis JSON must include:

- Analyzer and schema version.
- File name, size, and SHA-256 digest.
- Total, valid, and malformed record counts.
- Every discovered property and its statistics.
- Proposed SQL type and evidence.
- Identifier eligibility and rejection reasons.
- Auto-selected identifier, if unambiguous.
- Bounded malformed-record examples.
- Warnings about row width, excessive property counts, or lossy legacy mappings.

Phase 2 exit gate:

- Analyzer scans a large SDF with bounded memory.
- Type inference is deterministic regardless of property order.
- Identifier eligibility is proven across all valid records.
- Java analysis requires no Perl or PHP process.
- The same file produces the same analysis digest and manifest inputs.

## 8. Phase 3: Failsafe importer and durable queue

### 8.1 Create the upload and job lifecycle

1. Stream uploads to a private staging directory; do not load them into API memory.
2. Generate an opaque `upload_id` and safe server-side filename.
3. Record file size, digest, owner, creation time, and retention deadline.
4. Queue an analysis job.
5. Store analysis output in the database or an immutable artifact referenced by
   digest.
6. Accept a property-selection manifest only for the same upload digest and
   analysis version.
7. Create `batchlist`, `import_run`, and initial `import_record` state atomically.
8. Queue one import job and return `202 Accepted` with a status URL.
9. Delete or quarantine upload artifacts according to retention policy after the
   run reaches a terminal state.

### 8.2 Enforce one active upload import

Uploads may queue concurrently, but only one import may alter dataset/property
state at a time.

1. Acquire a database-backed global import lease.
2. Use a lease owner, expiry, and heartbeat rather than an in-process mutex.
3. Renew the lease at a bounded interval.
4. Stop before the next record if lease renewal fails.
5. Allow another worker to reclaim expired jobs.
6. Make the schema preparation step mutually exclusive.
7. Expose queue position and lease state through the API.

MariaDB implementation options must be tested against the deployed version:

- Transactional queue row with `SELECT ... FOR UPDATE SKIP LOCKED`.
- A queue row lease plus `GET_LOCK('molclass-import', timeout)` for global import
  serialization.

Do not rely only on `GET_LOCK`; durable job state is still required for recovery.

### 8.3 Define durable run steps

Use explicit, monotonic run steps:

```text
QUEUED
VERIFY_UPLOAD
PREPARE_SCHEMA
CREATE_RECORD_TRACKING
IMPORT_MOLECULES
GENERATE_INCHI
GENERATE_FINGERPRINTS
CALCULATE_DESCRIPTORS
BUILD_SIMILARITY_INDEX
FINALIZE
SUCCEEDED | PARTIAL | FAILED | CANCELLED
```

Rules:

1. Persist a step before executing it.
2. Persist its completion and counters before advancing.
3. A resumed worker starts at the first incomplete step.
4. A completed step must be idempotent when run again.
5. Step failures use stable error codes.
6. Optional post-processing failures produce `PARTIAL`, never false `SUCCEEDED`.
7. The dataset becomes model-eligible only after required steps complete.

### 8.4 Implement per-record transactions

For each SDF record:

1. Read the next record from the stream.
2. Locate the matching `import_record` by run and record number.
3. Skip an already successful record after verifying its source identifier.
4. Start an InnoDB transaction.
5. Mark the record `RUNNING` and increment its attempt count.
6. Parse and validate the mol block.
7. Extract and validate the selected source identifier.
8. Allocate the internal `mol_id` through auto-increment insertion.
9. Insert molecule structure and metadata.
10. Insert dataset membership with the exact source identifier.
11. Insert selected properties using prepared statements and typed setters.
12. Record the resulting `mol_id` and mark the record `SUCCEEDED`.
13. Commit the transaction.
14. On any molecule-specific error, roll back the complete molecule transaction.
15. In a new short transaction, mark the record `FAILED` with bounded diagnostic
    details.
16. Continue with the next record.
17. On database-wide or lease errors, stop the run so it can resume safely.

No failed record may leave a molecule, property, or membership row behind.

### 8.5 Make restart and retry idempotent

1. Identify records by `(import_run_id, record_number)` and source identifier.
2. Check the existing success state before inserting.
3. Enforce dataset/source uniqueness in the database.
4. Distinguish retryable database failures from permanent record failures.
5. Limit automatic attempts and expose manual retry for selected failures.
6. Keep the original error history in an append-only event or audit table.
7. Never suffix or mutate a duplicate source identifier automatically.
8. Mark duplicates as failed with a clear reason.
9. Recompute run counters from record state during recovery rather than trusting
   only incremented counters.

### 8.6 Refactor post-import computation

1. Process molecule IDs in bounded chunks.
2. Use bounded worker pools and bounded submission queues.
3. Use one managed connection per task or chunk and close it deterministically.
4. Record per-molecule computation status for InChI, fingerprints, and descriptors.
5. Do not replace failed fingerprints with empty bitsets without status.
6. Do not treat arbitrary SQL errors as evidence of a legacy schema.
7. Remove `System.gc()` and `OutOfMemoryError` recovery.
8. Replace thread termination with cooperative cancellation and interruption.
9. Use batch inserts where atomicity and diagnostics remain clear.
10. Set stage timeouts and fail or mark partial when a stage exceeds them.

### 8.7 Define import completion semantics

| Final state | Meaning |
| --- | --- |
| `SUCCEEDED` | All valid records and all required post-steps completed |
| `PARTIAL` | At least one record or optional post-step failed, but usable molecules exist |
| `FAILED` | No usable result or a required run-level step failed |
| `CANCELLED` | User or operator cancellation completed at a safe boundary |

Model building must be disabled for `FAILED` datasets. Product policy must decide
whether a `PARTIAL` dataset is model-eligible; the default should require explicit
acknowledgement.

Phase 3 exit gate:

- Killing a worker at every run step can be recovered without duplicate molecules.
- A malformed molecule does not stop subsequent records.
- A failed molecule leaves no partial domain rows.
- The importer remains within its memory budget on the reference large SDF.
- No Perl or PHP upload component is invoked.
- One and only one import holds the global import lease.

## 9. Phase 4: Upload API and frontend

### 9.1 Publish a versioned API contract

Implement at least these operations:

```text
POST   /api/v1/uploads
GET    /api/v1/uploads/{upload_id}
POST   /api/v1/uploads/{upload_id}/analysis
GET    /api/v1/uploads/{upload_id}/analysis
POST   /api/v1/imports
GET    /api/v1/imports/{import_run_id}
GET    /api/v1/imports/{import_run_id}/records
GET    /api/v1/imports/{import_run_id}/failures.csv
POST   /api/v1/imports/{import_run_id}/cancel
POST   /api/v1/imports/{import_run_id}/retry
```

API rules:

1. Use JSON error objects with stable `code`, `message`, and `request_id` fields.
2. Return correct HTTP status codes; never return an error string with HTTP 200.
3. Validate all identifiers, enums, lengths, and pagination bounds.
4. Never accept a raw SQL type or physical column name from the browser.
5. Authorize every upload and import against its owner or project.
6. Add OpenAPI examples for successful, partial, failed, and resumed imports.

### 9.2 Build the upload wizard

Implement these frontend stages:

1. Select file and display permitted size/type constraints.
2. Upload with progress and cancellation.
3. Show analysis progress.
4. Display record totals and malformed-record warnings.
5. Display every property, inferred type, null count, distinct count, and examples.
6. Select all properties by default.
7. Allow users to actively unselect properties.
8. Auto-select and explain the proposed identifier.
9. Allow another eligible identifier candidate to be chosen.
10. Prevent selection of an ineligible identifier.
11. Display existing-column reuse and new-column creation plans.
12. Require confirmation for type widening or a partial-data import.
13. Queue the import and navigate to durable status.
14. Show runstep, queue position, record counters, heartbeat, and elapsed time.
15. Show failed records without exposing internal stack traces.
16. Offer a failure report and safe retry/resume actions.

### 9.3 Correct batch pages

1. Display source filename, identifier property, selected properties, counts, owner,
   status, and timestamps.
2. Disable model creation until the import eligibility gate passes.
3. Fix route links so batch, prediction, model, and molecule IDs are not confused.
4. Replace hardcoded user filters with authenticated ownership filters.
5. Add server-side pagination and sorting.
6. Remove buttons that do not have implemented actions.
7. Provide a mobile navigation mechanism.

Phase 4 exit gate:

- A user can complete upload, property selection, import monitoring, and failure
  review without using a CLI.
- Browser state can be refreshed or reopened without losing job state.
- API errors are shown as errors and never as successful uploads.

## 10. Phase 5: Model jobs and model descriptions

### 10.1 Define supported model configurations

1. Inventory algorithms, feature types, and feature-selection methods implemented
   by `ModelBuilder`.
2. Remove unsupported options from the API and frontend.
3. Represent supported combinations in a server-provided capabilities endpoint.
4. Reject incompatible combinations before queueing a job.
5. Allow the target to be selected from imported properties with suitable class
   values.
6. Validate missing values, class counts, minimum class sizes, and label cardinality.
7. Require explicit positive-class selection for binary classification.

### 10.2 Add a durable model job lifecycle

Create or extend model job state with:

- Job ID, model ID, batch ID, and target property.
- Status, runstep, lease, heartbeat, attempts, and cancellation fields.
- Algorithm, hyperparameters, feature type, and feature-selection manifest.
- Random seed and split strategy.
- Training, validation, and holdout counts.
- Artifact URI, size, and SHA-256 checksum.
- Code revision, Java version, dependency manifest, and schema version.
- Created, started, and completed timestamps.
- Stable failure code and bounded diagnostic.

Recommended run steps:

```text
VALIDATE_DATA
BUILD_SPLITS
GENERATE_FEATURE_MATRIX
FIT_FEATURE_SELECTION
FIT_MODEL
EVALUATE
CALIBRATE
SERIALIZE
VERIFY_ARTIFACT
PUBLISH
```

### 10.3 Correct model evaluation

1. Use scaffold- or group-aware splitting to reduce chemical-series leakage.
2. Reserve an independent holdout set where dataset size permits.
3. Fit feature selection only on each training fold.
4. Fix random seeds and record them.
5. Report class support with every metric.
6. Include confusion matrix, balanced accuracy, precision, recall, F1, MCC, ROC AUC,
   and PR AUC where applicable.
7. Calibrate probabilities when the classifier and use case require it.
8. Report undefined metrics as undefined, not zero.
9. Record malformed, missing-target, and excluded molecule counts.
10. Compare against a documented baseline.

### 10.4 Produce a model card

Every published model must expose:

| Section | Required content |
| --- | --- |
| Identity | Model ID, name, version, owner, creation time, checksum |
| Purpose | Intended use, target definition, positive class, limitations |
| Dataset | Batch ID, source identifier property, counts, exclusions, class balance |
| Features | Fingerprint/descriptor version, selected feature count, preprocessing |
| Training | Algorithm, hyperparameters, seed, software and schema versions |
| Evaluation | Split method, fold/holdout metrics, confusion matrix, calibration |
| Applicability | Domain method, thresholds, known unsupported chemistry |
| Governance | Approval status, reviewer, expiration/review date |

1. Store a machine-readable model manifest next to the artifact.
2. Render the manifest as a structured model-detail page.
3. Keep raw Weka output as an optional diagnostic, not the primary description.
4. Do not publish a model until its artifact can be deserialized and checksum-
   verified in an isolated verification process.

Phase 5 exit gate:

- Only supported model combinations can be queued.
- Every model is reproducible from its stored manifest.
- Evaluation uses leakage-resistant splitting.
- The model detail page provides a complete, understandable model card.

## 11. Phase 6: Prediction correctness and serving

### 11.1 Separate prediction jobs from datasets

1. Remove the shared synthetic batch used for single predictions.
2. Create immutable prediction requests or jobs linked to one model version.
3. Never mutate global model-version state in a controller.
4. Distinguish `prediction_id`, `prediction_job_id`, `model_id`, `batch_id`, and
   `mol_id` in routes and DTOs.
5. Make prediction result upserts update all result values or prohibit duplicate
   insertion through a unique constraint.
6. Add asynchronous batch prediction and bounded synchronous single prediction.

### 11.2 Correct applicability-domain calculation

1. Load reference fingerprints from the model's recorded training batch.
2. Exclude the query molecule when it is part of the training set.
3. Define the exact similarity metric, fingerprint version, and neighbor count.
4. Use a bounded top-k heap rather than sorting all similarities.
5. Record applicability score, threshold, and in/out-of-domain result separately
   from classifier confidence.
6. Rename ambiguous fields such as response strength where semantics differ.
7. Test known in-domain and out-of-domain examples.

### 11.3 Bound model serving resources

1. Load models through a size-bounded least-recently-used cache.
2. Discover newly published models without restarting the service.
3. Verify artifact checksums before loading.
4. Do not share classifier instances concurrently unless library behavior is proven
   safe; otherwise use controlled per-model pools or serialized access.
5. Replace shared non-thread-safe formatters with local or thread-safe formatting.
6. Set per-request model count and prediction timeout limits.
7. Use try-with-resources for all database work.
8. Add circuit-breaking behavior for repeatedly failing model artifacts.

Phase 6 exit gate:

- Repeated single predictions do not accumulate hidden dataset rows.
- New models become available without restart.
- Applicability scores use the correct training reference set.
- Concurrent prediction tests are deterministic and remain within memory limits.

## 12. Phase 7: Molecule and structure search

### 12.1 Implement exact molecule lookup first

Support exact lookup by:

- Dataset source identifier.
- Internal `mol_id`.
- InChIKey.
- Canonical SMILES hash with source-string verification.
- InChI hash with source-string verification.

Implementation steps:

1. Define precedence and ambiguity behavior.
2. Return all ambiguous dataset matches rather than an arbitrary first row.
3. Preserve the query text; do not rewrite underscores or slashes.
4. Add ownership checks for private datasets.
5. Return a stable molecule summary DTO.
6. Add an exact-search box and molecule-detail route to the frontend.

Recommended indexes:

```sql
UNIQUE KEY uq_dataset_source (batch_id, source_identifier)
KEY ix_source_identifier (source_identifier, batch_id)
KEY ix_inchi_key (inchi_key, mol_id)
KEY ix_smiles_hash (canonical_smiles_sha256, mol_id)
KEY ix_inchi_hash (inchi_sha256, mol_id)
```

### 12.2 Implement property and text search

1. Define which properties are searchable.
2. Use exact or prefix predicates where possible.
3. Use MariaDB FULLTEXT or a dedicated search service for substring/full-text
   behavior.
4. Do not expect B-tree indexes to accelerate `%query%` predicates.
5. Add mandatory page size, maximum page size, stable sort, and continuation state.
6. Avoid inner joins that hide molecules missing optional InChI data.
7. Return only summary fields; fetch large structures and fingerprints separately.

### 12.3 Replace text fingerprint scans

1. Define one canonical binary encoding and version for each fingerprint.
2. Store bit counts beside each binary fingerprint.
3. Migrate existing textual fingerprints and verify bit-for-bit equivalence.
4. Compute only the fingerprint requested by a search operation.
5. Use bounded top-k result collection.
6. Select a scalable candidate-generation strategy based on measured dataset size.
7. Consider an RDKit database cartridge, LSH, or an inverted-bit candidate index for
   large collections.
8. Keep an exact brute-force implementation only as a bounded reference path.
9. Return pagination or a fixed maximum result count.

### 12.4 Implement substructure and scaffold search honestly

1. Define aromaticity, tautomer, charge, isotope, stereochemistry, and strict-match
   semantics.
2. Add a chemistry-aware prefilter before exact graph matching.
3. Batch-load candidate structures; remove N+1 queries.
4. Implement actual Murcko fragment extraction and membership storage.
5. Add both `(murcko_id, mol_id)` and `(mol_id, murcko_id)` indexes.
6. Remove UI controls for options not yet implemented.
7. Validate behavior against a curated chemistry test set.

### 12.5 Correct related-query SQL

1. Replace nondeterministic `GROUP BY table.*` queries.
2. Support `ONLY_FULL_GROUP_BY` in production SQL mode.
3. Bound every user-controlled result limit.
4. Select only required columns rather than raw large fingerprint fields.
5. Add query timeouts and cancellation.

Phase 7 exit gate:

- Exact identifier lookup is deterministic and index-backed.
- Search endpoints have bounded limits and stable pagination.
- Similarity search does not parse all text fingerprints into memory.
- Substructure and scaffold options have tested chemical semantics.
- Representative queries meet the agreed p95 target.

## 13. Phase 8: Database and memory performance hardening

### 13.1 Apply relational indexes

Add indexes only after duplicate cleanup and `EXPLAIN ANALYZE` validation.

| Table | Candidate index | Primary workload |
| --- | --- | --- |
| `batchmols` or replacement | `(batch_id, mol_id)` unique | List/import a dataset |
| `batchmols` or replacement | `(mol_id, batch_id)` | Find datasets for a molecule |
| `class_models` | `(batch_id, status, id)` | Dataset model list |
| Model job table | `(status, lease_expires_at, created_at)` | Worker leasing |
| `prediction_list` | `(batch_id, pred_id)` | Dataset predictions |
| `prediction_list` | `(model_id, pred_id)` | Model prediction history |
| `prediction_mols` | `(pred_id, mol_id)` unique | Prediction result retrieval |
| `prediction_mols` | `(mol_id, pred_id)` | Molecule prediction history |
| `moldb_moldata` | `(mol_name)` | Exact name lookup, subject to semantics |
| `sdftags` | Selected target/search columns | Model extraction and property search |
| `timeout_mols` | `(mol_id)` unique | Failure lookup |
| `tanimoto` if retained | `(mol_id1, ext, kr, mol_id2)` | Neighbor lookup |
| Murcko membership | `(murcko_id, mol_id)` | Scaffold members |
| Murcko membership | `(mol_id, murcko_id)` | Molecule scaffolds |

For each index:

1. Capture the before plan and timing.
2. Create the index on a production-sized clone.
3. Capture the after plan, latency, rows examined, and storage cost.
4. Verify write overhead under import load.
5. Keep the index only when it materially supports a defined query.

### 13.2 Remove N+1 database access

1. Inventory queries inside molecule, model, and similarity loops.
2. Batch reads by bounded ID sets.
3. Batch inserts with clear transaction boundaries.
4. Use joins or temporary staging tables for large ID sets.
5. Add query-count assertions to integration tests for critical API operations.

### 13.3 Establish memory budgets

Define and enforce budgets for:

| Process | Budget to define |
| --- | --- |
| API | Baseline heap/RSS, maximum upload buffer, maximum response size |
| Import worker | Heap, record buffer, descriptor queue, fingerprint queue |
| Model worker | Heap by feature type, maximum training rows/features, artifact size |
| Prediction worker | Model cache bytes, concurrent requests, feature matrix size |
| Database | Buffer pool, connection memory, temporary table limits |

Implementation steps:

1. Set bounded executor queue capacities.
2. Apply backpressure when queues are full.
3. Configure JDBC fetch sizes and streaming behavior explicitly.
4. Reuse expensive immutable fingerprint/descriptor configuration where safe.
5. Close every connection, statement, result set, stream, and executor.
6. Remove per-molecule metadata introspection.
7. Replace full sorts with top-k heaps.
8. Avoid full dense feature copies where sparse representation is supported.
9. Add maximum dataset size and feature-count policy with a clear rejection message.
10. Configure container limits and JVM `-Xms`, `-Xmx`, and GC policy together.
11. Capture heap dumps only through an operator-controlled diagnostic process.

### 13.4 Define performance benchmarks

Create reproducible benchmarks for at least:

1. Analyze a representative 100,000-record SDF.
2. Import the same SDF with a controlled malformed-record percentage.
3. Generate each supported fingerprint type.
4. Build small, medium, and maximum supported models.
5. Exact lookup over the production-scale molecule count.
6. Text/property search over production-scale property rows.
7. Similarity top-20 search over production-scale fingerprints.
8. Substructure search over a curated simple and complex query set.
9. Concurrent single predictions against cached and uncached models.

Record wall time, CPU, peak RSS/heap, database rows examined, temporary disk, and
p50/p95/p99 latency where applicable.

Phase 8 exit gate:

- Critical queries use reviewed plans and indexes.
- No critical path has an unbounded in-memory queue or result set.
- Benchmark results fit documented production capacity limits.
- Load tests do not produce connection leaks or incomplete silent results.

## 14. Phase 9: Security, observability, and operations

### 14.1 Authentication and authorization

1. Integrate the selected identity provider.
2. Define roles such as viewer, data importer, model builder, administrator, and
   auditor.
3. Authorize access to uploads, datasets, models, predictions, and destructive
   operations.
4. Add CSRF protection where cookie authentication is used.
5. Log authorization decisions without logging secrets or molecule payloads.
6. Use short-lived signed download URLs or authorized streaming for artifacts.

### 14.2 Secure input and artifacts

1. Validate SDF media type, extension, size, and content independently.
2. Store uploads outside the public web root.
3. Use random server-side filenames.
4. Scan uploads according to deployment security policy.
5. Escape output and apply a restrictive content security policy.
6. Treat Java model deserialization as a trusted-artifact boundary.
7. Permit model loading only from approved storage with checksum and provenance.
8. Add dependency, container, secret, and static analysis to CI.
9. Generate and retain an SBOM for each release.

### 14.3 Add operational signals

Expose metrics for:

- Queue depth and oldest queued job age.
- Active lease and heartbeat age.
- Import records per second and failure count by error code.
- Fingerprint and descriptor stage duration.
- Model build duration and failure count.
- Prediction latency and model-cache hit rate.
- Search latency, timeout count, and rows/candidates examined.
- JDBC pool utilization and connection acquisition latency.
- JVM heap, GC pause, thread count, and process RSS.
- Database storage, buffer-pool utilization, and slow queries.

Add:

1. Structured JSON logs with request, job, run, batch, model, and prediction IDs.
2. Health checks for process liveness only.
3. Readiness checks for database/schema compatibility and worker capability.
4. Alerting for expired leases, repeated job failure, queue age, memory pressure,
   disk pressure, and backup failure.
5. Dashboards for import, model, prediction, search, and database health.

### 14.4 Backups and disaster recovery

1. Define recovery point and recovery time objectives.
2. Automate encrypted database backups.
3. Back up model manifests and immutable artifacts.
4. Document upload retention separately from durable dataset retention.
5. Perform a restore into an isolated environment.
6. Verify schema migrations against restored data.
7. Record and review restore duration and data loss window.

Phase 9 exit gate:

- Authorization tests cover every mutating endpoint.
- No critical or high unresolved security findings remain.
- Operators can identify a stuck import without database inspection.
- A backup restoration drill has succeeded.

## 15. Phase 10: Tests, documentation, migration, and release

### 15.1 Build a root CI pipeline

The root workflow must execute from the correct project directories and include:

1. Gradle compile and unit tests.
2. Java importer integration tests against disposable MariaDB.
3. Migration from empty schema.
4. Migration from a sanitized legacy fixture.
5. FastAPI unit, contract, and integration tests.
6. Frontend type check, lint, and component tests.
7. Playwright tests against the actual MolClass stack.
8. Dependency, secret, static, and container scanning.
9. Docker image build and smoke test.
10. Artifact and SBOM publication for tagged builds.

Tests must not depend on a developer database or hardcoded historical batch IDs.
Tests must fail on model deserialization/build errors rather than substituting a
dummy classifier.

### 15.2 Required test suites

Importer tests:

- Empty SDF.
- Missing final delimiter.
- Invalid mol block followed by a valid record.
- Duplicate and blank identifier values.
- Properties introduced late in the file.
- Mixed numeric and text values.
- Integer and decimal boundary values.
- Very long property values.
- Existing property type reuse and widening.
- SQL failure during every molecule insert step.
- Worker termination and resume at every runstep.
- Repeated resume without duplicate molecules.
- Partial post-processing completion.

Model tests:

- Unsupported configuration rejection.
- Missing and imbalanced target classes.
- Scaffold split isolation.
- Deterministic results from fixed seeds.
- Artifact checksum verification.
- Published model reload and prediction equivalence.
- Applicability domain uses only training references.

Search tests:

- Identifiers containing underscores, slashes, spaces, and Unicode where supported.
- Ambiguous source identifiers across datasets.
- Exact InChIKey, SMILES, and InChI lookup.
- Pagination stability.
- Similarity reference-result equivalence.
- Substructure aromaticity, charge, and stereo semantics.
- `ONLY_FULL_GROUP_BY` compatibility.
- Query and result limits.

Frontend end-to-end tests:

- Upload to successful import.
- Auto-selected identifier and manual alternate selection.
- Property opt-out.
- Partial import with failure report.
- Refresh and resume status view.
- Target-property model creation.
- Model-card display.
- Exact molecule search and molecule detail.
- Similarity and substructure search.
- Single and batch prediction.
- Authorization denial and session expiry.
- Desktop and mobile navigation.

### 15.3 Replace stale documentation

Create or update:

| Document | Contents |
| --- | --- |
| `README.md` | Accurate architecture, supported workflow, quick start, and status |
| `INSTALL.md` | Java 17+, Gradle wrapper, MariaDB, profiles, and deployment prerequisites |
| `docs/ARCHITECTURE.md` | Components, trust boundaries, job flow, and deployment diagram |
| `docs/DATABASE_MODEL.md` | Current tables, relationships, constraints, indexes, and retention |
| `docs/IMPORT_WORKFLOW.md` | Analysis, identifier rules, type inference, transactions, resume, and errors |
| `docs/API.md` | Versioned endpoint contract and authentication |
| `docs/MODEL_CARD.md` | Model-description schema and interpretation |
| `docs/SEARCH.md` | Exact, text, similarity, substructure, and scaffold semantics |
| `docs/OPERATIONS.md` | Configuration, metrics, alerts, scaling, and common failures |
| `docs/BACKUP_RESTORE.md` | Backup, restore, verification, RPO, and RTO |
| `docs/MIGRATION.md` | Legacy migration, validation, rollback, and cutover |
| `SECURITY.md` | Reporting, supported versions, secret handling, and threat controls |
| `CHANGELOG.md` | User-visible and operational changes by release |

Remove or clearly archive documentation that describes SQLite, Java 8, H2-only
tests, obsolete CLI arguments, or unavailable endpoint behavior.

### 15.4 Migrate existing data

1. Freeze writes or establish a repeatable synchronization strategy.
2. Back up the source database.
3. Run a preflight report for invalid identifiers, duplicate memberships, orphaned
   rows, incompatible property types, and model schema drift.
4. Resolve each class of anomaly through an auditable policy.
5. Apply schema migrations to a staging clone.
6. Backfill source identifiers and dataset membership.
7. Backfill import/model status where provenance exists; mark unknown data clearly.
8. Convert fingerprints to the new encoding and verify equivalence.
9. Repackage or quarantine legacy models that lack trustworthy provenance.
10. Compare row counts, relationship counts, checksums, and representative API
    results.
11. Run production-scale benchmarks.
12. Rehearse rollback.

### 15.5 Cut over safely

1. Deploy the new stack to staging.
2. Import `uncoupler.sdf` through the complete browser workflow.
3. Verify source identifiers, selected properties, failed records, and inferred
   database types.
4. Build a model from the imported `class` property.
5. Verify model-card provenance and evaluation.
6. Exercise exact, similarity, substructure, and molecule-detail workflows.
7. Run concurrent prediction and memory tests.
8. Obtain security, data-owner, model-validation, and operations approval.
9. Schedule the production write freeze.
10. Back up production and apply migrations.
11. Deploy with legacy writes disabled.
12. Run smoke tests and reconciliation queries.
13. Monitor error rate, queue age, memory, and slow queries through the rollback
    window.
14. Remove legacy runtime access after the acceptance period.

## 16. Recommended pull request sequence

Keep changes reviewable and preserve a working migration path. The following order
is recommended:

1. `PR-01`: Architecture ADR, version policy, and legacy endpoint network block.
2. `PR-02`: Secrets/configuration cleanup and sanitized database bootstrap.
3. `PR-03`: Migration framework and schema baseline.
4. `PR-04`: InnoDB conversion and schema reconciliation.
5. `PR-05`: Import run, import record, dataset identity, and property registry tables.
6. `PR-06`: Java CLI parser, exit-code contract, and Gradle distribution artifact.
7. `PR-07`: Streaming SDF analyzer and analysis JSON schema.
8. `PR-08`: Complete-file type inference and identifier-candidate detection.
9. `PR-09`: Durable queue lease and worker heartbeat.
10. `PR-10`: Per-record transactional importer and idempotent resume.
11. `PR-11`: Bounded InChI, fingerprint, and descriptor stages.
12. `PR-12`: Upload/analysis/import API contract.
13. `PR-13`: Upload wizard and property-selection frontend.
14. `PR-14`: Import status, failure report, retry, and batch detail frontend.
15. `PR-15`: Model capabilities, target validation, and durable model jobs.
16. `PR-16`: Scaffold-aware evaluation and model manifest/card.
17. `PR-17`: Prediction lifecycle and applicability-domain correction.
18. `PR-18`: Exact molecule lookup and molecule-detail frontend.
19. `PR-19`: Indexed text/property search and pagination.
20. `PR-20`: Binary fingerprints and bounded similarity search.
21. `PR-21`: Substructure and Murcko implementation.
22. `PR-22`: Memory budgets, connection cleanup, and production JVM settings.
23. `PR-23`: Authentication, authorization, audit, and artifact controls.
24. `PR-24`: Metrics, health checks, alerts, backup, and restore automation.
25. `PR-25`: Root CI, integration fixtures, Playwright workflow, and security scans.
26. `PR-26`: Documentation replacement, migration rehearsal, and release candidate.

Each pull request must include its migration impact, rollback behavior, test evidence,
and observable operational changes.

## 17. Release gates

The production release cannot proceed until all gates pass.

### Data integrity gate

- Every operational table uses InnoDB.
- Foreign keys or documented integrity checks cover required relationships.
- Failed molecule transactions leave no partial domain rows.
- Import resume is idempotent.
- Dataset source identifiers are preserved and uniquely constrained.

### Functional gate

- Browser upload through model build works without CLI or legacy pages.
- All supported search modes work as documented.
- Single and batch predictions use explicit model versions.
- Partial imports and failures are visible and actionable.

### Model-validity gate

- Target and algorithm validation occurs before queueing.
- Evaluation uses leakage-resistant splits.
- Applicability-domain calculation uses training references.
- Every published model has a complete manifest and checksum.

### Performance gate

- Import, model, prediction, and search benchmarks meet agreed targets.
- Peak heap and RSS remain below configured limits with safety margin.
- Critical queries have reviewed execution plans.
- No unbounded queue, cache, query, or response remains on a public path.

### Security gate

- No shell or SQL injection path remains.
- Authentication and authorization cover every non-public operation.
- No production secrets or unsanitized user database dumps are tracked.
- No unresolved critical or high security findings remain.
- Model artifacts are provenance- and checksum-verified before deserialization.

### Operations gate

- Health, readiness, logs, metrics, dashboards, and alerts are active.
- Backup and isolated restore have succeeded.
- Migration rollback has been rehearsed.
- On-call and incident runbooks are approved.

### Documentation gate

- Architecture, database, import, API, model, search, deployment, backup, migration,
  and security documents match the release.
- Stale claims and obsolete setup paths have been removed or archived.

## 18. Initial production service-level targets

Confirm these targets against expected dataset sizes and infrastructure before
making them release requirements.

| Capability | Initial target |
| --- | --- |
| Exact identifier lookup | p95 below 200 ms at expected production scale |
| Paginated metadata search | p95 below 1 second for indexed queries |
| Similarity top-20 | p95 below 3 seconds at expected production scale |
| Import memory | Bounded independently of SDF record count |
| Prediction API | p95 below 1 second for one cached model, excluding structure conversion |
| Queue recovery | Expired job reclaimed within two lease intervals |
| Import durability | Zero partial molecule rows after injected record failure |
| Availability | Define after deployment topology and support coverage are agreed |

## 19. Open decisions

Resolve these before the dependent phase starts:

1. Is FastAPI or Spring the authoritative control API?
2. Are source identifiers unique only within a dataset or globally?
3. Can the same chemical structure be shared across datasets, and by what
   normalization rules?
4. Is a partially imported dataset eligible for model building?
5. Which model algorithms and feature families are supported in the first release?
6. What dataset and fingerprint scale must similarity search support?
7. Is MariaDB-only chemistry search required, or can an RDKit-backed service or
   cartridge be introduced?
8. What are the upload retention and failed-record retention periods?
9. Which identity provider and authorization model will be used?
10. What are the required RPO, RTO, availability, and performance targets?
11. Which historical models have enough provenance to migrate rather than rebuild?
12. Must all historical dynamic `sdftags` columns remain physically wide, or can
    low-use properties move to typed value tables?

## 20. Immediate next steps

Start with this exact sequence:

1. Approve or amend the target architecture in Section 2.
2. Complete the security containment actions in Phase 0.
3. Inventory the production MariaDB version, table sizes, row counts, engines,
   indexes, duplicates, and orphan rows using read-only queries.
4. Create the migration framework and a schema baseline.
5. Reconcile active Java/API fields with that schema.
6. Add import tracking, source identity, and property registry migrations.
7. Implement the Java CLI and streaming analysis command.
8. Validate analyzer behavior using `uncoupler.sdf` and malformed fixtures.
9. Implement the leased queue and per-record importer.
10. Fault-test worker termination and record failures before building the upload UI.

Do not begin model or search expansion until the import integrity gate passes. The
dataset, target labels, and molecule identity are the foundation for every model,
prediction, and search result.
