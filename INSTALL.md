# Installation & Deployment Guide

MolClass runs as a set of Docker Compose services. There is no other supported
way to run the current version — the Java pipeline (CDK/Weka), the FastAPI
service, and the Next.js frontend are built and wired together through
`docker-compose.yml`.

## Prerequisites

- Docker Engine with the Compose plugin (`docker compose version` should work).
- Enough RAM for the JVM workers. The example configuration below assumes a
  large host; if you're on something modest (a few GB, shared with other
  services), lower `MOLCLASS_MODEL_WORKER_MEMORY` and the other `*_MEMORY`
  variables accordingly — they're hard `mem_limit` ceilings, not reservations,
  and the JVM workers respect them (`-XX:MaxRAMPercentage`), so a smaller
  number just means smaller models train more slowly, not a crash, as long as
  it's not smaller than what a given job actually needs.
- Internet access during the first build (Gradle and npm both resolve
  dependencies from their public registries).

## 1. Clone the repository

```bash
git clone https://github.com/jwildenhain/molclass.git
cd molclass
```

## 2. Configure secrets

Copy the example environment file and fill in both required secrets:

```bash
cp .env.example .env
```

`.env` is untracked (gitignored) on purpose — never commit it. At minimum,
replace `MOLCLASS_DB_ROOT_PASSWORD` and `MOLCLASS_DB_PASSWORD` with long
random values (`openssl rand -hex 32` works well for both). Everything else
in `.env.example` has a sane default; see the comments in that file for what
each variable controls (ports, per-service memory limits, model-worker thread
count, and `MOLCLASS_DATA_INTAKE_ENABLED` for a read-only deployment).

## 3. Build and start

```bash
docker compose build
docker compose up -d
```

The first build compiles the Java pipeline and both the API and frontend
images — expect it to take a few minutes. `docker compose up -d` starts all
six services (`db`, `api`, `sdf-worker`, `model-worker`, `molecule-worker`,
`predictor`, `frontend`) and creates the database schema automatically from
`sql/v3/` on first boot.

## 4. Verify it's running

```bash
docker compose ps
```

All services should show `healthy` (or, for the two workers, just running —
they don't have HTTP health checks). Then:

```bash
curl http://127.0.0.1:8000/api/v1/health/readiness   # API
```

and open `http://127.0.0.1:3000` in a browser — that's the frontend, bound to
`127.0.0.1` by default. For a public deployment, put a reverse proxy (Apache,
nginx, Caddy) in front of it with TLS; `docker-compose.yml` intentionally
doesn't do this for you, since domain and certificate setup is
environment-specific.

## 5. Load some data

Upload an SDF file through the web UI's `/upload` page, or `POST` it to
`/api/v1/uploads`. This only works if `MOLCLASS_DATA_INTAKE_ENABLED` is at its
default (`true`, or unset) — a deployment explicitly configured as read-only
won't accept new uploads.

## Configuration reference

See `.env.example` for the full list of tunables (ports, per-service memory
limits, model-worker thread count and timeouts). `docker-compose.yml` is the
source of truth for what each one actually does.

## Programmatic / agent access

[`mcp-server/`](mcp-server/) exposes search and prediction as MCP tools for
AI assistants and agent workflows — see its own README for setup.

## Local development (without Docker)

For iterating on the Java pipeline or the FastAPI service directly against a
local MariaDB instead of rebuilding containers, see the scripts under
`tools/` (`setup-local-api-account.sh`, `run-api-local.sh`,
`run_v3_worker.sh`, `build-frontend.sh`, `install-systemd-units.sh`) — each
has a comment block explaining what it does and in what order to run them.
This path is for contributors working on MolClass itself; for just running
the application, use Docker Compose above.

## Running tests

```bash
./gradlew test                              # Java
cd molclass-frontend && npm run lint && npm run typecheck && npx playwright test
```
