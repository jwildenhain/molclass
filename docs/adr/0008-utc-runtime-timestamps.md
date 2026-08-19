# ADR 0008: UTC Runtime Timestamps

Date: 2026-08-14

Status: Accepted

## Context

The v3 schema uses `DATETIME(6)` and several `CURRENT_TIMESTAMP(6)` defaults.
MariaDB evaluates those defaults in the connection session time zone. A FastAPI
connection using Europe/London inserted a queued job one hour ahead of a Java worker
that compared `available_at` with `UTC_TIMESTAMP(6)`, so the job was invisible until
the daylight-saving offset elapsed.

## Decision

All MolClass database sessions use UTC:

- SQLAlchemy initializes every connection with `SET time_zone = '+00:00'`.
- Java SDF worker connections execute the same statement before any query.
- The compose MariaDB service starts with `--default-time-zone=+00:00`.
- Queue inserts set `available_at=UTC_TIMESTAMP(6)` explicitly.

API timestamps continue to use their existing contract field names. The database
remains authoritative for job ordering, leases, and heartbeats.

## Consequences

Queued work is immediately visible to workers regardless of host daylight-saving
rules. Existing historical `DATETIME` rows are not rewritten because their source
session zone cannot be proven universally; migration and rebuild audit timestamps
retain their recorded values.
