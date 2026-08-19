# MolClass Database Migrations

This directory contains ordered production schema migrations. Migrations are
forward-only in normal operation and must be applied by one controlled migration
process before API and worker startup.

## Current state

`V2_2_0__import_tracking_foundation.sql` creates the new InnoDB upload and import
subsystem without modifying legacy tables. It intentionally omits foreign keys to
legacy batch and molecule tables because those tables must first be reconciled and
converted from MyISAM to InnoDB.

Do not add those foreign keys until the migration preflight has confirmed:

- Parent and child column types match exactly.
- Duplicate memberships and identifiers have been resolved.
- Orphaned rows have been repaired or quarantined.
- Referenced parent tables use InnoDB.

## Rules

1. Never edit a migration after it has been applied to a shared environment.
2. Add a new migration for every subsequent change.
3. Test migrations against both an empty database and a sanitized legacy fixture.
4. Record preflight and reconciliation queries with the migration that needs them.
5. Use server-generated names and allowlisted SQL types for dynamic SDF properties.
6. Do not execute DDL from an HTTP request or from raw browser input.
7. Back up production and rehearse the migration on a production-sized clone before
   applying it.

