#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ -n "${CLOJURE_BIN:-}" ]; then
  clojure_bin="$CLOJURE_BIN"
elif [ -x /opt/homebrew/bin/clojure ]; then
  clojure_bin="/opt/homebrew/bin/clojure"
else
  clojure_bin="$(command -v clojure)"
fi

export SCI_MCP_ALLOWED_ROOTS="${SCI_MCP_ALLOWED_ROOTS:-$REPO_ROOT}"

cd "$REPO_ROOT"
exec "$clojure_bin" -M:mcp "$@"
