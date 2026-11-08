#!/usr/bin/env bash
# JDK 21+; independent of Gradle/Loom/Minecraft. CSV defaults outside the checkout.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-/tmp/coopcraft-ration-experiments.csv}"
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAVA="$JAVA_HOME/bin/java"
else
  JAVAC=javac
  JAVA=java
fi
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/coopcraft-rations.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
"$JAVAC" --release 21 -d "$BUILD" \
  "$ROOT"/src/main/java/com/thysummer/coopcraft/civilization/simulation/*.java \
  "$ROOT/tools/analysis/RationExperiments.java"
"$JAVA" -cp "$BUILD" RationExperiments > "$BUILD/results.csv"
cp "$BUILD/results.csv" "$OUT"
printf 'CSV: %s\n' "$OUT" >&2
