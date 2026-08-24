# MolClass MCP server

Exposes a running MolClass deployment's molecule search and prediction API as MCP
tools, so an MCP-compatible client (Claude Desktop, Claude Code, etc.) can look up
compounds and run predictions as part of a conversation or agent workflow.

Two ways to predict: `predict` works only on molecules already indexed on the
target deployment (find the id first with `search_molecule` or
`search_molecule_by_structure`); `submit_and_predict` registers a brand-new
structure from a raw SMILES string first. The latter needs the deployment's data
intake enabled -- production deployments configured as read-only (search/predict
only) will reject it with a clear `DATA_INTAKE_DISABLED` error rather than hang.

## Tools

- `list_models(query, limit)` -- browse available prediction models
- `search_molecule(query, limit)` -- find indexed compounds by name/identifier
- `search_molecule_by_structure(smiles, limit)` -- substructure search by SMILES
- `get_molecule(molecule_id)` -- detail for one molecule
- `get_predictions(molecule_id, limit)` -- predictions already computed (no quota cost)
- `predict(model_definition_id, molecule_id)` -- predict on an already-indexed molecule (uses quota)
- `submit_and_predict(smiles, model_definition_id, timeout_seconds)` -- register a new molecule and predict on it (uses quota, only on success)
- `usage_status()` -- remaining daily quota

## Setup

Requires Python 3.10+.

```bash
cd mcp-server
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

Point it at your MolClass deployment. Two URLs, because a plain `docker compose up
-d` exposes the predictor and the FastAPI service on different local ports with no
single front door; a deployment behind a reverse proxy (the FAQ page's "public
deployment" step) can point both at the same public URL instead:

```bash
export MOLCLASS_BASE_URL=http://127.0.0.1:8082  # predictor: search/predict on indexed molecules
export MOLCLASS_API_URL=http://127.0.0.1:8000   # FastAPI: submit_and_predict's registration step

# or, behind a reverse proxy:
export MOLCLASS_BASE_URL=https://your-domain.example
export MOLCLASS_API_URL=https://your-domain.example
```

Add it to your MCP client's config, e.g. Claude Desktop's `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "molclass": {
      "command": "/absolute/path/to/molclass/mcp-server/.venv/bin/python3",
      "args": ["/absolute/path/to/molclass/mcp-server/server.py"],
      "env": { "MOLCLASS_BASE_URL": "http://127.0.0.1:8082", "MOLCLASS_API_URL": "http://127.0.0.1:8000" }
    }
  }
}
```

## Daily quota

Each instance of this server self-limits to **100 predictions per day** (UTC,
resets at midnight), to keep a shared deployment from being overwhelmed by
automated requests. This is enforced locally by the server process, in a small
state file (default `~/.molclass-mcp/usage.json`, override with
`MOLCLASS_MCP_STATE_FILE`) -- it protects against accidental runaway use, not
against a deliberately malicious client. Read-only tools (search, `get_molecule`,
`get_predictions`) don't count against it; `predict` and `submit_and_predict` do,
the latter only when its pipeline actually succeeds. Override the limit
with `MOLCLASS_MCP_DAILY_LIMIT` if you're running your own deployment and want a
different number.
