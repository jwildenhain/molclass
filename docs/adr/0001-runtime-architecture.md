# ADR 0001: Authoritative MolClass Runtime Architecture

Date: 2026-08-13

Status: Accepted for implementation

## Context

MolClass currently contains several overlapping runtime paths:

- A Next.js frontend.
- A Spring Boot prediction API.
- A primary FastAPI application.
- A second FastAPI upload application.
- Java command-line chemistry and model code.
- Legacy PHP and Perl upload and administration code.

The implementations have different route contracts, database expectations, error
semantics, and job behavior. The deployed Docker topology starts Next.js, Spring
Boot, and MariaDB, but the Spring upload and model endpoints do not execute the full
workflow. Keeping several writable implementations active would preserve schema
drift and make import recovery unreliable.

## Decision

MolClass will use the following authoritative production architecture:

| Layer | Technology | Ownership |
| --- | --- | --- |
| Browser application | Next.js | User workflow and presentation |
| Control API | FastAPI | Authentication, request validation, REST contracts, job creation, status, and search orchestration |
| Chemistry and model workers | Java 17 built with the Gradle wrapper | SDF analysis/import, molecular computation, model building, and batch prediction |
| Durable queue and state | MariaDB InnoDB tables | Job leases, heartbeats, run steps, retries, and audit state |
| Persistent data | MariaDB InnoDB tables | Datasets, molecule identity, properties, models, and predictions |
| Model artifacts | Immutable filesystem abstraction initially | Checksummed model artifacts and model manifests |

The public API prefix will be `/api/v1`. Long-running work will return a job or run
identifier and will not execute synchronously in an HTTP request.

Java will be the only implementation allowed to analyze or import SDF files. The
FastAPI service will validate and queue work but will not parse the complete SDF or
alter property tables itself.

The internal database `mol_id` will remain separate from the selected SDF compound
identifier. The selected value will be stored as `source_identifier` and will be
unique within a dataset.

## Components to retire

The following components become migration-only and must receive no new production
features:

- Legacy PHP web pages in `html/molclass/web`.
- Perl SDF upload analysis and import scripts.
- The separate upload-only FastAPI application in `src/fastapi_app`.
- Spring Boot upload, queue, and prediction endpoints after equivalent versioned
  FastAPI endpoints are available.
- Duplicate Java/Python prediction orchestration paths after result reconciliation.

Until retirement, legacy components must not be publicly routed and must not write
to production concurrently with the authoritative path.

## Security decisions

1. Request values must never be interpolated into a shell command.
2. Browsers must never submit SQL types or physical database column names.
3. Database credentials must be injected at runtime and must not be committed.
4. Uploads must use opaque server-side names outside the public web root.
5. Model artifacts must be checksummed and trusted before Java deserialization.
6. The historical database seed must not be copied into application images or
   distributed as development data.

## Transaction decisions

1. All active application tables will use InnoDB.
2. One SDF molecule will be one database transaction.
3. A failed molecule transaction will be rolled back and its failure will be stored
   in a separate short transaction.
4. The importer will continue after permanent molecule-level failures.
5. Database-wide, schema, lease, and storage failures will stop the run for safe
   resumption.
6. Queue state will use a lease and heartbeat; an in-process mutex is insufficient.
7. Upload imports will be queued and only one import will execute at a time.

## Consequences

Positive consequences:

- There is one API contract and one SDF import implementation.
- Worker crashes can be recovered from durable state.
- Frontend errors can use normal HTTP and JSON semantics.
- Java chemistry code remains reusable without making the HTTP layer a JVM service.
- Legacy components can be removed incrementally.

Costs and constraints:

- FastAPI endpoints must be reconciled and versioned before Spring routes are
  removed.
- Java worker invocation needs a durable worker process rather than per-request
  Gradle or shell execution.
- Existing MyISAM tables require a measured data migration.
- Existing models and predictions require provenance and schema reconciliation.
- Deployment configuration must add an API and worker service and stop routing
  writes to Spring.

## Transition sequence

1. Contain legacy network access and remove committed/runtime secrets.
2. Establish versioned InnoDB migrations and import tracking.
3. Implement the Gradle-built Java SDF analyzer and importer worker.
4. Publish `/api/v1` upload, analysis, import, and job endpoints in FastAPI.
5. Connect the Next.js upload workflow to those endpoints.
6. Move model and prediction jobs to the same durable worker pattern.
7. Reconcile search contracts and connect the modern frontend.
8. Disable Spring writes and compare read results during a short shadow period.
9. Remove Spring, the secondary FastAPI application, PHP, and Perl from production
   images and routing.

## Reversal

This decision can be superseded by a new ADR. Reversal must preserve the durable
database contracts, source identifier semantics, per-record import transactions,
and versioned API behavior. It must not reactivate unauthenticated legacy writes.

