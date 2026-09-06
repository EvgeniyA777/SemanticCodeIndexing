#!/usr/bin/env bash
set -euo pipefail

# Repo-managed TypeScript LSP toolchain (plans/018 Stage 5a, ADR-046).
#
# typescript-language-server is the live-overlay exact-tier provider for the
# TypeScript lane. Like the tree-sitter toolchain (ADR-047) and the SCIP
# toolchains, it is resolved through explicit configuration and a
# repository-managed install rather than an ambient global command, so an
# overlay run is reproducible across developer and agent machines.
#
# Two pins matter here, both verified against the real server:
#
#   1. typescript-language-server drives a classic tsserver. TypeScript 7 ships
#      a native compiler with no lib/tsserver.js, and the server refuses to
#      start against it, so `typescript` is pinned to 5.x as a direct dependency
#      rather than floated.
#   2. The server locates TypeScript from the *workspace*, not from beside
#      itself. Indexing a workspace with no node_modules/typescript therefore
#      fails initialization unless the adapter passes tsserver.path in
#      initializationOptions. This script reports that path; the adapter sends
#      it.
#
# Resolution order for the server command (implemented by the provider adapter,
# mirrored here):
#   1. explicit provider option (:typescript_lsp_command)
#   2. environment: SEMIDX_TYPESCRIPT_LSP_COMMAND
#   3. repo-managed: .lsp-toolchain/node_modules/.bin/typescript-language-server
#   4. ambient PATH (developer convenience only)

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN_DIR="${SEMIDX_LSP_TOOLCHAIN_DIR:-$ROOT_DIR/.lsp-toolchain}"
MANIFEST_DIR="$ROOT_DIR/scripts/lsp-toolchain"
SERVER_PKG="typescript-language-server"
MANAGED_SERVER="$TOOLCHAIN_DIR/node_modules/.bin/typescript-language-server"
MANAGED_TSSERVER="$TOOLCHAIN_DIR/node_modules/typescript/lib/tsserver.js"
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

if ! command -v npm >/dev/null 2>&1; then
  echo "typescript_lsp_status=unavailable"
  echo "typescript_lsp_reason=npm_missing"
  exit 1
fi

if [[ ! -f "$MANIFEST_DIR/package.json" || ! -f "$MANIFEST_DIR/package-lock.json" ]]; then
  echo "typescript_lsp_status=unavailable"
  echo "typescript_lsp_reason=committed_manifest_missing:$MANIFEST_DIR"
  exit 1
fi

# Pinned versions come from the committed lockfile, not this script.
LOCKED_SERVER_VERSION="$(node -p "require('$MANIFEST_DIR/package-lock.json').packages['node_modules/$SERVER_PKG'].version")"
LOCKED_TS_VERSION="$(node -p "require('$MANIFEST_DIR/package-lock.json').packages['node_modules/typescript'].version")"

mkdir -p "$TOOLCHAIN_DIR"
cp "$MANIFEST_DIR/package.json" "$MANIFEST_DIR/package-lock.json" "$TOOLCHAIN_DIR/"

# `npm ci` fails closed if package.json and the lockfile disagree, and installs
# exactly the locked tree.
(cd "$TOOLCHAIN_DIR" && npm ci --silent --no-audit --no-fund >/dev/null)

if [[ ! -x "$MANAGED_SERVER" ]]; then
  echo "typescript_lsp_status=unavailable"
  echo "typescript_lsp_reason=server_not_installed"
  exit 1
fi

# The server is useless without a classic tsserver to drive; failing here beats
# failing later inside an initialize handshake.
if [[ ! -f "$MANAGED_TSSERVER" ]]; then
  echo "typescript_lsp_status=unavailable" >&2
  echo "typescript_lsp_reason=tsserver_missing:$MANAGED_TSSERVER" >&2
  echo "TypeScript 7 ships no lib/tsserver.js; the pin must stay on 5.x" >&2
  exit 1
fi

RESOLVED_SERVER_VERSION="$(cd "$TOOLCHAIN_DIR" && node -p "require('$SERVER_PKG/package.json').version")"
RESOLVED_TS_VERSION="$(cd "$TOOLCHAIN_DIR" && node -p "require('typescript/package.json').version")"

if [[ "$RESOLVED_SERVER_VERSION" != "$LOCKED_SERVER_VERSION" || "$RESOLVED_TS_VERSION" != "$LOCKED_TS_VERSION" ]]; then
  echo "typescript_lsp_status=unavailable" >&2
  echo "typescript_lsp_reason=version_drift" >&2
  echo "expected typescript-language-server=$LOCKED_SERVER_VERSION typescript=$LOCKED_TS_VERSION" >&2
  echo "resolved typescript-language-server=$RESOLVED_SERVER_VERSION typescript=$RESOLVED_TS_VERSION" >&2
  exit 1
fi

if [[ -n "$ENV_FILE" ]]; then
  {
    echo "SEMIDX_TYPESCRIPT_LSP_COMMAND=$MANAGED_SERVER"
    echo "SEMIDX_TYPESCRIPT_LSP_TSSERVER_PATH=$MANAGED_TSSERVER"
    echo "SEMIDX_TYPESCRIPT_LSP_VERSION=$RESOLVED_SERVER_VERSION"
  } > "$ENV_FILE"
  echo "wrote_env_file=$ENV_FILE"
fi

echo "typescript_lsp_status=managed"
echo "typescript_lsp_command=$MANAGED_SERVER"
echo "typescript_lsp_tsserver=$MANAGED_TSSERVER"
echo "typescript_lsp_version=$RESOLVED_SERVER_VERSION"
echo "typescript_lsp_tsc_version=$RESOLVED_TS_VERSION"
echo "export SEMIDX_TYPESCRIPT_LSP_COMMAND=$MANAGED_SERVER"
