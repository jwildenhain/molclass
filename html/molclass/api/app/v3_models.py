import json
import re
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.database import get_v3_db


router = APIRouter(prefix="/api/v1", tags=["v3 model creation"])
SAFE_COLUMN = re.compile(r"[A-Za-z0-9_]+")

ALGORITHMS = (
    {"code": "RandomForest", "name": "Random Forest", "description": "Stable parallel tree ensemble; recommended baseline."},
    {"code": "J48", "name": "J48", "description": "Interpretable C4.5 decision tree with parameter selection."},
    {"code": "NaiveBayes", "name": "Naive Bayes", "description": "Fast probabilistic baseline."},
    {"code": "SMO", "name": "SMO", "description": "Support vector classifier with calibrated probabilities."},
    {"code": "KNN", "name": "K-nearest neighbours", "description": "Distance-based classifier with parameter selection."},
    {"code": "LibSVM", "name": "LibSVM", "description": "Kernel SVM for nonlinear class boundaries."},
    {"code": "LMT", "name": "Logistic model tree", "description": "Logistic regression models arranged in a decision tree."},
    {"code": "LogitBoost", "name": "LogitBoost", "description": "Boosted additive logistic regression."},
    {"code": "Ensemble", "name": "Stacked ensemble", "description": "Legacy-compatible J48 and KNN stacking."},
    {"code": "Bagging", "name": "Bagged J48", "description": "Bootstrap-aggregated J48 trees; variance reduction on a standard tree base."},
    {"code": "AdaBoostM1", "name": "AdaBoost.M1 (J48)", "description": "Boosted J48 trees using the classic AdaBoost.M1 algorithm."},
)
ALGORITHM_CODES = {item["code"] for item in ALGORITHMS}

FEATURE_SELECTIONS = (
    {"code": "CfsSubsetEval", "name": "CFS subset", "description": "Correlation-based subset selection; recommended baseline."},
    {"code": "ReliefFAttributeEval", "name": "ReliefF", "description": "Rank features by local class separation."},
    {"code": "None", "name": "No selection", "description": "Train on every feature in the selected profile."},
)
FEATURE_SELECTION_CODES = {item["code"] for item in FEATURE_SELECTIONS}


class ModelNameUpdateRequest(BaseModel):
    model_name: str = Field(min_length=1, max_length=255)


class ModelDefinitionRequest(BaseModel):
    dataset_id: int = Field(gt=0)
    target_property_id: int = Field(gt=0)
    feature_profile_id: int = Field(gt=0)
    model_name: str = Field(min_length=1, max_length=255)
    algorithm_code: str = Field(min_length=1, max_length=64)
    feature_selection_code: str = Field(min_length=1, max_length=64)
    positive_class_label: str = Field(min_length=1, max_length=255)
    partial_dataset_acknowledged: bool = False
    created_by: str = Field(default="web-operator", min_length=1, max_length=255)


def _target_rows(db: Session, dataset_id: Optional[int] = None):
    where = "" if dataset_id is None else " AND d.dataset_id=:dataset_id"
    return db.execute(
        text(
            """
            SELECT d.dataset_id, d.name, d.original_filename, d.description, d.status,
                   d.imported_records, d.failed_records, d.not_processed_records,
                   d.partial_acknowledgement_required, d.created_by, d.created_at,
                   dp.property_id, pd.original_name, pd.physical_column_name,
                   pd.storage_mode, pd.sql_type_ddl, dp.present_count,
                   dp.blank_count, dp.distinct_count
              FROM dataset d
              JOIN dataset_property dp ON dp.dataset_id=d.dataset_id
              JOIN property_definition pd ON pd.property_id=dp.property_id
             WHERE d.model_eligible=1
               AND d.status IN ('MIGRATED','READY','PARTIAL')
               AND dp.selected_for_import=1
               AND dp.model_target_allowed=1
               AND pd.active=1
               AND pd.storage_mode='WIDE'
               AND dp.distinct_count BETWEEN 2 AND 100
            """ + where + " ORDER BY d.dataset_id DESC,pd.original_name"
        ),
        {} if dataset_id is None else {"dataset_id": dataset_id},
    ).mappings().all()


def _class_labels(db: Session, dataset_id: int, physical_column: str):
    if not SAFE_COLUMN.fullmatch(physical_column):
        raise HTTPException(status_code=500, detail={"code": "UNSAFE_PROPERTY_COLUMN"})
    sql = text(
        f"""
        SELECT TRIM(CAST(dmp.`{physical_column}` AS CHAR)) AS class_label,
               COUNT(*) AS support_count
          FROM dataset_molecule dm
          JOIN dataset_molecule_properties dmp
            ON dmp.dataset_molecule_id=dm.dataset_molecule_id
         WHERE dm.dataset_id=:dataset_id
           AND dmp.`{physical_column}` IS NOT NULL
           AND TRIM(CAST(dmp.`{physical_column}` AS CHAR))<>''
         GROUP BY TRIM(CAST(dmp.`{physical_column}` AS CHAR))
         ORDER BY support_count DESC,class_label
         LIMIT 101
        """
    )
    rows = db.execute(sql, {"dataset_id": dataset_id}).mappings().all()
    if len(rows) > 100:
        raise HTTPException(status_code=422, detail={"code": "TOO_MANY_TARGET_CLASSES"})
    return [
        {"label": row["class_label"], "supportCount": row["support_count"]}
        for row in rows
    ]


def _dataset_payload(db: Session, rows):
    datasets = []
    current = None
    for row in rows:
        if current is None or current["datasetId"] != row["dataset_id"]:
            current = {
                "datasetId": row["dataset_id"],
                "name": row["name"],
                "originalFilename": row["original_filename"],
                "description": row["description"],
                "status": row["status"],
                "importedRecords": row["imported_records"],
                "failedRecords": row["failed_records"],
                "notProcessedRecords": row["not_processed_records"],
                "partialAcknowledgementRequired": bool(row["partial_acknowledgement_required"]),
                "createdBy": row["created_by"],
                "createdAt": row["created_at"],
                "targets": [],
            }
            datasets.append(current)
        current["targets"].append(
            {
                "propertyId": row["property_id"],
                "name": row["original_name"],
                "sqlType": row["sql_type_ddl"],
                "presentCount": row["present_count"],
                "blankCount": row["blank_count"],
                "distinctCount": row["distinct_count"],
            }
        )
    return datasets


@router.get("/model-options")
def model_options(db: Session = Depends(get_v3_db)):
    profiles = db.execute(
        text(
            """
            SELECT feature_profile_id,profile_code,profile_version,description,status
              FROM feature_profile
             WHERE status LIKE 'READY%'
             ORDER BY profile_code,feature_profile_id
            """
        )
    ).mappings().all()
    return {
        "algorithms": list(ALGORITHMS),
        "featureSelections": list(FEATURE_SELECTIONS),
        "featureProfiles": [
            {
                "featureProfileId": row["feature_profile_id"],
                "code": row["profile_code"],
                "version": row["profile_version"],
                "description": row["description"],
                "status": row["status"],
            }
            for row in profiles
        ],
    }


@router.get("/model-datasets")
def model_datasets(db: Session = Depends(get_v3_db)):
    return {"datasets": _dataset_payload(db, _target_rows(db))}


@router.get("/model-datasets/{dataset_id}")
def model_dataset(dataset_id: int, db: Session = Depends(get_v3_db)):
    rows = _target_rows(db, dataset_id)
    datasets = _dataset_payload(db, rows)
    if not datasets:
        raise HTTPException(status_code=404, detail={"code": "MODEL_DATASET_NOT_FOUND"})
    dataset = datasets[0]
    by_id = {row["property_id"]: row for row in rows}
    for target in dataset["targets"]:
        source = by_id[target["propertyId"]]
        target["classLabels"] = _class_labels(db, dataset_id, source["physical_column_name"])
    return dataset


@router.post("/model-definitions", status_code=status.HTTP_201_CREATED)
def create_model_definition(
    request: ModelDefinitionRequest,
    db: Session = Depends(get_v3_db),
):
    if request.algorithm_code not in ALGORITHM_CODES:
        raise HTTPException(status_code=422, detail={"code": "UNSUPPORTED_ALGORITHM"})
    if request.feature_selection_code not in FEATURE_SELECTION_CODES:
        raise HTTPException(status_code=422, detail={"code": "UNSUPPORTED_FEATURE_SELECTION"})

    target = db.execute(
        text(
            """
            SELECT d.name AS dataset_name,d.status AS dataset_status,d.model_eligible,
                   d.partial_acknowledgement_required,pd.original_name,
                   pd.physical_column_name,pd.storage_mode,dp.distinct_count,
                   fp.profile_code,fp.profile_version,fp.status AS profile_status
              FROM dataset d
              JOIN dataset_property dp
                ON dp.dataset_id=d.dataset_id AND dp.property_id=:target_property_id
              JOIN property_definition pd ON pd.property_id=dp.property_id
              JOIN feature_profile fp ON fp.feature_profile_id=:feature_profile_id
             WHERE d.dataset_id=:dataset_id
               AND dp.selected_for_import=1
               AND dp.model_target_allowed=1
               AND pd.active=1
            """
        ),
        {
            "dataset_id": request.dataset_id,
            "target_property_id": request.target_property_id,
            "feature_profile_id": request.feature_profile_id,
        },
    ).mappings().first()
    if not target:
        raise HTTPException(status_code=422, detail={"code": "INVALID_MODEL_TARGET"})
    if not target["model_eligible"] or target["dataset_status"] not in {"MIGRATED", "READY", "PARTIAL"}:
        raise HTTPException(status_code=409, detail={"code": "DATASET_NOT_MODEL_ELIGIBLE"})
    if target["partial_acknowledgement_required"] and not request.partial_dataset_acknowledged:
        raise HTTPException(status_code=422, detail={"code": "PARTIAL_DATASET_ACKNOWLEDGEMENT_REQUIRED"})
    if target["storage_mode"] != "WIDE" or not 2 <= target["distinct_count"] <= 100:
        raise HTTPException(status_code=422, detail={"code": "UNSUPPORTED_MODEL_TARGET"})
    if not target["profile_status"].startswith("READY"):
        raise HTTPException(status_code=409, detail={"code": "FEATURE_PROFILE_NOT_READY"})

    labels = _class_labels(db, request.dataset_id, target["physical_column_name"])
    label_names = [item["label"] for item in labels]
    if len(label_names) < 2:
        raise HTTPException(status_code=422, detail={"code": "TARGET_REQUIRES_TWO_CLASSES"})
    if request.positive_class_label not in label_names:
        raise HTTPException(status_code=422, detail={"code": "POSITIVE_CLASS_NOT_FOUND"})
    if sum(item["supportCount"] for item in labels) < 30 or min(item["supportCount"] for item in labels) < 5:
        raise HTTPException(status_code=422, detail={"code": "INSUFFICIENT_CLASS_SUPPORT"})

    declared_labels = sorted(label for label in label_names if label != request.positive_class_label)
    declared_labels.append(request.positive_class_label)
    duplicate = db.execute(
        text(
            """
            SELECT model_definition_id,status
              FROM model_definition
             WHERE dataset_id=:dataset_id
               AND target_property_id=:target_property_id
               AND model_name=:model_name
               AND status NOT IN ('REJECTED','RETIRED')
             ORDER BY model_definition_id DESC
             LIMIT 1
            """
        ),
        {
            "dataset_id": request.dataset_id,
            "target_property_id": request.target_property_id,
            "model_name": request.model_name.strip(),
        },
    ).mappings().first()
    if duplicate:
        raise HTTPException(
            status_code=409,
            detail={
                "code": "MODEL_DEFINITION_ALREADY_EXISTS",
                "modelDefinitionId": duplicate["model_definition_id"],
                "status": duplicate["status"],
            },
        )

    metadata = {
        "createdThrough": "v3-model-api",
        "datasetName": target["dataset_name"],
        "targetProperty": target["original_name"],
        "featureProfile": target["profile_code"],
        "featureProfileVersion": target["profile_version"],
        "classSupport": labels,
    }
    try:
        db.execute(
            text(
                """
                INSERT INTO model_definition
                    (legacy_model_id,dataset_id,target_property_id,feature_profile_id,
                     model_name,algorithm_code,algorithm_options_json,
                     feature_selection_code,feature_selection_options_json,
                     positive_class_label,declared_class_labels_json,status,
                     published_model_build_id,created_by,definition_metadata_json,
                     created_at,updated_at)
                VALUES
                    (NULL,:dataset_id,:target_property_id,:feature_profile_id,
                     :model_name,:algorithm_code,'{}',:feature_selection_code,'{}',
                     :positive_class_label,:declared_labels,'PENDING_REBUILD',
                     NULL,:created_by,:metadata,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """
            ),
            {
                "dataset_id": request.dataset_id,
                "target_property_id": request.target_property_id,
                "feature_profile_id": request.feature_profile_id,
                "model_name": request.model_name.strip(),
                "algorithm_code": request.algorithm_code,
                "feature_selection_code": request.feature_selection_code,
                "positive_class_label": request.positive_class_label,
                "declared_labels": json.dumps(declared_labels, separators=(",", ":")),
                "created_by": request.created_by.strip(),
                "metadata": json.dumps(metadata, separators=(",", ":")),
            },
        )
        model_definition_id = db.execute(text("SELECT LAST_INSERT_ID()"), {}).scalar_one()
        db.execute(
            text(
                """
                INSERT INTO audit_event
                    (actor,action_code,entity_type,entity_id,event_details_json,created_at)
                VALUES
                    (:actor,'MODEL_DEFINITION_CREATED','MODEL_DEFINITION',:entity_id,
                     :details,UTC_TIMESTAMP(6))
                """
            ),
            {
                "actor": request.created_by.strip(),
                "entity_id": str(model_definition_id),
                "details": json.dumps(
                    {
                        "datasetId": request.dataset_id,
                        "targetPropertyId": request.target_property_id,
                        "featureProfileId": request.feature_profile_id,
                        "algorithm": request.algorithm_code,
                        "featureSelection": request.feature_selection_code,
                    },
                    separators=(",", ":"),
                ),
            },
        )
        db.commit()
    except Exception:
        db.rollback()
        raise

    return {
        "modelDefinitionId": model_definition_id,
        "status": "PENDING_REBUILD",
        "datasetId": request.dataset_id,
        "targetProperty": target["original_name"],
        "declaredClassLabels": declared_labels,
        "positiveClassLabel": request.positive_class_label,
    }


@router.patch("/model-definitions/{model_definition_id}")
def rename_model_definition(
    model_definition_id: int,
    request: ModelNameUpdateRequest,
    db: Session = Depends(get_v3_db),
):
    model_name = request.model_name.strip()
    if not model_name:
        raise HTTPException(status_code=422, detail={"code": "NAME_REQUIRED"})
    try:
        result = db.execute(
            text("UPDATE model_definition SET model_name=:model_name,updated_at=UTC_TIMESTAMP(6) WHERE model_definition_id=:id"),
            {"model_name": model_name, "id": model_definition_id},
        )
        if result.rowcount == 0:
            db.rollback()
            raise HTTPException(status_code=404, detail={"code": "MODEL_DEFINITION_NOT_FOUND"})
        db.execute(
            text(
                """
                INSERT INTO audit_event
                    (actor,action_code,entity_type,entity_id,event_details_json,created_at)
                VALUES
                    ('web-operator','MODEL_DEFINITION_RENAMED','MODEL_DEFINITION',:entity_id,
                     :details,UTC_TIMESTAMP(6))
                """
            ),
            {
                "entity_id": str(model_definition_id),
                "details": json.dumps({"modelName": model_name}, separators=(",", ":")),
            },
        )
        db.commit()
    except HTTPException:
        raise
    except Exception:
        db.rollback()
        raise
    return {"modelDefinitionId": model_definition_id, "name": model_name}
