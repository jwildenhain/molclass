#!/usr/bin/env bash
set -euo pipefail

# Issues a new API key for a trusted external caller of
# POST /api/v1/molecules/predict (see app/v3_molecules.py's _resolve_api_key and
# docs/V3_ADHOC_PREDICTION.md). Only the SHA-256 hash of the key is stored in the
# database -- the plaintext key exists only in the credential file this script writes
# and in whatever you paste it into on the caller's side. There is no way to recover
# a lost key; revoke it (UPDATE api_key SET revoked_at=UTC_TIMESTAMP(6) WHERE label=...)
# and issue a new one instead.
#
# Requires sql/v3/V11__api_keys_and_molecule_submission_quota.sql to already be applied.
#
# Usage: sudo tools/issue-api-key.sh <label>
#   e.g. sudo tools/issue-api-key.sh chemgrid

if [[ "$EUID" -ne 0 ]]; then
    printf '%s\n' "Run this script with sudo so MySQL socket authentication can connect." >&2
    exit 1
fi

label="${1:-}"
if [[ -z "$label" || ! "$label" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]]; then
    printf 'Usage: sudo %s <label>\n' "$0" >&2
    printf 'Label must be lowercase alphanumeric/underscore/hyphen, e.g. "chemgrid".\n' >&2
    exit 1
fi

invoking_user="$(printenv SUDO_USER || true)"
if [[ -z "$invoking_user" || "$invoking_user" == "root" ]]; then
    invoking_user="jw"
fi
invoking_home="$(getent passwd "$invoking_user" | cut -d: -f6)"
invoking_group="$(id -gn "$invoking_user")"
if [[ -z "$invoking_home" || ! -d "$invoking_home" ]]; then
    printf 'Cannot determine a home directory for %s.\n' "$invoking_user" >&2
    exit 1
fi

mysql_bin="$(command -v mysql)"
openssl_bin="$(command -v openssl)"
schema="molclass_v3"
secret_dir="$invoking_home/.config/molclass/api-keys"
secret_file="$secret_dir/$label.env"

if [[ -e "$secret_file" ]]; then
    printf 'A key for label "%s" already exists at %s -- refusing to overwrite.\n' "$label" "$secret_file" >&2
    printf 'Revoke the old row in api_key first if you actually want to reissue.\n' >&2
    exit 1
fi

table_exists="$("$mysql_bin" --protocol=socket -N -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$schema' AND table_name='api_key';")"
if [[ "$table_exists" != "1" ]]; then
    printf 'Table %s.api_key does not exist yet -- apply sql/v3/V11__api_keys_and_molecule_submission_quota.sql first.\n' "$schema" >&2
    exit 1
fi

raw_key="$("$openssl_bin" rand -hex 32)"
key_hash_hex="$(printf '%s' "$raw_key" | "$openssl_bin" dgst -sha256 -hex | awk '{print $NF}')"

"$mysql_bin" --protocol=socket -e \
    "INSERT INTO $schema.api_key (key_hash, label) VALUES (UNHEX('$key_hash_hex'), '$label');"

install -d -m 700 -o "$invoking_user" -g "$invoking_group" "$secret_dir"
temporary_file="$(mktemp "$secret_dir/$label.env.XXXXXX")"
chmod 600 "$temporary_file"
{
    printf '%s\n' "# API key for the MolClass ad-hoc molecule-predict endpoint, caller \"$label\"."
    printf '%s\n' "# Send as the X-MolClass-Api-Key header. Never commit this file."
    printf 'MOLCLASS_API_KEY=%s\n' "$raw_key"
} > "$temporary_file"
chown "$invoking_user:$invoking_group" "$temporary_file"
mv -f "$temporary_file" "$secret_file"
chmod 600 "$secret_file"

printf 'API key for "%s" issued. Credential written to %s (mode 0600).\n' "$label" "$secret_file"
