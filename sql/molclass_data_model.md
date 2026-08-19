# MolClass Data Model (Empty SQL Schema)

This document describes the MolClass database model represented in
[`molclass_data_model.sql`](molclass_data_model.sql).

## Summary

The model is schema-only and contains no `INSERT` data.  It defines core
molecule records, molecular annotations, batch/prediction metadata, and
computed tables used by fingerprinting and modeling.

## Notes

- The original upstream MySQL dump also uses `MyISAM` tables, so **foreign keys are not declared** at the database level.
- Relationships below are therefore logical data-model relationships inferred from shared keys.

## Core Relationships

- `batchlist.batch_id` (1) -> `batchmols.batch_id` (N)
- `batchmols.mol_id` -> `moldb_molstruc.mol_id`
- `batchmols.mol_id` -> `fingerprints.mol_id`
- `batchmols.mol_id` -> `moldb_moldata.mol_id`
- `batchmols.mol_id` -> `cdk_descriptors.mol_id`
- `batchmols.mol_id` -> `moldb_molstat(.mol_id)`
- `batchmols.mol_id` -> `moldb_molfgb.mol_id`
- `batchmols.mol_id` -> `moldb_molcfp(.mol_id)`
- `batchmols.mol_id` -> `inchi_key.mol_id`
- `class_models.model_id` -> `prediction_list.model_id` (N:1)
- `batchlist.batch_id` -> `prediction_list.batch_id`
- `prediction_list.pred_id` -> `prediction_mols.pred_id`
- `prediction_mols.mol_id` -> `batchmols.mol_id`

## Fingerprint tables and fallback status

The `fingerprints` table is updated by the multithreaded `Fingerprinter`.
A fallback mechanism for `SUB` was added for resilient processing:

- `sub_status`
  - `ok`
  - `fallback`
  - `error`
- `sub_status_message`
  - optional message describing why fallback was required

### Fallback semantics

- On successful `SUB` computation:
  - `sub_status = 'ok'`
  - `sub_status_message = NULL`
- On recoverable `SUB` compute failure (e.g. `OutOfMemoryError` or other exceptions):
  - `SUB` is stored as an empty text bitset
  - `sub_status = 'fallback'`
  - `sub_status_message` contains a compact exception summary
- On fatal worker failure:
  - `sub_status = 'error'`
  - `sub_status_message` contains worker exception summary

## Included migration step (existing DB)

To add fallback tracking columns to an existing schema:

```sql
ALTER TABLE fingerprints
  ADD COLUMN `sub_status` enum('ok', 'fallback', 'error') NOT NULL DEFAULT 'ok',
  ADD COLUMN `sub_status_message` varchar(255) DEFAULT NULL;
```

## Data-model file

- SQL: `sql/molclass_data_model.sql`
- Scope: structure only (no data rows)
