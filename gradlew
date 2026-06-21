#!/usr/bin/env sh
# Copyright (c) the Gradle project contributors.

APP_NAME="$(basename "$0")"
APP_HOME="$(cd "$(dirname "$0")" && pwd)"

# Find java.
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD=java
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

exec "$JAVACMD" $JAVA_OPTS \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
