# ADR 0004: Deterministic stratified model splits

## Status

Accepted

## Context

The initial v3 smoke builds partitioned records by applying `Long.hashCode` to sequential database identifiers. That function does not mix nearby integer values and produced no validation records for both 37-record smoke datasets. It also did not preserve class representation.

## Decision

- Split only records that have a recognized class and every required feature component.
- Group eligible records by class label.
- Apply a fixed 64-bit avalanche mix to the dataset-molecule identifier and build seed.
- Sort each class by the unsigned mixed value.
- Allocate approximately 80 percent training, 10 percent validation, and 10 percent holdout per class.
- Keep at least one training record for every represented class.
- Allocate holdout records only when class support is at least five and validation records only when support is at least ten.
- Persist every assignment in `model_training_member` and record `STRATIFIED_HASH_80_10_10_V1` in the build manifest.

## Consequences

Builds remain deterministic while small datasets receive meaningful validation and holdout partitions whenever their class support permits. Existing smoke builds made with the former split are not eligible for publication and must be rejected or superseded.
