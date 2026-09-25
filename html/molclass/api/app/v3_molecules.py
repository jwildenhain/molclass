import hashlib
import ipaddress
import json
from typing import Any, Optional

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.database import get_v3_db


router = APIRouter(prefix="/api/v1", tags=["v3 molecules"])

DAILY_SUBMISSION_LIMIT = 100


class MoleculePredictRequest(BaseModel):
    smiles: str = Field(min_length=1, max_length=4000)
    # Omit to predict every currently-published model this molecule doesn't already have a
    # result for -- only allowed for callers presenting a valid API key (see _resolve_api_key).
    model_definition_id: Optional[int] = Field(default=None, gt=0)


def _parse_ip(value: Optional[str]):
    if not value:
        return None
    try:
        parsed = ipaddress.ip_address(value.strip())
    except ValueError:
        return None
    if isinstance(parsed, ipaddress.IPv6Address) and parsed.ipv4_mapped:
        return parsed.ipv4_mapped
    return parsed


def _client_ip(request: Request) -> bytes:
    """Return a normalized packed address suitable for VARBINARY(16).

    Apache appends the actual client to X-Forwarded-For before the request reaches
    the private Next.js/FastAPI hop. Reading from the right prevents a caller from
    evading the quota by prepending a forged address. Forwarded headers are ignored
    for a direct request from a public peer.
    """
    peer = _parse_ip(request.client.host if request.client else None)
    peer_is_trusted_proxy = peer is None or peer.is_private or peer.is_loopback or peer.is_link_local
    if peer_is_trusted_proxy:
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            for candidate in reversed(forwarded.split(",")):
                parsed = _parse_ip(candidate)
                if parsed is not None:
                    return parsed.packed
    if peer is not None:
        return peer.packed
    return ipaddress.ip_address("0.0.0.0").packed


def _resolve_api_key(db: Session, request: Request) -> Optional[int]:
    raw_key = (request.headers.get("x-molclass-api-key") or "").strip()
    if not raw_key:
        return None
    key_hash = hashlib.sha256(raw_key.encode("utf-8")).digest()
    row = db.execute(
        text("SELECT api_key_id FROM api_key WHERE key_hash=:hash AND revoked_at IS NULL"),
        {"hash": key_hash},
    ).mappings().first()
    return int(row["api_key_id"]) if row else None


@router.post("/molecules/predict", status_code=status.HTTP_202_ACCEPTED)
def queue_molecule_predict(
    request: MoleculePredictRequest, http_request: Request, db: Session = Depends(get_v3_db)
):
    """Register a raw SMILES as a molecule (if not already known), compute its descriptors,
    and predict against one or more models -- all as one job. Poll GET /api/v1/jobs/{job_id};
    once status is SUCCEEDED, `result` holds either {moleculeId, modelDefinitionId, prediction}
    (single-model request) or {moleculeId, predictions: [...], errors: [...]} (predict-all-missing
    request, API-key callers only).

    The molecule need not already be indexed -- unlike POST /api/v3/models/.../predict on the
    predictor service, which only works for molecules already in the database.

    Anonymous callers (no X-MolClass-Api-Key) must specify model_definition_id and are limited
    to DAILY_SUBMISSION_LIMIT submissions per IP per UTC day. Callers presenting a valid,
    unrevoked API key are exempt from that quota and may omit model_definition_id to have every
    currently-published model this molecule is missing predicted in one job -- so a caller never
    has to track which models exist or re-request ones it already has.
    """
    smiles = request.smiles.strip()
    if not smiles:
        raise HTTPException(status_code=422, detail={"code": "INVALID_SMILES"})

    # All reads and writes share one transaction: the ORM Session auto-begins on the first
    # query, so any db.execute() before `with db.begin():` leaves that block finding a
    # transaction already open and raising InvalidRequestError.
    with db.begin():
        api_key_id = _resolve_api_key(db, http_request)
        predict_all_missing = request.model_definition_id is None
        if predict_all_missing and api_key_id is None:
            raise HTTPException(status_code=422, detail={"code": "MODEL_DEFINITION_ID_REQUIRED"})

        client_ip = _client_ip(http_request)

        if api_key_id is None:
            # The upsert locks one (UTC date, IP) row, so simultaneous requests
            # cannot all observe the same pre-limit COUNT and overrun the quota.
            db.execute(
                text(
                    "INSERT INTO molecule_submission_quota"
                    " (quota_date,client_ip,submission_count,updated_at)"
                    " VALUES (UTC_DATE(),:client_ip,1,UTC_TIMESTAMP(6))"
                    " ON DUPLICATE KEY UPDATE"
                    " submission_count=submission_count+1,updated_at=UTC_TIMESTAMP(6)"
                ),
                {"client_ip": client_ip},
            )
            submitted_today = db.execute(
                text(
                    "SELECT submission_count FROM molecule_submission_quota"
                    " WHERE quota_date=UTC_DATE() AND client_ip=:client_ip"
                ),
                {"client_ip": client_ip},
            ).scalar()
            if submitted_today is None:
                raise RuntimeError("anonymous submission quota row was not persisted")
            if int(submitted_today) > DAILY_SUBMISSION_LIMIT:
                raise HTTPException(
                    status_code=429,
                    detail={"code": "DAILY_QUOTA_EXCEEDED", "limit": DAILY_SUBMISSION_LIMIT},
                )

        payload = json.dumps(
            {
                "contractVersion": 1,
                "smiles": smiles,
                **(
                    {"predictAllMissing": True}
                    if predict_all_missing
                    else {"modelDefinitionId": request.model_definition_id}
                ),
            },
            sort_keys=True,
            separators=(",", ":"),
        )
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
        db.execute(
            text(
                """
                INSERT INTO molecule_submission
                    (job_id,api_key_id,client_ip,requested_model_definition_id,predict_all_missing)
                VALUES (:job_id,:api_key_id,:client_ip,:model_id,:predict_all_missing)
                """
            ),
            {
                "job_id": job_id,
                "api_key_id": api_key_id,
                "client_ip": client_ip,
                "model_id": request.model_definition_id,
                "predict_all_missing": predict_all_missing,
            },
        )
    return {"jobId": job_id, "status": "QUEUED"}
