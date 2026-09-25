#!/usr/bin/env bash
set -euo pipefail

# One-shot local setup for the API-key + submission-quota feature on
# POST /api/v1/molecules/predict:
#   1. Applies sql/v3/V11__api_keys_and_molecule_submission_quota.sql (idempotent --
#      skipped if the api_key table already exists).
#   2. Issues one API key with the given label (default: chemgrid), writing its
#      plaintext to ~/.config/molclass/api-keys/<label>.env (mode 0600). Skipped if
#      a key for that label was already issued.
#
# Usage: sudo tools/setup-molecule-submission-quota.sh [label]

if [[ "$EUID" -ne 0 ]]; then
    printf '%s\n' "Run this script with sudo so MySQL socket authentication can connect." >&2
    exit 1
fi

label="${1:-chemgrid}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
schema="molclass_v3"
mysql_bin="$(command -v mysql)"

table_exists="$("$mysql_bin" --protocol=socket -N -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$schema' AND table_name='api_key';")"

if [[ "$table_exists" == "1" ]]; then
    printf 'api_key table already exists -- skipping migration.\n'
else
    printf 'Applying %s ...\n' "sql/v3/V11__api_keys_and_molecule_submission_quota.sql"
    "$mysql_bin" --protocol=socket "$schema" < "$root/sql/v3/V11__api_keys_and_molecule_submission_quota.sql"
    printf 'Migration applied.\n'
fi

invoking_user="$(printenv SUDO_USER || true)"
if [[ -z "$invoking_user" || "$invoking_user" == "root" ]]; then
    invoking_user="jw"
fi
invoking_home="$(getent passwd "$invoking_user" | cut -d: -f6)"
secret_file="$invoking_home/.config/molclass/api-keys/$label.env"

if [[ -e "$secret_file" ]]; then
    printf 'A key for label "%s" already exists at %s -- leaving it in place.\n' "$label" "$secret_file"
else
    "$root/tools/issue-api-key.sh" "$label"
fi

printf 'Done. Source %s and pass its MOLCLASS_API_KEY as the X-MolClass-Api-Key header to test.\n' "$secret_file"
