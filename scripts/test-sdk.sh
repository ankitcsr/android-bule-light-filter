#!/usr/bin/env bash
# Run the same JUnit suite with the verified Gradle distribution's bundled JUnit.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or later}"
amber_gradle_lib="${AMBER_GRADLE_LIB:-/workspace/toolchains/gradle-8.9/lib}"
amber_junit="$amber_gradle_lib/junit-4.13.2.jar"
amber_hamcrest="$amber_gradle_lib/hamcrest-core-1.3.jar"
test -f "$amber_junit"
test -f "$amber_hamcrest"
mkdir -p app/build/sdk-tests
"$JAVA_HOME/bin/javac" --release 17 -classpath "$amber_junit:$amber_hamcrest" \
    -d app/build/sdk-tests app/src/main/java/com/anknonimp/amber/FilterMath.java \
    app/src/test/java/com/anknonimp/amber/FilterMathTest.java
"$JAVA_HOME/bin/java" -classpath "app/build/sdk-tests:$amber_junit:$amber_hamcrest" \
    org.junit.runner.JUnitCore com.anknonimp.amber.FilterMathTest
