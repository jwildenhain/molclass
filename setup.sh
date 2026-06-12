#!/usr/bin/env bash
set -e

# Directory for jars
LIB_DIR="$(dirname "${BASH_SOURCE[0]}")/lib"
mkdir -p "$LIB_DIR"

# List of required jars and their Maven coordinates
declare -A JARS
JARS[HikariCP-5.0.1.jar]="com/zaxxer/HikariCP/5.0.1/HikariCP-5.0.1.jar"
JARS[mysql-connector-java-8.0.33.jar]="com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar"
JARS[h2-2.2.224.jar]="com/h2database/h2/2.2.224/h2-2.2.224.jar"
JARS[junit-4.13.2.jar]="junit/junit/4.13.2/junit-4.13.2.jar"
JARS[hamcrest-core-1.3.jar]="org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"

BASE_URL="https://repo1.maven.org/maven2"

for JAR in "${!JARS[@]}"; do
  TARGET="$LIB_DIR/$JAR"
  if [[ -f "$TARGET" ]]; then
    echo "[setup] $JAR already present."
  else
    echo "[setup] Downloading $JAR..."
    curl -L -o "$TARGET" "$BASE_URL/${JARS[$JAR]}"
  fi
done

# Build the classpath string (include lib/*.jar and compiled classes)
CLASSPATH="$(pwd)/build/classes"
for jar in "$LIB_DIR"/*.jar; do
  CLASSPATH="$CLASSPATH:$jar"
done

# Write a helper file that can be sourced to set $CLASSPATH
cat > classpath.sh <<'EOF'
# Auto‑generated classpath for MolClass
export CLASSPATH="{{CLASSPATH}}"
EOF

# Replace placeholder with actual value
sed -i "s|{{CLASSPATH}}|$CLASSPATH|" classpath.sh

echo "[setup] All required JARs are present."
echo "[setup] Classpath helper created at $(pwd)/classpath.sh"
