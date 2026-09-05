#!/usr/bin/env bash
set -euo pipefail

# Regenerate the committed real-SCIP artifact for the plans/018 provider-authority
# Java corpus.
#
# This is a fixture aid, not part of the runtime. It proves the Stage 0 Java
# identity fixture against real scip-java output and gives the adapter tests a
# deterministic artifact to read without requiring the toolchain. The Stage 4
# provider adapter reads SCIP in the JVM and does not depend on this script.
#
# Requires: scripts/setup-scip-java.sh has been run (repo-managed toolchain).

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CORPUS_DIR="$ROOT_DIR/fixtures/provider-authority/corpus/java"
OUT_SCIP="$ROOT_DIR/fixtures/provider-authority/scip/java-corpus.scrubbed.scip"
TOOLCHAIN_DIR="${SEMIDX_SCIP_JAVA_TOOLCHAIN_DIR:-$ROOT_DIR/.scip-java-toolchain}"
LIB_DIR="$TOOLCHAIN_DIR/lib"
DRIVER_DIR="$TOOLCHAIN_DIR/driver"
MANIFEST="$ROOT_DIR/scripts/scip-java-toolchain/dependencies.txt"

if [[ ! -d "$LIB_DIR" || ! -d "$DRIVER_DIR" ]]; then
  echo "Java SCIP toolchain not found at $TOOLCHAIN_DIR" >&2
  echo "run scripts/setup-scip-java.sh first" >&2
  exit 1
fi

# Fail closed if the installed jars drifted from the committed pin: SCIP output
# depends on the toolchain version, and the fixture records it.
while read -r coordinate expected_sha || [[ -n "$coordinate" ]]; do
  [[ -z "$coordinate" || "$coordinate" == \#* ]] && continue
  rest="${coordinate#*:}"
  artifact_id="${rest%%:*}"
  version="${rest##*:}"
  jar_path="$LIB_DIR/$artifact_id-$version.jar"
  if [[ ! -f "$jar_path" ]]; then
    echo "pinned jar missing from the toolchain: $jar_path" >&2
    echo "re-run scripts/setup-scip-java.sh" >&2
    exit 1
  fi
  actual_sha="$(shasum -a 256 "$jar_path" | cut -d' ' -f1)"
  if [[ "$actual_sha" != "$expected_sha" ]]; then
    echo "scip-java toolchain drift for $jar_path" >&2
    echo "  pinned $expected_sha" >&2
    echo "  actual $actual_sha" >&2
    echo "re-run scripts/setup-scip-java.sh" >&2
    exit 1
  fi
done < "$MANIFEST"

PLUGIN_JAR="$LIB_DIR/semanticdb-javac-0.12.3.jar"
CLASSPATH="$(find "$LIB_DIR" -name '*.jar' | sort | tr '\n' ':')$DRIVER_DIR"

WORK_DIR="$(mktemp -d -t scip-java-corpus)"
trap 'rm -rf "$WORK_DIR"' EXIT
SEMANTICDB_OUT="$WORK_DIR/semanticdb"
CLASSES_OUT="$WORK_DIR/classes"
mkdir -p "$SEMANTICDB_OUT" "$CLASSES_OUT"

# Compile with the SemanticDB plugin. -sourceroot makes the emitted document
# paths corpus-relative, which is what the committed fixture must contain.
# Collected with a while-read loop rather than `mapfile`, which macOS's system
# bash 3.2 does not have.
JAVA_SOURCES=()
while IFS= read -r source_file; do
  JAVA_SOURCES+=("$source_file")
done < <(find "$CORPUS_DIR" -name '*.java' | sort)
if [[ ${#JAVA_SOURCES[@]} -eq 0 ]]; then
  echo "no .java sources under $CORPUS_DIR" >&2
  exit 1
fi

(cd "$CORPUS_DIR" && javac \
  -processorpath "$PLUGIN_JAR" \
  -Xplugin:"semanticdb -sourceroot:$CORPUS_DIR -targetroot:$SEMANTICDB_OUT" \
  -d "$CLASSES_OUT" \
  "${JAVA_SOURCES[@]}")

java -cp "$CLASSPATH" ScipJavaIndexer \
  "$SEMANTICDB_OUT" "$CORPUS_DIR" "$OUT_SCIP" --scrub-project-root

# A raw .scip embeds the absolute file:// path of the indexing machine. The
# driver scrubs metadata.project_root; verify it actually left nothing behind
# rather than trusting the flag.
if LC_ALL=C grep -q "file://" "$OUT_SCIP"; then
  echo "refusing to keep a fixture containing a host path: $OUT_SCIP" >&2
  exit 1
fi
if LC_ALL=C grep -q "$HOME" "$OUT_SCIP"; then
  echo "refusing to keep a fixture containing the home directory path: $OUT_SCIP" >&2
  exit 1
fi

echo "wrote $OUT_SCIP"
