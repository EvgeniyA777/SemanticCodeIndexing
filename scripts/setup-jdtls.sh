#!/usr/bin/env bash
set -euo pipefail

# Repo-managed Eclipse JDT Language Server toolchain (plans/018 Stage 5b,
# ADR-046, ADR-047).
#
# jdtls is the Java live-overlay provider. Like the tree-sitter, SCIP, and
# TypeScript LSP toolchains, it is resolved through explicit configuration and a
# repository-managed install rather than an ambient global command: a Homebrew
# or distribution jdtls pins nothing, so its version would differ between a
# developer machine and CI and the provider's output would differ with it.
#
# The pin is the milestone tarball plus its sha256. Verified before extraction,
# so a mirror serving something else fails closed instead of installing it.
#
# Two facts about this server that the pin cannot express, both verified
# against 1.54.0 and enforced by the adapter rather than here:
#
#   1. it requires a JDK 21 or newer to run, independent of the JDK semidx
#      itself runs on;
#   2. it needs a writable -configuration directory and a -data workspace
#      directory outside the repository.
#
# Resolution order for the install (implemented by the provider adapter,
# mirrored here):
#   1. explicit provider option (:java_lsp_home)
#   2. environment: SEMIDX_JDTLS_HOME
#   3. repo-managed: .jdtls-toolchain/
#   (no ambient PATH step: a `jdtls` on PATH is exactly the unpinned install
#    this script exists to replace)

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN_DIR="${SEMIDX_JDTLS_TOOLCHAIN_DIR:-$ROOT_DIR/.jdtls-toolchain}"

JDTLS_VERSION="1.54.0"
JDTLS_BUILD="202511261751"
JDTLS_TARBALL="jdt-language-server-${JDTLS_VERSION}-${JDTLS_BUILD}.tar.gz"
# The direct download.eclipse.org path intermittently returns 504; the
# downloads.php mirror selector with r=1 serves the same artifact, and the
# digest below is what makes either source acceptable.
JDTLS_URL="https://www.eclipse.org/downloads/download.php?file=/jdtls/milestones/${JDTLS_VERSION}/${JDTLS_TARBALL}&r=1"
JDTLS_SHA256="1a291a269bd88b3c4048219122961a52ec80872afbc7a3f34270b2ce77f7a14c"

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

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

MARKER="$TOOLCHAIN_DIR/.installed-$JDTLS_VERSION-$JDTLS_BUILD"
LAUNCHER_GLOB="$TOOLCHAIN_DIR/plugins/org.eclipse.equinox.launcher_*.jar"

if [[ -f "$MARKER" ]] && compgen -G "$LAUNCHER_GLOB" >/dev/null; then
  echo "jdtls_status=managed"
  echo "jdtls_home=$TOOLCHAIN_DIR"
  echo "jdtls_version=$JDTLS_VERSION"
  echo "jdtls_cached=true"
else
  if ! command -v curl >/dev/null 2>&1; then
    echo "jdtls_status=unavailable"
    echo "jdtls_reason=curl_missing"
    exit 1
  fi

  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT
  ARCHIVE="$TMP_DIR/$JDTLS_TARBALL"

  echo "downloading $JDTLS_TARBALL" >&2
  if ! curl -sSL --max-time 600 -o "$ARCHIVE" "$JDTLS_URL"; then
    echo "jdtls_status=unavailable"
    echo "jdtls_reason=download_failed"
    exit 1
  fi

  ACTUAL="$(sha256_of "$ARCHIVE")"
  if [[ "$ACTUAL" != "$JDTLS_SHA256" ]]; then
    echo "jdtls_status=unavailable" >&2
    echo "jdtls_reason=sha256_mismatch" >&2
    echo "expected $JDTLS_SHA256" >&2
    echo "actual   $ACTUAL" >&2
    exit 1
  fi

  # Replace any drifted install rather than unpacking on top of it.
  rm -rf "$TOOLCHAIN_DIR"
  mkdir -p "$TOOLCHAIN_DIR"
  tar -xzf "$ARCHIVE" -C "$TOOLCHAIN_DIR"

  if ! compgen -G "$LAUNCHER_GLOB" >/dev/null; then
    echo "jdtls_status=unavailable" >&2
    echo "jdtls_reason=launcher_missing_after_extract" >&2
    exit 1
  fi

  touch "$MARKER"

  echo "jdtls_status=managed"
  echo "jdtls_home=$TOOLCHAIN_DIR"
  echo "jdtls_version=$JDTLS_VERSION"
  echo "jdtls_cached=false"
fi

LAUNCHER="$(compgen -G "$LAUNCHER_GLOB" | head -1)"
echo "jdtls_launcher=$LAUNCHER"

if [[ -n "$ENV_FILE" ]]; then
  {
    echo "SEMIDX_JDTLS_HOME=$TOOLCHAIN_DIR"
    echo "SEMIDX_JDTLS_VERSION=$JDTLS_VERSION"
  } > "$ENV_FILE"
  echo "wrote_env_file=$ENV_FILE"
fi

echo "export SEMIDX_JDTLS_HOME=$TOOLCHAIN_DIR"
