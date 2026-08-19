# ADR 0002: Rebuild Models in a Clean Versioned Database

Date: 2026-08-14

Status: Accepted for implementation

Supersedes: Implementation decisions D-11 and D-20 dated 2026-08-14

## Context

The local `molclass_legacy.class_models` table contains 117 model definitions. Of
those rows, 116 contain a model/header BLOB pair. The checked-in `models_v2`
directory contains 115 pairs, all byte-for-byte identical to the corresponding
database BLOBs. Database model 120 contains an additional complete pair that is not
present in `models_v2`; model 119 is a metadata-only incomplete build.

The legacy database contains the datasets and source structures needed to rebuild
the models, but its core tables use MyISAM. Descriptor values are stored in a single
237-column table and fingerprints are text. Model artifacts and metadata are mixed
without a reproducible build manifest, dependency versions, split membership,
approval state, or checksums.

Reusing legacy feature tables would update Weka serialization but would not produce
models based on the selected CDK generation.

## Decision

Create a separate `molclass_v3` MariaDB database and leave `molclass_legacy`
unchanged as the migration source and baseline.

Use these pinned runtime versions:

| Dependency | Version |
| --- | --- |
| Java | 17 |
| Chemistry Development Kit | 2.12 |
| Weka stable | 3.8.7 |
| MariaDB | 10.11.x local deployment |

CDK 2.12 is the current CDK release and is already present in the repository. The
rebuild must replace local file-tree dependency resolution with explicit Gradle
coordinates and prevent older duplicate jars from entering the runtime classpath.
Weka moves from the repository's 3.8.6 jar to stable 3.8.7.

Every legacy model row becomes a v3 model definition, including incomplete or
unsupported definitions. Unsupported algorithms, missing targets, insufficient
classes, or failed feature generation produce a durable skipped/failed build reason
without stopping the rebuild campaign.

The first v3 rebuild preserves each stored configuration:

- Dataset (`batch_id`).
- Target property (`class_tag`).
- Feature profile (`data_type`).
- Classifier (`class_scheme`).
- Feature selection (`feature_selection`).
- Recoverable classifier options and class metadata.

All canonical structures are reprocessed through versioned normalization,
descriptor, and fingerprint generation. No legacy descriptor or fingerprint value
is used as v3 training input.

Existing v2 model bytes remain immutable baseline artifacts. V3 builds are stored
side by side and cannot become active until evaluation succeeds and a local operator
records approval.

## Database decisions

1. Every v3 table uses InnoDB.
2. Foreign keys represent required relationships.
3. Source dataset records remain distinct from canonical molecules.
4. Dataset properties remain wide where safe, with a property registry and typed
   overflow values.
5. Descriptor and fingerprint values are versioned by generation.
6. Descriptor vectors use a documented binary vector and versioned schema instead
   of hundreds of fixed columns.
7. Fingerprints use binary bitsets with bit counts and algorithm metadata.
8. Stable model definitions are separate from model build attempts.
9. Model artifacts remain in MySQL as checksummed LONGBLOB rows.
10. Exact training/validation/holdout membership is stored for every build.
11. Evaluations, approvals, jobs, events, and migration mappings are durable.
12. The legacy database is read only during migration and rebuilding.

## Rebuild sequence

1. Create the empty `molclass_v3` schema.
2. Migrate dataset metadata, source structures, selected properties, and exact model
   definitions from `molclass_legacy`.
3. Normalize source structures and establish canonical molecule mappings.
4. Register CDK 2.12 descriptor and fingerprint generations.
5. Recalculate every required descriptor and fingerprint.
6. Register v3 profiles equivalent to CDK, MACCS, EXT, EXTGO, KR, MCAT, PubChem,
   ALL, and JUMBO.
7. Preflight each model definition against its data and supported capabilities.
8. Build models with Weka 3.8.7 using the preserved configuration.
9. Record comparable legacy-style evaluation and scaffold-aware validation.
10. Store checksummed model and header artifacts in MySQL.
11. Compare v3 with the immutable v2 baseline where available.
12. Require explicit approval before activation.

## Prohibited shortcuts

1. Do not overwrite `molclass_legacy.class_models`.
2. Do not overwrite `models_v2` files.
3. Do not copy legacy descriptors or fingerprints into v3 feature generations.
4. Do not activate a successful build automatically.
5. Do not silently replace an unavailable classifier with Random Forest.
6. Do not report a skipped or partial build as successful.
7. Do not use unversioned local jars for CDK or Weka.
