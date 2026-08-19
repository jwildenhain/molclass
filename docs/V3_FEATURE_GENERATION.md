# MolClass v3 feature generation

## Contract

`V3FeatureGenerator` regenerates chemistry features with CDK 2.12. It never reads
legacy descriptor or fingerprint values. The default `MODEL` scope processes the
union of molecules referenced by migrated model definitions; `ALL` extends coverage
to every canonical v3 molecule.

The generator stores:

- A deterministic descriptor manifest ordered by descriptor implementation class
  and result position.
- Big-endian IEEE-754 double vectors with an explicit missing-value bit mask.
- Fixed-width fingerprint byte vectors using LSB-0 bit numbering.
- Per-feature status, checksum, attempt count, job ID, error, and timestamp.
- Whether the explicitly authorized `SUB` fallback was used.
- Exact component ordering for all nine migrated feature profiles.

## Recovered profile definitions

| Profile | Components |
| --- | --- |
| `CDK` | CDK molecular descriptors |
| `MACCS` | MACCS |
| `PubChem` | PubChem |
| `EXT` | Extended fingerprint |
| `KR` | Klekota-Roth |
| `EXTGO` | Extended + graph-only |
| `ALL` | CDK + MACCS + PubChem + Extended + Substructure + ECFP |
| `MCAT` | CDK + MACCS + PubChem + Substructure + Klekota-Roth |
| `JUMBO` | CDK + MACCS + PubChem + Extended + Substructure + Klekota-Roth + ECFP |

`ESFP` is generated for molecule-search compatibility but is not referenced by the
117 migrated model definitions.

`ECFP` is CDK's `CircularFingerprinter` configured as `CLASS_ECFP4` folded to 1024
bits -- the CDK analogue of RDKit's Morgan/ECFP fingerprint, added because none of
the other fingerprints here are circular/connectivity-based. Added to `ALL` and
`JUMBO` only; existing single-fingerprint profiles (`MACCS`, `PubChem`, etc.) and
`MCAT` are left unchanged.

## Memory and concurrency

CDK calculators are thread-local. The coordinator keeps at most four work items per
thread in flight and performs database writes on one transaction-owning thread.
Results are committed in bounded batches, so worker count and batch size determine
memory rather than dataset size.

## Apply schema support

```bash
mysql --protocol=TCP -h 127.0.0.1 -P 3306 \
  -u "$MOLCLASS_DB_USER" -p < sql/v3/V3__feature_generation_tracking.sql
```

## Run

```bash
export MOLCLASS_JDBC_URL='jdbc:mysql://127.0.0.1:3306/'
export MOLCLASS_DB_USER='molclass_feature_worker'
export MOLCLASS_DB_PASSWORD='use-a-secret-source'
./gradlew generateV3Features
```

A bounded smoke run:

```bash
./gradlew generateV3Features \
  -PfeatureArgs="--scope MODEL --threads 4 --batch-size 100 --limit 100"
```

Resume is implicit. The query selects molecules missing any successful component;
successful rows are checksummed and upserted, while failed rows are retried.

Full canonical coverage:

```bash
./gradlew generateV3Features \
  -PfeatureArgs="--scope ALL --threads 12 --batch-size 200"
```
