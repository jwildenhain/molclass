# V3 model creation contract

## Purpose

The v3 model workflow creates immutable training intent before any Weka build starts. It does
not expose legacy batch IDs, arbitrary class tags, unsupported classifiers, or unpublished
artifacts.

## API sequence

1. `GET /api/v1/model-datasets` lists datasets marked model-eligible with selected WIDE target
   properties containing 2 to 100 distinct nonblank values.
2. `GET /api/v1/model-datasets/{datasetId}` returns target class labels and support counts.
3. `GET /api/v1/model-options` returns READY CDK feature profiles and the supported Weka and
   feature-selection allowlists.
4. `POST /api/v1/model-definitions` validates the dataset, target, class support, positive class,
   profile, algorithm, cross-validation policy, build timeout, operator, and partial-dataset
   acknowledgement before inserting an audited `PENDING_REBUILD` definition.

A model name is unique among non-retired definitions for the same dataset and target. Duplicate
requests return HTTP 409 with the existing definition ID and status.

## Worker sequence

The supervised model pipeline checks for pending definitions. It generates model-scoped CDK
features only when component rows are absent and then rebuilds one definition with the configured
Weka worker count. Child feature and model processes have separate hard timeouts. Database named
locks prevent concurrent feature, model, or pipeline owners. The model worker reads the persisted
definition policy; explicit `--cv-folds` and `--model-timeout-seconds` values remain administrative
overrides. A timed-out build is recorded as `REBUILD_FAILED` with `BUILD_TIMEOUT`, and the queue
continues with the next definition.

A successful build is stored as `AWAITING_APPROVAL`. Evaluation, rejection, approval, and
publication remain separate audited actions. No API or worker in this workflow auto-approves a
model.

## Supported model options

Algorithms exposed by the API are RandomForest, J48, NaiveBayes, SMO, KNN, LibSVM, LMT,
LogitBoost, and Ensemble. Feature selection is restricted to CfsSubsetEval,
ReliefFAttributeEval, or None. Profiles are read from `feature_profile` and must have a status
beginning with `READY`.

The positive class must be one of the observed labels. At least 30 labeled records and at least
5 records per class are required. Partial imports require explicit operator acknowledgement.

Cross-validation may remain automatic or be forced from 2 through 10 folds. Automatic mode runs
cross-validation when validation or holdout class support is too sparse for reliable aggregate
statistics. Build timeout values are bounded from 60 seconds through 24 hours and default to two
hours. Both values are visible on the model-review page and are included in the creation audit.
# Feature-profile selection

`ALL` is the default for new definitions because the current 3,856-feature
contract has completed builds and is available with explicit molecule exclusions.
The model-options API returns only the latest active sealed revision for each code,
including its contract SHA-256, raw feature count, and coverage. Selecting a
profile fixes that exact revision on the immutable model definition.
