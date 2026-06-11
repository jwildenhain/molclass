from fastapi import FastAPI, Depends, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session
from sqlalchemy import text
from typing import List, Dict, Any, Union
import re

from app.database import get_db
from app.schemas import Dataset, ModelSummary, ModelDetail, CompoundIdResponse, ModelFingerprint

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
def get_datasets(db: Session = Depends(get_db)):
    """Retrieve all dataset batches in the database."""
    sql = "SELECT batch_id, info, tags, pmid, mol_type FROM batchlist"
    try:
        results = db.execute(text(sql)).mappings().all()
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
def get_models(db: Session = Depends(get_db)):
    """Retrieve a list of all machine learning models in the system."""
    sql = """
        SELECT model_id, name, classes, data_type, class_tag, class_scheme, info, pmid, filename 
        FROM class_models 
        LEFT JOIN batchlist USING (batch_id)
    """
    try:
        results = db.execute(text(sql)).mappings().all()
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
