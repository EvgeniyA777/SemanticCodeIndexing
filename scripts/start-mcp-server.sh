#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ -n "${CLOJURE_BIN:-}" ]; then
  clojure_bin="$CLOJURE_BIN"
else
  clojure_bin="$(command -v clojure || true)"
fi

if [ -z "${clojure_bin:-}" ]; then
  echo "clojure command not found; set CLOJURE_BIN or add clojure to PATH" >&2
  exit 1
fi

export SCI_MCP_ALLOWED_ROOTS="${SCI_MCP_ALLOWED_ROOTS:-$REPO_ROOT}"

cd "$REPO_ROOT"
exec "$clojure_bin" -M:mcp "$@"
