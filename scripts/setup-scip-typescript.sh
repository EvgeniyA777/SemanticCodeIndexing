#!/usr/bin/env bash
set -euo pipefail

# Repo-managed scip-typescript toolchain (plans/018 Stage 3, ADR-046).
#
# scip-typescript is the exact-tier semantic provider for the TypeScript lane.
# Like the tree-sitter toolchain (ADR-047), it is resolved through explicit
# configuration and a repository-managed install rather than an ambient global
# command, so a SCIP index is reproducible across developer and agent machines.
#
# Reproducibility: the pin lives in the committed lockfile
# scripts/scip-toolchain/package-lock.json, and the install is `npm ci` against
# it. That fixes not only scip-typescript but its transitive `typescript`
# version (scip-typescript depends on `typescript: ^5.6.2`, which would
# otherwise drift and silently change SCIP output while a golden still claims a
# specific version).
#
# Resolution order for the CLI (implemented by the provider adapter, mirrored
# here):
#   1. explicit provider option (:scip_typescript_cli_path)
#   2. environment: SEMIDX_SCIP_TYPESCRIPT_CLI_PATH
#   3. repo-managed: .scip-toolchain/node_modules/.bin/scip-typescript
#   4. ambient PATH (developer convenience only)

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN_DIR="${SEMIDX_SCIP_TOOLCHAIN_DIR:-$ROOT_DIR/.scip-toolchain}"
MANIFEST_DIR="$ROOT_DIR/scripts/scip-toolchain"
SCIP_TYPESCRIPT_PKG="@sourcegraph/scip-typescript"
MANAGED_CLI="$TOOLCHAIN_DIR/node_modules/.bin/scip-typescript"
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
  echo "scip_typescript_status=unavailable"
  echo "scip_typescript_reason=npm_missing"
  exit 1
fi

if [[ ! -f "$MANIFEST_DIR/package.json" || ! -f "$MANIFEST_DIR/package-lock.json" ]]; then
  echo "scip_typescript_status=unavailable"
  echo "scip_typescript_reason=committed_manifest_missing:$MANIFEST_DIR"
  exit 1
fi

# Pinned versions come from the committed lockfile, not this script.
LOCKED_SCIP_VERSION="$(node -p "require('$MANIFEST_DIR/package-lock.json').packages['node_modules/$SCIP_TYPESCRIPT_PKG'].version")"
LOCKED_TS_VERSION="$(node -p "require('$MANIFEST_DIR/package-lock.json').packages['node_modules/typescript'].version")"

mkdir -p "$TOOLCHAIN_DIR"
cp "$MANIFEST_DIR/package.json" "$MANIFEST_DIR/package-lock.json" "$TOOLCHAIN_DIR/"

# `npm ci` fails closed if package.json and the lockfile disagree, and installs
# exactly the locked tree.
(cd "$TOOLCHAIN_DIR" && npm ci --silent --no-audit --no-fund >/dev/null)

if [[ ! -x "$MANAGED_CLI" ]]; then
  echo "scip_typescript_status=unavailable"
  echo "scip_typescript_reason=cli_not_installed"
  exit 1
fi

RESOLVED_SCIP_VERSION="$(cd "$TOOLCHAIN_DIR" && node -p "require('$SCIP_TYPESCRIPT_PKG/package.json').version")"
RESOLVED_TS_VERSION="$(cd "$TOOLCHAIN_DIR" && node -p "require('typescript/package.json').version")"

if [[ "$RESOLVED_SCIP_VERSION" != "$LOCKED_SCIP_VERSION" || "$RESOLVED_TS_VERSION" != "$LOCKED_TS_VERSION" ]]; then
  echo "scip_typescript_status=unavailable" >&2
  echo "scip_typescript_reason=version_drift" >&2
  echo "expected scip-typescript=$LOCKED_SCIP_VERSION typescript=$LOCKED_TS_VERSION" >&2
  echo "resolved scip-typescript=$RESOLVED_SCIP_VERSION typescript=$RESOLVED_TS_VERSION" >&2
  exit 1
fi

if [[ -n "$ENV_FILE" ]]; then
  {
    echo "SEMIDX_SCIP_TYPESCRIPT_CLI_PATH=$MANAGED_CLI"
    echo "SEMIDX_SCIP_TYPESCRIPT_VERSION=$RESOLVED_SCIP_VERSION"
    echo "SEMIDX_SCIP_TYPESCRIPT_TSC_VERSION=$RESOLVED_TS_VERSION"
  } > "$ENV_FILE"
  echo "wrote_env_file=$ENV_FILE"
fi

echo "scip_typescript_status=managed"
echo "scip_typescript_cli=$MANAGED_CLI"
echo "scip_typescript_version=$RESOLVED_SCIP_VERSION"
echo "scip_typescript_tsc_version=$RESOLVED_TS_VERSION"
echo "export SEMIDX_SCIP_TYPESCRIPT_CLI_PATH=$MANAGED_CLI"
