# V3 model review and approval

The model-review page is the human release surface for V3 model builds. It shows model definitions, build evidence, validation and cross-validation metrics, artifacts, lifecycle state, and any immutable approval record.

## Decision workflow

1. Open the `/model-review` page.
2. Select a definition whose latest build is `AWAITING_APPROVAL`.
3. Review its dataset, class balance, exclusions, model parameters, AUC, F1, fold evidence, artifact metadata, and warnings.
4. Enter the reviewer identity, review token, and a required rationale.
5. Choose `Approve & publish` or `Reject build`.
6. Confirm the immutable decision in the browser prompt.
7. The page reloads after the canonical Java transaction succeeds.

Approval publishes the reviewed build. Rejection records the decision without publication. Technical replacement remains a separate `SUPERSEDED` lifecycle operation and is not presented as a human decision.

## Security boundary

The normal FastAPI database account remains read-only. The mutation bridge is disabled unless all of the following are configured:

- `MOLCLASS_MODEL_APPROVAL_ENABLED=true`
- `MOLCLASS_MODEL_REVIEW_TOKEN` contains a high-entropy secret
- `MOLCLASS_APPROVAL_DB_USER` names a dedicated least-privilege approval account
- `MOLCLASS_APPROVAL_DB_PASSWORD` contains that account password
- `MOLCLASS_REPO_ROOT` points to the repository containing `gradlew`

Optional: `MOLCLASS_MODEL_APPROVAL_TIMEOUT_SECONDS` defaults to 120 seconds.

The browser sends the review token only in the `X-MolClass-Review-Token` header. The page keeps it in component memory and clears it after a successful decision. It is never placed in a URL or persisted by the application.

The API invokes `./gradlew --no-daemon :approveV3Model` without a shell.

The bridge must generate the named-flag CLI contract that `molclass.models.V3ModelApproval` actually parses. It builds:

```
-PapprovalArgs=--build-id <id> --decision APPROVE|REJECT --actor "<reviewer>" --note "<note>"
```

`V3ModelApproval.Config.parse` accepts only `--jdbc-url`, `--db-user`, `--db-password`, `--schema`, `--build-id`, `--decision`, `--actor`, and `--note`. It rejects positional values with `unknown option`. The subprocess environment must therefore export `MOLCLASS_JDBC_URL`, `MOLCLASS_DB_SCHEMA`, `MOLCLASS_DB_USER`, and `MOLCLASS_DB_PASSWORD`; no other names are read by the approval class. Those overrides apply to the child process only, so the FastAPI worker keeps its own read-only account.

`app/tests/test_v3_model_reviews.py` pins both the flag contract and the environment names, so a future edit that reverts to positional arguments fails the targeted suite instead of failing at the first real approval. The existing Java approval code remains responsible for transactional validation, the immutable approval row, build-state transition, and publication. FastAPI does not duplicate those database mutations.

## HTTP contract

`POST /api/v1/model-builds/{model_build_id}/decision` requires:

- Header: `X-MolClass-Review-Token`
- JSON `decision`: `APPROVE` or `REJECT`
- JSON `reviewer`: a stable operator identifier
- JSON `note`: the evidence and rationale for the decision

Only the latest eligible build should be submitted. The Java approval transaction rejects stale, already-decided, or otherwise ineligible builds.

## Local activation

Two separate MySQL accounts back the local service. Neither is created by hand.

| Account | Provisioned by | Privileges |
| --- | --- | --- |
| `molclass_api` | `sudo tools/setup-local-api-account.sh` | `SELECT` on `molclass_v3.*`, `INSERT` on the seven tables the API writes |
| `molclass_model_approver` | `sudo tools/setup-local-model-approver.sh` | Six table reads, `INSERT` on `model_approval` and `audit_event`, `UPDATE` on `model_build` and `model_definition` |

The v3 API contains no `UPDATE` or `DELETE` statement, so `molclass_api` is granted no update or delete privilege anywhere in the schema. The separation is therefore enforced by the database rather than by convention: the ordinary service account structurally cannot approve, publish, reject, or supersede a build, even if the application layer were compromised. Only the canonical Java transaction, running as the approver account, can change model lifecycle state.

Each script writes its credential to an untracked `0600` file under `~/.config/molclass/` and regenerates nothing if a valid credential is already present, so both are safe to re-run.

Start the service with:

```bash
tools/run-api-local.sh
```

That launcher sources both credential files, defaults `MOLCLASS_REPO_ROOT` to the repository, keeps the legacy API disabled, and binds to `127.0.0.1:8000`. Secrets are sourced rather than passed as arguments, so they never appear in the process table or shell history. If `model-approval.env` is absent the API still starts and the approval bridge simply stays disabled.

## Deployment limits

### The bridge requires a host deployment, not the current API container

`html/molclass/api/Dockerfile` builds from `python:3.12-slim` and copies only `app/`. That image contains no JDK, no Gradle wrapper, and no Java source tree, so `./gradlew :approveV3Model` cannot run inside it.

The failure mode is safe rather than silent. `_approval_mutation_available()` requires `MOLCLASS_REPO_ROOT/gradlew` to be an existing file, so inside the container it returns `False`, the review page renders its decision controls disabled, and `POST /api/v1/model-builds/{id}/decision` answers `503`. No partial or unvalidated approval can occur.

The consequence is that web-interface approval currently works only where FastAPI runs on a host that has the repository, a JDK, and the Gradle wrapper. Running the API from `html/molclass/api/run.sh` on this machine satisfies that. `docker compose` does not.

Choosing a containerised approval path is a deliberate architecture decision and should not be improvised by mounting the repository and a JDK into the API image; that would put a build toolchain and full source tree inside a public-facing service container. The queued administrative job described below is the better direction.



The shared-token bridge is intended for the current loopback or trusted administrative deployment. Do not expose it directly to an untrusted LAN or the public internet. Before broader deployment, put the endpoint behind authenticated operator identity, TLS, role authorization, request-rate limiting, and CSRF protection appropriate to the chosen reverse-proxy or identity-provider architecture.

Keep the technical Gradle command available as an emergency administrative fallback, not as the routine reviewer workflow.
