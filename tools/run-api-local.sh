#!/usr/bin/env bash
set -euo pipefail

# Starts the local FastAPI service with both credential files loaded.
#
# The ordinary API account (read plus insert) comes from api.env. The model
# approval bridge credential and review token come from model-approval.env and
# are used only to spawn the canonical Java approval transaction, which receives
# them through a private child-process environment.
#
# Secrets are sourced, never passed as arguments, so they stay out of the
# process table and out of shell history.

config_dir="${MOLCLASS_CONFIG_DIR:-$HOME/.config/molclass}"
api_env="$config_dir/api.env"
approval_env="$config_dir/model-approval.env"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ ! -f "$api_env" ]]; then
    printf 'Missing %s. Run: sudo %s/tools/setup-local-api-account.sh\n' "$api_env" "$repo_root" >&2
    exit 1
fi

set -a
# shellcheck disable=SC1090
. "$api_env"
if [[ -f "$approval_env" ]]; then
    # shellcheck disable=SC1090
    . "$approval_env"
else
    printf 'Note: %s is absent, so web model approval stays disabled.\n' "$approval_env" >&2
fi
set +a

export MOLCLASS_REPO_ROOT="${MOLCLASS_REPO_ROOT:-$repo_root}"
export MOLCLASS_LEGACY_API_ENABLED="${MOLCLASS_LEGACY_API_ENABLED:-false}"
export MOLCLASS_API_ADDRESS="${MOLCLASS_API_ADDRESS:-127.0.0.1}"
export MOLCLASS_API_PORT="${MOLCLASS_API_PORT:-8000}"

printf 'Starting MolClass API on %s:%s as database user %s.\n' \
    "$MOLCLASS_API_ADDRESS" "$MOLCLASS_API_PORT" "${MOLCLASS_DB_USER:-<unset>}"
if [[ "${MOLCLASS_MODEL_APPROVAL_ENABLED:-false}" == "true" ]]; then
    printf 'Model approval bridge: enabled (approver account %s).\n' "${MOLCLASS_APPROVAL_DB_USER:-<unset>}"
else
    printf 'Model approval bridge: disabled.\n'
fi

exec "$repo_root/html/molclass/api/run.sh"
