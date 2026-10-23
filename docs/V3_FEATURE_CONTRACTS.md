# MolClass v3 Immutable Feature Contracts

## Contract rule

Feature-profile composition is append-only from schema migration V14 onward. A
profile revision is sealed by a canonical JSON manifest and SHA-256 digest. Its
component rows, source versions, transformations, and raw feature count cannot be
changed after sealing. A changed component set creates a new profile revision and
supersedes the previous revision for new model definitions; existing definitions
continue to reference their original revision.

## Current profiles

| Code | Components | Raw features |
|---|---|---:|
| CDK | CDK descriptors | 454 |
| MACCS | MACCS | 166 |
| PubChem | PubChem | 881 |
| EXT | Extended | 1024 |
| KR | Klekota-Roth | 4860 |
| EXTGO | Extended + graph-only | 2048 |
| ALL | CDK + MACCS + PubChem + Extended + Substructure + ECFP4 | 3856 |
| MCAT | CDK + MACCS + PubChem + Substructure + Klekota-Roth | 6668 |
| JUMBO | CDK + MACCS + PubChem + Extended + Substructure + Klekota-Roth + ECFP4 | 8716 |

`ALL` is the model-creation default because the current contract is generated and
has successful completed builds. Feature selection remains model-specific and can
reduce the final Weka header substantially.

## Build provenance

Every build created after V14 receives these immutable values at insertion time:

- `feature_profile_id_at_build`
- `feature_contract_json`
- `feature_contract_sha256`
- `raw_feature_count`
- `feature_contract_capture=CAPTURED_AT_BUILD`

The database injects the same contract, digest, and raw count into
`build_manifest_json` and recomputes the manifest digest transactionally. Earlier
builds remain `LEGACY_NOT_CAPTURED`; V14 intentionally does not infer historical
composition from a profile that was mutable at the time.

## Readiness

Readiness is calculated per profile, not copied from one global feature-generation
result. The profile records required, ready, failed, and missing molecule counts.
`READY_WITH_EXCLUSIONS` remains executable only when explicit feature exclusions
are enabled; individual failed molecules retain their record-level error evidence.

## Operations

Apply `sql/v3/V14__immutable_feature_contracts.sql` before running the updated
feature generator or API. Then run:

```bash
MOLCLASS_ALLOW_FEATURE_EXCLUSIONS=true ./gradlew generateV3Features \
  -PfeatureArgs='--scope MODEL --threads 16 --batch-size 200'

./gradlew auditV3FeatureCoverage \
  -PauditArgs='--output docs/V3_FEATURE_COVERAGE_AUDIT.md'
```

Never edit a sealed profile or assign a superseded profile to a new definition.
Create and publish a new revision instead.
