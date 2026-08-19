#!/usr/bin/env bash
set -euo pipefail

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
account="molclass_model_approver"
schema="molclass_v3"
secret_dir="$invoking_home/.config/molclass"
secret_file="$secret_dir/model-approval.env"

db_password=""
if [[ -f "$secret_file" ]]; then
    db_password="$(sed -n 's/^MOLCLASS_APPROVAL_DB_PASSWORD=//p' "$secret_file" | head -n 1)"
fi
if [[ -n "$db_password" && ! "$db_password" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'Existing credential file has an unexpected password format: %s\n' "$secret_file" >&2
    exit 1
fi
if [[ -z "$db_password" ]]; then
    db_password="$("$openssl_bin" rand -hex 32)"
fi

"$mysql_bin" --protocol=socket <<SQL
CREATE USER IF NOT EXISTS '$account'@'localhost' IDENTIFIED BY '$db_password';
ALTER USER '$account'@'localhost' IDENTIFIED BY '$db_password';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$account'@'localhost';
GRANT SELECT ON $schema.model_build TO '$account'@'localhost';
GRANT SELECT ON $schema.model_definition TO '$account'@'localhost';
GRANT SELECT ON $schema.model_class TO '$account'@'localhost';
GRANT SELECT ON $schema.model_training_member TO '$account'@'localhost';
GRANT SELECT ON $schema.model_evaluation TO '$account'@'localhost';
GRANT SELECT ON $schema.model_artifact TO '$account'@'localhost';
GRANT INSERT ON $schema.model_approval TO '$account'@'localhost';
GRANT INSERT ON $schema.audit_event TO '$account'@'localhost';
GRANT UPDATE ON $schema.model_build TO '$account'@'localhost';
GRANT UPDATE ON $schema.model_definition TO '$account'@'localhost';

CREATE USER IF NOT EXISTS '$account'@'127.0.0.1' IDENTIFIED BY '$db_password';
ALTER USER '$account'@'127.0.0.1' IDENTIFIED BY '$db_password';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$account'@'127.0.0.1';
GRANT SELECT ON $schema.model_build TO '$account'@'127.0.0.1';
GRANT SELECT ON $schema.model_definition TO '$account'@'127.0.0.1';
GRANT SELECT ON $schema.model_class TO '$account'@'127.0.0.1';
GRANT SELECT ON $schema.model_training_member TO '$account'@'127.0.0.1';
GRANT SELECT ON $schema.model_evaluation TO '$account'@'127.0.0.1';
GRANT SELECT ON $schema.model_artifact TO '$account'@'127.0.0.1';
GRANT INSERT ON $schema.model_approval TO '$account'@'127.0.0.1';
GRANT INSERT ON $schema.audit_event TO '$account'@'127.0.0.1';
GRANT UPDATE ON $schema.model_build TO '$account'@'127.0.0.1';
GRANT UPDATE ON $schema.model_definition TO '$account'@'127.0.0.1';
SQL

install -d -m 700 -o "$invoking_user" -g "$invoking_group" "$secret_dir"
temporary_file="$(mktemp "$secret_dir/model-approval.env.XXXXXX")"
chmod 600 "$temporary_file"
{
    printf '%s\n' '# Local MolClass model-approval database credentials.'
    printf '%s\n' '# Source into the FastAPI service environment; never commit this file.'
    printf 'MOLCLASS_APPROVAL_DB_USER=%s\n' "$account"
    printf 'MOLCLASS_APPROVAL_DB_PASSWORD=%s\n' "$db_password"
} > "$temporary_file"
chown "$invoking_user:$invoking_group" "$temporary_file"
mv -f "$temporary_file" "$secret_file"
chmod 600 "$secret_file"

printf 'Created %s for localhost socket and 127.0.0.1 TCP only.\n' "$account"
printf 'Stored application credentials in %s with mode 0600.\n' "$secret_file"
