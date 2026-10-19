#!/usr/bin/env bash
# Build the distributable AgentNotes plugin zip.
# Usage: ./dist.sh [deploy-dir]
#   deploy-dir (optional): existing content is removed, then the fresh zip is copied in.
# Needs a JDK 17-23 (NOT 25) — set JAVA_HOME or let the sdkman fallback pick one.
set -euo pipefail
cd "$(dirname "$0")"

TARGET_DIR="${1:-}"

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
echo "Using JAVA_HOME=$JAVA_HOME"

# drop stale zips so the printed result is unambiguously THIS build
rm -f build/distributions/*.zip

./gradlew buildPlugin

echo
echo "Distribution ready:"
ls -1 build/distributions/*.zip
echo 'Install via: Settings > Plugins > gear icon > "Install Plugin from Disk..."'

if [ -n "$TARGET_DIR" ]; then
    mkdir -p "$TARGET_DIR"
    TARGET_DIR="$(cd "$TARGET_DIR" && pwd)"
    if [ "$TARGET_DIR" = "/" ] || [ "$TARGET_DIR" = "$HOME" ]; then
        echo "ERROR: refusing to wipe $TARGET_DIR" >&2
        exit 1
    fi
    find "$TARGET_DIR" -mindepth 1 -delete
    cp build/distributions/*.zip "$TARGET_DIR"/
    echo
    echo "Deployed to $TARGET_DIR:"
    ls -1 "$TARGET_DIR"
fi
