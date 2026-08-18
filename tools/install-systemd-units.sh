#!/usr/bin/env bash
set -euo pipefail

# Installs the three MolClass services as systemd *user* units.
#
# User units are used deliberately: they need no root, they run as the account
# that already owns the repository and the 0600 credential files in
# ~/.config/molclass/, and they inherit that account's file permissions. The one
# thing they cannot do by default is start before the user logs in -- enabling
# lingering (a single root action, printed at the end) fixes that.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
unit_source="$repo_root/tools/systemd"
unit_target="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
units=(molclass-api.service molclass-predictor.service molclass-frontend.service)

if [[ "$EUID" -eq 0 ]]; then
    printf 'Run this as your normal user, not root: these are systemd --user units.\n' >&2
    exit 1
fi

missing=0
for credential in api.env; do
    if [[ ! -f "$HOME/.config/molclass/$credential" ]]; then
        printf 'Missing %s. Run: sudo %s/tools/setup-local-api-account.sh\n' \
            "$HOME/.config/molclass/$credential" "$repo_root" >&2
        missing=1
    fi
done
[[ "$missing" -eq 0 ]] || exit 1

if [[ ! -f "$repo_root/molclass-frontend/.next/standalone/server.js" ]]; then
    printf 'No standalone frontend build found. Run: %s/tools/build-frontend.sh\n' "$repo_root" >&2
    exit 1
fi

# The API unit points PATH at .venv so it survives systemd's minimal
# environment. That is only correct if .venv actually has everything
# requirements.txt asks for -- verify it here rather than discovering a
# missing module through a crash-restart loop after install.
venv_python="$repo_root/.venv/bin/python3"
if [[ ! -x "$venv_python" ]]; then
    printf 'No virtualenv at %s/.venv. Create one and install requirements.txt first.\n' "$repo_root" >&2
    exit 1
fi
if ! "$venv_python" -c "
import importlib.metadata as m, re, sys
missing = []
for line in open('$repo_root/html/molclass/api/requirements.txt'):
    line = line.strip()
    if not line or line.startswith('#'):
        continue
    name = re.split(r'[<>=!~]', line, 1)[0].strip()
    try:
        m.version(name)
    except m.PackageNotFoundError:
        missing.append(name)
sys.exit(1 if missing else 0)
" 2>/dev/null; then
    printf 'The .venv at %s/.venv is missing packages requirements.txt needs.\n' "$repo_root" >&2
    printf 'Run: %s/.venv/bin/python3 -m pip install -r %s/html/molclass/api/requirements.txt\n' \
        "$repo_root" "$repo_root" >&2
    exit 1
fi

install -d -m 700 "$unit_target"
for unit in "${units[@]}"; do
    install -m 644 "$unit_source/$unit" "$unit_target/$unit"
    printf 'installed %s\n' "$unit_target/$unit"
done

systemctl --user daemon-reload
systemctl --user enable "${units[@]}"
systemctl --user restart "${units[@]}"

printf '\nStatus:\n'
systemctl --user --no-pager --lines=0 status "${units[@]}" || true

cat <<NOTE

Next steps
----------
Follow logs:      journalctl --user -u molclass-predictor -f
Restart one:      systemctl --user restart molclass-api
Rebuild frontend: $repo_root/tools/build-frontend.sh && systemctl --user restart molclass-frontend

These user units start at login. To make them start at boot without a login,
run this once (the only step needing root):

    sudo loginctl enable-linger $USER
NOTE
