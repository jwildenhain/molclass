from pydantic import BaseModel
from typing import Optional, Dict

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

