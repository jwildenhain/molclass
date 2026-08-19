# MolClass v3 model rebuild

`V3ModelRebuilder` processes migrated definitions sequentially after their feature
profile is ready. It builds sparse Weka 3.8.7 datasets from regenerated CDK 2.12
features and never reads legacy serialized models or feature values.

Each build records a deterministic hash-based 80/10/10 train, validation, and
holdout split plus every excluded dataset record. It stores class support, accuracy,
kappa, weighted precision, recall, F1 and AUC, checksummed model/header artifacts,
and the exact runtime contract. Successful builds stop at `AWAITING_APPROVAL`.

Supported legacy classifier codes preserve the options recovered from the compiled
legacy builder. `Ensemble2` is explicitly `UNSUPPORTED_CONFIGURATION` because its
implementation is absent; it is not replaced by a different classifier.

```bash
mysql -u "$MOLCLASS_DB_USER" -p < sql/v3/V4__model_rebuild_constraints.sql
./gradlew rebuildV3Models -PmodelArgs="--model-id 1"
./gradlew rebuildV3Models -PmodelArgs="--limit 10"
./gradlew rebuildV3Models
```
