# ADR 0003: Explicit feature-exclusion readiness

## Status

Accepted

## Context

Legacy MolClass datasets contain a small number of malformed or empty structures. The v3 feature worker first parses the stored molfile and then retries with the canonical SMILES recovered during migration. Some records can remain unparseable after both attempts.

Silently publishing an incomplete feature generation would hide chemistry-data loss. Blocking every model indefinitely would also prevent rebuilding datasets whose failed records can be explicitly excluded and audited.

## Decision

- A feature generation with no remaining incomplete molecules is published as `READY` or `READY_MODEL_SCOPE`.
- A generation with failures remains `COMPLETED_WITH_ERRORS` by default and blocks model rebuilding.
- An operator may explicitly set `MOLCLASS_ALLOW_FEATURE_EXCLUSIONS=true` for a retry/finalization run.
- The worker then publishes the generation and all feature profiles as `READY_WITH_EXCLUSIONS`.
- The job ends as `COMPLETED_WITH_EXCLUSIONS` with error code `FEATURE_RECORD_EXCLUSIONS`.
- Failed descriptor and fingerprint rows, attempt counts, error codes, and error messages remain unchanged.
- Model building must exclude molecules missing any required profile component and persist an exclusion record in `model_training_member`; it must never synthesize feature values for them.
- Model publication remains a separate human approval decision.

## Consequences

Feature loss is visible, queryable, and requires an explicit operator action. Rebuilds can proceed for partially recoverable legacy datasets while retaining exact molecule-level provenance and preventing accidental automatic publication.
