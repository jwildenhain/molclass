# MolClass v3 prediction API

The Spring Boot predictor exposes only human-published v3 builds under `/api/v3`.

- `GET /api/v3/models?query=&limit=100` searches published model names, algorithms, profiles, and legacy IDs. Each result carries `holdoutAccuracy`, `holdoutAuc` (`WEIGHTED_AUC`), and `holdoutF1` (`WEIGHTED_F1`) pulled from `model_evaluation` for that build's `HOLDOUT` set; any metric missing for a build (e.g. a degenerate confusion matrix) comes back `null` rather than a synthetic 0.
- `GET /api/v3/molecules?query=&limit=25` performs indexed exact lookup by molecule ID, InChIKey, SDF source identifier, or canonical SMILES, then falls back to a primary-name prefix.
- `GET /api/v3/molecules/{moleculeId}` returns molecule identity and scaffold information.
- `GET /api/v3/molecules/{moleculeId}/predictions?limit=200` returns saved prediction history without starting jobs (default limit 50, maximum 200, newest first).
- `POST /api/v3/models/{modelDefinitionId}/molecules/{moleculeId}/predict` predicts one existing v3 molecule.

The model loader follows `model_definition.published_model_build_id`, requires the production generation label and `PUBLISHED` status, checks artifact format/size/SHA-256, applies a Java deserialization allowlist, and decompresses MODEL and HEADER artifacts. Models load lazily into an access-ordered cache (four by default), replacing the prior eager loading of hundreds of classpath artifacts.

Prediction reconstructs features from `molecule_descriptor_vector`, `molecule_fingerprint`, and ordered `feature_profile_component` rows. Every header name and vector length must match the approved model; missing or failed features reject the request rather than being imputed silently.

Required environment variables are `MOLCLASS_DB_USER` and `MOLCLASS_DB_PASSWORD`. The JDBC URL, v3 schema, port, cache size, and artifact limit are configurable with the `MOLCLASS_*` variables in `application.properties`. Legacy classpath models are disabled and excluded from the Boot JAR by default; enable loading with `MOLCLASS_LEGACY_MODELS_ENABLED=true` and bundle them only with `-PbundleLegacyModels`.


## Reusing saved results

History records include `predictionJobId`, `modelBuildId`, `modelDefinitionId`,
`modelName`, `algorithm`, `predictedClass`, `distribution`, `confidenceScore`,
`applicabilityScore`, `inApplicabilityDomain`, and `createdAt`. The history endpoint
may return repeated predictions for a build; retain its newest entry for display.
It does not currently return `trainingScaffoldCount` or pagination metadata.

To offer only missing predictions, compare each saved `modelBuildId` with the
currently published build returned by `GET /api/v3/models`. Match by molecule and
build, not model name or definition ID alone: publication can change the active
build. Do not treat a failed history request or a response at the 200-record cap
as proof that a prediction is absent. A successful new result can be added to the
visible collection immediately, without waiting for other model requests.

The synchronous POST response calls confidence `responseStrength`; history calls
it `confidenceScore`. Both can be mapped to the same displayed confidence, with
class probabilities taken from `distribution`. Preserve null applicability values
as unknown rather than converting them to an out-of-domain result.

ChemGrid can retrieve this information server-side through the same endpoints.
Reading history does not submit a prediction or consume the ad-hoc registration
quota. For a structure not yet indexed, use the authenticated registration and
missing-model workflow documented in [ad-hoc prediction](V3_ADHOC_PREDICTION.md).
The ChemGrid tab is a separate consumer; its cache/display logic must explicitly
fetch or synchronize these results.
