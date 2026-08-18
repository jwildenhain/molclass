#!/usr/bin/env bash
set -euo pipefail

# Builds the Next.js frontend and stages the assets the standalone server needs.
#
# `next build` with output:"standalone" emits .next/standalone/server.js but does
# NOT copy public/ or .next/static/ next to it -- the Dockerfile does that with
# explicit COPY steps. Running the standalone server without this staging yields
# a site with no CSS, no fonts, and no client chunks, so do it here too.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
frontend="$repo_root/molclass-frontend"

cd "$frontend"

npm run build

mkdir -p .next/standalone/.next
cp -r public/. .next/standalone/public 2>/dev/null || true
cp -r .next/static .next/standalone/.next/static

printf 'Standalone build staged at %s/.next/standalone/server.js\n' "$frontend"
printf 'Restart the service with: systemctl --user restart molclass-frontend\n'
