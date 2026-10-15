#!/usr/bin/env bash
# Run only the integration tests (classes matching *IntegrationTest).
# They exercise the real `git` binary — git must be on PATH.
# Needs a JDK 17-23 (NOT 25) — set JAVA_HOME or let the sdkman fallback pick one.
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${JAVA_HOME:-}" ] || "${JAVA_HOME}/bin/java" -version 2>&1 | grep -qE '"(2[4-9])' ; then
    for candidate in "$HOME"/.sdkman/candidates/java/21* "$HOME"/.sdkman/candidates/java/17*; do
        if [ -x "$candidate/bin/java" ]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: set JAVA_HOME to a JDK 17-23 and re-run." >&2
    exit 1
fi

./gradlew test --rerun --tests "*IntegrationTest" "$@"
