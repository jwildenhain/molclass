#!/usr/bin/env bash
set -euo pipefail

# Creates the least-privilege MySQL account used by
# molclass.importer.V3SdfWorker (tools/run_v3_worker.sh sdf) and writes its
# credential to an untracked 0600 environment file.
#
# This role can lease SDF analysis/import jobs, persist per-record import state,
# register molecules and properties, and evolve only the allowlisted wide
# property table. It cannot create datasets/jobs, build or approve models,
# publish artifacts, or delete application data.

if [[ "$EUID" -ne 0 ]]; then
    printf '%s\n' "Run this script with sudo so MySQL socket authentication can create accounts." >&2
    exit 1
fi

invoking_user="$(printenv SUDO_USER || true)"
if [[ -z "$invoking_user" || "$invoking_user" == "root" ]]; then
    invoking_user="${MOLCLASS_CREDENTIAL_OWNER:-}"
fi
if [[ -z "$invoking_user" || "$invoking_user" == "root" ]]; then
    printf '%s\n' "Run through sudo as the target user or set MOLCLASS_CREDENTIAL_OWNER." >&2
    exit 1
fi
invoking_home="$(getent passwd "$invoking_user" | cut -d: -f6)"
invoking_group="$(id -gn "$invoking_user")"
if [[ -z "$invoking_home" || ! -d "$invoking_home" ]]; then
    printf 'Cannot determine a home directory for %s.\n' "$invoking_user" >&2
    exit 1
fi

mysql_bin="$(command -v mysql)"
openssl_bin="$(command -v openssl)"
account="molclass_import_worker"
schema="molclass_v3"
secret_dir="$invoking_home/.config/molclass"
secret_file="$secret_dir/import-worker.env"

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

insert_tables=(
    job_event property_definition dataset_property import_run_property
    import_record dataset_molecule molecule dataset_molecule_properties
    property_value_overflow property_schema_change
)
update_tables=(
    job upload_artifact import_run dataset dataset_property import_run_property
    import_record property_definition
)

{
    for host in localhost 127.0.0.1; do
        printf "CREATE USER IF NOT EXISTS '%s'@'%s' IDENTIFIED BY '%s';\n" "$account" "$host" "$db_password"
        printf "ALTER USER '%s'@'%s' IDENTIFIED BY '%s';\n" "$account" "$host" "$db_password"
        printf "REVOKE ALL PRIVILEGES, GRANT OPTION FROM '%s'@'%s';\n" "$account" "$host"
        printf "GRANT SELECT, CREATE TEMPORARY TABLES ON %s.* TO '%s'@'%s';\n" "$schema" "$account" "$host"
        for table in "${insert_tables[@]}"; do
            printf "GRANT INSERT ON %s.%s TO '%s'@'%s';\n" "$schema" "$table" "$account" "$host"
        done
        for table in "${update_tables[@]}"; do
            printf "GRANT UPDATE ON %s.%s TO '%s'@'%s';\n" "$schema" "$table" "$account" "$host"
        done
        printf "GRANT ALTER ON %s.dataset_molecule_properties TO '%s'@'%s';\n" "$schema" "$account" "$host"
    done
    printf 'FLUSH PRIVILEGES;\n'
} | "$mysql_bin" --protocol=socket

install -d -m 700 -o "$invoking_user" -g "$invoking_group" "$secret_dir"
temporary_file="$(mktemp "$secret_dir/import-worker.env.XXXXXX")"
chmod 600 "$temporary_file"
{
    printf '%s\n' '# Local MolClass SDF analysis/import worker database credentials.'
    printf '%s\n' '# Source before running tools/run_v3_worker.sh sdf. Never commit this file.'
    printf 'MOLCLASS_JDBC_URL=%s\n' "jdbc:mariadb://127.0.0.1:3306/$schema"
    printf 'MOLCLASS_DB_USER=%s\n' "$account"
    printf 'MOLCLASS_DB_PASSWORD=%s\n' "$db_password"
} > "$temporary_file"
chown "$invoking_user:$invoking_group" "$temporary_file"
mv -f "$temporary_file" "$secret_file"
chmod 600 "$secret_file"

printf 'Account %s provisioned. Credential written to %s (mode 0600).\n' "$account" "$secret_file"
