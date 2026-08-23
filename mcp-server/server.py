#!/usr/bin/env python3
"""MCP server exposing MolClass molecule search and prediction as tools.

Talks to a running MolClass deployment's v3 prediction API (see
spring_boot_predictor/src/main/java/molclass/predictor/V3PredictionController.java).
Predictions only work for molecules already indexed in that deployment's database --
this server cannot register brand-new structures, since the production API
deliberately has no route for that (see the FAQ page in molclass-frontend).
"""
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx
from mcp.server.fastmcp import FastMCP

BASE_URL = os.environ.get("MOLCLASS_BASE_URL", "http://127.0.0.1:8082").rstrip("/")
DAILY_LIMIT = int(os.environ.get("MOLCLASS_MCP_DAILY_LIMIT", "100"))
STATE_PATH = Path(
    os.environ.get("MOLCLASS_MCP_STATE_FILE", str(Path.home() / ".molclass-mcp" / "usage.json"))
)

mcp = FastMCP("molclass")


def _today() -> str:
    return datetime.now(timezone.utc).date().isoformat()


def _load_usage() -> dict[str, Any]:
    if STATE_PATH.exists():
        try:
            usage = json.loads(STATE_PATH.read_text())
            if usage.get("date") == _today() and isinstance(usage.get("count"), int):
                return usage
        except (json.JSONDecodeError, OSError):
            pass
    return {"date": _today(), "count": 0}


def _save_usage(usage: dict[str, Any]) -> None:
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    STATE_PATH.write_text(json.dumps(usage))


def _get(path: str, params: dict[str, Any] | None = None) -> Any:
    response = httpx.get(f"{BASE_URL}{path}", params=params, timeout=30.0)
    response.raise_for_status()
    return response.json()


def _post(path: str) -> Any:
    response = httpx.post(f"{BASE_URL}{path}", timeout=60.0)
    response.raise_for_status()
    return response.json()


@mcp.tool()
def list_models(query: str = "", limit: int = 25) -> list[dict[str, Any]]:
    """List MolClass prediction models available on this deployment, optionally filtered by name."""
    params: dict[str, Any] = {"limit": limit}
    if query:
        params["query"] = query
    return _get("/api/v3/models", params=params)


@mcp.tool()
def search_molecule(query: str, limit: int = 10) -> list[dict[str, Any]]:
    """Search this deployment's indexed compounds by name, identifier, or SMILES substring."""
    return _get("/api/v3/molecules", params={"query": query, "limit": limit})


@mcp.tool()
def search_molecule_by_structure(smiles: str, limit: int = 10) -> dict[str, Any]:
    """Substructure-search this deployment's indexed compounds using a SMILES pattern."""
    return _get("/api/v3/molecules/substructure", params={"smiles": smiles, "limit": limit})


@mcp.tool()
def get_molecule(molecule_id: int) -> dict[str, Any]:
    """Get details for one indexed molecule by its MolClass molecule ID."""
    return _get(f"/api/v3/molecules/{molecule_id}")


@mcp.tool()
def get_predictions(molecule_id: int, limit: int = 50) -> list[dict[str, Any]]:
    """Get predictions already computed for a molecule. Read-only -- does not use the daily quota."""
    return _get(f"/api/v3/molecules/{molecule_id}/predictions", params={"limit": limit})


@mcp.tool()
def predict(model_definition_id: int, molecule_id: int) -> dict[str, Any]:
    """Run a new prediction for one indexed molecule against one model.

    The molecule must already be indexed on this deployment -- find its id first with
    search_molecule or search_molecule_by_structure. Counts against this server's
    daily quota (see usage_status); each call spends one molecule of quota only if
    the prediction call itself succeeds.
    """
    usage = _load_usage()
    if usage["count"] >= DAILY_LIMIT:
        return {
            "error": "DAILY_QUOTA_EXCEEDED",
            "message": (
                f"This MCP server allows {DAILY_LIMIT} predictions per day; "
                "that limit has been reached for today (UTC). Try again after 00:00 UTC."
            ),
        }
    result = _post(f"/api/v3/models/{model_definition_id}/molecules/{molecule_id}/predict")
    usage["count"] += 1
    _save_usage(usage)
    if isinstance(result, dict):
        result["_quotaRemainingToday"] = DAILY_LIMIT - usage["count"]
    return result


@mcp.tool()
def usage_status() -> dict[str, Any]:
    """Check how much of today's prediction quota this MCP server has left."""
    usage = _load_usage()
    return {
        "date": usage["date"],
        "used": usage["count"],
        "limit": DAILY_LIMIT,
        "remaining": max(0, DAILY_LIMIT - usage["count"]),
    }


if __name__ == "__main__":
    mcp.run()
