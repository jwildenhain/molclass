# Predicting on a structure that isn't indexed yet

The path for "I have a compound structure, not a MolClass molecule ID, and I want a bioactivity
prediction right now" — e.g. a sibling app on the same host (chemgrid.org, or any of the Shiny
apps sharing this box) handing MolClass a raw SMILES for one of its own compounds.

## The endpoint

```
POST /api/v1/molecules/predict
{"smiles": "CC(=O)Oc1ccccc1C(=O)O", "model_definition_id": 1}
→ 202 {"jobId": 4120, "status": "QUEUED"}
```

Async, not synchronous: the structure needs registering (CDK-normalized, deduplicated by InChIKey
against everything already known), its descriptors computed, then predicted against the model.
Poll `GET /api/v1/jobs/{jobId}` until `status` is `SUCCEEDED` or `FAILED`; on success `result`
holds `{moleculeId, modelDefinitionId, prediction}`. A structure CDK can't parse or normalize
fails the job with a clear error rather than silently producing junk.

**Not gated by `MOLCLASS_DATA_INTAKE_ENABLED` or `MOLCLASS_CONFIGURATION_ENABLED`**: those flags
block growing the training corpus (uploads, import triggers, new/edited model definitions) on a
read-only deployment. Ad-hoc prediction runs against an already-approved model rather than
building or reconfiguring one, so it stays reachable on a read-only deployment — including on
tyerschem2 production, where both flags are currently `false`.

This is the same endpoint the MCP server's `submit_and_predict` tool wraps (see
[mcp-server/README.md](../mcp-server/README.md)) — that wrapper is a reasonable reference for the
poll loop if you're calling this from application code rather than a browser.

## What actually processes the job

`molclass.molecules.V3MoleculePredictWorker`, launched via:

```bash
source ~/.config/molclass/molecule-worker.env
tools/run_v3_worker.sh molecule --poll-seconds 3 --lease-seconds 120
```

Same claim/lease/heartbeat/recover pattern as the SDF and model-pipeline workers. Three steps per
job: register the molecule (`V3AdhocMoleculeRegistrar` — CDK parse/normalize, InChI-based
dedup, insert into `molecule`), compute its descriptors (`V3FeatureGenerator`, the same class the
SDF import path uses), then call the predictor's existing
`POST /api/v3/models/{id}/molecules/{moleculeId}/predict`.

**This worker existed in the codebase but was never actually reachable**: `tools/run_v3_worker.sh`
only recognized `sdf` and `model` roles, so every `MOLECULE_PREDICT_REQUEST` job sat `QUEUED`
forever with no worker polling for it. Fixed alongside this doc — the `molecule` role now exists.

## Database account

Needs its own credential, provisioned like every other worker account here:

```bash
sudo tools/setup-local-molecule-worker-account.sh
```

Writes `~/.config/molclass/molecule-worker.env` (mode 0600). Grants: everything
`molclass_feature_worker` has (descriptor/fingerprint generation) plus `INSERT` on `molecule` —
the one thing feature-worker deliberately doesn't have, since it only ever computes descriptors
for molecules that already exist. The prediction step itself writes nothing under this account; it
persists on the predictor service's own credentials.

## For a same-host caller like chemgrid

Both FastAPI and the predictor bind to loopback only (`127.0.0.1`) in this deployment style — a
sibling app on the *same host* (as chemgrid.org, MolClass, and the Shiny apps all are, per the
shared analytics script in [V3_ANALYTICS.md](V3_ANALYTICS.md)) reaches `127.0.0.1:8000` (or
whichever `MOLCLASS_API_PORT`) directly, server-side, with no new network exposure needed. A
browser-side call from chemgrid's own frontend JS would need `MOLCLASS_ALLOWED_ORIGINS` to include
chemgrid's origin (see `app/config.py`); a server-side call from chemgrid's backend doesn't need
CORS at all.
