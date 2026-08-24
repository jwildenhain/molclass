from fastapi import FastAPI, Depends, HTTPException, status, BackgroundTasks, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session
from sqlalchemy import text
from typing import List, Dict, Any, Union, Optional
import re
import os
import subprocess
import uuid
import threading
import smtplib
from email.message import EmailMessage

from app.database import get_db, SessionLocal
from app.v3_uploads import router as v3_upload_router
from app.v3_models import router as v3_model_router
from app.v3_datasets import router as v3_dataset_router
from app.v3_molecules import router as v3_molecules_router
from app.config import settings
from app.schemas import (
    Dataset, ModelSummary, ModelDetail, CompoundIdResponse, ModelFingerprint,
    ModelCreateRequest, ModelCreateResponse, SimilarityResponse,
    ScaffoldMatchResponse, TextSearchResponse,
    SinglePredictionRequest, SinglePredictionTaskResponse, PredictionTaskStatusResponse,
    StructureSearchRequest, StructureSearchResult
)

app = FastAPI(
    title="MolClass REST API",
    description="Modernized REST API for the MolClass chemical informatics portal. Exposes datasets, compounds, descriptors, fingerprints, and model predictions.",
    version="2.0.1",
    docs_url="/docs",
    redoc_url="/redoc"
)

# Configure CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.allowed_origins,
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Content-Type"],
)

app.include_router(v3_upload_router)
app.include_router(v3_model_router)
app.include_router(v3_dataset_router)
app.include_router(v3_molecules_router)

def _is_data_intake_route(method: str, path: str) -> bool:
    if method != "POST":
        return False
    if path == "/api/v1/uploads":
        return True
    if path.startswith("/api/v1/uploads/") and path.endswith("/imports"):
        return True
    if path == "/api/v1/molecules/predict":
        return True
    return path == "/api/v1/model-definitions"


@app.middleware("http")
async def production_route_gate(request: Request, call_next):
    path = request.url.path
    allowed = (
        settings.legacy_api_enabled
        or path.startswith("/api/v1")
        or path in {"/docs", "/redoc", "/openapi.json"}
    )
    if not allowed:
        return JSONResponse(status_code=404, content={"detail": {"code": "ROUTE_DISABLED"}})
    # A read-only deployment (serving search/predictions against already-approved
    # models) blocks new data entering the pipeline at all: uploads, import
    # triggers, and new model definitions. Existing data stays fully readable.
    if not settings.data_intake_enabled and _is_data_intake_route(request.method, path):
        return JSONResponse(status_code=403, content={"detail": {"code": "DATA_INTAKE_DISABLED"}})
    return await call_next(request)

@app.get("/", include_in_schema=False)
def index():
    return {
        "instructions": "http://sysbiolab.bio.ed.ac.uk/wiki/index.php/MolClass#MolClass_REST_service",
        "api_documentation": "/docs"
    }

@app.get("/dataset", response_model=List[Dataset], tags=["Datasets"])
def get_datasets(
    who: Optional[str] = None,
    username: Optional[str] = None,
    mol_type: Optional[str] = None,
    db: Session = Depends(get_db)
):
    """Retrieve dataset batches in the database, with optional filters for mol_type and username."""
    sql = "SELECT batch_id, info, tags, pmid, mol_type FROM batchlist WHERE 1"
    params = {}
    if mol_type:
        sql += " AND mol_type = :mol_type"
        params["mol_type"] = mol_type
    if username:
        if who == "you":
            sql += " AND username = :username"
        elif who == "other":
            sql += " AND username <> :username"
        else:
            sql += " AND username = :username"
        params["username"] = username
    try:
        results = db.execute(text(sql), params).mappings().all()
        return [dict(r) for r in results]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/dataset/{id}", response_model=Dataset, tags=["Datasets"])
def get_dataset(id: int, db: Session = Depends(get_db)):
    """Retrieve details of a single dataset batch by its ID."""
    sql = "SELECT batch_id, info, tags, pmid, mol_type FROM batchlist WHERE batch_id = :id"
    try:
        row = db.execute(text(sql), {"id": id}).mappings().first()
        if not row:
            raise HTTPException(status_code=404, detail=f"Dataset batch with ID {id} not found")
        return dict(row)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/dataset/{id}/compounds", response_model=List[CompoundIdResponse], tags=["Datasets"])
def get_dataset_compounds(id: int, db: Session = Depends(get_db)):
    """Retrieve list of molecule IDs belonging to a dataset batch."""
    sql = "SELECT mol_id FROM batchlist JOIN batchmols USING (batch_id) WHERE batch_id = :id"
    try:
        results = db.execute(text(sql), {"id": id}).mappings().all()
        return [dict(r) for r in results]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

def find_repo_root(start_path: str) -> str:
    current = os.path.abspath(start_path)
    for _ in range(5):
        if os.path.exists(os.path.join(current, "molclass.conf.xml")):
            return current
        current = os.path.dirname(current)
    return current

def get_subprocess_env() -> Dict[str, str]:
    env = os.environ.copy()
    perl5lib = os.path.expanduser("~/perl5/lib/perl5")
    if os.path.exists(perl5lib):
        if "PERL5LIB" in env:
            env["PERL5LIB"] = f"{perl5lib}:{env['PERL5LIB']}"
        else:
            env["PERL5LIB"] = perl5lib
    return env


def send_email_notification(to_email: str, subject: str, body: str):
    if not to_email or to_email == "dummy@example.com" or not to_email.strip():
        return
    
    from_email = settings.molclass_email
    msg = EmailMessage()
    msg.set_content(body)
    msg["Subject"] = subject
    msg["From"] = from_email
    msg["To"] = to_email
    
    try:
        with smtplib.SMTP("localhost") as s:
            s.send_message(msg)
        print(f"Sent email notification to: {to_email}")
    except Exception as e:
        print(f"Warning: Failed to send email notification to {to_email}: {e}")

def delete_batch_task(batch_id: int):
    """
    Executes the cascade deletes natively in Python using SQLAlchemy.
    Matches the exact deletions performed by delete_batch.pl.
    """
    db = SessionLocal()
    try:
        queries = [
            "DELETE FROM `batchlist` WHERE batch_id = :batch_id",
            "DELETE FROM cdk_descriptors USING cdk_descriptors, batchmols WHERE cdk_descriptors.mol_id = batchmols.mol_id AND batchmols.batch_id = :batch_id",
            "DELETE FROM `class_models` WHERE batch_id = :batch_id",
            "DELETE FROM fingerprints USING fingerprints, batchmols WHERE fingerprints.mol_id = batchmols.mol_id AND batchmols.batch_id = :batch_id",
            "DELETE FROM inchi_key USING inchi_key, batchmols WHERE inchi_key.mol_id = batchmols.mol_id AND batchmols.batch_id = :batch_id",
            "DELETE FROM moldb_molbfp USING moldb_molbfp, batchmols WHERE moldb_molbfp.mol_id = batchmols.mol_id AND batchmols.batch_id = :batch_id",
            "DELETE FROM moldb_moldata USING moldb_moldata, batchmols WHERE moldb_moldata.mol_id = batchmols.mol_id AND batchmols.batch_id = :batch_id",
            "DELETE FROM moldb_molfgb USING moldb_molfgb, batchmols WHERE moldb_molfgb.mol_id = batchmols.mol_id AND batchmols.batch_id = :batch_id",
            "DELETE FROM moldb_molhfp USING moldb_molhfp, batchmols WHERE moldb_molhfp.mol_id = batchmols.mol_id AND batchmols.batch_id = :batch_id",
            "DELETE FROM moldb_molstat USING moldb_molstat, batchmols WHERE moldb_molstat.mol_id = batchmols.mol_id AND batchmols.batch_id = :batch_id",
            "DELETE FROM moldb_molstruc USING moldb_molstruc, batchmols WHERE moldb_molstruc.mol_id = batchmols.mol_id AND batchmols.batch_id = :batch_id",
            "DELETE FROM `prediction_list` WHERE batch_id = :batch_id",
            "DELETE FROM prediction_mols USING prediction_mols, batchmols WHERE prediction_mols.mol_id = batchmols.mol_id AND batchmols.batch_id = :batch_id",
            "DELETE FROM sdftags USING sdftags, batchmols WHERE sdftags.mol_id = batchmols.mol_id AND batchmols.batch_id = :batch_id",
            "DELETE FROM timeout_mols USING timeout_mols, batchmols WHERE timeout_mols.mol_id = batchmols.mol_id AND batchmols.batch_id = :batch_id",
            "DELETE FROM `batchmols` WHERE batch_id = :batch_id"
        ]
        for query in queries:
            db.execute(text(query), {"batch_id": batch_id})
        db.commit()
        print(f"Dataset deletion for batch {batch_id} complete.")
    except Exception as e:
        db.rollback()
        print(f"Error deleting batch {batch_id}: {e}")
    finally:
        db.close()

def create_model_and_predictions_task(model_id: int, username: str, pred_name_base: str, email: str):
    """
    Orchestrates the model creation and prediction loops natively in Python.
    Matches the exact training/prediction orchestration of pred_model_upload.pl.
    """
    repo_root = find_repo_root(settings.tools_dir)
    log_dir = os.path.join(repo_root, "log")
    os.makedirs(log_dir, exist_ok=True)
    
    # 1. Train the model: ./deploy.sh molclass.ModelBuilder <model_id>
    cmd_builder = ["./deploy.sh", "molclass.ModelBuilder", str(model_id)]
    try:
        with open(os.path.join(log_dir, "output_modelbuilder.log"), "a") as out_b, \
             open(os.path.join(log_dir, "error_modelbuilder.log"), "a") as err_b:
            subprocess.run(
                cmd_builder,
                stdout=out_b,
                stderr=err_b,
                cwd=repo_root,
                env=get_subprocess_env(),
                check=True
            )
    except Exception as e:
        print(f"Error building model {model_id}: {e}")
        send_email_notification(
            email,
            "Model Creation Failed",
            f"Your model creation for model {model_id} failed during Weka model building. Error: {e}"
        )
        return
        
    # Send email for model creation complete
    model_url = f"{settings.website}/view_model_detail.php?model_id={model_id}"
    send_email_notification(
        email,
        "Model Creation Complete",
        f"Your model creation is complete. You can check the created model at {model_url}"
    )

    # 2. Query all batches from the batchlist table:
    db = SessionLocal()
    last_batch_id = None
    try:
        batches_sql = "SELECT batch_id FROM batchlist"
        batches = db.execute(text(batches_sql)).scalars().all()
        
        # 3. Loop through batches, insert prediction lists, and run Predictor
        for batch_id in batches:
            last_batch_id = batch_id
            pred_name = f"{pred_name_base}{batch_id}"
            
            insert_pred_sql = """
                INSERT INTO prediction_list (username, batch_id, model_id, pred_name, email)
                VALUES (:username, :batch_id, :model_id, :pred_name, 'dummy@example.com')
            """
            db.execute(text(insert_pred_sql), {
                "username": username,
                "batch_id": batch_id,
                "model_id": model_id,
                "pred_name": pred_name
            })
            db.commit()
            
            pred_id = db.execute(text("SELECT LAST_INSERT_ID()")).scalar()
            
            # Run ./deploy.sh molclass.Predictor <pred_id>
            cmd_predictor = ["./deploy.sh", "molclass.Predictor", str(pred_id)]
            try:
                with open(os.path.join(log_dir, "output_predictor.log"), "a") as out_p, \
                     open(os.path.join(log_dir, "error_predictor.log"), "a") as err_p:
                    subprocess.run(
                        cmd_predictor,
                        stdout=out_p,
                        stderr=err_p,
                        cwd=repo_root,
                        env=get_subprocess_env(),
                        check=True
                    )
            except Exception as e:
                print(f"Error predicting for batch {batch_id} with model {model_id}: {e}")
                
        # Send prediction complete email
        if last_batch_id is not None:
            batch_url = f"{settings.website}/view_batch_detail.php?batch_id={last_batch_id}"
            send_email_notification(
                email,
                "Prediction Complete",
                f"The Prediction for model {model_id} against all existing molecules in the database has been completed. For further details please visit {batch_url}"
            )
    except Exception as e:
        print(f"Error in model prediction loop: {e}")
    finally:
        db.close()


def resolve_mol_id(identifier: str, db: Session) -> int:
    clean_id = identifier.replace('_', '/')
    if clean_id.isdigit():
        return int(clean_id)
    
    # Query using UNION on individual indexes to guarantee O(1)/O(log N) lookup
    sql = """
        SELECT mol_id FROM sdftags WHERE compound_name = :clean_id
        UNION
        SELECT mol_id FROM moldb_moldata WHERE mol_name = :clean_id
        UNION
        SELECT mol_id FROM inchi_key WHERE inchi_key = :clean_id
        UNION
        SELECT mol_id FROM inchi_key WHERE smiles = :clean_id
        UNION
        SELECT mol_id FROM inchi_key WHERE inchi = :clean_id
        LIMIT 1
    """
    row = db.execute(text(sql), {"clean_id": clean_id}).scalar()
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Compound matching '{identifier}' not found"
        )
    return int(row)

@app.get("/compound/{id}", tags=["Compounds"])
def get_compound(id: str, db: Session = Depends(get_db)):
    """
    Retrieve complete details of a compound. 
    Accepts molecule ID, compound name, SMILES, InChI, or InChI key.
    Note: Underscores in the input string are replaced with slashes (e.g. for InChI strings).
    """
    mol_id = resolve_mol_id(id, db)
    sql = """
        SELECT a.*, b.inchi_key, b.smiles, c.compound_name, c.class as class_tag, 
               c.classifier, c.activity_class, c.*, b.inchi 
        FROM sdftags c 
        LEFT JOIN inchi_key b USING (mol_id) 
        LEFT JOIN moldb_moldata a USING (mol_id) 
        WHERE mol_id = :mol_id
    """
    try:
        row = db.execute(text(sql), {"mol_id": mol_id}).mappings().first()
        if not row:
            raise HTTPException(status_code=404, detail=f"Compound matching '{id}' not found")
        return dict(row)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/compound/{id}/structurefingerprint", tags=["Compounds"])
def get_compound_structure_fingerprint(id: str, db: Session = Depends(get_db)):
    """Retrieve structural fingerprints and InChI key fields for a compound."""
    mol_id = resolve_mol_id(id, db)
    sql = """
        SELECT fingerprints.*, inchi_key.* 
        FROM fingerprints 
        LEFT JOIN inchi_key USING (mol_id) 
        WHERE mol_id = :mol_id
    """
    try:
        row = db.execute(text(sql), {"mol_id": mol_id}).mappings().first()
        if not row:
            raise HTTPException(status_code=404, detail=f"Compound matching '{id}' not found")
        return dict(row)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/compound/{id}/propertyfingerprint", tags=["Compounds"])
def get_compound_property_fingerprint(id: str, db: Session = Depends(get_db)):
    """Retrieve physical property descriptors (CDK and Weka) for a compound."""
    mol_id = resolve_mol_id(id, db)
    sql = """
        SELECT moldb_molstat.*, cdk_descriptors.* 
        FROM moldb_molstat 
        LEFT JOIN cdk_descriptors USING (mol_id) 
        WHERE mol_id = :mol_id
    """
    try:
        row = db.execute(text(sql), {"mol_id": mol_id}).mappings().first()
        if not row:
            raise HTTPException(status_code=404, detail=f"Compound matching '{id}' not found")
        return dict(row)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/compound/{id}/modelfingerprint", response_model=ModelFingerprint, tags=["Compounds"])
def get_compound_model_fingerprint(id: str, db: Session = Depends(get_db)):
    """Retrieve trained machine learning model likelihood scores for a compound."""
    mol_id = resolve_mol_id(id, db)
    sql = """
        SELECT prediction_list.model_id, prediction_mols.lhood 
        FROM prediction_mols 
        LEFT JOIN prediction_list USING (pred_id) 
        WHERE mol_id = :mol_id
    """
    try:
        results = db.execute(text(sql), {"mol_id": mol_id}).mappings().all()
        predictions = {}
        for r in results:
            predictions[f"model_{r['model_id']}"] = r['lhood']
            
        return {
            "mol_id": mol_id,
            "predictions": predictions
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/compound/{id}/models", tags=["Compounds"])
def get_compound_models(id: str, db: Session = Depends(get_db)):
    """Retrieve detailed machine learning predictions grouped by models for a compound."""
    mol_id = resolve_mol_id(id, db)
    sql = """
        SELECT prediction_mols.*, prediction_list.model_id, prediction_list.batch_id 
        FROM prediction_mols 
        LEFT JOIN prediction_list USING (pred_id) 
        WHERE mol_id = :mol_id 
        GROUP BY mol_id, model_id
    """
    try:
        results = db.execute(text(sql), {"mol_id": mol_id}).mappings().all()
        return [dict(r) for r in results]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/model", response_model=List[ModelSummary], tags=["Models"])
def get_models(
    batch_id: Optional[int] = None,
    who: Optional[str] = None,
    username: Optional[str] = None,
    data_type: Optional[str] = None,
    class_scheme: Optional[str] = None,
    db: Session = Depends(get_db)
):
    """Retrieve all machine learning models in the system, with optional filtering."""
    sql = """
        SELECT model_id, name, classes, data_type, class_tag, class_scheme, info, pmid, filename 
        FROM class_models 
        LEFT JOIN batchlist USING (batch_id)
        WHERE 1
    """
    params = {}
    if batch_id is not None:
        sql += " AND batch_id = :batch_id"
        params["batch_id"] = batch_id
    if data_type:
        sql += " AND data_type = :data_type"
        params["data_type"] = data_type
    if class_scheme:
        sql += " AND class_scheme = :class_scheme"
        params["class_scheme"] = class_scheme
    if username:
        if who == "you":
            sql += " AND class_models.username = :username"
        elif who == "other":
            sql += " AND class_models.username <> :username"
        else:
            sql += " AND class_models.username = :username"
        params["username"] = username
    try:
        results = db.execute(text(sql), params).mappings().all()
        return [dict(r) for r in results]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/model/{id}", response_model=ModelDetail, tags=["Models"])
def get_model(id: int, db: Session = Depends(get_db)):
    """Retrieve specific details and logs for a machine learning model by its ID."""
    sql = """
        SELECT model_id, classes, data_type, class_tag, class_scheme, printout 
        FROM class_models 
        WHERE model_id = :id
    """
    try:
        row = db.execute(text(sql), {"id": id}).mappings().first()
        if not row:
            raise HTTPException(status_code=404, detail=f"Model with ID {id} not found")
        return dict(row)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/search/text", response_model=List[TextSearchResponse], tags=["Search"])
def text_search(query_string: str, limit: int = 50, db: Session = Depends(get_db)):
    """Search for compounds by molecule ID, compound name, InChI key, SMILES, or InChI."""
    search_query = f"%{query_string}%"
    sql = """
        SELECT mol_id, mol_name, inchi_key.inchi_key, inchi_key.smiles 
        FROM moldb_moldata 
        JOIN inchi_key USING (mol_id) 
        LEFT JOIN sdftags USING (mol_id) 
        WHERE inchi_key.inchi_key LIKE :query 
           OR inchi_key.smiles LIKE :query 
           OR inchi_key.inchi LIKE :query 
           OR moldb_moldata.mol_name LIKE :query 
           OR sdftags.compound_name LIKE :query
        LIMIT :limit
    """
    try:
        results = db.execute(text(sql), {"query": search_query, "limit": limit}).mappings().all()
        return [dict(r) for r in results]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/compound/{id}/similar", response_model=List[SimilarityResponse], tags=["Compounds"])
def get_compound_similar(id: str, limit: int = 100, db: Session = Depends(get_db)):
    """Retrieve structurally similar compounds based on Tanimoto similarity scores."""
    mol_id = resolve_mol_id(id, db)
    sql = """
        SELECT mol_id2 as mol_id, ext, kr 
        FROM tanimoto 
        WHERE mol_id1 = :mol_id 
        ORDER BY ext DESC, kr DESC 
        LIMIT :limit
    """
    try:
        results = db.execute(text(sql), {"mol_id": mol_id, "limit": limit}).mappings().all()
        return [dict(r) for r in results]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/compound/{id}/scaffold", response_model=List[ScaffoldMatchResponse], tags=["Compounds"])
def get_compound_scaffold_matches(id: str, db: Session = Depends(get_db)):
    """Retrieve other compounds sharing the same Bemis-Murcko scaffold framework."""
    mol_id = resolve_mol_id(id, db)
    scaffold_sql = "SELECT murcko_id FROM murcko_mol WHERE mol_id = :mol_id LIMIT 1"
    try:
        murcko_id = db.execute(text(scaffold_sql), {"mol_id": mol_id}).scalar()
        if murcko_id is None:
            raise HTTPException(status_code=404, detail="No Murcko scaffold framework found for this compound")
        
        sql = """
            SELECT mol_id, mol_name, smiles, inchi_key 
            FROM murcko_mol 
            JOIN moldb_moldata USING (mol_id) 
            LEFT JOIN inchi_key USING (mol_id) 
            WHERE murcko_id = :murcko_id
        """
        results = db.execute(text(sql), {"murcko_id": murcko_id}).mappings().all()
        return [dict(r) for r in results]
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/model/create", response_model=ModelCreateResponse, tags=["Models"])
def create_model(request: ModelCreateRequest, background_tasks: BackgroundTasks, db: Session = Depends(get_db)):
    """
    Trigger the creation and training of a new machine learning model.
    Checks for duplicates, inserts the model configuration record, and spawns the training worker background job.
    """
    # Strict validation to prevent shell injection in arguments
    if not re.match(r"^[a-zA-Z0-9_\-\.]+$", request.username):
        raise HTTPException(status_code=400, detail="Invalid username format")
    if not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", request.email):
        raise HTTPException(status_code=400, detail="Invalid email format")
    if not re.match(r"^[a-zA-Z0-9_\-\. ]+$", request.classifier):
        raise HTTPException(status_code=400, detail="Invalid classifier format")
    if not re.match(r"^[a-zA-Z0-9_\-\.]+$", request.data_type):
        raise HTTPException(status_code=400, detail="Invalid data_type format")
    if not re.match(r"^[a-zA-Z0-9_\-\. ]+$", request.class_scheme):
        raise HTTPException(status_code=400, detail="Invalid class_scheme format")

    check_sql = """
        SELECT model_id FROM class_models 
        WHERE batch_id = :batch_id 
          AND class_tag = :classifier 
          AND data_type = :data_type 
          AND class_scheme = :class_scheme
    """
    try:
        existing_id = db.execute(text(check_sql), {
            "batch_id": request.batch_id,
            "classifier": request.classifier,
            "data_type": request.data_type,
            "class_scheme": request.class_scheme
        }).scalar()
        
        if existing_id is not None:
            return ModelCreateResponse(
                model_id=existing_id,
                message="The model configuration already exists in the system."
            )
            
        insert_sql = """
            INSERT INTO class_models (username, batch_id, class_tag, data_type, class_scheme, email, printout) 
            VALUES (:username, :batch_id, :classifier, :data_type, :class_scheme, :email, '')
        """
        db.execute(text(insert_sql), {
            "username": request.username,
            "batch_id": request.batch_id,
            "classifier": request.classifier,
            "data_type": request.data_type,
            "class_scheme": request.class_scheme,
            "email": request.email
        })
        db.commit()

        model_id = db.execute(text("SELECT LAST_INSERT_ID()")).scalar()
        
        pred_name = str(request.batch_id)
        background_tasks.add_task(create_model_and_predictions_task, model_id, request.username, str(request.batch_id), request.email)
        
        return ModelCreateResponse(
            model_id=model_id,
            message="Model creation job successfully submitted in the background."
        )
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=500, detail=str(e))

@app.delete("/dataset/{id}", tags=["Datasets"])
def delete_dataset(id: int, background_tasks: BackgroundTasks, db: Session = Depends(get_db)):
    """
    Trigger the deletion of a dataset batch.
    Validates batch presence and spawns the background Perl deletion job.
    """
    check_sql = "SELECT batch_id FROM batchlist WHERE batch_id = :id"
    batch_check = db.execute(text(check_sql), {"id": id}).scalar()
    if batch_check is None:
        raise HTTPException(status_code=404, detail=f"Dataset batch with ID {id} not found")

    background_tasks.add_task(delete_batch_task, id)

    return {"message": f"Deletion job for batch {id} successfully submitted in the background."}


@app.get("/dataset/{id}/predictions", tags=["Datasets"])
def get_dataset_predictions(id: int, db: Session = Depends(get_db)):
    """Retrieve predictions associated with a dataset batch."""
    sql = """
        SELECT prediction_list.batch_id, pred_id, model_id, classes, data_type, class_tag, class_scheme 
        FROM prediction_list 
        JOIN class_models USING (model_id) 
        WHERE prediction_list.batch_id = :id
    """
    try:
        results = db.execute(text(sql), {"id": id}).mappings().all()
        return [dict(r) for r in results]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/model/{id}/predictions", tags=["Models"])
def get_model_predictions(id: int, db: Session = Depends(get_db)):
    """Retrieve all prediction batches executed under a specific model."""
    sql = """
        SELECT pred_id, batch_id, info 
        FROM prediction_list 
        JOIN batchlist USING (batch_id) 
        WHERE model_id = :id
    """
    try:
        results = db.execute(text(sql), {"id": id}).mappings().all()
        return [dict(r) for r in results]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/prediction/{id}", tags=["Predictions"])
def get_prediction(id: int, db: Session = Depends(get_db)):
    """Retrieve details of a single prediction job by its ID."""
    sql = """
        SELECT pred_id, prediction_list.printout, prediction_list.batch_id, model_id, classes, data_type, class_tag, class_scheme 
        FROM prediction_list 
        JOIN class_models USING (model_id) 
        WHERE pred_id = :id
    """
    try:
        row = db.execute(text(sql), {"id": id}).mappings().first()
        if not row:
            raise HTTPException(status_code=404, detail=f"Prediction with ID {id} not found")
        return dict(row)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/prediction/{id}/results", tags=["Predictions"])
def get_prediction_results(id: int, db: Session = Depends(get_db)):
    """Retrieve compound-by-compound prediction results for a specific prediction run."""
    sql = """
        SELECT mol_id, main_class, distribution, lhood, mol_name, inchi_key 
        FROM sdftags 
        JOIN inchi_key USING (mol_id) 
        JOIN prediction_mols USING (mol_id) 
        JOIN moldb_moldata USING (mol_id) 
        WHERE pred_id = :id
    """
    try:
        results = db.execute(text(sql), {"id": id}).mappings().all()
        return [dict(r) for r in results]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# Task store and lock for thread-safety
prediction_tasks = {}
prediction_tasks_lock = threading.Lock()

def run_single_prediction_task(
    task_id: str,
    identifier_type: str,
    identifier: str,
    model_ids: List[int]
):
    repo_root = find_repo_root(settings.tools_dir)
    temp_sdf_path = os.path.join(repo_root, f"uploads/temp_single_{task_id}.sdf")
    batch_id = None
    db = SessionLocal()
    
    try:
        # Step 1: Update status to running
        with prediction_tasks_lock:
            if task_id in prediction_tasks:
                prediction_tasks[task_id]["status"] = "running"

        # Step 2: Convert SMILES/InChI to SDF using CDK
        cmd = [
            "bash", "-c",
            f"source ./classpath.sh && java -Dweka.core.WekaPackageManager.offline=true -cp \"$CLASSPATH\" molclass.SdfConverter {identifier_type} '{identifier}'"
        ]
        res = subprocess.run(cmd, capture_output=True, text=True, cwd=repo_root)
        if res.returncode != 0:
            raise RuntimeError(f"CDK SdfConverter failed: {res.stderr or res.stdout}")
        
        sdf_content = res.stdout.rstrip()
        if not sdf_content or "V2000" not in sdf_content:
            raise RuntimeError(f"Invalid SDF generated from SdfConverter: {sdf_content}")

        # Step 3: Write the SDF file
        os.makedirs(os.path.dirname(temp_sdf_path), exist_ok=True)
        with open(temp_sdf_path, "w") as f:
            f.write(sdf_content)

        # Step 3.5: Run sdfcheck.pl to generate the def file
        check_script = os.path.join(settings.tools_dir, "sdfcheck.pl")
        check_cmd = [
            "perl", check_script,
            temp_sdf_path
        ]
        check_res = subprocess.run(check_cmd, capture_output=True, text=True, cwd=repo_root, env=get_subprocess_env())
        if check_res.returncode != 0:
            raise RuntimeError(f"sdfcheck.pl failed: {check_res.stderr}\nStdout: {check_res.stdout}")

        # Step 4: Import molecule to a temporary test batch via SdfImporter
        info_string = f"Single molecule prediction task {task_id}"
        
        import_cmd = [
            "./deploy.sh", "molclass.SdfImporter",
            temp_sdf_path,
            "single_pred_user",
            "dummy@example.com",
            "test",
            "0",
            info_string,
            f"single_{task_id}"
        ]
        
        import_res = subprocess.run(import_cmd, capture_output=True, text=True, cwd=repo_root, env=get_subprocess_env())
        if import_res.returncode != 0:
            raise RuntimeError(f"SdfImporter failed: {import_res.stderr}\nStdout: {import_res.stdout}")

        # Step 5: Find the batch_id
        batch_id_query = text("SELECT batch_id FROM batchlist WHERE info = :info")
        batch_id = db.execute(batch_id_query, {"info": info_string}).scalar()
        if not batch_id:
            raise RuntimeError("Temporary batch creation failed: batch_id not found in database")

        # Step 6: Loop through selected models and run Predictor
        for model_id in model_ids:
            try:
                # Check if model exists
                model_exists = db.execute(
                    text("SELECT model_id FROM class_models WHERE model_id = :id"),
                    {"id": model_id}
                ).scalar()
                
                if not model_exists:
                    with prediction_tasks_lock:
                        if task_id in prediction_tasks:
                            prediction_tasks[task_id]["results"][model_id] = {
                                "error": f"Model with ID {model_id} not found in system"
                            }
                            prediction_tasks[task_id]["processed_count"] += 1
                    continue

                # Insert prediction job record
                pred_name = f"single_pred_{task_id}_{model_id}"
                insert_sql = """
                    INSERT INTO prediction_list (username, batch_id, model_id, pred_name, email)
                    VALUES (:username, :batch_id, :model_id, :pred_name, :email)
                """
                db.execute(text(insert_sql), {
                    "username": "single_pred_user",
                    "batch_id": batch_id,
                    "model_id": model_id,
                    "pred_name": pred_name,
                    "email": "dummy@example.com"
                })
                db.commit()
                
                pred_id = db.execute(text("SELECT LAST_INSERT_ID()")).scalar()
                
                # Run Predictor via deploy.sh
                pred_cmd = [
                    "./deploy.sh",
                    "molclass.Predictor",
                    str(pred_id)
                ]
                pred_res = subprocess.run(pred_cmd, capture_output=True, text=True, cwd=repo_root)
                
                # Retrieve result from prediction_mols
                result_sql = """
                    SELECT main_class, distribution, lhood 
                    FROM prediction_mols 
                    WHERE pred_id = :pred_id
                """
                row = db.execute(text(result_sql), {"pred_id": pred_id}).mappings().first()
                
                with prediction_tasks_lock:
                    if task_id in prediction_tasks:
                        if row:
                            prediction_tasks[task_id]["results"][model_id] = {
                                "main_class": row["main_class"],
                                "distribution": row["distribution"],
                                "likelihood": row["lhood"]
                            }
                        else:
                            prediction_tasks[task_id]["results"][model_id] = {
                                "error": f"No prediction outcome generated. Stderr: {pred_res.stderr}"
                            }
                        prediction_tasks[task_id]["processed_count"] += 1
            except Exception as model_err:
                with prediction_tasks_lock:
                    if task_id in prediction_tasks:
                        prediction_tasks[task_id]["results"][model_id] = {
                            "error": f"Error during model prediction: {str(model_err)}"
                        }
                        prediction_tasks[task_id]["processed_count"] += 1

        # Step 7: Completed
        with prediction_tasks_lock:
            if task_id in prediction_tasks:
                prediction_tasks[task_id]["status"] = "completed"
                
    except Exception as e:
        with prediction_tasks_lock:
            if task_id in prediction_tasks:
                prediction_tasks[task_id]["status"] = "failed"
                prediction_tasks[task_id]["error"] = str(e)
                
    finally:
        # Cleanup batch from database
        if batch_id:
            try:
                delete_script = os.path.join(settings.tools_dir, "delete_batch.pl")
                subprocess.run(
                    ["perl", delete_script, str(batch_id)],
                    capture_output=True, cwd=repo_root, env=get_subprocess_env()
                )
            except Exception:
                pass
        # Cleanup temporary SDF file
        if os.path.exists(temp_sdf_path):
            try:
                os.remove(temp_sdf_path)
            except Exception:
                pass
        # Cleanup temporary def file
        temp_def_path = os.path.join(repo_root, f"uploads/sdf2moldb_temp_single_{task_id}.sdf.def")
        if os.path.exists(temp_def_path):
            try:
                os.remove(temp_def_path)
            except Exception:
                pass
        db.close()


@app.post("/prediction/single", response_model=SinglePredictionTaskResponse, tags=["Predictions"])
def submit_single_prediction(request: SinglePredictionRequest, background_tasks: BackgroundTasks):
    """
    Submit a single molecule (SMILES or InChI string) to run predictions against a set of models in the background.
    Creates and returns a task_id handler to fetch results.
    """
    if request.identifier_type.lower() not in ["smiles", "inchi"]:
        raise HTTPException(status_code=400, detail="identifier_type must be either 'smiles' or 'inchi'")
    if not request.identifier.strip():
        raise HTTPException(status_code=400, detail="identifier string cannot be empty")
    if not request.model_ids:
        raise HTTPException(status_code=400, detail="model_ids list cannot be empty")

    task_id = str(uuid.uuid4())
    
    with prediction_tasks_lock:
        prediction_tasks[task_id] = {
            "task_id": task_id,
            "status": "pending",
            "processed_count": 0,
            "total_count": len(request.model_ids),
            "results": {},
            "error": None
        }
        
    background_tasks.add_task(
        run_single_prediction_task,
        task_id,
        request.identifier_type.lower(),
        request.identifier,
        request.model_ids
    )
    
    return SinglePredictionTaskResponse(
        task_id=task_id,
        status="pending",
        message="Single molecule prediction task submitted successfully."
    )


@app.get("/prediction/task/{task_id}", response_model=PredictionTaskStatusResponse, tags=["Predictions"])
def get_prediction_task_status(task_id: str):
    """
    Retrieve the status and results of a submitted single molecule prediction task.
    Supports fetching intermediate results while the task is still running.
    """
    with prediction_tasks_lock:
        if task_id not in prediction_tasks:
            raise HTTPException(status_code=404, detail=f"Prediction task with ID {task_id} not found")
        return prediction_tasks[task_id]


@app.post("/search/structure", response_model=List[StructureSearchResult], tags=["Search"])
def structure_search(request: StructureSearchRequest):
    """
    Perform structural similarity or substructure searches in the database using CDK.
    Does not use checkmol/matchmol binaries, but executes a fast, optimized, parallelized JVM search process.
    """
    if request.search_type.lower() not in ["similarity", "substructure"]:
        raise HTTPException(status_code=400, detail="search_type must be either 'similarity' or 'substructure'")
    if request.query_type.lower() not in ["smiles", "inchi"]:
        raise HTTPException(status_code=400, detail="query_type must be either 'smiles' or 'inchi'")
    if not request.query_string.strip():
        raise HTTPException(status_code=400, detail="query_string cannot be empty")
    if request.fingerprint_type.lower() not in ["maccs", "pubchem", "kr"]:
        raise HTTPException(status_code=400, detail="fingerprint_type must be 'maccs', 'pubchem', or 'kr'")

    # Run StructureSearch via deploy.sh
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../.."))
    cmd = [
        "./deploy.sh",
        "molclass.StructureSearch",
        request.search_type.lower(),
        request.query_type.lower(),
        request.query_string,
        request.fingerprint_type.lower(),
        str(request.limit),
        str(request.threshold)
    ]
    
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, cwd=repo_root)
        if res.returncode != 0:
            raise HTTPException(status_code=500, detail=f"Search failed: {res.stderr}")
        
        # Parse output JSON
        import json
        output = res.stdout
        
        json_lines = []
        for line in output.splitlines():
            line_str = line.strip()
            if line_str.startswith("[deploy]") or line_str.startswith("---") or not line_str:
                continue
            json_lines.append(line_str)
            
        json_str = "".join(json_lines)
        results = json.loads(json_str)
        return results
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))



from app.v3_model_reviews import router as v3_model_reviews_router
app.include_router(v3_model_reviews_router)
