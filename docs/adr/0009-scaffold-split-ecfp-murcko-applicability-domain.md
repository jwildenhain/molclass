# ADR 0009: Scaffold-stratified splitting, ECFP fingerprint, Murcko applicability domain

## Status

Accepted

## Context

`STRATIFIED_HASH_80_10_10_V1` (ADR 0004) stratifies by class label but has no
awareness of molecular structure. Molecules sharing a Bemis-Murcko scaffold
(near-duplicates differing only in substituents) can land on both sides of a
split, inflating apparent validation/holdout performance relative to true
generalization. Separately, the feature-generation pipeline had no circular
(ECFP/Morgan-style) fingerprint, and applicability-domain scoring for a
prediction had no structural basis for judging whether a query molecule
resembles what a model was actually trained on.

While building this, two independent, real CDK/data defects were found and
fixed, both of which had been silently degrading data quality:

- `MurckoFragmenter.scaffold()`'s output requires re-perceiving atom types,
  implicit hydrogens, and aromaticity -- and that re-perception must run
  `Kekulization.kekulize()` **before** `Aromaticity.apply()`, not after,
  in both the whole-molecule and post-fragmentation cases. Getting this
  backwards (the natural-looking order) leaves some fragments with an
  aromatic bond flagged but never given a concrete order, which fails much
  later and far from the actual cause ("Unsupported bond order: UNSET").
- A subset of registry molfiles (traced to a "SciTegic" export tool) write
  atom-block lines as whitespace-*delimited* trailing tokens instead of
  MDL's required fixed-*width* columns. CDK's strict column reader rejects
  these outright. Re-tokenizing and re-emitting in true fixed-width form
  recovered all 136 real molecules affected by this in the live database;
  a further 6 have a genuinely empty atom block with no SMILES fallback and
  are excluded, not fixable.

## Decision

- Add `MurckoScaffoldCore` (`src/molclass/models/`), a shared, connection-
  per-call, Spring-free scaffold computation core used both by training-time
  splitting and by `spring_boot_predictor`'s `MurckoScaffoldService` (which
  now delegates to it) for prediction-time applicability scoring -- one
  implementation of the Kekulize-before-Aromaticity fix, not two.
- Add `--split-strategy SCAFFOLD` to `V3ModelRebuilder`, gated opt-in
  (`HASH` remains the default, byte-for-byte unchanged). Within each
  class-label group, candidates are further grouped by Murcko scaffold
  (or a per-molecule singleton group for acyclic/unscaffoldable molecules,
  keyed by `molecule_id` rather than `dataset_molecule_id` -- which also
  closes a leak where the same registered molecule could appear under two
  `dataset_molecule_id` rows in one dataset and land on both sides of a
  HASH split). Whole scaffold groups, never split, are greedily assigned to
  VALIDATION then HOLDOUT (largest groups first, DeepChem `ScaffoldSplitter`
  -style), remainder to TRAIN. A class-label with fewer than 3 distinct
  scaffold groups falls back to the HASH per-instance split for that label
  specifically, recorded in the build manifest (`scaffoldFallbackLabels`).
- Record `STRATIFIED_SCAFFOLD_80_10_10_V1` in the build manifest and
  `split_strategy` column when used, alongside `scaffoldGenerationVersion`
  for traceability against future `MurckoScaffoldCore` fixes.
- Add `ECFP` (CDK `CircularFingerprinter`, `CLASS_ECFP4`, folded to 1024
  bits) to `V3FeatureGenerator`, included in the `ALL` and `JUMBO` feature
  profiles only.
- Integrate Murcko scaffold computation into `V3FeatureGenerator`'s main
  molecule loop (sequential, same `write` connection as the rest of the
  batch, committed per molecule rather than batched -- scaffold computation
  is cheap and a multi-hour full-database run should not lose an
  in-progress batch's scaffolds to an interrupted run). A single molecule's
  scaffold failure is caught, logged, and counted, never aborting the job.
- Add `Bagging` and `AdaBoostM1` (both wrapping `J48`) as algorithm options,
  and generalize `DeterministicParallelSmote` from a single hardcoded model
  definition ID to a configurable training-set-size threshold
  (`--parallel-smote-min-instances`, default 5000) so any sufficiently large
  training set benefits, not just the one it was originally built for.

## Consequences

Scaffold-stratified splitting is a stricter, more honest generalization
test: it is expected and normal for measured kappa to *decrease* for some
models under it, revealing leakage-driven overfitting the HASH split was
masking, not a regression. Live experiments on 4 low-kappa candidates
confirmed both directions -- one candidate's holdout kappa went from -0.26
to +0.25 (the HASH split was unfairly penalizing real signal), another's
went from -0.04 to -0.07 (HASH was masking real overfitting). Rebuilding
published `ALL`/`JUMBO` models to pick up `ECFP` showed the same duality on
a specific model, whose holdout kappa dropped substantially (0.54 to 0.19)
even though holdout AUC *improved* (0.75 to 0.84) -- consistent with ECFP
adding 1024 dimensions to the feature space that `SpreadSubsample`+`SMOTE`
resample in (before `CfsSubsetEval` ever narrows it down), shifting the
decision threshold rather than the model's underlying discrimination.
Published-model rebuilds are reviewed individually on their own merits, not
assumed to be improvements.
