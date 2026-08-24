import json
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.database import get_v3_db


router = APIRouter(prefix="/api/v1", tags=["v3 molecules"])


class MoleculePredictRequest(BaseModel):
    smiles: str = Field(min_length=1, max_length=4000)
    model_definition_id: int = Field(gt=0)


@router.post("/molecules/predict", status_code=status.HTTP_202_ACCEPTED)
def queue_molecule_predict(request: MoleculePredictRequest, db: Session = Depends(get_v3_db)):
    """Register a raw SMILES as a molecule (if not already known), compute its descriptors,
    and predict against one model -- all as one job. Poll GET /api/v1/jobs/{job_id}; once
    status is SUCCEEDED, `result` holds {moleculeId, modelDefinitionId, prediction}.

    The molecule need not already be indexed -- unlike POST /api/v3/models/.../predict on the
    predictor service, which only works for molecules already in the database.
    """
    smiles = request.smiles.strip()
    if not smiles:
        raise HTTPException(status_code=422, detail={"code": "INVALID_SMILES"})

    payload = json.dumps(
        {"contractVersion": 1, "smiles": smiles, "modelDefinitionId": request.model_definition_id},
        sort_keys=True,
        separators=(",", ":"),
    )
    with db.begin():
        job_result = db.execute(
            text(
                """
                INSERT INTO job
                    (job_type,status,runstep,priority,payload_json,maximum_attempts,available_at)
                VALUES ('MOLECULE_PREDICT_REQUEST','QUEUED','QUEUED',0,:payload,3,UTC_TIMESTAMP(6))
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
                        'Ad-hoc molecule prediction queued by upload API',NULL)
                """
            ),
            {"job_id": job_id},
        )
    return {"jobId": job_id, "status": "QUEUED"}
