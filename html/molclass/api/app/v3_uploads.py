import hashlib
import json
import os
import uuid
from pathlib import Path
from typing import Any, Optional

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile, status
from pydantic import BaseModel, Field
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.config import settings
from app.database import get_v3_db


router = APIRouter(prefix="/api/v1", tags=["v3 uploads"])


class ImportManifestRequest(BaseModel):
    dataset_name: str = Field(min_length=1, max_length=255)
    description: Optional[str] = Field(default=None, max_length=10000)
    publication_reference: Optional[str] = Field(default=None, max_length=255)
    molecule_type: Optional[str] = Field(default=None, max_length=32)
    identifier_property: str = Field(min_length=1, max_length=255)
    identifier_confirmed: bool
    selected_properties: Optional[list[str]] = None
    created_by: str = Field(default="local-operator", min_length=1, max_length=255)


def _analysis(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        return json.loads(value)
    raise ValueError("analysis JSON is unavailable")


def _upload_row(db: Session, upload_id: int):
    return db.execute(
        text(
            """
            SELECT upload_id,storage_key,original_filename,HEX(content_sha256) content_sha256,
                   content_length,media_type,status,analysis_version,analysis_json,
                   analysis_error_code,analysis_error_message,created_at,analyzed_at
              FROM upload_artifact
             WHERE upload_id=:upload_id AND deleted_at IS NULL
            """
        ),
        {"upload_id": upload_id},
    ).mappings().first()


def _latest_job(db: Session, upload_id: int):
    return db.execute(
        text(
            """
            SELECT job_id,job_type,status,runstep,attempt_count,maximum_attempts,
                   error_code,error_message,created_at,started_at,heartbeat_at,finished_at
              FROM job
             WHERE CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.uploadId')) AS UNSIGNED)
                   = :upload_id
             ORDER BY job_id DESC
             LIMIT 1
            """
        ),
        {"upload_id": upload_id},
    ).mappings().first()


@router.get("/health/readiness")
def readiness(db: Session = Depends(get_v3_db)):
    try:
        db.execute(text("SELECT 1")).scalar_one()
        count = db.execute(
            text(
                """
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema=DATABASE()
                   AND table_name IN
                       ('upload_artifact','job','job_event','import_run','dataset')
                """
            )
        ).scalar_one()
        if count != 5:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail={"code": "SCHEMA_INCOMPATIBLE"},
            )
        return {"status": "UP"}
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "DATABASE_UNAVAILABLE"},
        )


@router.post("/uploads", status_code=status.HTTP_202_ACCEPTED)
async def create_upload(
    file: UploadFile = File(...),
    db: Session = Depends(get_v3_db),
):
    original = (file.filename or "").replace("\\", "/").split("/")[-1].strip()
    if not original or len(original) > 255 or "\x00" in original:
        raise HTTPException(status_code=422, detail={"code": "INVALID_FILENAME"})
    if not original.lower().endswith(".sdf"):
        raise HTTPException(status_code=415, detail={"code": "SDF_REQUIRED"})

    root = settings.upload_root
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    storage_key = f"{uuid.uuid4().hex}.sdf"
    temporary = root / f".{storage_key}.part"
    target = root / storage_key
    digest = hashlib.sha256()
    length = 0
    moved = False

    try:
        with temporary.open("xb") as output:
            while True:
                chunk = await file.read(1024 * 1024)
                if not chunk:
                    break
                length += len(chunk)
                if length > settings.max_upload_bytes:
                    raise HTTPException(
                        status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                        detail={"code": "UPLOAD_TOO_LARGE"},
                    )
                digest.update(chunk)
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        if length == 0:
            raise HTTPException(status_code=422, detail={"code": "EMPTY_UPLOAD"})
        os.replace(temporary, target)
        moved = True

        with db.begin():
            upload_result = db.execute(
                text(
                    """
                    INSERT INTO upload_artifact
                        (storage_key,original_filename,content_sha256,content_length,
                         media_type,status,retention_until)
                    VALUES
                        (:storage_key,:original_filename,:content_sha256,:content_length,
                         :media_type,'ANALYSIS_QUEUED',
                         DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 DAY))
                    """
                ),
                {
                    "storage_key": storage_key,
                    "original_filename": original,
                    "content_sha256": digest.digest(),
                    "content_length": length,
                    "media_type": (file.content_type or "chemical/x-mdl-sdfile")[:127],
                },
            )
            upload_id = int(upload_result.lastrowid)
            payload = json.dumps(
                {"contractVersion": 1, "uploadId": upload_id},
                sort_keys=True,
                separators=(",", ":"),
            )
            job_result = db.execute(
                text(
                    """
                    INSERT INTO job
                        (job_type,status,runstep,priority,payload_json,maximum_attempts,available_at)
                    VALUES ('SDF_ANALYZE','QUEUED','QUEUED',0,:payload,3,UTC_TIMESTAMP(6))
                    """
                ),
                {"payload": payload},
            )
            job_id = int(job_result.lastrowid)
            db.execute(
                text(
                    """
                    INSERT INTO job_event
                        (job_id,event_type,runstep,event_message,event_details_json)
                    VALUES (:job_id,'JOB_QUEUED','QUEUED',
                            'SDF analysis queued by upload API',NULL)
                    """
                ),
                {"job_id": job_id},
            )
        return {
            "uploadId": upload_id,
            "jobId": job_id,
            "status": "ANALYSIS_QUEUED",
            "contentSha256": digest.hexdigest(),
            "contentLength": length,
        }
    except HTTPException:
        db.rollback()
        raise
    except Exception:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "UPLOAD_QUEUE_UNAVAILABLE"},
        )
    finally:
        await file.close()
        temporary.unlink(missing_ok=True)
        if moved and "upload_id" not in locals():
            target.unlink(missing_ok=True)


@router.get("/uploads/{upload_id}")
def get_upload(upload_id: int, db: Session = Depends(get_v3_db)):
    row = _upload_row(db, upload_id)
    if not row:
        raise HTTPException(status_code=404, detail={"code": "UPLOAD_NOT_FOUND"})
    job = _latest_job(db, upload_id)
    result = {
        "uploadId": row["upload_id"],
        "originalFilename": row["original_filename"],
        "contentSha256": row["content_sha256"].lower(),
        "contentLength": row["content_length"],
        "mediaType": row["media_type"],
        "status": row["status"],
        "analysisVersion": row["analysis_version"],
        "analysisError": None
        if not row["analysis_error_code"]
        else {
            "code": row["analysis_error_code"],
            "message": row["analysis_error_message"],
        },
        "createdAt": row["created_at"],
        "analyzedAt": row["analyzed_at"],
        "analysis": _analysis(row["analysis_json"]) if row["analysis_json"] else None,
        "job": dict(job) if job else None,
    }
    return result


@router.get("/jobs/{job_id}")
def get_job(job_id: int, db: Session = Depends(get_v3_db)):
    row = db.execute(
        text(
            """
            SELECT j.job_id,j.job_type,j.status,j.runstep,j.attempt_count,j.maximum_attempts,
                   j.cancel_requested_at,j.error_code,j.error_message,j.created_at,
                   j.started_at,j.heartbeat_at,j.finished_at,
                   ir.import_run_id,ir.dataset_id,ir.total_records,ir.success_records,
                   ir.failed_records,ir.not_processed_records
              FROM job j
              LEFT JOIN import_run ir ON ir.job_id=j.job_id
             WHERE j.job_id=:job_id
            """
        ),
        {"job_id": job_id},
    ).mappings().first()
    if not row:
        raise HTTPException(status_code=404, detail={"code": "JOB_NOT_FOUND"})
    return dict(row)


@router.post("/uploads/{upload_id}/imports", status_code=status.HTTP_202_ACCEPTED)
def queue_import(
    upload_id: int,
    request: ImportManifestRequest,
    db: Session = Depends(get_v3_db),
):
    row = _upload_row(db, upload_id)
    if not row:
        raise HTTPException(status_code=404, detail={"code": "UPLOAD_NOT_FOUND"})
    if row["status"] != "ANALYZED" or not row["analysis_json"]:
        raise HTTPException(status_code=409, detail={"code": "ANALYSIS_NOT_READY"})
    if not request.identifier_confirmed:
        raise HTTPException(status_code=422, detail={"code": "IDENTIFIER_CONFIRMATION_REQUIRED"})

    analysis = _analysis(row["analysis_json"])
    properties = {item["name"]: item for item in analysis.get("properties", [])}
    identifier = properties.get(request.identifier_property)
    if not identifier or not identifier.get("identifierEligible"):
        raise HTTPException(status_code=422, detail={"code": "IDENTIFIER_NOT_ELIGIBLE"})

    selected = request.selected_properties
    if selected is None:
        selected = list(properties)
    selected = list(dict.fromkeys(selected))
    unknown = [name for name in selected if name not in properties]
    if unknown:
        raise HTTPException(
            status_code=422,
            detail={"code": "UNKNOWN_PROPERTIES", "properties": unknown[:20]},
        )
    if request.identifier_property not in selected:
        raise HTTPException(status_code=422, detail={"code": "IDENTIFIER_MUST_BE_IMPORTED"})

    canonical_analysis = json.dumps(analysis, sort_keys=True, separators=(",", ":"))
    manifest = {
        "contractVersion": 1,
        "uploadId": upload_id,
        "identifierProperty": request.identifier_property,
        "selectedProperties": selected,
        "datasetName": request.dataset_name.strip(),
        "description": request.description,
        "publicationReference": request.publication_reference,
        "moleculeType": request.molecule_type,
        "createdBy": request.created_by.strip(),
    }
    canonical_manifest = json.dumps(manifest, sort_keys=True, separators=(",", ":"))
    analysis_hash = hashlib.sha256(canonical_analysis.encode("utf-8")).digest()
    manifest_hash = hashlib.sha256(canonical_manifest.encode("utf-8")).digest()

    existing = db.execute(
        text(
            """
            SELECT import_run_id,job_id,dataset_id,status
              FROM import_run
             WHERE upload_id=:upload_id AND manifest_sha256=:manifest_sha256
            """
        ),
        {"upload_id": upload_id, "manifest_sha256": manifest_hash},
    ).mappings().first()
    if existing:
        return {
            "importRunId": existing["import_run_id"],
            "jobId": existing["job_id"],
            "datasetId": existing["dataset_id"],
            "status": existing["status"],
            "idempotentReplay": True,
        }

    try:
        with db.begin_nested():
            dataset_result = db.execute(
                text(
                    """
                    INSERT INTO dataset
                        (upload_id,name,original_filename,description,publication_reference,
                         molecule_type,status,total_records,created_by)
                    VALUES
                        (:upload_id,:name,:filename,:description,:publication_reference,
                         :molecule_type,'IMPORT_QUEUED',:total_records,:created_by)
                    """
                ),
                {
                    "upload_id": upload_id,
                    "name": request.dataset_name.strip(),
                    "filename": row["original_filename"],
                    "description": request.description,
                    "publication_reference": request.publication_reference,
                    "molecule_type": request.molecule_type,
                    "total_records": int(analysis.get("totalRecords", 0)),
                    "created_by": request.created_by.strip(),
                },
            )
            dataset_id = int(dataset_result.lastrowid)
            job_payload = json.dumps(
                {
                    "contractVersion": 1,
                    "uploadId": upload_id,
                    "datasetId": dataset_id,
                },
                sort_keys=True,
                separators=(",", ":"),
            )
            job_result = db.execute(
                text(
                    """
                    INSERT INTO job
                        (job_type,status,runstep,priority,payload_json,maximum_attempts,available_at)
                    VALUES ('SDF_IMPORT','QUEUED','QUEUED',0,:payload,3,UTC_TIMESTAMP(6))
                    """
                ),
                {"payload": job_payload},
            )
            job_id = int(job_result.lastrowid)
            run_result = db.execute(
                text(
                    """
                    INSERT INTO import_run
                        (job_id,upload_id,dataset_id,status,runstep,
                         identifier_property_name,analysis_sha256,manifest_sha256,
                         selected_properties_json,total_records)
                    VALUES
                        (:job_id,:upload_id,:dataset_id,'QUEUED','QUEUED',
                         :identifier_property,:analysis_sha256,:manifest_sha256,
                         :selected_properties,:total_records)
                    """
                ),
                {
                    "job_id": job_id,
                    "upload_id": upload_id,
                    "dataset_id": dataset_id,
                    "identifier_property": request.identifier_property,
                    "analysis_sha256": analysis_hash,
                    "manifest_sha256": manifest_hash,
                    "selected_properties": json.dumps(
                        selected, ensure_ascii=False, separators=(",", ":")
                    ),
                    "total_records": int(analysis.get("totalRecords", 0)),
                },
            )
            import_run_id = int(run_result.lastrowid)
            db.execute(
                text(
                    """
                    INSERT INTO job_event
                        (job_id,event_type,runstep,event_message,event_details_json)
                    VALUES (:job_id,'JOB_QUEUED','QUEUED',
                            'SDF import queued after identifier confirmation',NULL)
                    """
                ),
                {"job_id": job_id},
            )
        db.commit()
    except Exception:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "IMPORT_QUEUE_UNAVAILABLE"},
        )

    return {
        "importRunId": import_run_id,
        "jobId": job_id,
        "datasetId": dataset_id,
        "status": "QUEUED",
        "idempotentReplay": False,
    }


@router.get("/imports/{import_run_id}")
def get_import_run(import_run_id: int, db: Session = Depends(get_v3_db)):
    row = db.execute(
        text(
            """
            SELECT ir.import_run_id, ir.upload_id, ir.dataset_id, d.name AS dataset_name,
                   ir.status, ir.runstep, ir.identifier_property_name,
                   ir.total_records, ir.success_records, ir.failed_records,
                   ir.not_processed_records, ir.error_code, ir.error_message,
                   ir.created_at, ir.started_at, ir.finished_at,
                   j.job_id, j.status AS job_status, j.runstep AS job_runstep,
                   j.attempt_count, j.maximum_attempts, j.lease_owner,
                   j.heartbeat_at, j.error_code AS job_error_code,
                   j.error_message AS job_error_message
              FROM import_run ir
              JOIN job j ON j.job_id = ir.job_id
              JOIN dataset d ON d.dataset_id = ir.dataset_id
             WHERE ir.import_run_id = :import_run_id
            """
        ),
        {"import_run_id": import_run_id},
    ).mappings().first()
    if not row:
        raise HTTPException(status_code=404, detail={"code": "IMPORT_RUN_NOT_FOUND"})

    counts = db.execute(
        text(
            """
            SELECT status, COUNT(*) AS record_count
              FROM import_record
             WHERE import_run_id = :import_run_id
             GROUP BY status
             ORDER BY status
            """
        ),
        {"import_run_id": import_run_id},
    ).mappings().all()
    failures = db.execute(
        text(
            """
            SELECT record_number, source_identifier, status, runstep,
                   attempt_count, error_code, error_message
              FROM import_record
             WHERE import_run_id = :import_run_id
               AND status IN ('FAILED', 'NOT_PROCESSED')
             ORDER BY record_number
             LIMIT 100
            """
        ),
        {"import_run_id": import_run_id},
    ).mappings().all()

    return {
        "importRunId": row["import_run_id"],
        "uploadId": row["upload_id"],
        "datasetId": row["dataset_id"],
        "datasetName": row["dataset_name"],
        "status": row["status"],
        "runstep": row["runstep"],
        "identifierProperty": row["identifier_property_name"],
        "totalRecords": row["total_records"],
        "successRecords": row["success_records"],
        "failedRecords": row["failed_records"],
        "notProcessedRecords": row["not_processed_records"],
        "errorCode": row["error_code"],
        "errorMessage": row["error_message"],
        "createdAt": row["created_at"],
        "startedAt": row["started_at"],
        "finishedAt": row["finished_at"],
        "job": {
            "jobId": row["job_id"],
            "status": row["job_status"],
            "runstep": row["job_runstep"],
            "attemptCount": row["attempt_count"],
            "maximumAttempts": row["maximum_attempts"],
            "leaseOwner": row["lease_owner"],
            "heartbeatAt": row["heartbeat_at"],
            "errorCode": row["job_error_code"],
            "errorMessage": row["job_error_message"],
        },
        "recordStatusCounts": {item["status"]: item["record_count"] for item in counts},
        "failureSamples": [dict(item) for item in failures],
        "failureSamplesTruncated": row["failed_records"] + row["not_processed_records"] > 100,
    }
