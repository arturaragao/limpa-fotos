#!/bin/sh
# Minimal bootstrap; CI regenerates the official wrapper when the JAR is absent.
APP_HOME=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P) || exit 1
if [ ! -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
    echo 'gradle-wrapper.jar ausente. Execute: gradle wrapper --gradle-version 8.13' >&2
    exit 1
fi
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi
exec "$JAVACMD" -Dfile.encoding=UTF-8 -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
