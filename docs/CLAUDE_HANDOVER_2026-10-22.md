# MolClass handover to Claude

Date: 2026-10-22
Repository: `/mnt/wdc_store/gitlab/molclass`
Primary branch: `master`
Production site: `https://molclass.chemgrid.org/`

This document supersedes the 2026-08-17 handover. Historical details remain
available in Git history, but old build IDs and operational assumptions must not
be treated as current without querying the database.

## 1. Current objective

Maintain MolClass as a production molecular-classification service while
preserving reproducibility, database integrity, model-review controls, and the
public ChemGrid integrations.

The production-sensitive paths are:

- Browser frontend and public API at `molclass.chemgrid.org`.
- Spring Boot prediction service used by MolClass and ChemGrid integrations.
- Public MCP gateway at `mcp.chemgrid.org`.
- Shared ChemGrid Google Analytics integration.
- SDF import, feature-generation, model-build, and molecule-prediction workers.

Do not deploy merely because source has changed. Treat source synchronization,
local runtime updates, and production deployment as three separate operations.

## 2. Repository state at handover

- GitHub pull request 5 was merged on 2026-10-22.
- The merge updates Next.js from `16.2.9` to `16.3.8` and refreshes the frontend
  lockfile.
- The pull request was mergeable and all GitHub checks passed: deployment smoke
  test, MCP server check, and Playwright tests.
- Local `master` was fast-forwarded to the pull-request merge before this
  handover was written.
- Claude-managed worktrees had no unique commits or uncommitted application
  changes. They were fast-forwarded from `86505fa` to the current `master`.
- The generated `.claude/launch.json` files are local session metadata and were
  preserved.
- The handover update itself must be committed and pushed before another clone
  can see it.

## 3. What changed since the August handover

The repository advanced by more than fifty commits. Major completed areas are:

- V3 InnoDB schema, migrations, indexes, audit tooling, and database runbooks.
- Java/Gradle SDF analysis and queued import with per-record failure tracking.
- Feature-generation and model-rebuild workers with database leasing.
- Deterministic SMOTE, robust KNN tuning, scaffold-aware splitting, ECFP and
  Murcko-scaffold support, and applicability-domain evidence.
- Adaptive cross-validation for small datasets and explicit undefined metrics.
- Immutable approval/rejection records and technical `SUPERSEDED` lifecycle
  handling.
- Web model review, dataset review, molecule details, model search, structure
  search, upload progress, static help/news/FAQ pages, and sortable tables.
- Ad-hoc molecule registration and prediction with API keys, atomic quotas, and
  a dedicated molecule worker.
- MCP server support for model discovery, compound lookup, stored predictions,
  and SMILES prediction submission.
- Frontend Google Analytics integration and the MolClass molecule-of-the-month
  landing-page workflow.
- Removal of stale Ant/NetBeans build artifacts and legacy duplicate Java paths.

The current architecture and commands are documented primarily in:

- `README.md`
- `INSTALL.md`
- `docker-compose.yml`
- `docs/V3_OPERATIONS_RUNBOOK.md`
- `docs/V3_PRODUCTION_RUNBOOK.md`
- `docs/V3_MODEL_REVIEW.md`
- `docs/V3_ADHOC_PREDICTION.md`
- `docs/V3_ANALYTICS.md`

## 4. Source, local runtime, and production are not identical

### GitHub and local source

GitHub and the primary local checkout include the Next.js `16.3.8` update and
this handover once pushed.

### Local runtime

The local frontend was last rebuilt and restarted from commit `7160348` before
pull request 5 was merged. Source may therefore be newer than the currently
running local frontend until a deliberate rebuild is performed.

### Production runtime

The live frontend was last deployed from commit `7160348`. It contains the
MolClass molecule-of-the-month card and linked activity workflow. Pull request 5
and this handover have not been deployed to production.

Production is a copied deployment under `/home/tyerschem2/molclass`, not a
normal Git checkout. Do not infer the deployed revision from a production Git
command. Compare tracked source hashes or use a release manifest before a
deployment.

The last production frontend update rebuilt and recreated only
`molclass-frontend-1`. The API, predictor, database, workers, MCP gateway, and
analytics configuration were not restarted or reconfigured.

## 5. Last verified production health

The following read-only checks succeeded after the last frontend deployment:

- Public homepage returned HTTP 200.
- Public model listing returned a real model.
- Public molecule search returned a real molecule.
- Spring predictor health returned HTTP 200.
- Public MCP health and initialization succeeded.
- MCP server identified itself as BoxPlotR `1.29.1` using protocol
  `2025-06-18`.
- MCP advertised all MolClass tools.
- Read-only `molclass_list_models` succeeded.
- Read-only `molclass_get_predictions` returned a stored prediction for
  molecule 187 with `isError: false`.
- No stateful prediction was submitted during verification.
- The job table contained no `QUEUED`, `RUNNING`, `RETRY`, or `PENDING` jobs.

The public MCP gateway is hosted separately from the repository's stdio MCP
server. Its production process runs from `/opt/boxplotr-mcp` and communicates
with MolClass through the protected local production API.

## 6. Analytics contract

The frontend loads `/chemgrid-analytics.js` from `src/app/layout.tsx`.

The deployed analytics asset is managed by the TyersChem2 deployment rather
than tracked in this repository. Its source is:

`/home/jw/Documents/TyersChem2/deploy/analytics/chemgrid-analytics.js`

The last verification confirmed that the live asset matched that source and
loaded both tags:

- MolClass: `G-0S0YE5CENT`
- Central ChemGrid: `G-LMCPLNFJPV`

Do not replace, shadow, or remove this externally managed asset during a
frontend deployment. Verify both dynamic tag scripts after every production
frontend update.

## 7. Model-building and approval state

The August handover referred to build 140 and definition 93. Those identifiers
are historical and must not be assumed to remain actionable.

Before any approval or rejection:

1. Query the current latest build for the definition.
2. Confirm it is `AWAITING_APPROVAL`.
3. Confirm there is no immutable human decision already recorded.
4. Review stored metrics, folds, exclusions, and applicability evidence.
5. Let the user make the decision through the interface.

Never approve or reject a model merely to unblock automation. Do not mutate
approval state with ad-hoc SQL. The canonical Java transaction and the
least-privilege approval account remain authoritative.

The production `model-worker` was last observed stopped with exit code 143 after
completing its previous work in August. Its queue was empty. Existing prediction
serving remained healthy. Do not restart the old image blindly: compare it with
current source, rebuild it deliberately, check database leases and queue state,
then start it as an isolated production change.

## 8. Import and data-integrity requirements

The following requirements remain authoritative:

- Uploads are queued; concurrent dataset uploads are not required.
- Continue after malformed molecules or individual record failures.
- Track each record transactionally and preserve actionable failure details.
- Keep database molecule IDs distinct from unique SDF compound identifiers.
- Auto-select a non-null unique SDF property as the identifier.
- Import other properties unless the user explicitly deselects them.
- Reuse existing property columns and add missing property columns safely.
- Infer MySQL types only after inspecting all records in the dataset.
- Use the Java/Gradle importer rather than restoring the legacy PHP/Perl path.
- Keep model-build lifecycle locking and database leases correct under
  parallelism.

## 9. Known risks and follow-up work

- Production and the local running frontend are intentionally behind current
  source after the Next.js dependency merge.
- Ketcher `3.17.2` reports a Node.js engine requirement newer than the Node 20
  production frontend image and the Node 22 local runtime. Builds currently
  complete, but the runtime migration should be planned and tested explicitly.
- Frontend dependency audit findings still require a controlled triage. Do not
  run an automatic force fix against production dependencies.
- The stopped production model worker needs an image/source comparison and a
  controlled rebuild before reactivation.
- Production is not a Git checkout, so release identity needs an explicit
  manifest or immutable image tag.
- The public MCP prediction-submission path is quota-consuming and stateful.
  Prefer list/get tools for routine health checks.

## 10. Safe continuation sequence

1. Confirm the primary checkout and Claude worktree are on the same expected
   commit.
2. Install the locked frontend dependencies and build Next.js locally with the
   supported Node target.
3. Run the relevant frontend and deployment smoke checks for the dependency
   update.
4. Review audit findings individually and avoid unrelated upgrades.
5. If deployment is requested, back up the production frontend source and build
   only the frontend service.
6. Verify the homepage, model API, molecule API, predictor health, both analytics
   tags, MCP health, MCP tool discovery, and a read-only stored prediction.
7. Do not submit a live prediction merely as a health check.
8. Address the model worker separately from the frontend dependency deployment.

## 11. Production safety rules

- Never treat a source sync as permission to deploy.
- Never restart unrelated production services during a frontend update.
- Never print database passwords, API keys, review tokens, or SSH private keys.
- Never broaden the model-approval database grants.
- Never approve a model without the user's explicit evidence-based decision.
- Never overwrite the TyersChem2-managed analytics asset.
- Back up production source before overlaying tracked frontend files.
- Prefer read-only verification and avoid consuming public prediction quota.
- Record the exact source commit used for every future production deployment.

## 12. Immediate status summary

- Repository dependency branch: resolved through merged pull request 5.
- Primary local source: synchronized with GitHub before this documentation edit.
- Claude worktrees: synchronized with the stable branch before this edit and
  should be fast-forwarded again after the handover commit.
- Production deployment: deliberately unchanged.
- Google Analytics: last verified operational and unchanged.
- Public MCP integration: last verified operational and unchanged.
- MolClass prediction serving: last verified operational and unchanged.
- Model rebuild worker: stopped; requires separate controlled remediation.
