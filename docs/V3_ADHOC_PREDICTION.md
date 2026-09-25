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

### Anonymous callers: 100 submissions/day per IP

Without an API key, `model_definition_id` is required (single model per request) and the caller is
limited to 100 `POST /api/v1/molecules/predict` submissions per UTC day, counted by client IP
(`X-Forwarded-For`'s first hop, since this deployment sits behind Apache → Next.js → FastAPI — see
`_client_ip` in `app/v3_molecules.py`). Over the limit: `429 {"detail": {"code":
"DAILY_QUOTA_EXCEEDED", "limit": 100}}`. The count lives in the `molecule_submission` table
(one row per request, regardless of outcome) — reset is implicit, since the quota query only counts
rows from the current UTC day.

### API-key callers: no quota, and predict every missing model in one call

Send `X-MolClass-Api-Key: <key>`. A valid, unrevoked key (checked against `api_key.key_hash`, a
SHA-256 hash — the plaintext is never stored) does two things:

1. Exempts the request from the 100/day IP quota entirely.
2. Lets you **omit** `model_definition_id`. The job then predicts this molecule against every
   currently-published model it doesn't already have a result for — computed fresh against the
   predictor's own records each time (`GET /api/v3/models`, `GET /api/v3/molecules/{id}/predictions`),
   never a hardcoded list — so a newly published model is picked up automatically without any
   change on the caller's side. Result shape differs from the single-model form:

   ```json
   {"jobId": 4121, "status": "QUEUED"}
   ```
   ```json
   // GET /api/v1/jobs/4121 once SUCCEEDED:
   {"moleculeId": 150561, "modelsPublished": 15, "modelsAlreadyPredicted": 2,
    "predictions": [{"modelDefinitionId": 1, "predictedClass": "...", "...": "..."}, ...],
    "errors": ["Model 9: predictor returned 500: ..."]}
   ```

   One model failing to predict doesn't fail the job or block the others — it's collected into
   `errors` alongside whatever did succeed.

Issue a key with `sudo tools/issue-api-key.sh <label>` (e.g. `chemgrid`) — see
[Database account](#database-account) below for the migration it depends on. The plaintext key
exists only in the credential file the script writes; there's no way to recover a lost one, only
revoke (`UPDATE api_key SET revoked_at=UTC_TIMESTAMP(6) WHERE label=...`) and reissue.

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

## Migration: API keys and the submission quota

```bash
mysql -u "$MOLCLASS_DB_USER" -p molclass_v3 < sql/v3/V11__api_keys_and_molecule_submission_quota.sql
```

Adds `api_key` (hashed keys, see above) and `molecule_submission` (one row per
`POST /api/v1/molecules/predict` request — `job_id`, `api_key_id` if any, `client_ip`, whether it
was a single-model or predict-all-missing request — the source of truth for both the daily quota
count and per-caller audit).

## For a same-host caller like chemgrid

Both FastAPI and the predictor bind to loopback only (`127.0.0.1`) in this deployment style — a
sibling app on the *same host* (as chemgrid.org, MolClass, and the Shiny apps all are, per the
shared analytics script in [V3_ANALYTICS.md](V3_ANALYTICS.md)) reaches `127.0.0.1:8000` (or
whichever `MOLCLASS_API_PORT`) directly, server-side, with no new network exposure needed. A
browser-side call from chemgrid's own frontend JS would need `MOLCLASS_ALLOWED_ORIGINS` to include
chemgrid's origin (see `app/config.py`); a server-side call from chemgrid's backend doesn't need
CORS at all.
