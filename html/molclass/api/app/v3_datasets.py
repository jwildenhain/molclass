from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.database import get_v3_db


router = APIRouter(prefix="/api/v1", tags=["v3 datasets"])
DATASET_STATUSES = {"MIGRATED", "IMPORTING", "READY", "PARTIAL", "FAILED"}


@router.get("/datasets")
def datasets(
    query: Optional[str] = Query(default=None, max_length=255),
    dataset_status: Optional[str] = Query(default=None, alias="status", max_length=32),
    limit: int = Query(default=100, ge=1, le=250),
    offset: int = Query(default=0, ge=0),
    db: Session = Depends(get_v3_db),
):
    if dataset_status is not None and dataset_status not in DATASET_STATUSES:
        raise HTTPException(status_code=422, detail={"code": "INVALID_DATASET_STATUS"})
    needle = query.strip() if query else None
    pattern = f"%{needle}%" if needle else None
    where = " WHERE 1=1"
    parameters = {"limit": limit, "offset": offset}
    if dataset_status:
        where += " AND d.status=:dataset_status"
        parameters["dataset_status"] = dataset_status
    if pattern:
        where += " AND (d.name LIKE :pattern OR d.original_filename LIKE :pattern OR CAST(d.dataset_id AS CHAR)=:exact_id)"
        parameters["pattern"] = pattern
        parameters["exact_id"] = needle

    total = db.execute(
        text("SELECT COUNT(*) FROM dataset d" + where),
        parameters,
    ).scalar_one()
    rows = db.execute(
        text(
            """
            SELECT d.dataset_id,d.legacy_batch_id,d.upload_id,d.name,d.original_filename,
                   d.description,d.publication_reference,d.molecule_type,d.status,
                   d.total_records,d.imported_records,d.failed_records,
                   d.not_processed_records,d.partial_acknowledgement_required,
                   d.model_eligible,d.created_by,d.created_at,d.updated_at,
                   ip.original_name AS identifier_property,
                   (SELECT COUNT(*) FROM dataset_property dp
                     WHERE dp.dataset_id=d.dataset_id AND dp.selected_for_import=1) AS property_count,
                   (SELECT COUNT(*) FROM model_definition md
                     WHERE md.dataset_id=d.dataset_id) AS model_definition_count,
                   ir.import_run_id,ir.status AS import_status,ir.runstep AS import_runstep
              FROM dataset d
              LEFT JOIN property_definition ip ON ip.property_id=d.identifier_property_id
              LEFT JOIN import_run ir ON ir.import_run_id=(
                   SELECT MAX(ir2.import_run_id) FROM import_run ir2
                    WHERE ir2.dataset_id=d.dataset_id)
            """ + where + " ORDER BY d.dataset_id DESC LIMIT :limit OFFSET :offset"
        ),
        parameters,
    ).mappings().all()
    return {
        "total": total,
        "limit": limit,
        "offset": offset,
        "datasets": [
            {
                "datasetId": row["dataset_id"],
                "legacyBatchId": row["legacy_batch_id"],
                "uploadId": row["upload_id"],
                "name": row["name"],
                "originalFilename": row["original_filename"],
                "description": row["description"],
                "publicationReference": row["publication_reference"],
                "moleculeType": row["molecule_type"],
                "status": row["status"],
                "totalRecords": row["total_records"],
                "importedRecords": row["imported_records"],
                "failedRecords": row["failed_records"],
                "notProcessedRecords": row["not_processed_records"],
                "partialAcknowledgementRequired": bool(row["partial_acknowledgement_required"]),
                "modelEligible": bool(row["model_eligible"]),
                "identifierProperty": row["identifier_property"],
                "propertyCount": row["property_count"],
                "modelDefinitionCount": row["model_definition_count"],
                "createdBy": row["created_by"],
                "createdAt": row["created_at"],
                "updatedAt": row["updated_at"],
                "latestImport": None if row["import_run_id"] is None else {
                    "importRunId": row["import_run_id"],
                    "status": row["import_status"],
                    "runstep": row["import_runstep"],
                },
            }
            for row in rows
        ],
    }
