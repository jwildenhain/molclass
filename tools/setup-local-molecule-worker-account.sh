#!/usr/bin/env bash
set -euo pipefail

# Creates the least-privilege MySQL account used by
# molclass.molecules.V3MoleculePredictWorker (tools/run_v3_worker.sh molecule)
# and writes its credential to an untracked 0600 environment file.
#
# Distinct from molclass_feature_worker: that account computes descriptors for
# molecules that already exist. This worker's first step -- registering a
# brand-new ad-hoc molecule from a raw SMILES string
# (V3AdhocMoleculeRegistrar.register()) -- needs INSERT on `molecule` too,
# which feature_worker deliberately does not have. Everything after
# registration (descriptor/fingerprint generation) is the identical write
# surface as feature_worker, so this account is exactly that grant set plus
# one table. The prediction step itself writes nothing here -- it's an HTTP
# call to the predictor service, which persists results under its own
# credentials.

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
account="molclass_molecule_worker"
schema="molclass_v3"
secret_dir="$invoking_home/.config/molclass"
secret_file="$secret_dir/molecule-worker.env"

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

# Same write surface as molclass_feature_worker, plus `molecule` for
# V3AdhocMoleculeRegistrar's ad-hoc registration step.
insert_tables=(
    job job_event molecule descriptor_generation descriptor_schema fingerprint_definition
    feature_profile_component molecule_descriptor_vector molecule_fingerprint
    scaffold_definition molecule_scaffold
)
update_tables=(
    job descriptor_generation descriptor_schema fingerprint_definition feature_profile
    molecule_descriptor_vector molecule_fingerprint
)
delete_tables=(feature_profile_component)

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
        for table in "${delete_tables[@]}"; do
            printf "GRANT DELETE ON %s.%s TO '%s'@'%s';\n" "$schema" "$table" "$account" "$host"
        done
    done
    printf 'FLUSH PRIVILEGES;\n'
} | "$mysql_bin" --protocol=socket

install -d -m 700 -o "$invoking_user" -g "$invoking_group" "$secret_dir"
temporary_file="$(mktemp "$secret_dir/molecule-worker.env.XXXXXX")"
chmod 600 "$temporary_file"
{
    printf '%s\n' '# Local MolClass ad-hoc molecule prediction worker database credentials.'
    printf '%s\n' '# Source before running tools/run_v3_worker.sh molecule. Never commit this file.'
    printf 'MOLCLASS_JDBC_URL=%s\n' "jdbc:mariadb://127.0.0.1:3306/$schema"
    printf 'MOLCLASS_DB_USER=%s\n' "$account"
    printf 'MOLCLASS_DB_PASSWORD=%s\n' "$db_password"
} > "$temporary_file"
chown "$invoking_user:$invoking_group" "$temporary_file"
mv -f "$temporary_file" "$secret_file"
chmod 600 "$secret_file"

printf 'Account %s provisioned. Credential written to %s (mode 0600).\n' "$account" "$secret_file"
