#!/usr/bin/env bash
# Run a v3 queue worker outside Docker, against the local MariaDB.
#
#   tools/run_v3_worker.sh sdf   [--poll-seconds 2 --lease-seconds 120]
#   tools/run_v3_worker.sh model [--poll-seconds 30 --threads 8]
#
# Credentials come from an untracked .env at the repository root (see
# .env.example). They are sourced, never echoed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

role="${1:-}"
shift || true
case "$role" in
  sdf)   main="molclass.importer.V3SdfWorker" ;;
  model) main="molclass.models.V3ModelPipelineWorker" ;;
  *) echo "usage: $0 {sdf|model} [worker args...]" >&2; exit 2 ;;
esac

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

: "${MOLCLASS_DB_USER:?set MOLCLASS_DB_USER (put it in an untracked .env)}"
: "${MOLCLASS_DB_PASSWORD:?set MOLCLASS_DB_PASSWORD (put it in an untracked .env)}"
export MOLCLASS_JDBC_URL="${MOLCLASS_JDBC_URL:-jdbc:mariadb://127.0.0.1:3306/molclass_v3}"
export MOLCLASS_V3_SCHEMA="${MOLCLASS_V3_SCHEMA:-molclass_v3}"
export MOLCLASS_UPLOAD_ROOT="${MOLCLASS_UPLOAD_ROOT:-$ROOT/uploads/v3}"

app_jar="$(ls -1 build/libs/MolClass-*.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$app_jar" ] || { echo "No build/libs/MolClass-*.jar. Run: ./gradlew jar" >&2; exit 1; }

driver="$(find "$HOME/.gradle/caches" -name 'mariadb-java-client-*.jar' 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$driver" ] || { echo "MariaDB JDBC driver not found in the Gradle cache." >&2; exit 1; }

# Read from the Gradle-generated runtime classpath rather than globbing lib/*.jar
# directly: lib/ only holds the jars not resolved from Maven (see build.gradle),
# so a bare glob silently drops the CDK/Weka modules the build actually links
# against and desyncs this worker's descriptor catalog / model versions from it.
cp_file="build/runtime-classpath.txt"
[ -s "$cp_file" ] || { echo "Missing $cp_file. Run: ./gradlew runtimeClasspathFile" >&2; exit 1; }

CP="$app_jar:$driver:$(cat "$cp_file")"

echo "worker : $main"
echo "jar    : $app_jar"
echo "schema : $MOLCLASS_V3_SCHEMA"
echo "uploads: $MOLCLASS_UPLOAD_ROOT"
exec java -cp "$CP" "$main" "$@"
