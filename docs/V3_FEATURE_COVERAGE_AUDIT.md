# MolClass v3 CDK feature coverage audit

Audit date: 2026-08-17  
Target schema: `molclass_v3`  
Result: PASS for every molecule currently referenced by a model definition.

## Scope and safety

This audit used only `SELECT` statements and `information_schema` metadata. It did not
run feature generation, model rebuilding, artifact reads, DDL, DML, model approval, or
source-control commands.

Feature payload columns were not selected. Coverage was established from row identity,
status, error metadata, and SHA-256 metadata.

## Population reconciliation

| Population | Count |
| --- | ---: |
| Model-eligible datasets | 88 |
| Imported dataset records | 225,257 |
| Failed or not-processed dataset records | 0 |
| Distinct molecules in model-eligible datasets | 149,302 |
| Distinct molecules referenced by at least one model definition | 92,054 |
| Eligible molecules not referenced by a current definition | 57,248 |

The 57,248 unreferenced molecules are not missing from an existing model profile. The
feature store is demand-driven: they require generation only when a new model definition
references their dataset/profile combination.

## Normalization states

| State | Molecules |
| --- | ---: |
| `MIGRATED_RAW` | 147,092 |
| `MIGRATED_SMILES_FALLBACK` | 1,961 |
| `IMPORTED_CDK_2_12` | 107 |
| `FEATURE_FAILED` | 142 |
| **Total** | **149,302** |

The same 142 failed molecules have a `FAILED` row in the descriptor generation and in
each of the seven fingerprint definitions. Error codes are present on every failed row.
Successful rows have valid 32-byte SHA-256 metadata; failed rows intentionally have no
payload hash. No fingerprint row reports fallback use.

## Versioned definitions

The active descriptor generation is:

- Generation: `cdk-2.12-molecular-v1`
- CDK: `2.12`
- Implementation: `v3-feature-generator-1`
- Status: `READY_WITH_EXCLUSIONS`
- Successful rows: 91,912
- Failed rows: 142

Seven fingerprint definitions are present and all are `READY_WITH_EXCLUSIONS`:

| ID | Code | Bits | Implementation |
| ---: | --- | ---: | --- |
| 1 | MACCS | 166 | `org.openscience.cdk.fingerprint.MACCSFingerprinter` |
| 2 | PubChem | 881 | `org.openscience.cdk.fingerprint.PubchemFingerprinter` |
| 3 | EXT | 1,024 | `org.openscience.cdk.fingerprint.ExtendedFingerprinter` |
| 4 | SUB | 307 | `org.openscience.cdk.fingerprint.SubstructureFingerprinter` |
| 5 | KR | 4,860 | `org.openscience.cdk.fingerprint.KlekotaRothFingerprinter` |
| 6 | GOFP | 1,024 | `org.openscience.cdk.fingerprint.GraphOnlyFingerprinter` |
| 7 | ESFP | 79 | `org.openscience.cdk.fingerprint.EStateFingerprinter` |

Each fingerprint definition has 91,912 successful rows and the same 142 failed rows.

## Profile component coverage

All nine feature profiles are `READY_WITH_EXCLUSIONS`. For each row below, every
required molecule has either a successful component row or a recorded failed row.
`Missing` means no row existed at all.

| Profile | Required molecules | Successful | Failed | Missing | Components |
| --- | ---: | ---: | ---: | ---: | ---: |
| ALL | 33,701 | 33,700 | 1 | 0 | 5 |
| CDK | 5,933 | 5,933 | 0 | 0 | 1 |
| EXT | 2,043 | 2,043 | 0 | 0 | 1 |
| EXTGO | 2,004 | 2,004 | 0 | 0 | 2 |
| JUMBO | 23,958 | 23,819 | 139 | 0 | 6 |
| KR | 13,544 | 13,544 | 0 | 0 | 1 |
| MACCS | 2,791 | 2,790 | 1 | 0 | 1 |
| MCAT | 50,684 | 50,616 | 68 | 0 | 5 |
| PubChem | 15,248 | 15,246 | 2 | 0 | 1 |

Counts are per profile, so failed counts overlap when the same failed molecule is used by
multiple profiles. Every component within a profile has the same required/success/failed
reconciliation.

## Acceptance decision

The current CDK feature regeneration gate passes for existing model definitions:

- Every required descriptor/fingerprint component row exists.
- Successful rows have valid hash metadata.
- Failed rows have explicit errors rather than silent gaps.
- Profile status communicates exclusions.
- Model split and exclusion reconciliation is independently checked by
  `auditV3Production`.

This result does not claim that all model-eligible molecules are precomputed. Before adding
a model definition that references one of the 57,248 currently unreferenced molecules,
run feature generation for the selected profile and repeat this component-level
reconciliation.

## Authoritative query shape

Coverage was calculated from distinct model requirements, not from total feature-table
counts:

```sql
WITH required AS (
  SELECT DISTINCT md.feature_profile_id, dm.molecule_id
  FROM molclass_v3.model_definition md
  JOIN molclass_v3.dataset_molecule dm ON dm.dataset_id = md.dataset_id
)
SELECT fp.profile_code,
       fpc.component_order,
       COUNT(*) AS required_count,
       SUM(CASE WHEN COALESCE(mdv.status, mf.status)
                    IN ('SUCCEEDED', 'SUCCEEDED_WITH_MISSING')
                THEN 1 ELSE 0 END) AS succeeded_count,
       SUM(CASE WHEN COALESCE(mdv.status, mf.status) = 'FAILED'
                THEN 1 ELSE 0 END) AS failed_count,
       SUM(mdv.molecule_id IS NULL AND mf.molecule_id IS NULL) AS missing_count
FROM required r
JOIN molclass_v3.feature_profile fp
  ON fp.feature_profile_id = r.feature_profile_id
JOIN molclass_v3.feature_profile_component fpc
  ON fpc.feature_profile_id = r.feature_profile_id
LEFT JOIN molclass_v3.molecule_descriptor_vector mdv
  ON fpc.descriptor_generation_id IS NOT NULL
 AND mdv.descriptor_generation_id = fpc.descriptor_generation_id
 AND mdv.molecule_id = r.molecule_id
LEFT JOIN molclass_v3.molecule_fingerprint mf
  ON fpc.fingerprint_definition_id IS NOT NULL
 AND mf.fingerprint_definition_id = fpc.fingerprint_definition_id
 AND mf.molecule_id = r.molecule_id
GROUP BY fp.profile_code, fpc.component_order
ORDER BY fp.profile_code, fpc.component_order;
```
