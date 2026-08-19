# ADR 0005: Immutable compressed model builds

## Status

Accepted

## Context

The first rebuild implementation reused generation number 1 and deleted prior membership, evaluation, artifact, and approval rows on retry. This destroyed audit evidence. A 37-record JUMBO random forest also produced a 121 MB uncompressed Java serialization because Weka repeats large header structures throughout the object graph.

## Decision

- Every rebuild attempt inserts a new `model_build` row.
- Generation numbers increase monotonically within a model definition and generation label.
- Prior build children and approvals are never deleted by the builder.
- The stratified and compressed contract uses generation label `v3-cdk-2.12-weka-3.8.7-stratified-gzip-v1`.
- Model and header objects are serialized directly through GZIP rather than first materializing an uncompressed byte array.
- Artifacts use format `JAVA_SERIALIZATION_WEKA_3_8_7_GZIP`, media type `application/gzip`, and a SHA-256 checksum over the stored compressed payload.
- Prediction code must verify the checksum, decompress the payload, and enforce the artifact-format allowlist before Java deserialization.

## Consequences

Retries are append-only and independently approvable. Compression reduces database I/O, storage, `max_allowed_packet` risk, and transient serialization memory. Previously built uncompressed smoke artifacts remain immutable but are not eligible for publication under the new generation contract.
