# MolClass handover to Claude

Date: 2026-08-17  
Repository: `/mnt/wdc_store/gitlab/molclass`  
Primary local database: `molclass_v3` on the local MySQL instance

## 1. User objective

Bring MolClass to a production-ready state while preserving its historical datasets and model definitions. The application is being migrated toward Java/Gradle services, a clean InnoDB relational model, robust SDF importing, reproducible CDK/Weka model rebuilding, explicit lifecycle states, and a usable web review and approval workflow.

The latest user requirement was explicit: model approval should happen through the web interface, not routinely through a manual Gradle command.

## 2. Critical current state

- Model definition 93 was rebuilt as model build 140.
- Build 140 was last known to be `AWAITING_APPROVAL`.
- Older build 109 for definition 93 was technically marked `SUPERSEDED`.
- Build 109 had no human approval or rejection record before supersession.
- No model was approved or published by Codex during the latest work.
- Database migration V10 for technical supersession was applied to the local `molclass_v3` database.
- The latest web approval implementation was written successfully but was not tested, built, reread, deployed, or activated.
- FastAPI and the frontend were not restarted after the latest edits.
- Git status was deliberately not inspected. Assume the worktree contains uncommitted changes from multiple session phases.
- The dedicated MySQL account `molclass_model_approver` has now been created for `localhost` and `127.0.0.1` only.
- Its credential is stored at `/home/jw/.config/molclass/model-approval.env` with mode `0600`.
- A TCP connection to `127.0.0.1` was verified as `molclass_model_approver@localhost`.
- Effective grants were verified as the required model-evidence reads, `INSERT` on `model_approval` and `audit_event`, and `UPDATE` on `model_build` and `model_definition` only.
- Build 140 remained `AWAITING_APPROVAL` after verification; no model state was changed.

Do not approve build 140 or any other model without the user's explicit decision after they inspect the evidence.

## 3. Completed model-building work

### Adaptive cross-validation and metrics

The principal implementation is in:

- `src/molclass/models/V3ModelRebuilder.java`

Implemented behavior:

- Small-data fallback is triggered when validation or holdout has fewer than 10 examples for any observed class.
- Ten-fold cross-validation is used when at least 10 usable molecules are available.
- For fewer than 10 usable molecules, the fold count is reduced to the usable molecule count.
- Cross-validation is deterministic and stratified over all nonexcluded records.
- Aggregate and per-fold `CROSS_VALIDATION` metrics are stored.
- Undefined AUC and Kappa are represented explicitly rather than fabricated.
- Precision and F1 use a robust zero-contribution policy for undefined class/fold cases.
- The build manifest records the cross-validation contract.
- The approval gate validates metric values and cross-validation evidence.

Twenty-one targeted root tests covering this work passed at the time it was implemented. Later supersession and approval-interface tests were not included in that run.

### Technical supersession lifecycle

Relevant files include:

- `sql/v3/V10__model_build_supersession.sql`
- `src/molclass/models/V3ModelSupersession.java`
- `gradle/v3-approval.gradle`
- `src/molclass/models/V3ModelRebuilder.java`

Implemented behavior:

- `SUPERSEDED` is a technical lifecycle state.
- It is distinct from the immutable human decisions `APPROVED` and `REJECTED`.
- Supersession is permitted only where no human decision exists.
- Replacement builds populate `parent_model_build_id`.
- The builder records schema V10.
- Production audit logic understands superseded builds.
- A Gradle `supersedeV3Model` task exists.
- Definition 93 returned to `PENDING_REBUILD` after build 109 was superseded, then produced build 140.

New supersession unit tests were added previously but were not run.

## 4. Latest approval-interface implementation

### Files changed

- `molclass-frontend/src/app/model-review/page.tsx`
- `html/molclass/api/app/v3_model_reviews.py`
- `html/molclass/api/app/config.py`
- `html/molclass/api/app/tests/test_v3_model_reviews.py`
- `docs/V3_MODEL_REVIEW.md`
- `docs/V3_PRODUCTION_RUNBOOK.md`

### Frontend behavior

The model review page now contains:

- Reviewer identity input.
- Password-style review-token input.
- Required decision-note textarea.
- `Approve & publish` button.
- `Reject build` button.
- Browser confirmation explaining that the decision is immutable.
- Controls enabled only when the selected latest build is `AWAITING_APPROVAL`, no approval record exists, and the API reports mutation capability.
- Token and note clearing when another definition is selected.
- Token clearing and page reload after a successful decision.
- Existing Tailwind visual language rather than a separate design system.

The browser uses a relative API URL, consistent with the existing review GET requests.

### FastAPI behavior

A new endpoint was added:

- `POST /api/v1/model-builds/{model_build_id}/decision`
- Header: `X-MolClass-Review-Token`
- JSON fields: `decision`, `reviewer`, and `note`

The endpoint:

- Is disabled by default.
- Uses constant-time token comparison.
- Accepts only `APPROVE` or `REJECT`.
- Restricts reviewer identifiers to a conservative character set.
- Requires a nonempty rationale.
- Invokes `./gradlew --no-daemon :approveV3Model` without a shell.
- Uses separate approval database credentials.
- Leaves all approval validation, immutable decision insertion, state transition, and publication to the existing Java transaction.
- Converts Java/Gradle failure to HTTP 409, process startup failure to 503, and timeout to 504.
- Limits surfaced command output to the final 3000 characters.

### Configuration contract

The bridge requires all of:

- `MOLCLASS_MODEL_APPROVAL_ENABLED=true`
- `MOLCLASS_MODEL_REVIEW_TOKEN=<high-entropy-secret>`
- `MOLCLASS_APPROVAL_DB_USER=<dedicated-approval-user>`
- `MOLCLASS_APPROVAL_DB_PASSWORD=<secret>`
- `MOLCLASS_REPO_ROOT=/mnt/wdc_store/gitlab/molclass`

Optional:

- `MOLCLASS_MODEL_APPROVAL_TIMEOUT_SECONDS=120`

The ordinary FastAPI database account should remain read-only. Do not substitute it for the approval account.

## 5. Immediate continuation sequence

1. Read the six latest changed files once and inspect the exact resulting diff without reverting unrelated edits.
2. Confirm the real `approveV3Model` argument contract in `gradle/v3-approval.gradle` and its Java main class.
3. Correct the FastAPI bridge if the positional contract differs from the currently generated value:
   `<modelBuildId> <APPROVE|REJECT> "<reviewer>" "<note>"`.
4. Run only the targeted FastAPI model-review tests.
5. Inspect `molclass-frontend/package.json` and run its defined typecheck/build command.
6. Use the confirmed `molclass_model_approver` credential for canonical approval operations; its localhost-only, table-specific grants are already installed.
7. Generate a high-entropy local review token and place all bridge variables in the actual service environment, not source control.
8. Restart FastAPI and the Next.js frontend using the repository's established deployment method.
9. Open `/model-review` and confirm build 140 renders with enabled controls.
10. Let the user inspect metrics and choose the decision. Do not select a decision for them.
11. After a user-authorized decision, verify the immutable approval row, build status, definition publication pointer, and refreshed interface.
12. Update the runbook if the actual service manager, credentials, or Gradle contract differs from the current documentation.

## 6. Highest-risk unvalidated point

The new Python bridge currently constructs `approvalArgs` as four positional values:

`<modelBuildId> <decision> "<reviewer>" "<note>"`

This was based on the known Gradle bridge design but was not rechecked against the current Java/Gradle implementation during the final edit. Verify it before allowing a real approval. A mismatch should fail safely with HTTP 409, but the interface would not complete the decision.

Other unvalidated details:

- FastAPI import and route tests have not been run.
- The Next.js page has not been typechecked or built.
- The edited documentation was not reread after the atomic write.
- Service environment and reverse-proxy behavior have not been tested.
- No end-to-end approval request has been sent.
- The endpoint returns the expected terminal status after Gradle succeeds, then reloads the page for authoritative database state.

## 7. Security and production limits

- The current review token is a shared administrative secret, not an identity provider.
- Reviewer identity is supplied by the operator and is not cryptographically bound to a login.
- Keep this workflow loopback-only or behind a trusted administrative boundary.
- Before LAN or public exposure, add TLS, authenticated identity, role authorization, rate limiting, audit correlation, and suitable CSRF protection.
- Never expose the Gradle or Java command directly over HTTP.
- Never duplicate the approval SQL in FastAPI.
- Never let the normal API database account mutate approval or publication state.
- Decision notes containing a double quote, backslash, carriage return, or newline are rejected to protect command argument parsing.
- The synchronous subprocess can occupy one FastAPI worker thread for up to the configured timeout. This is acceptable for rare local decisions, but should become a queued administrative job for broader production use.

## 8. Earlier application requirements that remain authoritative

- Uploads are queued; concurrent dataset uploads are not required.
- Import should continue after a single malformed molecule or failed record.
- Maintain robust per-record transaction and progress tracking.
- Distinguish the database molecule ID from the unique, non-null SDF compound identifier.
- A unique non-null SDF property should be auto-selected as the identifier.
- Other properties are imported unless actively deselected.
- Existing property names map to existing columns; new properties add appropriately typed columns.
- Property type inference must inspect all records before choosing MySQL types.
- The importer should be Java/Gradle-based rather than the legacy PHP/Perl import path.
- Dataset-level model building may process all records together.
- Small datasets use adaptive cross-validation as described above.
- The host has 32 cores and 128 GB RAM; batch work may use up to roughly 75 percent of memory.
- Parallel model building is desired, but database coordination and per-definition lifecycle locking must remain correct.
- `SUPERSEDED` is allowed for technical replacement and must not masquerade as rejection.
- Human model decisions should be made in the interface.

Earlier work also moved Java build support to Gradle with a wrapper, improved the multithreaded fingerprinter, developed the Java SDF importer, added clean database-model SQL/documentation, and strengthened importer tracking. Inspect those implementations and their current tests before declaring them production-ready.

## 9. Operational cautions

- The local sandbox repeatedly failed with `bwrap: loopback: Failed RTM_NEWADDR`. Escalated workspace commands succeeded.
- Project edits were applied with atomic Python replacements because `apply_patch` was affected by that sandbox failure.
- Failed edit attempts were guarded so they wrote no files; the final attempt exited successfully.
- Do not use destructive Git commands or revert unrelated changes.
- Do not assume all historical model builds have meaningful AUC/F1. Undefined statistics can result from tiny folds, absent classes, exclusions, or prior broken build behavior; inspect stored evaluation details.
- Do not mark the production-readiness effort complete merely because the approval UI exists.

## 10. Definition of a safe next milestone

A safe next milestone is reached when:

- The approval CLI contract is confirmed.
- Targeted API tests and frontend type/build checks pass.
- A local review token is configured alongside the already verified least-privilege approval account.
- The interface visibly enables only build 140 or another valid latest awaiting build.
- A user-authorized test decision completes through Java and is reflected correctly in MySQL and the refreshed page.
- No direct approval SQL exists outside the canonical Java transaction.

## 11. Session closure update

The database-access blocker is resolved.

- Setup utility: `tools/setup-local-model-approver.sh`
- Runtime credential: `/home/jw/.config/molclass/model-approval.env`
- Credential mode: `0600`
- Verified identity: `molclass_model_approver@localhost`
- Verified pending build: model build 140 for definition 93 remains `AWAITING_APPROVAL`
- Approval/rejection performed during setup: none

The Codex goal associated with this long migration and production-readiness session is being closed as complete at the user's request. Remaining work in this document is follow-on production activation, validation, and user-directed model review, not a continuation of the resolved database-access blocker.

The next agent should not recreate or broaden the MySQL account. Source the private credential only inside the process that needs it, never print its password, and keep routine FastAPI reads on the separate read-only account.
