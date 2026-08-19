#!/usr/bin/env bash
set -euo pipefail

# Creates the least-privilege MySQL account used by molclass.features.V3FeatureGenerator
# (./gradlew generateV3Features) and writes its credential to an untracked 0600
# environment file.
#
# Distinct from molclass_api (read-mostly), molclass_model_approver (publish/
# approve/reject only), and molclass_model_worker (V3ModelRebuilder). The feature
# generator computes and stores CDK descriptors/fingerprints for molecules and
# links feature-profile components -- a different write surface again.
#
# One deliberate deviation from every other account script here: this is the
# first account in this project that receives DELETE, and only on exactly one
# table. linkFeatureProfiles() replaces a feature profile's component list with
# DELETE FROM feature_profile_component WHERE feature_profile_id=? followed by
# fresh INSERTs -- always scoped to one profile at a time, driven entirely by a
# hardcoded Java map, never by external input. No other DELETE exists anywhere
# in this codebase's write paths, and this account gets none beyond this one
# narrowly-scoped case.

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
account="molclass_feature_worker"
schema="molclass_v3"
secret_dir="$invoking_home/.config/molclass"
secret_file="$secret_dir/feature-worker.env"

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

# Tables written via INSERT (some also via ON DUPLICATE KEY UPDATE, which MySQL
# requires UPDATE privilege for even though it's one statement -- those also
# appear in update_tables below).
insert_tables=(
    job job_event descriptor_generation descriptor_schema fingerprint_definition
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
temporary_file="$(mktemp "$secret_dir/feature-worker.env.XXXXXX")"
chmod 600 "$temporary_file"
{
    printf '%s\n' '# Local MolClass feature-generation worker database credentials.'
    printf '%s\n' '# Source before running ./gradlew generateV3Features. Never commit this file.'
    printf 'MOLCLASS_JDBC_URL=%s\n' "jdbc:mysql://127.0.0.1:3306/$schema"
    printf 'MOLCLASS_DB_USER=%s\n' "$account"
    printf 'MOLCLASS_DB_PASSWORD=%s\n' "$db_password"
} > "$temporary_file"
chown "$invoking_user:$invoking_group" "$temporary_file"
mv -f "$temporary_file" "$secret_file"
chmod 600 "$secret_file"

printf 'Account %s provisioned. Credential written to %s (mode 0600).\n' "$account" "$secret_file"
