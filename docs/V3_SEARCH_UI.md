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
"Select all" and "Predict selected" open
`/search?tab=models&molecules=<comma-separated moleculeIds>`. The handoff uses the
Next.js-integrated browser history API, so opening the client-side model panel does
not wait for a server navigation response. Successful registration uses the same handoff.

`ModelMoleculeSearchPanel` has no inline molecule search of its own — it only reads the incoming
`?molecules=` list, fetches each ID via `GET /api/v3/molecules/{id}`, and pins them into
selection. Molecules can be removed individually from the prediction panel but not re-added there;
adding more means going back to structure search. This keeps molecule lookup in one place instead
of duplicating it across both tabs. "Search another molecule" returns to a bare
`/search`, removing the previous molecule, SMILES, and scaffold URL parameters.

"Clear search" is available for every registry search; linked structures retain
"Clear, back to search". Clearing resets the query, results, selection, search mode,
errors, and sketcher, then focuses the query input. Editing the query discards old
results and selection. Request-version checks prevent an outdated search response
or registration completion from restoring a cleared search or changing its tab.
Clearing does not cancel an already-submitted backend registration job.

## Linked structures from ChemGrid

`/search?smiles=<smiles>` accepts a standalone, URL-encoded SMILES deep link. The optional
`&scaffold=<smiles>` parameter adds a nearest-training-scaffold comparison when the caller has
one. Either form initializes the molecule-search text box from `smiles`; a supplied molecule is
therefore ready for an exact registry lookup without retyping or pasting it.

The deep link renders `LinkedStructurePreview` above the structure-search form — loaded client-side only
(`next/dynamic`, `ssr:false`) since it draws both structures with the `smiles-drawer` vendor
script (`public/vendor/smiles-drawer-2.3.0.min.js`). "Find this molecule" / "Find compounds with
this scaffold" run an exact/substructure search on that value; "Clear, back to search" (next to
the Exact match/Substructure toggle, not in the panel itself) resets all search state and
navigates to a bare `/search`.

### Molecule of the month

The first landing-page card is the "MolClass molecule of the month" entry point. The whole card,
including "Explore activity", links to `/search?smiles=<canonical-smiles>` using a Next.js URL
object so the SMILES is encoded safely. The current molecule is BMS-820132. Its card briefly
identifies it as an investigational oral partial glucokinase activator developed for type 2
diabetes and loads its canonical SMILES into both the structure preview and search text box.

Opening the card does not claim that the molecule already exists in the local registry and does
not start a prediction job automatically. The user first runs the exact lookup; if no match is
found, the existing ad-hoc registration path described below makes it available to approved
MolClass models.

If the exact search finds nothing, a "Register this structure and predict" button appears instead
of the usual empty-state text — it submits the linked SMILES to
`POST /api/v1/molecules/predict` (no API key, so single-model mode against the first published
model — see [V3_ADHOC_PREDICTION.md](V3_ADHOC_PREDICTION.md)), polls the job, and on success
navigates to `/search?tab=models&molecules=<newMoleculeId>` with the new molecule pre-selected.
This is the only place in the frontend that calls the ad-hoc endpoint; everywhere else predicts
against molecules already in the registry.

## Model + molecule prediction

- The classification-models table adds **AUC** and **F1** columns (`holdoutAuc`/`holdoutF1` from
  `GET /api/v3/models`, sourced from the `WEIGHTED_AUC`/`WEIGHTED_F1` rows in `model_evaluation`
  for that build's `HOLDOUT` set — see [V3_PREDICTION_API.md](V3_PREDICTION_API.md)). A build
  without an evaluable metric (e.g. a degenerate confusion matrix) shows `n/a`, never a
  synthetic value.
- Selected molecules load saved predictions from
  `GET /api/v3/molecules/{id}/predictions?limit=200`. Results appear without pressing
  Run, including after reload; the newest result for each model build is shown.
- Only models missing a successful result for at least one selected molecule are
  offered. Submissions include only missing molecule/build pairs. A newer published
  build remains eligible while the older build's saved result stays visible.
- History must load successfully before new predictions can run. Failures offer a
  retry. The history API currently caps responses at 200 records; if that limit is
  reached, the UI shows the retrieved results but disables new predictions rather
  than assuming the remaining history contains no matching result.
- Pending cards appear immediately. Each response updates its card independently;
  the progress count/bar tracks completed requests, including failures. Successful
  cards remain visible when another model fails or selections change.
- For one molecule, model cards fill a responsive grid of up to four columns. For
  multiple molecules, results remain grouped by molecule. Mobile cards stack and
  wrap metadata without horizontal overflow. Selections cannot change during a run.
- Saved history uses `confidenceScore`; synchronous predictions use
  `responseStrength`. The UI normalizes those fields and leaves the training-scaffold
  count unreported when the history API does not supply it.

## Regression gate and deployment record

Run `npm run test:ui` from `molclass-frontend` before deploying changes to these
flows. The gate builds the standalone production app and runs the critical suite
in Chromium, Firefox, WebKit, Android Chrome, and iPhone Safari emulation, using
mocked APIs. Unexpected API requests and browser exceptions fail the test.
See [frontend testing instructions](../molclass-frontend/README.md#ui-regression-gate)
for installation, reports, and CI configuration.

On 2026-10-28, the frontend handoff, progressive results, saved-result reuse, and
search-reset fixes were deployed to `molclass.chemgrid.org`. Only the frontend
container was replaced. Live verification covered a prediction for molecule
150763, loading its 21 saved model results without POST requests, reload, clearing,
switching molecules, and delayed search responses. The final local UI gate passed
125 checks (25 scenarios across five browser profiles), including through
`tools/build-frontend.sh`.

Production rollback images and source backups were retained under
`/home/tyerschem2/molclass/backups/` for `predict-selection-20261028`,
`progressive-results-20261028`, `saved-results-20261028`, and `search-reset-20261028`.
These frontend deployments do not imply deployment of the separate pending
backend, feature-contract, or model-worker changes. Production is a copied source
installation; compare deployed files before applying further patches.

ChemGrid's separate MolClass-tab implementation is managed in its own task.
Changes to this MolClass frontend do not automatically update that integration.

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
