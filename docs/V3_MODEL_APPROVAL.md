# MolClass v3 model approval

Model rebuilding never publishes automatically. Each immutable build stops at `AWAITING_APPROVAL`.

## Contract

Required environment variables:

- `MOLCLASS_JDBC_URL`
- `MOLCLASS_DB_USER`
- `MOLCLASS_DB_PASSWORD`

Required CLI arguments:

- `--build-id <positive integer>`
- `--decision APPROVE|REJECT`
- `--actor <authenticated operator identifier>`

Optional argument: `--note <text>`.

Example:

```bash
./gradlew approveV3Model -PapprovalArgs='--build-id 42 --decision APPROVE --actor operator@example.org --note reviewed'
```

Approval verifies the production generation label, non-working-tree code revision, manifest checksum, exact membership totals, at least two classes, all expected evaluation metrics, exactly one MODEL and HEADER artifact, gzip artifact formats, lengths, and SHA-256 checksums. It then supersedes the previous published build, updates the indexed definition pointer, inserts one immutable decision, and writes an audit event in one transaction.

Rejection does not require a production-format artifact because malformed and obsolete smoke builds must remain rejectable. It marks the build `REJECTED`, returns the definition to `PENDING_REBUILD` when no earlier build is published, and writes the same decision and audit trail.
