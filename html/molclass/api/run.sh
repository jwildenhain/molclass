#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
exec python3 -m uvicorn app.main:app \
  --host "${MOLCLASS_API_ADDRESS:-127.0.0.1}" \
  --port "${MOLCLASS_API_PORT:-8000}"
