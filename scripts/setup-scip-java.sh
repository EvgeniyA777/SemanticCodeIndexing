#!/usr/bin/env bash
set -euo pipefail

# Repo-managed Java SCIP toolchain (plans/018 Stage 4, ADR-046).
#
# scip-java is the exact-tier semantic provider for the Java lane. Like the
# tree-sitter toolchain (ADR-047) and the scip-typescript toolchain (Stage 3), it
# is resolved through explicit configuration and a repository-managed install
# rather than an ambient global command, so a SCIP index is reproducible across
# developer and agent machines.
#
# Shape of the toolchain (owner decision 2026-09-05): an EXTERNAL process, never
# a semidx runtime dependency. The upstream `scip-java` CLI is a Scala artifact
# that pulls in coursier and an embedded Kotlin compiler; none of that is needed.
# The pipeline is:
#
#   javac + semanticdb-javac plugin  ->  .semanticdb
#   ScipJavaIndexer (scip-semanticdb) ->  .scip
#
# No build tool, no pom.xml, and no coursier are required, so a plain directory
# of .java sources can be indexed directly.
#
# Reproducibility: every jar and its sha256 are pinned in
# scripts/scip-java-toolchain/dependencies.txt and verified on download. A digest
# mismatch fails closed rather than producing an index a committed golden would
# silently disagree with.
#
# Resolution order for the toolchain directory (mirrored by the provider adapter):
#   1. explicit provider option (:scip_java_toolchain_dir)
#   2. environment: SEMIDX_SCIP_JAVA_TOOLCHAIN_DIR
#   3. repo-managed: .scip-java-toolchain/

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN_DIR="${SEMIDX_SCIP_JAVA_TOOLCHAIN_DIR:-$ROOT_DIR/.scip-java-toolchain}"
MANIFEST_DIR="$ROOT_DIR/scripts/scip-java-toolchain"
MANIFEST="$MANIFEST_DIR/dependencies.txt"
DRIVER_SRC="$MANIFEST_DIR/ScipJavaIndexer.java"
LIB_DIR="$TOOLCHAIN_DIR/lib"
DRIVER_DIR="$TOOLCHAIN_DIR/driver"
MAVEN_CENTRAL="${SEMIDX_MAVEN_CENTRAL_URL:-https://repo1.maven.org/maven2}"
ENV_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --write-env-file)
      ENV_FILE="${2:-}"
      shift 2
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

for tool in curl javac java shasum; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "scip_java_status=unavailable"
    echo "scip_java_reason=${tool}_missing"
    exit 1
  fi
done

if [[ ! -f "$MANIFEST" || ! -f "$DRIVER_SRC" ]]; then
  echo "scip_java_status=unavailable"
  echo "scip_java_reason=committed_manifest_missing:$MANIFEST_DIR"
  exit 1
fi

mkdir -p "$LIB_DIR" "$DRIVER_DIR"

CLASSPATH=""
while read -r coordinate expected_sha || [[ -n "$coordinate" ]]; do
  # Skip comments and blank lines.
  [[ -z "$coordinate" || "$coordinate" == \#* ]] && continue

  group_id="${coordinate%%:*}"
  rest="${coordinate#*:}"
  artifact_id="${rest%%:*}"
  version="${rest##*:}"
  jar_name="$artifact_id-$version.jar"
  jar_path="$LIB_DIR/$jar_name"
  group_path="${group_id//./\/}"
  url="$MAVEN_CENTRAL/$group_path/$artifact_id/$version/$jar_name"

  if [[ -f "$jar_path" ]]; then
    actual_sha="$(shasum -a 256 "$jar_path" | cut -d' ' -f1)"
    if [[ "$actual_sha" != "$expected_sha" ]]; then
      echo "cached jar does not match the pin, refetching: $jar_name" >&2
      rm -f "$jar_path"
    fi
  fi

  if [[ ! -f "$jar_path" ]]; then
    if ! curl -fsSL --max-time 180 -o "$jar_path" "$url"; then
      rm -f "$jar_path"
      echo "scip_java_status=unavailable" >&2
      echo "scip_java_reason=download_failed:$jar_name" >&2
      exit 1
    fi
  fi

  actual_sha="$(shasum -a 256 "$jar_path" | cut -d' ' -f1)"
  if [[ "$actual_sha" != "$expected_sha" ]]; then
    rm -f "$jar_path"
    echo "scip_java_status=unavailable" >&2
    echo "scip_java_reason=sha256_mismatch:$jar_name" >&2
    echo "expected $expected_sha" >&2
    echo "actual   $actual_sha" >&2
    exit 1
  fi

  CLASSPATH="${CLASSPATH:+$CLASSPATH:}$jar_path"
done < "$MANIFEST"

if [[ -z "$CLASSPATH" ]]; then
  echo "scip_java_status=unavailable" >&2
  echo "scip_java_reason=manifest_listed_no_jars" >&2
  exit 1
fi

# Compile the committed driver against the pinned jars.
if ! javac -cp "$CLASSPATH" -d "$DRIVER_DIR" "$DRIVER_SRC" 2>&1; then
  echo "scip_java_status=unavailable" >&2
  echo "scip_java_reason=driver_compile_failed" >&2
  exit 1
fi

# The plugin jar is passed to javac's -processorpath, not the driver classpath.
PLUGIN_JAR="$(ls "$LIB_DIR"/semanticdb-javac-*.jar 2>/dev/null | head -1)"
if [[ -z "$PLUGIN_JAR" ]]; then
  echo "scip_java_status=unavailable" >&2
  echo "scip_java_reason=semanticdb_javac_missing" >&2
  exit 1
fi

if [[ -n "$ENV_FILE" ]]; then
  {
    echo "SEMIDX_SCIP_JAVA_TOOLCHAIN_DIR=$TOOLCHAIN_DIR"
    echo "SEMIDX_SCIP_JAVA_PLUGIN_JAR=$PLUGIN_JAR"
    echo "SEMIDX_SCIP_JAVA_CLASSPATH=$CLASSPATH:$DRIVER_DIR"
  } > "$ENV_FILE"
  echo "wrote_env_file=$ENV_FILE"
fi

echo "scip_java_status=managed"
echo "scip_java_toolchain_dir=$TOOLCHAIN_DIR"
echo "scip_java_plugin_jar=$PLUGIN_JAR"
echo "scip_java_driver_dir=$DRIVER_DIR"
echo "scip_java_javac_version=$(javac -version 2>&1 | awk '{print $2}')"
echo "export SEMIDX_SCIP_JAVA_TOOLCHAIN_DIR=$TOOLCHAIN_DIR"
