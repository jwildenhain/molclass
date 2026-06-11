#!/usr/bin/env bash
#
# Startup script for Uvicorn ASGI server to run the FastAPI REST API
#
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "[api] Starting FastAPI server on port 5000..."
# Start uvicorn with auto-reload enabled for development/debugging
python3 -m uvicorn app.main:app --host 0.0.0.0 --port 5000 --reload
