# Downloaded, converted, not yet imported

7 public toxicity/safety datasets pulled via `PyTDC` (`tdc.single_pred.Tox`), converted to SDF with
the same RDKit pipeline used for Tox21, and validated with the project's `SdfAnalyzer`. None of
these have been uploaded into `molclass_v3` — that's the point of this directory. Full provenance
and the original conversion session are documented in
[docs/V3_DESCRIPTOR_CATALOG_INCIDENT_2026-08-23.md §7](../../docs/V3_DESCRIPTOR_CATALOG_INCIDENT_2026-08-23.md).

| dataset (`tdc.single_pred.Tox` name) | endpoint | compounds | conversion | class balance |
|---|---|---|---|---|
| `herg` | hERG cardiac channel blocker | 655 | 655/655, 0 malformed | — |
| `herg_karim` | hERG blocker (larger cohort) | 13,445 | 13,445/13,445, 0 malformed | 6,718 / 6,727 |
| `ames` | Ames bacterial mutagenicity | 7,278 | 7,278/7,278, 0 malformed | 3,974 / 3,304 |
| `dili` | Drug-induced liver injury | 475 | 475/475, 0 malformed | 236 / 239 |
| `skin_reaction` | Skin sensitization | 404 | 404/404, 0 malformed | 274 / 130 |
| `carcinogens_lagunin` | Rodent carcinogenicity | 280 | 280/280, 0 malformed | 60 / 220 |
| `clintox` | Clinical-trial toxicity failure | 1,478 | 1,478/1,478, 0 malformed | 112 / 1,366 |

All 7 converted at 100% (no RDKit valence failures) and every endpoint is present on 100% of
records — no sparsity, so none would hit the `model_target_allowed` gap that Tox21 did; they'd
auto-qualify as model targets on import with no manual override needed.

## Files per dataset

- `<name>.sdf` — the converted, import-ready structure file.
- `<name>.analysis.json` — the `SdfAnalyzer` validation result for that SDF.
- `<name>.pkl` — PyTDC's cached raw pull (intermediate; not needed to import, kept for
  reproducibility).
- `data/<name>.tab` — the raw TDC download PyTDC cached to disk before conversion.
- `convert_all.py` — the conversion script that produced the `.sdf` files from the `.tab` sources.

## To actually onboard one

Same path as Tox21: `POST /api/v1/uploads` with the `.sdf` file, then
`POST /api/v1/uploads/{upload_id}/imports` once the identifier/property selection looks right, then
build models via `/model-creation` once it's `READY`.

## Also surveyed, not pulled

- **ToxCast** — same TDC source as Tox21, but a 617-assay panel; merging all 617 labels into one
  wide file was judged out of scope for a download-and-assemble pass.
- **MoleculeNet** (BACE, BBBP, SIDER, HIV, MUV) — lives behind DeepChem's S3-backed `molnet` loader
  rather than plain files.
- **OpenADMET, Polaris, CACHE, Kaggle** — each needs its own client library or authentication not
  available in the session that did this pull.
