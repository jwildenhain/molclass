#!/usr/bin/env bash
set -euo pipefail

# Creates the least-privilege MySQL account used by the ordinary FastAPI service
# and writes its credential to an untracked 0600 environment file.
#
# The account deliberately receives no UPDATE and no DELETE privilege anywhere.
# The v3 API only ever inserts, so this account structurally cannot approve,
# publish, reject, or otherwise mutate model lifecycle state. Those transitions
# remain exclusive to molclass_model_approver via the canonical Java transaction.

if [[ "$EUID" -ne 0 ]]; then
    printf '%s\n' "Run this script with sudo so MySQL socket authentication can create accounts." >&2
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
account="molclass_api"
schema="molclass_v3"
secret_dir="$invoking_home/.config/molclass"
secret_file="$secret_dir/api.env"

db_password=""
if [[ -f "$secret_file" ]]; then
    db_password="$(sed -n 's/^MOLCLASS_DB_PASSWORD=//p' "$secret_file" | head -n 1)"
fi
if [[ -n "$db_password" && ! "$db_password" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'Existing credential file has an unexpected password format: %s\n' "$secret_file" >&2
    exit 1
fi
if [[ -z "$db_password" ]]; then
    db_password="$("$openssl_bin" rand -hex 32)"
fi

# Tables the v3 API inserts into. Every other privilege stays withheld.
#
# scaffold_definition/molecule_scaffold (Murcko scaffold storage) and
# prediction_job/prediction_result (persisted predictions) were added when the
# Spring predictor gained write paths of its own; job was already granted for
# the FastAPI upload/import pipeline and is reused for prediction job rows.
insert_tables=(
    dataset import_run job job_event upload_artifact model_definition audit_event
    scaffold_definition molecule_scaffold prediction_job prediction_result
)

{
    for host in localhost 127.0.0.1; do
        printf "CREATE USER IF NOT EXISTS '%s'@'%s' IDENTIFIED BY '%s';\n" "$account" "$host" "$db_password"
        printf "ALTER USER '%s'@'%s' IDENTIFIED BY '%s';\n" "$account" "$host" "$db_password"
        printf "REVOKE ALL PRIVILEGES, GRANT OPTION FROM '%s'@'%s';\n" "$account" "$host"
        printf "GRANT SELECT ON %s.* TO '%s'@'%s';\n" "$schema" "$account" "$host"
        for table in "${insert_tables[@]}"; do
            printf "GRANT INSERT ON %s.%s TO '%s'@'%s';\n" "$schema" "$table" "$account" "$host"
        done
    done
    printf 'FLUSH PRIVILEGES;\n'
} | "$mysql_bin" --protocol=socket

install -d -m 700 -o "$invoking_user" -g "$invoking_group" "$secret_dir"
temporary_file="$(mktemp "$secret_dir/api.env.XXXXXX")"
chmod 600 "$temporary_file"
{
    printf '%s\n' '# Local MolClass FastAPI database credentials.'
    printf '%s\n' '# Read and insert only. Never commit this file.'
    printf 'MOLCLASS_DB_USER=%s\n' "$account"
    printf 'MOLCLASS_DB_PASSWORD=%s\n' "$db_password"
    printf 'MOLCLASS_V3_DB_HOST=%s\n' '127.0.0.1'
    printf 'MOLCLASS_V3_DB_PORT=%s\n' '3306'
    printf 'MOLCLASS_V3_SCHEMA=%s\n' "$schema"
} > "$temporary_file"
chown "$invoking_user:$invoking_group" "$temporary_file"
mv -f "$temporary_file" "$secret_file"
chmod 600 "$secret_file"

printf 'Account %s provisioned. Credential written to %s (mode 0600).\n' "$account" "$secret_file"
