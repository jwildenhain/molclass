# V3 search UI

## Route consolidation

`/search` now hosts both molecule-lookup surfaces as tabs, replacing two previously separate
pages:

- `/search` (default tab `structure`) — registry lookup by ID, name, InChIKey, canonical SMILES,
  or drawn/typed substructure. No model involved.
- `/search?tab=models` — the published-model registry plus the model + molecule prediction flow
  (formerly `/prediction-list/models`).

`SearchTabs.tsx` reads `?tab=` and falls back to `structure` for any unrecognized value. The two
panels live in `src/app/search/{StructureSearchPanel,ModelMoleculeSearchPanel}.tsx`.

Legacy routes redirect rather than 404: `/structure-search` → `/search`;
`/prediction-list/models`, `/prediction-list/models/{id}`, `/prediction-list/{id}` →
`/search?tab=models`.

## Structure search → prediction handoff

Every result row on the structure-search tab renders a 2D structure thumbnail
(`MoleculeStructure`, backed by `GET /api/v3/molecules/{id}/structure.svg`) and a checkbox.
"Select all" and a per-molecule-ID "Predict selected" button navigate to
`/search?tab=models&molecules=<comma-separated moleculeIds>`.

`ModelMoleculeSearchPanel` has no inline molecule search of its own — it only reads the incoming
`?molecules=` list, fetches each ID via `GET /api/v3/molecules/{id}`, and pins them into
selection. Molecules can be removed individually from the prediction panel but not re-added there;
adding more means going back to structure search. This keeps molecule lookup in one place instead
of duplicating it across both tabs.

## Model + molecule prediction

- The classification-models table adds **AUC** and **F1** columns (`holdoutAuc`/`holdoutF1` from
  `GET /api/v3/models`, sourced from the `WEIGHTED_AUC`/`WEIGHTED_F1` rows in `model_evaluation`
  for that build's `HOLDOUT` set — see [V3_PREDICTION_API.md](V3_PREDICTION_API.md)). A build
  without an evaluable metric (e.g. a degenerate confusion matrix) shows `n/a`, never a
  synthetic value.
- Selection is many-to-many: N selected molecules × M selected models run as N×M prediction
  requests (`Promise.all`, no batching endpoint).
- Results render one card per molecule (thumbnail, name/ID, link to prediction history) laid out
  in a responsive grid (up to 4 per row), each containing that molecule's per-model outcome
  cards stacked underneath — grouping avoids interleaving different molecules' results in one
  flat list once multiple molecules are involved.

## Upload progress reporter

`/upload` polls `GET /api/v1/imports/{importRunId}` every 1.5s (backing off to 3s on a failed
poll) once an import reaches the `queued` phase, until `status` reaches a terminal value
(`SUCCEEDED`, `PARTIAL`, `FAILED`, `CANCELLED`) or the job itself fails/cancels. The endpoint
already returned live, per-record-accurate counts (`successRecords`, `failedRecords`,
`notProcessedRecords`, `totalRecords`) fed by `V3SdfImporter`'s per-record counter updates — no
backend change was needed, only the frontend poll and a progress bar / terminal-state summary.

## Model-creation dataset picker

- The **Records** summary card uses `Intl.NumberFormat` compact notation (`158K`, not `158,000`
  or a lossy `158.2K`) so a large total can't force the stat card wider than its grid cell; the
  exact value is available as a hover title.
- The dataset table's first column shows only `ID {datasetId}` (the original filename/name moved
  to a hover title) so the column stays narrow and the table no longer needs horizontal scroll on
  a standard viewport.
- The description cell is editable inline via `PATCH /api/v1/datasets/{dataset_id}`
  (`html/molclass/api/app/v3_datasets.py`), accepting `name` and/or `description`; both are
  optional and at least one must be present, `name` is trimmed and rejected if empty after
  trimming, and a missing dataset returns `404 DATASET_NOT_FOUND`.
