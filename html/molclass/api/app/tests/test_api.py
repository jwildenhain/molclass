# test_api.py
"""
Unit‑test suite for the MolClass FastAPI service.

The real DB is replaced with a lightweight mock (`FakeSession`) that
implements just enough of the SQLAlchemy Session API for the routes
to run.  This lets us verify:
* routing & path parameters,
* request‑body validation,
* background‑task registration,
* correct HTTP status codes,
* and the shape of the JSON responses.

All endpoints from `main.py` are exercised.
"""

import json
import sys
import os
from typing import Any, Dict, List, Optional

import pytest
from fastapi import status
from fastapi.testclient import TestClient

# Ensure the project root is in sys.path so 'app' can be imported.
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))

# Import the FastAPI app defined in the project.
from app.main import app, get_db  # type: ignore

# ----------------------------------------------------------------------
# Mock database session
# ----------------------------------------------------------------------
class _FakeResult:
    """Default result – returns empty collections / None, but can be customized for tests."""
    def __init__(self, scalar_value: Any = None, first_result: Optional[Dict] = None, all_results: Optional[List[Dict]] = None):
        self._scalar = scalar_value
        self._first = first_result
        self._all = all_results

    # .mappings() returns self to allow .all() / .first() calls.
    def mappings(self) -> "_FakeResult":
        return self

    # Return all results list.
    def all(self) -> List[Dict]:
        return self._all if self._all is not None else []

    # Return the first result dict.
    def first(self) -> Dict:
        return self._first if self._first is not None else {"id": 1}

    # .scalar() returns the preset scalar value (or None)
    def scalar(self) -> Any:
        return self._scalar


# Global variable to capture the FakeSession used during request
LAST_FAKE_SESSION: Optional["FakeSession"] = None


class FakeSession:
    """A very small stub that mimics the subset of SQLAlchemy Session used
    by the API routes.  It records the last SQL statement for debugging.
    """
    def __init__(self):
        self.last_sql: str | None = None

    def execute(self, sql, params: Optional[Dict] = None):
        sql_lower = str(sql).strip().lower()
        # Prioritize storing INSERT statements for background task assertions.
        if sql_lower.startswith("insert"):
            self.last_sql = sql_lower
        elif self.last_sql is None:
            self.last_sql = sql_lower

        # Special handling for statements that the code expects a scalar result.
        if "select last_insert_id()" in sql_lower:
            return _FakeResult(scalar_value=12345)
        if "select model_id from class_models" in sql_lower:
            return _FakeResult(scalar_value=None, first_result=None, all_results=[])

        # For any other SELECT, return a generic record with common fields.
        if sql_lower.startswith("select"):
            dummy = {
                "model_id": 1,
                "batch_id": 1,
                "pred_id": 1,
                "prediction_list.printout": "",
                "classes": "A",
                "data_type": "type",
                "class_tag": "tag",
                "class_scheme": "scheme",
                "printout": "",
                "mol_id": 1,
                "mol_name": "Dummy",
                "inchi_key": "XYZ",
                "smiles": "C",
                "compound_name": "Dummy",
                "info": "",
                "pmid": "",
                "filename": "",
                "lhood": 0.5,
                "ext": 0.9,
                "kr": 0.8,
                "distribution": "",
                "main_class": "",
                "maccs": "",
                "fingerprints": {},
                "prediction_mols": {},
                "prediction_list": {},
            }
            return _FakeResult(scalar_value=1, first_result=dummy, all_results=[dummy])
        # All other statements (INSERT/DELETE/UPDATE) just succeed.
        return _FakeResult()

    def commit(self):
        pass

    def rollback(self):
        pass

    def close(self):
        pass

# ----------------------------------------------------------------------
# Pytest fixtures
# ----------------------------------------------------------------------
@pytest.fixture
def client():
    """
    Provide a TestClient with the `get_db` dependency overridden to use
    our FakeSession.  The fixture yields a client that can be used in any
    test function.
    """
    def _override_get_db():
        global LAST_FAKE_SESSION
        session = FakeSession()
        LAST_FAKE_SESSION = session
        try:
            yield session
        finally:
            session.close()

    app.dependency_overrides[get_db] = _override_get_db
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()

# ----------------------------------------------------------------------
# Helper payloads
# ----------------------------------------------------------------------
MODEL_CREATE_PAYLOAD = {
    "username": "test_user",
    "email": "test_user@example.com",
    "batch_id": 1,
    "classifier": "RandomForest",
    "data_type": "JUMBO",
    "class_scheme": "ALL",
}

# ----------------------------------------------------------------------
# Tests – one per endpoint
# ----------------------------------------------------------------------
def test_root_endpoint(client: TestClient):
    resp = client.get("/")
    assert resp.status_code == status.HTTP_200_OK
    data = resp.json()
    assert "instructions" in data
    assert "api_documentation" in data

def test_get_datasets(client: TestClient):
    resp = client.get("/dataset")
    assert resp.status_code == status.HTTP_200_OK
    assert isinstance(resp.json(), list)

def test_get_single_dataset(client: TestClient):
    resp = client.get("/dataset/1")
    assert resp.status_code == status.HTTP_200_OK
    data = resp.json()
    assert "batch_id" in data

def test_get_dataset_compounds(client: TestClient):
    resp = client.get("/dataset/1/compounds")
    assert resp.status_code == status.HTTP_200_OK
    assert isinstance(resp.json(), list)

def test_text_search(client: TestClient):
    resp = client.get("/search/text", params={"query_string": "aspirin"})
    assert resp.status_code == status.HTTP_200_OK
    assert isinstance(resp.json(), list)

def test_get_compound(client: TestClient):
    resp = client.get("/compound/1")
    assert resp.status_code == status.HTTP_200_OK
    data = resp.json()
    assert "mol_id" in data or "mol_name" in data

def test_get_compound_structure_fingerprint(client: TestClient):
    resp = client.get("/compound/1/structurefingerprint")
    assert resp.status_code == status.HTTP_200_OK
    assert isinstance(resp.json(), dict)

def test_get_compound_property_fingerprint(client: TestClient):
    resp = client.get("/compound/1/propertyfingerprint")
    assert resp.status_code == status.HTTP_200_OK
    assert isinstance(resp.json(), dict)

def test_get_compound_model_fingerprint(client: TestClient):
    resp = client.get("/compound/1/modelfingerprint")
    assert resp.status_code == status.HTTP_200_OK
    data = resp.json()
    assert "predictions" in data

def test_get_compound_models(client: TestClient):
    resp = client.get("/compound/1/models")
    assert resp.status_code == status.HTTP_200_OK
    assert isinstance(resp.json(), list)

def test_get_compound_similar(client: TestClient):
    resp = client.get("/compound/1/similar")
    assert resp.status_code == status.HTTP_200_OK
    assert isinstance(resp.json(), list)

def test_get_compound_scaffold_matches(client: TestClient):
    resp = client.get("/compound/1/scaffold")
    assert resp.status_code == status.HTTP_200_OK
    assert isinstance(resp.json(), list)

def test_get_models(client: TestClient):
    resp = client.get("/model")
    assert resp.status_code == status.HTTP_200_OK
    assert isinstance(resp.json(), list)

def test_get_single_model(client: TestClient):
    resp = client.get("/model/1")
    assert resp.status_code == status.HTTP_200_OK
    data = resp.json()
    assert "model_id" in data

def test_create_model_success(client: TestClient):
    resp = client.post("/model/create", json=MODEL_CREATE_PAYLOAD)
    assert resp.status_code == status.HTTP_200_OK
    data = resp.json()
    assert "model_id" in data
    assert data["model_id"] == 12345
    assert "message" in data

def test_delete_dataset_success(client: TestClient):
    resp = client.delete("/dataset/1")
    assert resp.status_code == status.HTTP_200_OK
    data = resp.json()
    assert "message" in data
    assert "Deletion job for batch 1" in data["message"]

def test_get_dataset_predictions(client: TestClient):
    resp = client.get("/dataset/1/predictions")
    assert resp.status_code == status.HTTP_200_OK
    assert isinstance(resp.json(), list)

def test_get_model_predictions(client: TestClient):
    resp = client.get("/model/1/predictions")
    assert resp.status_code == status.HTTP_200_OK
    assert isinstance(resp.json(), list)

def test_get_prediction(client: TestClient):
    resp = client.get("/prediction/1")
    assert resp.status_code == status.HTTP_200_OK
    data = resp.json()
    assert "pred_id" in data

def test_get_prediction_results(client: TestClient):
    resp = client.get("/prediction/1/results")
    assert resp.status_code == status.HTTP_200_OK
    assert isinstance(resp.json(), list)

def test_background_task_registration_on_create_model(client: TestClient):
    resp = client.post("/model/create", json=MODEL_CREATE_PAYLOAD)
    assert resp.status_code == status.HTTP_200_OK
    fake_session = LAST_FAKE_SESSION
    assert fake_session is not None
    assert "insert into class_models" in (fake_session.last_sql or "")

def test_create_model_invalid_email(client: TestClient):
    bad_payload = MODEL_CREATE_PAYLOAD.copy()
    bad_payload["email"] = "not-an-email"
    resp = client.post("/model/create", json=bad_payload)
    assert resp.status_code == status.HTTP_400_BAD_REQUEST
    assert "Invalid email format" in resp.text

