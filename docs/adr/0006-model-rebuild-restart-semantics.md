# ADR 0006: Model rebuild restart semantics

## Status

Accepted

## Decision

- The default rebuild queue selects only definitions in `PENDING_REBUILD`.
- Failed definitions are retried only by explicitly supplying `--model-id` after the failure has been reviewed or fixed.
- `UNSUPPORTED_CONFIGURATION` is terminal and is never selected by the worker.
- The worker acquires the database named lock before recovery or selection.
- Once the lock is held, any prior build still marked `RUNNING` is necessarily orphaned. Its build becomes `INTERRUPTED`, its job becomes `FAILED`, and an audit event records recovery.
- A new attempt always receives a new immutable build row and generation number.

## Consequences

Worker restarts are deterministic and auditable. Permanent failures cannot consume an unbounded retry loop, while an operator can still request a targeted retry after corrective work.
