#!/usr/bin/env bash
set -euo pipefail

# Creates the least-privilege MySQL account used by molclass.models.V3ModelRebuilder
# (./gradlew rebuildV3Models) and writes its credential to an untracked 0600
# environment file.
#
# This is distinct from both molclass_api (read-mostly, no UPDATE anywhere --
# see setup-local-api-account.sh) and molclass_model_approver (publish/approve/
# reject lifecycle transitions only -- see setup-local-model-approver.sh). The
# rebuild worker needs a wider but still bounded write surface: it creates and
# advances its own job/model_build rows, writes training-partition membership,
# evaluation metrics, artifacts, and (for --split-strategy SCAFFOLD) Murcko
# scaffold rows -- but it never publishes/approves a build and never deletes
# anything anywhere.

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
account="molclass_model_worker"
schema="molclass_v3"
secret_dir="$invoking_home/.config/molclass"
secret_file="$secret_dir/model-worker.env"

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

# Every table the rebuild worker inserts into. job/model_build/model_definition
# also need UPDATE (below) to advance runstep/status; every other table here is
# insert-only, same discipline as molclass_api.
insert_tables=(
    job model_build model_class model_training_member model_evaluation
    model_artifact scaffold_definition molecule_scaffold audit_event
)
update_tables=(job model_build model_definition)

{
    for host in localhost 127.0.0.1; do
        printf "CREATE USER IF NOT EXISTS '%s'@'%s' IDENTIFIED BY '%s';\n" "$account" "$host" "$db_password"
        printf "ALTER USER '%s'@'%s' IDENTIFIED BY '%s';\n" "$account" "$host" "$db_password"
        printf "REVOKE ALL PRIVILEGES, GRANT OPTION FROM '%s'@'%s';\n" "$account" "$host"
        printf "GRANT SELECT ON %s.* TO '%s'@'%s';\n" "$schema" "$account" "$host"
        for table in "${insert_tables[@]}"; do
            printf "GRANT INSERT ON %s.%s TO '%s'@'%s';\n" "$schema" "$table" "$account" "$host"
        done
        for table in "${update_tables[@]}"; do
            printf "GRANT UPDATE ON %s.%s TO '%s'@'%s';\n" "$schema" "$table" "$account" "$host"
        done
    done
    printf 'FLUSH PRIVILEGES;\n'
} | "$mysql_bin" --protocol=socket

install -d -m 700 -o "$invoking_user" -g "$invoking_group" "$secret_dir"
temporary_file="$(mktemp "$secret_dir/model-worker.env.XXXXXX")"
chmod 600 "$temporary_file"
{
    printf '%s\n' '# Local MolClass model-rebuild worker database credentials.'
    printf '%s\n' '# Source before running ./gradlew rebuildV3Models. Never commit this file.'
    # The root module's classpath carries the bundled mysql-connector jars (see
    # build.gradle's lib/*.jar fileTree), not mariadb-java-client -- that driver
    # is spring_boot_predictor-only. Use the mysql:// scheme here to match what
    # V3ModelRebuilder can actually load, per the production runbook (11.5).
    printf 'MOLCLASS_JDBC_URL=%s\n' "jdbc:mysql://127.0.0.1:3306/$schema"
    printf 'MOLCLASS_DB_USER=%s\n' "$account"
    printf 'MOLCLASS_DB_PASSWORD=%s\n' "$db_password"
} > "$temporary_file"
chown "$invoking_user:$invoking_group" "$temporary_file"
mv -f "$temporary_file" "$secret_file"
chmod 600 "$secret_file"

printf 'Account %s provisioned. Credential written to %s (mode 0600).\n' "$account" "$secret_file"
