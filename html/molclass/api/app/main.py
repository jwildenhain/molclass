from fastapi import FastAPI, Depends, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session
from sqlalchemy import text
from typing import List, Dict, Any, Union, Optional
import re
import os
import subprocess

from app.database import get_db
from app.config import settings
from app.schemas import (
    Dataset, ModelSummary, ModelDetail, CompoundIdResponse, ModelFingerprint,
    ModelCreateRequest, ModelCreateResponse, SimilarityResponse,
    ScaffoldMatchResponse, TextSearchResponse
)

app = FastAPI(
    title="MolClass REST API",
    description="Modernized REST API for the MolClass chemical informatics portal. Exposes datasets, compounds, descriptors, fingerprints, and model predictions.",
    version="2.0.0",
    docs_url="/docs",
    redoc_url="/redoc"
)

# Configure CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

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
        results = db.execute(text(sql), {"mol_id": mol_id}).all()
        predictions = {}
        for r in results:
            predictions[f"model_{r[0]}"] = r[1]
            
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
def create_model(request: ModelCreateRequest, db: Session = Depends(get_db)):
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
        perl_script = os.path.join(settings.tools_dir, "pred_model_upload.pl")
        
        cmd = [
            "perl",
            perl_script,
            request.username,
            str(model_id),
            pred_name,
            request.email
        ]
        
        repo_root = find_repo_root(settings.tools_dir)
        os.makedirs(os.path.join(repo_root, "log"), exist_ok=True)
        
        log_dir = os.path.join(settings.tools_dir, "log")
        os.makedirs(log_dir, exist_ok=True)
        out_log = open(os.path.join(log_dir, "output_model_creation_api.log"), "a")
        err_log = open(os.path.join(log_dir, "error_output_model_creation_api.log"), "a")
        
        subprocess.Popen(cmd, stdout=out_log, stderr=err_log, cwd=repo_root, env=get_subprocess_env(), start_new_session=True)
        
        return ModelCreateResponse(
            model_id=model_id,
            message="Model creation job successfully submitted in the background."
        )
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=500, detail=str(e))

@app.delete("/dataset/{id}", tags=["Datasets"])
def delete_dataset(id: int, db: Session = Depends(get_db)):
    """
    Trigger the deletion of a dataset batch.
    Validates batch presence and spawns the background Perl deletion job.
    """
    check_sql = "SELECT batch_id FROM batchlist WHERE batch_id = :id"
    batch_check = db.execute(text(check_sql), {"id": id}).scalar()
    if batch_check is None:
        raise HTTPException(status_code=404, detail=f"Dataset batch with ID {id} not found")

    perl_script = os.path.join(settings.tools_dir, "delete_batch.pl")
    cmd = ["perl", perl_script, str(id)]
    
    try:
        repo_root = find_repo_root(settings.tools_dir)
        os.makedirs(os.path.join(repo_root, "log"), exist_ok=True)
        
        log_dir = os.path.join(settings.tools_dir, "log")
        os.makedirs(log_dir, exist_ok=True)
        out_log = open(os.path.join(log_dir, "output_delete_batch_api.log"), "a")
        err_log = open(os.path.join(log_dir, "error_output_delete_batch_api.log"), "a")
        
        subprocess.Popen(cmd, stdout=out_log, stderr=err_log, cwd=repo_root, env=get_subprocess_env(), start_new_session=True)
        
        return {"message": f"Deletion job for batch {id} successfully submitted in the background."}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

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
