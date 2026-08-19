# MolClass v3 Operations Runbook

Status: production candidate

## Runtime boundary

The first v3 release is local-only. The Spring predictor binds to `127.0.0.1` by
default, the Next.js server is the browser-facing origin, and MariaDB must not be
published to another workstation. Legacy PHP/Perl routes and the legacy Spring API
remain disabled.

## Required predictor configuration

Set these values through the process supervisor or a root-readable environment
file. Do not place database passwords in Gradle arguments, shell history, or tracked
configuration files.

```text
MOLCLASS_JDBC_URL=jdbc:mariadb://127.0.0.1:3306/molclass_v3
MOLCLASS_DB_USER=<least-privilege-predictor-user>
MOLCLASS_DB_PASSWORD=<secret>
MOLCLASS_V3_SCHEMA=molclass_v3
MOLCLASS_PREDICTOR_ADDRESS=127.0.0.1
MOLCLASS_PREDICTOR_PORT=8082
```

Default resource limits are an eight-connection database pool, 32 HTTP worker
threads, 128 HTTP connections, a four-model LRU cache, and 128 MiB maximum per
compressed model artifact. Override a limit only after measuring the workstation.

## Build and start

```bash
./gradlew :spring_boot_predictor:bootJar
java -jar spring_boot_predictor/build/libs/spring_boot_predictor-3.0.0.jar
```

Startup fails if MariaDB cannot be reached or the configured v3 schema lacks a
required serving table. This is deliberate: a process supervisor should retry a
transient database outage rather than expose a partially initialized predictor.

## Health and readiness

```bash
curl --fail http://127.0.0.1:8082/actuator/health/liveness
curl --fail http://127.0.0.1:8082/actuator/health/readiness
```

Liveness reports process state. Readiness also checks the database and the v3
serving schema. Health details are not returned over HTTP. Having zero published
models is valid and leaves readiness `UP`; the model catalogue is then empty.

## Model rebuild boundary

Run rebuilds with an external deadline and an immutable code revision:

```bash
timeout --signal=TERM --kill-after=30s 30m \
  env MOLCLASS_CODE_REVISION=<immutable-revision> MOLCLASS_MODEL_THREADS=8 \
  ./gradlew rebuildV3Models -PmodelArgs='--limit 1 --threads 8'
```

On worker restart, run the recovery-only pass before claiming another model:

```bash
./gradlew rebuildV3Models -PmodelArgs='--limit 0 --threads 8'
```

The worker records interrupted attempts and never deletes an earlier attempt.

## Human publication boundary

Rebuild completion does not publish a model. Review its split membership,
exclusions, holdout metrics, manifest, artifact sizes, and checksums first. Then use
the approval CLI with an explicit local operator identity. Never automate approval.

```bash
./gradlew approveV3Model \
  -PapprovalArgs='--build-id <id> --decision APPROVE --actor <operator> --note "review reference"'
```

Reject unsuitable builds with `--decision REJECT`. Both decisions are immutable
audit events.

## Backup

Use a client option file with mode `0600` so credentials are not visible in process
arguments. Back up the schema before migrations or approval changes:

```bash
mariadb-dump --defaults-extra-file=/etc/molclass/db-client.cnf \
  --single-transaction --routines --triggers --hex-blob molclass_v3 \
  | gzip > molclass_v3-$(date +%Y%m%dT%H%M%S).sql.gz
```

Record the SHA-256 checksum beside every backup and copy both files to storage that
is not on the MolClass database volume. The v3 model artifacts are in MariaDB and
are included in this dump.

## Restore exercise

Restore into a new schema, never over the only working database:

```bash
mysql --defaults-extra-file=/etc/molclass/db-client.cnf \
  -e 'CREATE DATABASE molclass_v3_restore CHARACTER SET utf8mb4 COLLATE utf8mb4_bin'
gzip -dc <backup.sql.gz> | mysql \
  --defaults-extra-file=/etc/molclass/db-client.cnf molclass_v3_restore
```

Start a predictor instance on another loopback port with
`MOLCLASS_V3_SCHEMA=molclass_v3_restore`, verify readiness, model catalogue counts,
and representative molecule searches, then remove the exercise schema only after
recording the result.

## Database capacity

The current 128 MiB InnoDB buffer pool is below the v3 working set. Follow
`docs/MYSQL_V3_PRODUCTION_TUNING.md` and measure after increasing it. Do not change
global MariaDB memory settings while a rebuild or import is active.

## Incident evidence

Preserve these records before retrying or restarting work:

- Model build, runstep, membership, evaluation, artifact, and audit rows.
- Import run and per-record transaction rows.
- Predictor and worker logs around the failure timestamp.
- Database server error log and disk/memory pressure evidence.

Do not delete failed, interrupted, or rejected attempts. They are part of the
production audit trail.

## Local compose deployment

Create an untracked `.env` from `.env.example`, replace both placeholder secrets,
and keep the file mode at `0600`. Then build and start the v3-only stack:

```bash
docker compose --env-file .env up -d --build
docker compose ps
```

Only the frontend and predictor are host-published, both on `127.0.0.1`. MariaDB is
reachable only on the compose network; use `docker compose exec db mariadb` for
local administration. The first empty-volume startup applies `sql/v3/V1` through
`V6` in filename order. Existing volumes are never migrated by Docker entrypoint
initialization, so apply new migrations explicitly before replacing an established
container.

## Supervised model pipeline worker

The `model-worker` Compose service owns the named `molclass-v3-model-pipeline:<schema>` lock.
It sleeps when no model definition is `PENDING_REBUILD`. For pending work it checks whether
any feature-profile component row is absent, runs model-scoped CDK feature generation only
when required, and rebuilds one definition at a time. Feature and model child processes have
independent hard timeouts. Successful builds remain `AWAITING_APPROVAL`; the worker never
approves or publishes them.

Runtime controls are `MOLCLASS_MODEL_THREADS`, `MOLCLASS_MODEL_POLL_SECONDS`,
`MOLCLASS_FEATURE_TIMEOUT_MINUTES`, `MOLCLASS_MODEL_TIMEOUT_MINUTES`, and
`MOLCLASS_CODE_REVISION`. Treat the revision value as immutable release provenance.
