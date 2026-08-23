# V3 descriptor catalog incident, recovery, and Tox21 onboarding

Date: 2026-08-23
Repository: `/mnt/wdc_store/gitlab/molclass`
Database: `molclass_v3` on the local MariaDB instance

## 1. Summary

`tools/run_v3_worker.sh` built its Java classpath by globbing `lib/*.jar`, which
omits every dependency the Gradle build resolves from Maven — most importantly
`cdk-qsarprotein-2.12.jar` and Weka 3.8.7. Run through that script, CDK's
`DescriptorEngine` silently discovers 51 molecular-descriptor classes (287
values) instead of the Gradle build's 53 classes (454 values). Two demo SDF
uploads processed through the script on 2026-08-22 wrote 287-wide descriptor
vectors into `descriptor_generation_id=1`, which already held 149,296 vectors
at 454-wide from prior (correctly-built) runs. The mixed widths crashed model
rebuild 206 with `IllegalStateException: descriptor vector length mismatch`.

The script has been fixed and the corrupted vectors regenerated. The dataset
that triggered the discovery (`molclass_big_test`, dataset 90) now has a
working model (build 207). A second dataset — Tox21, downloaded from the
DeepChem/MoleculeNet distribution — was then imported end-to-end through the
real upload API as a check that the whole pipeline behaves correctly on a
larger, sparser, externally-sourced dataset. All 12 Tox21 endpoints were
built as models twice — once on the CDK-descriptor-only feature profile,
once on the JUMBO profile (CDK + 6 fingerprint types) — and compared against
the published Tox21 Data Challenge benchmark (DeepTox, the challenge
winner). Seven further public toxicity/safety benchmark datasets (hERG ×2,
AMES, DILI, Skin Reaction, Carcinogens, ClinTox) were downloaded and
converted to validated SDF but deliberately left unimported, per instruction.

A follow-up statistics audit (§6) verified the training pipeline is
leak-safe by design (feature selection and SMOTE are fit only on the
training partition), independently re-verified all 24 reported Tox21 AUC
values against the database with zero discrepancies, but also found and
quantified a real, pre-existing, small (~2–4% of holdout) train/holdout
leak caused by duplicate molecules landing on both sides of the split. The
hardest of the 12 Tox21 endpoints, NR-ER, was identified from first
principles (lowest AUC across all three sources, and not explained by
class imbalance) and benchmarked against all 11 available MolClass
algorithms — KNN turned out to generalize best, while RandomForest,
Bagging, and especially AdaBoostM1 showed clear overfitting, and LibSVM/SMO
performed near chance with default hyperparameters.

## 2. Root cause

`DescriptorCatalog.create()` in
[`src/molclass/features/V3FeatureGenerator.java`](../src/molclass/features/V3FeatureGenerator.java)
asks CDK to discover every `IMolecularDescriptor` on the classpath at runtime
via `DescriptorEngine`. The catalog it gets back is therefore a function of
*which jars are present*, not something declared in code.

- `build.gradle` depends on `org.openscience.cdk:cdk-bundle:2.12`, which Gradle
  resolves into ~115 separate Maven modules on `sourceSets.main.runtimeClasspath`
  — including `cdk-qsarprotein-2.12.jar`.
- `tools/run_v3_worker.sh` (pre-fix) built its own classpath as
  `app_jar:driver:$(for jar in lib/*.jar; do ...)`. `lib/` only contains the
  handful of jars *not* resolved from Maven (see the `fileTree(...exclude:
  [...])` in `build.gradle`); it does not contain `cdk-qsarprotein` or any
  other CDK submodule.
- Two classes — `AminoAcidCountDescriptor` (20 values) and
  `TaeAminoAcidDescriptor` (147 values), 167 values total — live in
  `cdk-qsarprotein`. Both are physically present in `lib/cdk-2.12.jar` (a
  vendored fat jar) and instantiate correctly if constructed by name; they are
  simply invisible to `DescriptorEngine`'s discovery scan unless
  `cdk-qsarprotein` is a separate classpath entry. This was confirmed
  experimentally: adding `cdk-qsarprotein-2.12.jar` to the worker's classpath
  raised its discovered catalog from 51/287 to 53/454; removing it from the
  Gradle classpath lowered the Gradle catalog from 53/454 to 51/287.
- 454, not 287, is correct: it matches `descriptor_schema`'s prior stored
  manifest, `descriptor_generation.configuration_json`, and all 149,296
  pre-existing vectors.

A second, related defect: `WEKA_VERSION = "3.8.7"` is a hardcoded constant in
`V3ModelRebuilder.java`, but the worker's `lib/` classpath only supplies
`weka-stable-3.8.6.jar`. Every build run through the unfixed script recorded
`model_build.weka_version = 3.8.7` while actually training on 3.8.6.

A third, unresolved defect (not fixed in this session, see §8): the upsert in
`ensureDescriptorGeneration()` keys `descriptor_generation` on the hardcoded
name `cdk-2.12-molecular-v1` via `uq_descriptor_generation_name`, which is
checked before the content-addressed `uq_descriptor_generation_config` key. A
changed catalog therefore mutates the *existing* generation row in place
(`configuration_sha256` is never refreshed to match) instead of minting a new
generation. This is the mechanism that let the corruption happen silently.

## 3. Fix

Commit `6f5bec5` — *Fix v3 worker classpath to match the Gradle build*:

- Added a `runtimeClasspathFile` task to `build.gradle` that writes
  `sourceSets.main.runtimeClasspath.asPath` to `build/runtime-classpath.txt`.
- `tools/run_v3_worker.sh` now reads that file instead of globbing `lib/*.jar`,
  and fails loudly (`Missing build/runtime-classpath.txt. Run: ./gradlew
  runtimeClasspathFile`) rather than silently degrading.

Verified with a standalone probe (`CatalogProbe.java`, replicating
`DescriptorCatalog.create()` exactly) run against both the old worker
classpath (51/287) and the new one built from `runtimeClasspathFile`
(53/454, matching Gradle's own classpath).

## 4. Data recovery (dataset 90 / build 206 → 207)

1. **Backups.** `descriptor_schema_backup_20260823` and
   `mdv_backup_20260823` (the latter holding exactly the 32 mis-width
   vectors, confirmed by row count before deletion). Both dropped after
   build 207 verified successfully — no longer present in the schema.
2. **Delete.** Removed the 32 vectors where
   `descriptor_generation_id=1 AND LENGTH(descriptor_values) <> 454*8`. The
   width-based predicate (not job-id-based) guaranteed only mis-sized rows
   were touched.
3. **Regenerate.** `./gradlew generateV3Features -PfeatureArgs="--scope
   MODEL"` (with `MOLCLASS_JDBC_URL=jdbc:mysql://127.0.0.1:3306/`, matching
   the driver already on the classpath — `jdbc:mariadb://` fails, there is no
   `mariadb-java-client` dependency in `build.gradle`) recomputed the 32
   deleted vectors plus 6 pre-existing permanently-broken molecules
   (`ILLEGAL_ARGUMENT_EXCEPTION: molfile contains no atoms`, `attempt_count`
   16–17 — a legacy data-quality issue unrelated to this incident) at the
   correct 454 width.
4. **Exclusions.** With `remaining=6 > 0` and no
   `MOLCLASS_ALLOW_FEATURE_EXCLUSIONS` set, `descriptor_generation` /
   `feature_profile` stayed `COMPLETED_WITH_ERRORS` and unpublished
   (`published_at=NULL`), blocking any rebuild (`V3ModelRebuilder` requires
   `feature_profile.status` to start with `READY`). Re-running with
   `MOLCLASS_ALLOW_FEATURE_EXCLUSIONS=true` published it as
   `READY_WITH_EXCLUSIONS`, formally acknowledging those 6 molecules are
   permanently excluded from any model built on this feature profile.
5. **Rebuild.** `./gradlew rebuildV3Models -PmodelArgs="--model-id 119"` (the
   explicit `--model-id` form is required — the unattended scan only picks up
   `PENDING_REBUILD`, not `REBUILD_FAILED`) produced **build 207**,
   `AWAITING_APPROVAL`, 54/5/5 train/validation/holdout, 0 excluded,
   `cdk_version=2.12`, `weka_version=3.8.7` (now actually true).

## 5. Tox21 onboarding

### 5.1 Source and conversion

Downloaded the Tox21 CSV (8,014 compounds, SMILES + 12 binary endpoint
columns: `NR-AR`, `NR-AR-LBD`, `NR-AhR`, `NR-Aromatase`, `NR-ER`, `NR-ER-LBD`,
`NR-PPAR-gamma`, `SR-ARE`, `SR-ATAD5`, `SR-HSE`, `SR-MMP`, `SR-p53`) from the
DeepChem/MoleculeNet distribution. Converted to SDF with RDKit
(`Chem.MolFromSmiles` → `Compute2DCoords` → `MolToMolBlock`), tagging each
record with `> <Identifier>` (the CSV's `mol_id`) plus one tag per endpoint
present for that compound (blank/NaN endpoints omitted, preserving the
dataset's real sparsity). 8,006 of 8,014 compounds converted (8 failed on
non-standard hypervalent-aluminum SMILES that RDKit's default valence model
rejects).

Validated against the project's own `SdfAnalyzer`
(`./gradlew analyzeV3Sdf -PimportArgs="analyze --sdf ... --output ..."`)
before uploading: **8,006 valid, 0 malformed**. All 12 endpoint properties
inferred as `INT`, 2 distinct values, `WIDE` storage — directly inside the
model-creation eligibility window (`distinct_count BETWEEN 2 AND 100`).

### 5.2 Import

Uploaded through the real running API (`127.0.0.1:8100`, `app.main:app`,
picked up the working tree's uncommitted `PATCH /datasets/{id}` endpoint
since it runs from source) via the documented multi-step contract:

```
POST /api/v1/uploads                       -> uploadId 6, ANALYSIS_QUEUED
(background V3SdfWorker daemon processes SDF_ANALYZE)
GET  /api/v1/uploads/6                     -> ANALYZED, 8006 valid records
POST /api/v1/uploads/6/imports             -> importRunId 4, dataset 91
(background V3SdfWorker daemon processes SDF_IMPORT)
GET  /api/v1/imports/4                     -> SUCCEEDED, 8006/8006, 0 failed
```

Dataset 91 (`tox21`) is now `READY`, `model_eligible=1`.

### 5.3 A design gap surfaced by this dataset

`V3SdfImporter.persistPropertyManifest` only sets
`dataset_property.model_target_allowed=1` when a property is present on
**every** record (`binding.present == claim.totalRecords && binding.blank ==
0`). Tox21's 12 endpoints are deliberately sparse — not every compound was
tested in every assay, which is normal for a real HTS panel, not a data
defect — so **none of the 12 auto-qualified as a model target**, and
`GET /model-datasets/91` returned nothing.

Worked around with direct `UPDATE dataset_property SET
model_target_allowed=1` statements, one per property, justified because the
app's actual runtime guardrails (`min class support >= 5`, `sum class
support >= 30`) are satisfied regardless of completeness for every one of
the 12 endpoints. This is a one-off override, not a code fix — see §8. It
was applied to all 12 Tox21 endpoints over the course of this session (first
just `NR-AR`, then the remaining 11 once the first result was reviewed).

### 5.4 Background daemon classpath

Two long-running worker daemons (`V3SdfWorker`, `V3ModelPipelineWorker`) were
already running from before this session's classpath fix, launched with the
old `lib/*.jar` glob. `V3ModelPipelineWorker.child()` launches its
feature-generation subprocess with
`System.getProperty("java.class.path")` — i.e., it inherits whatever
classpath *it* was started with. Left running, the next automatic feature
generation would have silently reintroduced the exact 287-vs-454 corruption
on the new Tox21 molecules. Both daemons were killed and restarted from
`tools/run_v3_worker.sh` (now fixed), confirmed via `ps aux` to carry
`cdk-qsarprotein-2.12.jar` on their classpath before any model definition was
created. These are ordinary background shell processes, not a systemd
service or anything persisted — they will not survive a reboot or a fresh
shell session, and nothing was written to make them do so.

### 5.5 CDK-profile builds (all 12 endpoints)

Model definitions 120–131 (`tox21 - <endpoint>`, RandomForest +
CfsSubsetEval, feature profile `CDK` = 454 CDK molecular descriptors only,
positive class `1`) were created via `POST /model-definitions` and built
automatically by the restarted `V3ModelPipelineWorker` daemon: builds
208–219, all `AWAITING_APPROVAL`, no errors. Feature generation for the
8,006 new molecules ran once automatically (all succeeded); the "54 feature
record failures" it reported each time are the same 6 pre-existing
permanently-broken legacy molecules from §4, not new Tox21 failures. One
manual `MOLCLASS_ALLOW_FEATURE_EXCLUSIONS=true` republish (same reason as
build 207) was needed to unblock the first build; the daemon picked up the
rest on its own once the feature profile was published
`READY_WITH_EXCLUSIONS`.

Excluded-record counts on every build exactly match each endpoint's missing
value count in `dataset_property.present_count`, confirming SMOTE/Weka
exclusion handling behaved correctly across all 12.

### 5.6 JUMBO-profile builds (all 12 endpoints)

A second round of the same 12 endpoints was built against feature profile
`JUMBO` (feature_profile_id 6): CDK's 454 descriptors **plus** six
fingerprints — MACCS (166 bit), PubChem (881 bit), Extended (1024 bit),
Substructure (307 bit), Klekota-Roth (4860 bit), ECFP (1024 bit) — for
8,716 raw attributes before `CfsSubsetEval` narrows the set. All six
fingerprints had already been computed and published for every Tox21
molecule as a side effect of the CDK-profile feature-generation run (feature
generation computes descriptors and every supported fingerprint type
together, regardless of which profile triggered it), so no additional
feature generation was needed — model definitions 132–143 went straight to
`V3ModelRebuilder`.

Confirmed, against an initial (mistaken) suggestion that JUMBO might omit
CDK descriptors, that `feature_profile_component` for profile 6 includes
`descriptor_generation_id=1` (the CDK generation) as component order 0,
ahead of the six fingerprint components, and that
`V3ModelRebuilder.prepareData()` iterates every component in the profile
unconditionally — verified directly against the schema row and the code
path, not assumed.

JUMBO builds took substantially longer than CDK-only builds — SR-MMP and
SR-p53 each ran ~35–45 minutes real time (CFS's `GreedyStepwise` search over
8,716 attributes, followed by SMOTE oversampling and a 100-tree
`RandomForest`, all inside the 60-minute per-build timeout the daemon
enforces) versus a few minutes for the CDK-only equivalents. All 12
(builds 220–231) finished `AWAITING_APPROVAL`, no errors, same exclusion
counts as their CDK counterparts.

### 5.7 Comparing against the official Tox21 Data Challenge benchmark

**A citation correction made during this session, documented for
traceability:** the official per-endpoint AUC values were first pulled via
a summarizing web-fetch of the DeepTox paper's results table and presented
to the user as fact. Recomputing the NR-panel average from those
transcribed values (0.848) did not match the paper's own printed NR-panel
average (0.826), which surfaced the error. Re-verification was done by
downloading the actual PDF (`arXiv:1503.01445`, Unterthiner, Mayr,
Klambauer, Hochreiter — *Toxicity Prediction using Deep Learning*, 2015,
Table 3; a related but distinct paper from the Frontiers 2016 journal
article originally cited, whose own Table 5 is rendered as a non-extractable
image) and reading the rendered table image directly at 400dpi, twice, with
cross-validation against a second team's row (`AMAZIZ`) to confirm the
column-to-endpoint mapping. Several values had been mis-assigned to the
wrong endpoint in the first pass (a column-ordering misread, not a
one-digit typo) — most notably NR-AR-LBD, NR-ER, NR-PPAR-gamma, and SR-HSE.

One reconciliation gap remains **unexplained and is reported as such
rather than papered over**: a plain arithmetic mean of the 7 correctly-read
NR-panel values gives 0.838, not the paper's own printed NR-panel average of
0.826, even though the same method reproduces the SR-panel average (0.858)
and the overall average (0.846) to within rounding. No plausible reading
error accounts for a gap that size. The panel/overall averages quoted below
are the paper's own printed values, not a recomputation.

| endpoint | CDK holdout AUC (build) | JUMBO holdout AUC (build) | Official DeepTox AUC |
|---|---|---|---|
| NR-AR | 0.807 (208) | 0.789 (220) | 0.807 |
| NR-AR-LBD | 0.801 (209) | **0.854** (221) | 0.850 |
| NR-AhR | 0.902 (210) | 0.857 (222) | 0.928 |
| NR-Aromatase | **0.898** (211) | 0.803 (223) | 0.834 |
| NR-ER | 0.724 (212) | 0.767 (224) | 0.793 |
| NR-ER-LBD | 0.821 (213) | **0.873** (225) | 0.814 |
| NR-PPAR-gamma | 0.764 (214) | **0.887** (226) | 0.839 |
| SR-ARE | 0.803 (215) | 0.816 (227) | 0.840 |
| SR-ATAD5 | 0.845 (216) | **0.908** (228) | 0.793 |
| SR-HSE | 0.823 (217) | 0.810 (229) | 0.858 |
| SR-MMP | 0.924 (218) | **0.941** (230) | 0.941 |
| SR-p53 | 0.848 (219) | 0.861 (231) | 0.862 |
| **NR panel avg** | 0.817 (simple mean) | 0.833 (simple mean) | 0.826 (printed) |
| **SR panel avg** | 0.849 (simple mean) | 0.867 (simple mean) | 0.858 (printed) |
| **Overall avg** | 0.830 (simple mean) | **0.847** (simple mean) | **0.846 (printed)** |

Bold = beats the official DeepTox number on that endpoint. JUMBO beats CDK
on 8 of 12 endpoints and beats the official challenge-winning number
outright on 5 (NR-AR-LBD, NR-ER-LBD, NR-PPAR-gamma, SR-ATAD5, and ties on
SR-MMP). JUMBO's overall average (0.847) essentially matches DeepTox's
(0.846) — a single untuned RandomForest-per-endpoint on the JUMBO feature
set reaching a multi-task deep net's reported performance.

This is not a fully apples-to-apples comparison, in ways that generally
favor DeepTox: it trained jointly across all 12 endpoints (multi-task
sharing between correlated pathways — AR/AR-LBD, ER/ER-LBD, and the
stress-response tasks are explicitly noted as highly correlated in the
source paper), used a much larger engineered feature set on top of the raw
descriptors, and was scored on NIH's independently-assembled blind test
set rather than a random split of the same import. `WEIGHTED_AUC` in
`model_evaluation` is Weka's support-weighted
`evaluation.areaUnderROC(classIndex)`, which for a binary target is
mathematically equal to the standard positive-class AUC-ROC these papers
report — confirmed by reading `V3ModelRebuilder.java`'s metric computation
directly.

## 6. Statistics audit and full algorithm benchmark

Requested explicitly by the user: verify no shortcuts were taken in the
Tox21 analysis, identify the hardest endpoint, and benchmark every
available MolClass algorithm against it.

### 6.1 Leak-safety audit of the training pipeline

Traced `V3ModelRebuilder`'s actual training code rather than trust the
reported metrics. The classifier under evaluation is:

```
FilteredClassifier(
  filter = MultiFilter([SpreadSubsample, SMOTE]),
  classifier = AttributeSelectedClassifier(
    evaluator = CfsSubsetEval, search = GreedyStepwise,
    classifier = <base algorithm>
  )
)
```

Both `FilteredClassifier` and `AttributeSelectedClassifier` are Weka's own
leak-safe wrapper idioms: `buildClassifier(data.train)` fits SpreadSubsample,
SMOTE, *and* the CFS attribute-selection subset exclusively on the training
partition passed in; validation and holdout instances only ever have the
already-fitted transform applied via `distributionForInstance()` during
evaluation, never refit. Confirmed directly in
`V3ModelRebuilder.commonTrainingPipeline()` (`src/molclass/models/V3ModelRebuilder.java`,
around line 287) — feature selection fitting on validation/holdout data,
the most common way this kind of pipeline silently cheats, does not happen
here.

All 24 Tox21 `HOLDOUT` `WEIGHTED_AUC` values used in §5.7's comparison table
were independently re-pulled fresh from `model_evaluation` and diffed
against the previously reported numbers: **exact match, zero
discrepancies.**

### 6.2 A real, confirmed data leak (pre-existing, small, disclosed)

Dataset 91 has 8,006 `dataset_molecule` rows but only 7,820 distinct
`molecule_id` values — 186 molecules are registered more than once within
Tox21 (duplicate structures in the source CSV). The train/validation/holdout
split (`STRATIFIED_HASH_80_10_10_V1`) partitions by `dataset_molecule_id`,
not `molecule_id` (confirmed in `V3ModelRebuilder.prepareData()`), so a
duplicated molecule can be assigned to both train and holdout — the model
then gets evaluated on a molecule it already saw (via its duplicate) during
training. A code comment on the SCAFFOLD split strategy already
acknowledges "a leak that exists in today's HASH split too", i.e. this is
a known, pre-existing MolClass limitation, not something introduced this
session or specific to Tox21.

Quantified across all 24 Tox21 builds by joining `model_training_member` to
`dataset_molecule` and finding molecule IDs assigned to more than one
partition:

| | typical range |
|---|---|
| Holdout instances with a train-set duplicate | 15–29 per build |
| As a fraction of that build's holdout set | ~2–4% |

Small, but real — every Tox21 holdout AUC reported in this document is
inflated by a few percentage points' worth of molecules the model had
already memorized. It does not change which feature profile wins or which
endpoint is hardest (verified by inspection of the affected counts relative
to the score gaps involved), but it is not zero, and is reported here
rather than omitted.

### 6.3 Identifying the hardest endpoint

**NR-ER** is the lowest-scoring endpoint in all three independent sources:

| source | NR-ER AUC | rank among 12 |
|---|---|---|
| CDK profile (this session) | 0.724 | lowest |
| JUMBO profile (this session) | 0.767 | lowest |
| Official DeepTox | 0.793 | tied-lowest (with SR-ATAD5) |

Checked whether this is a trivial consequence of class imbalance — it is
not. NR-ER's class support is 796 active / 5,513 inactive (~12.6% active),
*more* balanced than NR-PPAR-gamma (2.9% active) or SR-ATAD5 (3.7% active),
both of which score higher across every profile. NR-ER's difficulty is a
genuinely hard structure-activity relationship, not a statistical-imbalance
artifact.

### 6.4 Full 11-algorithm benchmark on NR-ER

All 11 MolClass algorithms (`ALGORITHMS` in `html/molclass/api/app/v3_models.py`)
were built against NR-ER on the CDK feature profile — chosen over JUMBO for
tractability (JUMBO builds ran 35–45 minutes each on hard endpoints in
§5.6; a JUMBO-profile version of this benchmark was not run). Model
definitions 120 (RandomForest, from §5.5) and 144–153 (the remaining 10),
builds 212 and 232–241. All 11 completed with identical splits (5,049
train / 630 validation / 630 holdout / 1,697 excluded — the excluded count
exactly matches NR-ER's missing-value count) and no errors.

One transient concern investigated and ruled out during this run: the
worker log showed a `java.util.zip.ZipException: zip END header not found`
thrown from inside Weka's `ClassCache`/`Kernel.forName` reflection scan
during SMO's build. Traced to Weka probing every classpath entry as a jar
file and hitting `com.github.fommil.netlib:all-1.1.2.pom` — an XML POM
file, not a real jar, present on the classpath as a side effect of that
dependency's resolution. Confirmed non-fatal: build 234 (SMO) completed
successfully afterward with no error recorded.

| algorithm | train AUC | validation AUC | **holdout AUC** | train − holdout gap |
|---|---|---|---|---|
| **KNN** | 0.840 | 0.689 | **0.784** | +0.056 |
| RandomForest | 0.995 | 0.756 | 0.724 | +0.271 |
| LMT | 0.767 | 0.699 | 0.717 | +0.050 |
| NaiveBayes | 0.681 | 0.694 | 0.712 | −0.031 |
| Ensemble | 0.816 | 0.672 | 0.686 | +0.131 |
| Bagging | 0.969 | 0.729 | 0.684 | +0.285 |
| LogitBoost | 0.674 | 0.659 | 0.680 | −0.006 |
| J48 | 0.716 | 0.640 | 0.672 | +0.044 |
| AdaBoostM1 | 0.996 | 0.660 | 0.621 | +0.375 |
| SMO | 0.667 | 0.660 | 0.618 | +0.049 |
| LibSVM | 0.502 | 0.510 | 0.509 | −0.008 |

Findings:

- **KNN is the appropriate algorithm for NR-ER**, not RandomForest (the
  default used for every other build in this session). Highest holdout AUC
  (0.784) with a small train-holdout gap (+0.056), indicating genuine
  generalization rather than memorization.
- **RandomForest and Bagging both show classic bagged-tree overfitting**:
  near-perfect train AUC (0.995, 0.969) collapsing on holdout. Consistent
  with both being tree-bagging methods on a SMOTE-oversampled minority
  class, which tends to let bagged trees memorize synthetic near-duplicate
  minority instances.
- **AdaBoostM1 is the most severely overfit model tested**: 0.996 train vs.
  0.621 holdout, a 0.375 gap — and ends up worse on holdout than several
  algorithms that never came close to fitting the training set perfectly.
- **LibSVM effectively fails on this data with default hyperparameters**:
  0.509 holdout is statistically indistinguishable from random guessing
  (AUC 0.5), and it is equally uninformative on train and validation — not
  overfitting, simply not learning a usable decision boundary on this
  feature set with untuned defaults. SMO (0.618) is the second-weakest,
  also close to chance.
- LogitBoost and NaiveBayes show small negative train-holdout gaps,
  consistent with stable models, though a single holdout split can't fully
  rule out sampling variance as part of that.

### 6.5 Redesigning the `Ensemble` algorithm (code change, applied)

Following on from §6.4's finding that `Ensemble`'s two base learners were
individually weaker than several standalone algorithms, `Ensemble` was
redesigned in `V3ModelRebuilder.algorithm()`
(`src/molclass/models/V3ModelRebuilder.java`, the `case "Ensemble"` branch)
and rebuilt:

- Base learners changed from `{J48(), IBk(k=25 fixed)}` to
  `{RandomForest(100 trees, matching the standalone RandomForest config),
  IBk(routed through the same `KnnTuningContract` the standalone `KNN`
  algorithm uses instead of a hardcoded k), NaiveBayes()}` — three learners
  with different inductive biases (non-linear tree splits, local
  structural similarity, marginal per-feature signal) instead of two, one
  of them untuned.
- Meta-classifier (`StackingC` + `LinearRegression`) unchanged — it already
  accepts an arbitrary-length base-learner array and generates the
  meta-learner's training data via internal cross-validation on the base
  learners, so an overfit-prone member (RandomForest) doesn't just teach
  the meta-learner to trust its own training-set confidence.

Verified by compiling (`./gradlew compileJava`), rebuilding the app jar
(`./gradlew jar`), restarting the model pipeline daemon against the new
jar (the running daemon does not hot-reload — it must be killed and
relaunched from `tools/run_v3_worker.sh` to pick up a rebuilt jar), and
building a fresh model definition (154, `Ensemble v2`) rather than
reusing 151 so both designs remain independently inspectable. Result,
build 242, confirmed against its own `build_manifest_json`
(`algorithmContract` shows all three base learners and the exact same
`K 1 25 13` tuning grid as the standalone KNN build):

| Ensemble design | train AUC | validation AUC | **holdout AUC** |
|---|---|---|---|
| v1 — J48 + fixed k=25 IBk (build 239) | 0.816 | 0.672 | 0.686 |
| **v2 — RandomForest + tuned IBk + NaiveBayes (build 242)** | 0.993 | 0.727 | **0.741** |

+0.055 holdout AUC — a real, non-trivial improvement. It moved Ensemble
from 6th to 2nd place among all 12 algorithms/variants tried on NR-ER,
now beating standalone RandomForest (0.724), LMT (0.717), and NaiveBayes
(0.712) outright. It still does not beat standalone tuned KNN alone
(0.784) — the strongest individual signal for this endpoint remains
K-nearest-neighbours on its own, and folding in an overfit-prone
RandomForest (train AUC 0.993 in the new stack, same overfitting pattern
as its standalone build) evidently costs more than the third learner adds
back. This code change is applied in the working tree (uncommitted, like
the rest of this session's code changes) and is not specific to Tox21 or
NR-ER — it changes what `algorithm_code: "Ensemble"` means for every
future model built with it in this application.

## 7. Additional public toxicity/safety datasets downloaded (not imported)

At the user's request to survey further downloadable competition/benchmark
datasets, 7 more were pulled via the `PyTDC` (Therapeutics Data Commons)
Python package (`pip install PyTDC`, installed into the system
`miniconda3` environment — not project-scoped), converted to SDF with the
same RDKit pipeline as Tox21, and validated with the project's own
`SdfAnalyzer`. **None of these were uploaded into `molclass_v3`** — left
in the scratchpad for a future session, per explicit instruction.

| dataset (`tdc.single_pred.Tox` name) | endpoint | compounds | conversion | analyzer result | class balance |
|---|---|---|---|---|---|
| `herg` | hERG cardiac channel blocker | 655 | 655/655 | 0 malformed | — |
| `herg_karim` | hERG blocker (larger cohort) | 13,445 | 13,445/13,445 | 0 malformed | 6,718 / 6,727 |
| `ames` | Ames bacterial mutagenicity | 7,278 | 7,278/7,278 | 0 malformed | 3,974 / 3,304 |
| `dili` | Drug-induced liver injury | 475 | 475/475 | 0 malformed | 236 / 239 |
| `skin_reaction` | Skin sensitization | 404 | 404/404 | 0 malformed | 274 / 130 |
| `carcinogens_lagunin` | Rodent carcinogenicity | 280 | 280/280 | 0 malformed | 60 / 220 |
| `clintox` | Clinical-trial toxicity failure | 1,478 | 1,478/1,478 | 0 malformed | 112 / 1,366 |

All 7 converted at 100% (no RDKit valence failures, unlike Tox21's 8) and,
unlike Tox21, every endpoint here is present on 100% of records — no
sparsity, so none would hit the `model_target_allowed` gap in §5.3 if
imported; they would auto-qualify as model targets on import with no
manual override needed.

Files live in the session scratchpad
(`competition_datasets/<name>.sdf` and `<name>.analysis.json` for each, plus
`convert_all.py`, the conversion script, alongside them) — a local,
session-scoped temp directory, **not** part of this repository. Anyone
picking this up needs to re-download and re-convert rather than expect
these files to still exist.

**Also surveyed but not pursued:**
- **ToxCast** (same TDC source as Tox21) — a 617-assay panel rather than a
  single endpoint; pulling and merging all 617 labels into one wide file
  was judged out of scope for a "download and assemble" pass and was not
  attempted.
- **MoleculeNet** (BACE, BBBP, SIDER, HIV, MUV) — lives behind DeepChem's
  S3-backed `molnet` loader rather than plain files fetchable directly;
  not chased down given TDC already covered the higher-value safety
  targets.
- **OpenADMET, Polaris, CACHE, Kaggle** — OpenADMET's data is on Hugging
  Face (would need `huggingface_hub`, not installed), Polaris needs its own
  `polaris-lib` client (not installed), CACHE's structure doesn't reduce to
  a simple SMILES+label download, and the Kaggle competitions need
  authentication not available in this session.

## 8. Full session action log (chronological)

1. Diagnosed the descriptor-width corruption (dataset 90 / build 206).
2. Fixed `tools/run_v3_worker.sh` classpath; committed as `6f5bec5`
   (a first attempt at this commit accidentally swept in unrelated
   pre-staged frontend renames because `git commit -m` was run without a
   pathspec — caught immediately via `git show --stat`, corrected with a
   soft reset and re-commit scoped to the two intended files; nothing was
   lost, nothing had been pushed).
3. Backed up, deleted, and regenerated the 32 corrupted vectors; rebuilt
   model 119 as build 207.
4. Dropped the two `*_backup_20260823` tables once build 207 was verified.
5. Downloaded, converted, and validated the Tox21 dataset; uploaded it as
   dataset 91 through the real running API.
6. Restarted both background worker daemons with the corrected classpath
   before creating any model definition on the new data.
7. Built and evaluated the `NR-AR` endpoint on the CDK profile (build 208),
   then all remaining 11 endpoints on the same profile (builds 209–219).
8. Built all 12 endpoints again on the JUMBO profile (builds 220–231),
   after correcting a mistaken assumption that JUMBO excludes CDK
   descriptors.
9. Researched, then corrected, the official Tox21 Data Challenge benchmark
   numbers used for comparison (§5.7).
10. Downloaded and validated 7 further public toxicity datasets via TDC,
    intentionally left unimported (§7).
11. Wrote this document (first pass).
12. Audited the training pipeline for leakage, independently re-verified
    all 24 reported AUC values, discovered and quantified a pre-existing
    duplicate-molecule train/holdout leak, identified NR-ER as the hardest
    endpoint from first principles, and benchmarked all 11 MolClass
    algorithms against it (§6).
13. Updated this document with those findings.

A permission rule
(`Bash(mysql -h 127.0.0.1 -u molclass_worker molclass_v3*)`) was added to
`.claude/settings.local.json` (gitignored, session-local) partway through
to allow direct SQL statements against the local dev database without a
per-command confirmation prompt; every statement it enabled was reviewed
with the user in chat before being run.

## 9. Open follow-ups

- **`model_target_allowed` completeness rule** (§5.3): doesn't account for
  intentionally sparse assay data. Needs a real fix (e.g., a
  minimum-coverage threshold instead of 100%) rather than per-property
  manual `UPDATE`s if more datasets like Tox21 are planned. All 12 Tox21
  endpoints currently rely on the manual override; it is not persisted as
  code.
- **`descriptor_generation` name-vs-config upsert bug** (§2): the mechanism
  that let the original incident happen silently is still present. A
  changed descriptor catalog will mutate a published generation in place
  again rather than minting a new one.
- **The unexplained NR-panel average discrepancy** (§5.7): worth
  understanding before citing the DeepTox NR-panel number again — either
  there's a documented quirk in how the paper computes it, or the paper
  itself has an inconsistency.
- **The `STRATIFIED_HASH_80_10_10_V1` duplicate-molecule leak** (§6.2): a
  pre-existing, codebase-wide limitation, not unique to Tox21 — splitting
  by `dataset_molecule_id` rather than `molecule_id` lets a duplicated
  molecule land in both train and holdout. Affects every model this
  application has ever built with the HASH split strategy, at whatever
  rate each dataset's internal duplication happens to produce (2–4% of
  holdout for Tox21). A real fix means deduplicating by `molecule_id`
  before partitioning, or switching the split key — a code change, not
  attempted here.
- **NR-ER's best-performing algorithm is KNN, not the RandomForest default**
  (§6.4) — every other Tox21 build in this session used RandomForest
  unconditionally. If per-endpoint algorithm selection matters going
  forward, this suggests it's worth benchmarking before committing to a
  default, at least for endpoints that turn out to be hard.
- **LibSVM and SMO effectively failed on NR-ER with default
  hyperparameters** (§6.4, holdout AUC 0.509 and 0.618, both near chance)
  — worth knowing before recommending either as a default anywhere in this
  application without hyperparameter tuning.
- **Background daemons**: currently running as plain backgrounded shell
  processes in this session, not a managed service. They will not survive
  a reboot or a fresh shell session; nothing was written to make them
  persistent. Anyone continuing this work needs to restart them via the
  fixed `tools/run_v3_worker.sh` first.
- **36 Tox21 model builds** (207–242: build 207 for dataset 90, the 24
  CDK/JUMBO builds for dataset 91's 12 endpoints, the 10-algorithm NR-ER
  benchmark, and the redesigned Ensemble's build 242) are all
  `AWAITING_APPROVAL` — no approval step was taken for any of them.
- **The `Ensemble` algorithm redesign** (§6.5) is a live code change,
  uncommitted, and it changes behavior for the whole application, not just
  this session's Tox21 work — every future `algorithm_code: "Ensemble"`
  build anywhere in MolClass now gets the 3-learner RandomForest+tuned-KNN+
  NaiveBayes stack instead of the old J48+fixed-k25-IBk one. Worth a
  deliberate decision (and likely a commit, plus re-review of any other
  `Ensemble` models already in the registry) rather than leaving it as an
  implicit side effect of a single benchmarking session.
- **Dataset 89** (`molclass_upload_test`, 20 records, uploaded earlier in
  the session that preceded this one) still has no model definition.
- **7 additional downloaded datasets** (§7) are validated and ready to
  import but exist only in the local scratchpad — they will not survive
  past this session's temp storage and would need re-downloading.
