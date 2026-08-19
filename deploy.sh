#!/usr/bin/env bash
#
# Deployment & Execution script for MolClass (Gradle-first)
#
# Usage:
#   ./deploy.sh <ClassName|ToolName> [Arguments...]
#
# Examples:
#   ./deploy.sh SdfImporter <sdf_target> <username> <email> <mol_type> <pmid> <info> <id>
#   ./deploy.sh Predictor 123
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ ! -x "./gradlew" ]]; then
    echo "[deploy] Error: ./gradlew is not executable."
    echo "[deploy] Run: chmod +x gradlew"
    exit 1
fi

if [[ ! -f "./setup.sh" ]]; then
    echo "[deploy] Warning: ./setup.sh not found. Dependency bootstrap may fail."
fi

if [[ ! -f "./gradle/wrapper/gradle-wrapper.jar" ]]; then
    echo "[deploy] Error: Gradle wrapper jar missing."
    echo "[deploy] Expected ./gradle/wrapper/gradle-wrapper.jar"
    exit 1
fi

if [[ ! -x "./setup.sh" ]]; then
    echo "[deploy] Error: Missing setup.sh; cannot ensure lib/ dependencies are available."
    echo "[deploy] Run ./setup.sh first to download required jars into lib/."
    exit 1
fi

# Quick sanity check: setup should have generated runtime dependency jars.
if [[ ! -d "./lib" || -z "$(ls -1 ./lib/*.jar 2>/dev/null)" ]]; then
    echo "[deploy] Error: No jar dependencies found in ./lib/."
    echo "[deploy] Run ./setup.sh to download required jars, then retry."
    exit 1
fi

if [[ $# -lt 1 ]]; then
    cat <<'USAGE'
Usage:
  ./deploy.sh <ClassName|ToolName> [Arguments...]

ClassName examples:
  molclass.Predictor
  molclass.ModelBuilder
  molclass.SdfImporter

ToolName examples:
  Predictor
  ModelBuilder
  SdfImporter
  DBConnectionTest
USAGE
    exit 0
fi

TARGET_CLASS="$1"
shift

if [[ -z "${GRADLE_OPTS:-}" ]]; then
    export GRADLE_OPTS="-Dorg.gradle.daemon=false"
fi

# Validate DB connectivity first via the Java launcher, using Main dispatch.
./gradlew -q run --args="DBConnectionTest"

ARGS="$TARGET_CLASS"
if [[ $# -gt 0 ]]; then
    ARGS="$ARGS $*"
fi

echo "[deploy] Starting ${TARGET_CLASS}"
echo "--------------------------------------------------"
./gradlew -q run --args="$ARGS"
echo "--------------------------------------------------"
echo "[deploy] Finished execution of ${TARGET_CLASS}."
