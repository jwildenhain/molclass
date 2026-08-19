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
   profile, algorithm, operator, and partial-dataset acknowledgement before inserting an audited
   `PENDING_REBUILD` definition.

A model name is unique among non-retired definitions for the same dataset and target. Duplicate
requests return HTTP 409 with the existing definition ID and status.

## Worker sequence

The supervised model pipeline checks for pending definitions. It generates model-scoped CDK
features only when component rows are absent and then rebuilds one definition with the configured
Weka worker count. Child feature and model processes have separate hard timeouts. Database named
locks prevent concurrent feature, model, or pipeline owners.

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
