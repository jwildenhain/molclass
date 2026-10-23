# MolClass Handover - 2026-10-23

## Repository state

The current work is on `master` in `/mnt/wdc_store/gitlab/molclass`. It includes
the benchmark datasets, model policy/rebuild changes, modern frontend repairs, and
V14 immutable feature-contract work. Do not discard the pre-existing dirty-tree
changes; they are part of the same production-readiness implementation stream.

## Completed in this pass

- The frontend production build passes.
- Model review deep links, 25-item pagination, mobile overflow, and the distinction
  between configured CV policy and actual build evaluation are repaired.
- ChemGrid SMILES parameters survive switching from Structure Search to Activity.
  An unindexed structure is not assigned a fabricated molecule ID; the UI directs
  the operator to registry lookup/import first.
- `ALL` is restored as the model-creation default.
- V14 defines sealed, append-only feature-profile revisions and exact build-time
  contract snapshots.
- Model options expose contract digest, raw feature count, and profile-specific
  coverage.
- Definitions 201 and 202 retain their Ensemble contracts and receive a reviewed
  six-hour timeout policy for retry.

## Required operational sequence

1. Back up `molclass_v3` and rehearse V14 on a current clone.
2. Apply V14 locally, then run model-scoped feature generation with exclusions
   explicitly enabled.
3. Generate `docs/V3_FEATURE_COVERAGE_AUDIT.md` with
   `auditV3FeatureCoverage` and run the production audit.
4. Reset definitions 201 and 202 from `REBUILD_FAILED` to `PENDING_REBUILD` with an
   audit event, then rebuild them using at most 16 threads each and no more than
   96 GiB aggregate Java heap.
5. Triage the approval queue using validation/holdout evidence. Do not bulk-publish
   the 197 awaiting builds.
6. Commit and push only after Java/API/frontend validation succeeds.
7. Sync TyersChem2, deploy locally, run smoke tests, then deploy ChemGrid through
   its normal rollback-capable process. Preserve Google Analytics, MCP, and the
   MolClass prediction service configuration.

## Important compatibility boundary

Updated feature generation requires V14. Updated model builds record schema
version `V14`. Existing serialized models remain usable; their build rows are
explicitly labelled `LEGACY_NOT_CAPTURED` for feature-contract provenance.
