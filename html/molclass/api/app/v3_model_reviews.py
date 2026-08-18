import hmac
import json
import os
import re
import subprocess
from typing import Any, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, Header
from pydantic import BaseModel, Field
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.config import settings
from app.database import get_v3_db

router = APIRouter(prefix="/api/v1", tags=["model-reviews"])

class ModelDecisionRequest(BaseModel):
    decision: str = Field(min_length=6, max_length=16)
    reviewer: str = Field(min_length=1, max_length=128)
    note: str = Field(min_length=1, max_length=2048)


def _approval_mutation_available() -> bool:
    wrapper = settings.approval_repo_root / "gradlew"
    return bool(
        settings.model_approval_enabled
        and settings.model_review_token
        and settings.approval_db_user
        and settings.approval_db_pass
        and wrapper.is_file()
    )


def _command_value(value: str, field_name: str) -> str:
    if any(character in value for character in ('"', "\\", "\r", "\n")):
        raise HTTPException(
            status_code=422,
            detail=f"{field_name} contains unsupported quoting or line-break characters",
        )
    return f'"{value}"'


def _approval_failure_detail(output: str) -> str:
    output = output.strip()
    marker = "Model approval failed:"
    marker_index = output.rfind(marker)
    if marker_index >= 0:
        output = output[marker_index + len(marker):].strip()
        # Gradle appends its own build-failure banner after the Java message.
        # Keep only the canonical reason so reviewers see an actionable detail.
        for boundary in ("\n\nFAILURE:", "\nFAILURE:", "\n\n* What went wrong"):
            boundary_index = output.find(boundary)
            if boundary_index >= 0:
                output = output[:boundary_index].strip()
    if not output:
        return "The canonical model approval command failed without an error message"
    return output[-3000:]


def _run_approval(
    model_build_id: int,
    decision: str,
    reviewer: str,
    note: str,
) -> dict[str, object]:
    wrapper = settings.approval_repo_root / "gradlew"
    approval_args = " ".join(
        (
            "--build-id",
            str(model_build_id),
            "--decision",
            decision,
            "--actor",
            _command_value(reviewer, "reviewer"),
            "--note",
            _command_value(note, "note"),
        )
    )
    command = [
        str(wrapper),
        "--no-daemon",
        ":approveV3Model",
        f"-PapprovalArgs={approval_args}",
    ]

    jdbc_url = (
        f"jdbc:mysql://{settings.v3_db_host}:{settings.v3_db_port}/"
        f"{settings.v3_db_name}"
        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    )
    process_environment = os.environ.copy()
    process_environment.update(
        {
            "MOLCLASS_JDBC_URL": jdbc_url,
            "MOLCLASS_DB_SCHEMA": settings.v3_db_name,
            "MOLCLASS_DB_USER": settings.approval_db_user,
            "MOLCLASS_DB_PASSWORD": settings.approval_db_pass,
        }
    )

    completed = subprocess.run(
        command,
        cwd=settings.approval_repo_root,
        env=process_environment,
        capture_output=True,
        text=True,
        timeout=settings.model_approval_timeout_seconds,
        check=False,
    )
    output = "\n".join(part for part in (completed.stdout, completed.stderr) if part)
    if completed.returncode != 0:
        raise HTTPException(status_code=409, detail=_approval_failure_detail(output))

    return {
        "modelBuildId": model_build_id,
        "decision": decision,
        "buildStatus": "PUBLISHED" if decision == "APPROVE" else "REJECTED",
        "reviewer": reviewer,
        "message": "The canonical Java approval transaction completed successfully.",
    }



def _json_value(value: Any, fallback: Any) -> Any:
    if value is None:
        return fallback
    if isinstance(value, (dict, list, int, float, bool)):
        return value
    try:
        return json.loads(value)
    except (TypeError, ValueError, json.JSONDecodeError):
        return fallback


def _build_summary(row: Any) -> dict[str, Any]:
    values = row._mapping
    return {
        "modelBuildId": values["model_build_id"],
        "generationLabel": values["generation_label"],
        "generationNumber": values["generation_number"],
        "status": values["build_status"],
        "runstep": values["runstep"],
        "versions": {
            "java": values["java_version"],
            "cdk": values["cdk_version"],
            "weka": values["weka_version"],
            "codeRevision": values["code_revision"],
            "databaseSchema": values["database_schema_version"],
        },
        "randomSeed": values["random_seed"],
        "splitStrategy": values["split_strategy"],
        "splitConfiguration": _json_value(values["split_configuration_json"], {}),
        "counts": {
            "training": values["training_count"],
            "validation": values["validation_count"],
            "holdout": values["holdout_count"],
            "excluded": values["excluded_count"],
        },
        "manifest": _json_value(values["build_manifest_json"], None),
        "manifestSha256": values["manifest_sha256"],
        "error": (
            {
                "code": values["error_code"],
                "message": values["error_message"],
            }
            if values["error_code"] or values["error_message"]
            else None
        ),
        "createdAt": values["build_created_at"],
        "startedAt": values["started_at"],
        "finishedAt": values["finished_at"],
        "publishedAt": values["published_at"],
    }


@router.get("/model-reviews")
def list_model_reviews(
    status: Optional[str] = Query(default=None, min_length=1, max_length=32, pattern=r"^[A-Za-z0-9_]+$"),
    limit: int = Query(default=100, ge=1, le=250),
    offset: int = Query(default=0, ge=0),
    db: Session = Depends(get_v3_db),
) -> dict[str, Any]:
    status_filter = "WHERE md.status = :status" if status is not None else ""
    parameters = {"limit": limit, "offset": offset}
    if status is not None:
        parameters["status"] = status.upper()

    rows = db.execute(
        text(
            f"""
            SELECT md.model_definition_id,
                   md.model_name,
                   md.status AS definition_status,
                   md.algorithm_code,
                   md.feature_selection_code,
                   md.positive_class_label,
                   md.created_by,
                   md.created_at,
                   md.updated_at,
                   d.dataset_id,
                   d.name AS dataset_name,
                   p.original_name AS target_property,
                   fp.profile_code,
                   mb.model_build_id,
                   mb.status AS build_status,
                   mb.runstep,
                   mb.generation_number,
                   mb.finished_at,
                   mb.published_at,
                   ma.approval_status,
                   ma.approved_by,
                   ma.approved_at
              FROM model_definition md
              JOIN dataset d ON d.dataset_id = md.dataset_id
              JOIN property_definition p ON p.property_id = md.target_property_id
              JOIN feature_profile fp ON fp.feature_profile_id = md.feature_profile_id
              LEFT JOIN model_build mb
                ON mb.model_build_id = (
                    SELECT mb_latest.model_build_id
                      FROM model_build mb_latest
                     WHERE mb_latest.model_definition_id = md.model_definition_id
                     ORDER BY mb_latest.generation_number DESC
                     LIMIT 1
                )
              LEFT JOIN model_approval ma ON ma.model_build_id = mb.model_build_id
             {status_filter}
             ORDER BY md.updated_at DESC, md.model_definition_id DESC
             LIMIT :limit OFFSET :offset
            """
        ),
        parameters,
    ).all()

    items = []
    for row in rows:
        value = row._mapping
        items.append(
            {
                "modelDefinitionId": value["model_definition_id"],
                "modelName": value["model_name"],
                "status": value["definition_status"],
                "dataset": {
                    "datasetId": value["dataset_id"],
                    "name": value["dataset_name"],
                },
                "targetProperty": value["target_property"],
                "featureProfile": value["profile_code"],
                "algorithm": value["algorithm_code"],
                "featureSelection": value["feature_selection_code"],
                "positiveClassLabel": value["positive_class_label"],
                "createdBy": value["created_by"],
                "createdAt": value["created_at"],
                "updatedAt": value["updated_at"],
                "latestBuild": (
                    {
                        "modelBuildId": value["model_build_id"],
                        "status": value["build_status"],
                        "runstep": value["runstep"],
                        "generationNumber": value["generation_number"],
                        "finishedAt": value["finished_at"],
                        "publishedAt": value["published_at"],
                    }
                    if value["model_build_id"] is not None
                    else None
                ),
                "approval": (
                    {
                        "status": value["approval_status"],
                        "approvedBy": value["approved_by"],
                        "approvedAt": value["approved_at"],
                    }
                    if value["approval_status"] is not None
                    else None
                ),
            }
        )

    return {"items": items, "limit": limit, "offset": offset, "returned": len(items)}


@router.get("/model-definitions/{model_definition_id}/review")
def get_model_review(
    model_definition_id: int,
    db: Session = Depends(get_v3_db),
) -> dict[str, Any]:
    definition = db.execute(
        text(
            """
            SELECT md.model_definition_id,
                   md.legacy_model_id,
                   md.model_name,
                   md.status,
                   md.algorithm_code,
                   md.algorithm_options_json,
                   md.feature_selection_code,
                   md.feature_selection_options_json,
                   md.positive_class_label,
                   md.declared_class_labels_json,
                   md.published_model_build_id,
                   md.created_by,
                   md.definition_metadata_json,
                   md.created_at,
                   md.updated_at,
                   d.dataset_id,
                   d.name AS dataset_name,
                   d.status AS dataset_status,
                   d.model_eligible,
                   p.property_id AS target_property_id,
                   p.original_name AS target_property,
                   fp.feature_profile_id,
                   fp.profile_code,
                   fp.profile_version,
                   fp.status AS profile_status
              FROM model_definition md
              JOIN dataset d ON d.dataset_id = md.dataset_id
              JOIN property_definition p ON p.property_id = md.target_property_id
              JOIN feature_profile fp ON fp.feature_profile_id = md.feature_profile_id
             WHERE md.model_definition_id = :model_definition_id
            """
        ),
        {"model_definition_id": model_definition_id},
    ).first()
    if definition is None:
        raise HTTPException(status_code=404, detail="Model definition was not found.")

    value = definition._mapping
    builds = db.execute(
        text(
            """
            SELECT mb.model_build_id,
                   mb.generation_label,
                   mb.generation_number,
                   mb.status AS build_status,
                   mb.runstep,
                   mb.java_version,
                   mb.cdk_version,
                   mb.weka_version,
                   mb.code_revision,
                   mb.database_schema_version,
                   mb.random_seed,
                   mb.split_strategy,
                   mb.split_configuration_json,
                   mb.training_count,
                   mb.validation_count,
                   mb.holdout_count,
                   mb.excluded_count,
                   mb.build_manifest_json,
                   HEX(mb.manifest_sha256) AS manifest_sha256,
                   mb.error_code,
                   mb.error_message,
                   mb.created_at AS build_created_at,
                   mb.started_at,
                   mb.finished_at,
                   mb.published_at
              FROM model_build mb
             WHERE mb.model_definition_id = :model_definition_id
             ORDER BY mb.generation_number DESC
            """
        ),
        {"model_definition_id": model_definition_id},
    ).all()
    build_items = [_build_summary(row) for row in builds]
    latest_build_id = build_items[0]["modelBuildId"] if build_items else None

    evaluations: list[dict[str, Any]] = []
    artifacts: list[dict[str, Any]] = []
    approval: Optional[dict[str, Any]] = None
    if latest_build_id is not None:
        evaluation_rows = db.execute(
            text(
                """
                SELECT evaluation_set,
                       fold_number,
                       class_label,
                       metric_code,
                       metric_value,
                       support_count,
                       metric_details_json,
                       created_at
                  FROM model_evaluation
                 WHERE model_build_id = :model_build_id
                 ORDER BY evaluation_set, metric_code, class_label, fold_number
                """
            ),
            {"model_build_id": latest_build_id},
        ).all()
        evaluations = [
            {
                "evaluationSet": row.evaluation_set,
                "foldNumber": row.fold_number,
                "classLabel": row.class_label,
                "metricCode": row.metric_code,
                "metricValue": row.metric_value,
                "supportCount": row.support_count,
                "details": _json_value(row.metric_details_json, None),
                "createdAt": row.created_at,
            }
            for row in evaluation_rows
        ]

        artifact_rows = db.execute(
            text(
                """
                SELECT model_artifact_id,
                       artifact_kind,
                       artifact_format,
                       media_type,
                       artifact_size,
                       HEX(artifact_sha256) AS artifact_sha256,
                       created_at
                  FROM model_artifact
                 WHERE model_build_id = :model_build_id
                 ORDER BY artifact_kind, model_artifact_id
                """
            ),
            {"model_build_id": latest_build_id},
        ).all()
        artifacts = [
            {
                "modelArtifactId": row.model_artifact_id,
                "kind": row.artifact_kind,
                "format": row.artifact_format,
                "mediaType": row.media_type,
                "size": row.artifact_size,
                "sha256": row.artifact_sha256,
                "createdAt": row.created_at,
            }
            for row in artifact_rows
        ]

        approval_row = db.execute(
            text(
                """
                SELECT approval_status, approved_by, approval_note, approved_at
                  FROM model_approval
                 WHERE model_build_id = :model_build_id
                """
            ),
            {"model_build_id": latest_build_id},
        ).first()
        if approval_row is not None:
            approval = {
                "status": approval_row.approval_status,
                "approvedBy": approval_row.approved_by,
                "note": approval_row.approval_note,
                "approvedAt": approval_row.approved_at,
            }

    return {
        "definition": {
            "modelDefinitionId": value["model_definition_id"],
            "legacyModelId": value["legacy_model_id"],
            "modelName": value["model_name"],
            "status": value["status"],
            "dataset": {
                "datasetId": value["dataset_id"],
                "name": value["dataset_name"],
                "status": value["dataset_status"],
                "modelEligible": bool(value["model_eligible"]),
            },
            "targetProperty": {
                "propertyId": value["target_property_id"],
                "name": value["target_property"],
            },
            "featureProfile": {
                "featureProfileId": value["feature_profile_id"],
                "code": value["profile_code"],
                "version": value["profile_version"],
                "status": value["profile_status"],
            },
            "algorithm": {
                "code": value["algorithm_code"],
                "options": _json_value(value["algorithm_options_json"], {}),
            },
            "featureSelection": {
                "code": value["feature_selection_code"],
                "options": _json_value(value["feature_selection_options_json"], {}),
            },
            "positiveClassLabel": value["positive_class_label"],
            "declaredClassLabels": _json_value(value["declared_class_labels_json"], []),
            "publishedModelBuildId": value["published_model_build_id"],
            "createdBy": value["created_by"],
            "metadata": _json_value(value["definition_metadata_json"], {}),
            "createdAt": value["created_at"],
            "updatedAt": value["updated_at"],
        },
        "builds": build_items,
        "latestBuild": build_items[0] if build_items else None,
        "evaluations": evaluations,
        "artifacts": artifacts,
        "approval": approval,
        "approvalMutationAvailable": _approval_mutation_available(),
    }


@router.post("/model-builds/{model_build_id}/decision")
def decide_model_build(
    model_build_id: int,
    request: ModelDecisionRequest,
    review_token: str = Header(default="", alias="X-MolClass-Review-Token"),
) -> dict[str, object]:
    if not _approval_mutation_available():
        raise HTTPException(
            status_code=503,
            detail="Model approval is disabled or incompletely configured",
        )
    if not hmac.compare_digest(review_token, settings.model_review_token):
        raise HTTPException(status_code=403, detail="Invalid model-review token")
    if model_build_id <= 0:
        raise HTTPException(status_code=422, detail="model_build_id must be positive")

    decision = request.decision.strip().upper()
    if decision not in {"APPROVE", "REJECT"}:
        raise HTTPException(
            status_code=422,
            detail="decision must be APPROVE or REJECT",
        )

    reviewer = request.reviewer.strip()
    if not re.fullmatch(r"[A-Za-z0-9_.@+-]+", reviewer):
        raise HTTPException(
            status_code=422,
            detail="reviewer may contain only letters, numbers, _, ., @, +, or -",
        )
    note = request.note.strip()
    if not note:
        raise HTTPException(status_code=422, detail="A decision note is required")

    try:
        return _run_approval(model_build_id, decision, reviewer, note)
    except subprocess.TimeoutExpired as error:
        raise HTTPException(
            status_code=504,
            detail="The canonical model approval command timed out",
        ) from error
    except OSError as error:
        raise HTTPException(
            status_code=503,
            detail=f"Could not start the canonical model approval command: {error}",
        ) from error

