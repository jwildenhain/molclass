from pydantic import BaseModel, Field
from typing import Optional, List, Dict, Any

class Dataset(BaseModel):
    batch_id: int
    info: Optional[str] = None
    tags: Optional[str] = None
    pmid: Optional[str] = None
    mol_type: Optional[str] = None

    class Config:
        from_attributes = True

class ModelSummary(BaseModel):
    model_id: int
    name: Optional[str] = None
    classes: Optional[str] = None
    data_type: Optional[str] = None
    class_tag: Optional[str] = None
    class_scheme: Optional[str] = None
    info: Optional[str] = None
    pmid: Optional[str] = None
    filename: Optional[str] = None

    class Config:
        from_attributes = True

class ModelDetail(BaseModel):
    model_id: int
    classes: Optional[str] = None
    data_type: Optional[str] = None
    class_tag: Optional[str] = None
    class_scheme: Optional[str] = None
    printout: Optional[str] = None

    class Config:
        from_attributes = True

class CompoundIdResponse(BaseModel):
    mol_id: int

class ModelFingerprint(BaseModel):
    mol_id: int
    predictions: Dict[str, Optional[float]]

class ModelCreateRequest(BaseModel):
    batch_id: int
    classifier: str
    data_type: str
    class_scheme: str
    email: str
    username: str

class ModelCreateResponse(BaseModel):
    model_id: int
    message: str

class SimilarityResponse(BaseModel):
    mol_id: int
    ext: float
    kr: float

class ScaffoldMatchResponse(BaseModel):
    mol_id: int
    mol_name: Optional[str] = None
    smiles: Optional[str] = None
    inchi_key: Optional[str] = None

class TextSearchResponse(BaseModel):
    mol_id: int
    mol_name: Optional[str] = None
    inchi_key: Optional[str] = None
    smiles: Optional[str] = None

class SinglePredictionRequest(BaseModel):
    identifier_type: str = Field(..., description="Either 'smiles' or 'inchi'")
    identifier: str = Field(..., description="The SMILES or InChI identifier string")
    model_ids: List[int] = Field(..., description="List of model IDs to run predictions against")

class SinglePredictionTaskResponse(BaseModel):
    task_id: str
    status: str
    message: str

class PredictionTaskStatusResponse(BaseModel):
    task_id: str
    status: str
    processed_count: int
    total_count: int
    results: Dict[int, Optional[Dict[str, Any]]]
    error: Optional[str] = None


