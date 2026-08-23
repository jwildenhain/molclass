# MolClass MCP server

Exposes a running MolClass deployment's molecule search and prediction API as MCP
tools, so an MCP-compatible client (Claude Desktop, Claude Code, etc.) can look up
compounds and run predictions as part of a conversation or agent workflow.

Predictions only work for molecules already indexed on the target deployment --
this server cannot register brand-new structures. Use `search_molecule` or
`search_molecule_by_structure` to find a molecule's id first.

## Tools

- `list_models(query, limit)` -- browse available prediction models
- `search_molecule(query, limit)` -- find indexed compounds by name/identifier
- `search_molecule_by_structure(smiles, limit)` -- substructure search by SMILES
- `get_molecule(molecule_id)` -- detail for one molecule
- `get_predictions(molecule_id, limit)` -- predictions already computed (no quota cost)
- `predict(model_definition_id, molecule_id)` -- run a new prediction (uses quota)
- `usage_status()` -- remaining daily quota

## Setup

Requires Python 3.10+.

```bash
cd mcp-server
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

Point it at your MolClass deployment (defaults to `http://127.0.0.1:8082`, i.e. the
predictor service directly, which is what a plain `docker compose up -d` exposes on
localhost):

```bash
export MOLCLASS_BASE_URL=http://127.0.0.1:8082      # a local deployment, or
export MOLCLASS_BASE_URL=https://your-domain.example  # a deployment behind a reverse proxy
```

Add it to your MCP client's config, e.g. Claude Desktop's `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "molclass": {
      "command": "/absolute/path/to/molclass/mcp-server/.venv/bin/python3",
      "args": ["/absolute/path/to/molclass/mcp-server/server.py"],
      "env": { "MOLCLASS_BASE_URL": "http://127.0.0.1:8082" }
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
`get_predictions`) don't count against it; only `predict` does. Override the limit
with `MOLCLASS_MCP_DAILY_LIMIT` if you're running your own deployment and want a
different number.
