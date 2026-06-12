#!/usr/bin/env bash
#
# Deployment & Execution script for MolClass
#
# Usage:
#   ./deploy.sh <ClassName> [Arguments...]
#
# Example:
#   ./deploy.sh molclass.Predictor 1
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 1. Check if setup and classpath exist
if [[ ! -f "./classpath.sh" ]]; then
    echo "[deploy] Error: ./classpath.sh not found."
    echo "[deploy] Please run './setup.sh' first to resolve dependencies and generate classpath."
    exit 1
fi

# Source the classpath script to export CLASSPATH
source ./classpath.sh

# 2. Check if build directory exists and classes are compiled
if [[ ! -d "build/classes" || -z "$(ls -A build/classes 2>/dev/null)" ]]; then
    echo "[deploy] Build directory empty. Compiling project via Ant first..."
    ant compile
fi

# 3. Perform database connection sanity check
if ! java -Dweka.core.WekaPackageManager.offline=true --add-opens java.base/java.lang=ALL-UNNAMED -cp "$CLASSPATH" descriptors.DBConnectionTest; then
    echo "[deploy] Error: Database connection check failed."
    echo "[deploy] Please verify your database credentials and configuration in 'molclass.conf.xml'."
    exit 1
fi

# 4. Check class argument
if [[ $# -lt 1 ]]; then
    echo "Usage:"
    echo "  ./deploy.sh <ClassName> [Arguments...]"
    echo ""
    echo "Available core classes to run:"
    echo "  molclass.Main                     - Command line main entry point"
    echo "  descriptors.AutomaticCalcDriver   - Calculate CDK molecular descriptors"
    echo "  fingerprints.Fingerprinter        - Generate molecular fingerprints"
    echo "  fingerprints.Similarity           - Calculate molecular similarities"
    echo "  molclass.ModelBuilder             - Build and evaluate Weka machine learning models"
    echo "  molclass.Predictor                - Apply Weka models to predict batch activities"
    exit 0
fi

MAIN_CLASS="$1"
shift

echo "[deploy] Starting $MAIN_CLASS..."
echo "--------------------------------------------------"

# Execute the Java class with modern JVM compatibility arguments
java -Dweka.core.WekaPackageManager.offline=true \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     -cp "$CLASSPATH" \
     "$MAIN_CLASS" "$@"

echo "--------------------------------------------------"
echo "[deploy] Finished execution of $MAIN_CLASS."
