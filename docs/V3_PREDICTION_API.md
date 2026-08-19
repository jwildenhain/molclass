# MolClass v3 prediction API

The Spring Boot predictor exposes only human-published v3 builds under `/api/v3`.

- `GET /api/v3/models?query=&limit=100` searches published model names, algorithms, profiles, and legacy IDs.
- `GET /api/v3/molecules?query=&limit=25` performs indexed exact lookup by molecule ID, InChIKey, SDF source identifier, or canonical SMILES, then falls back to a primary-name prefix.
- `POST /api/v3/models/{modelDefinitionId}/molecules/{moleculeId}/predict` predicts one existing v3 molecule.

The model loader follows `model_definition.published_model_build_id`, requires the production generation label and `PUBLISHED` status, checks artifact format/size/SHA-256, applies a Java deserialization allowlist, and decompresses MODEL and HEADER artifacts. Models load lazily into an access-ordered cache (four by default), replacing the prior eager loading of hundreds of classpath artifacts.

Prediction reconstructs features from `molecule_descriptor_vector`, `molecule_fingerprint`, and ordered `feature_profile_component` rows. Every header name and vector length must match the approved model; missing or failed features reject the request rather than being imputed silently.

Required environment variables are `MOLCLASS_DB_USER` and `MOLCLASS_DB_PASSWORD`. The JDBC URL, v3 schema, port, cache size, and artifact limit are configurable with the `MOLCLASS_*` variables in `application.properties`. Legacy classpath models are disabled and excluded from the Boot JAR by default; enable loading with `MOLCLASS_LEGACY_MODELS_ENABLED=true` and bundle them only with `-PbundleLegacyModels`.
