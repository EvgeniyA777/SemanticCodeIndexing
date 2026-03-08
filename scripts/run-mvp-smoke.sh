#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ROOT_PATH="${1:-.}"
QUERY_PATH="${2:-contracts/examples/queries/symbol-target.json}"
OUT_PATH="${3:-/tmp/semantic-code-indexing-smoke.json}"

clojure -M:runtime --root "$ROOT_PATH" --query "$QUERY_PATH" --out "$OUT_PATH"
printf "smoke_output=%s\n" "$OUT_PATH"
