#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

TMP_BASE="${TMPDIR:-.tmp}"

mkdir -p "$TMP_BASE"

./scripts/validate-contracts.sh
clojure -M:test
./scripts/run-benchmarks.sh

for q in contracts/examples/queries/*.json; do
  out="$TMP_BASE/sci-gate-$(basename "$q" .json).json"
  ./scripts/run-mvp-smoke.sh . "$q" "$out" >/dev/null
  if [[ ! -s "$out" ]]; then
    echo "gate_failed: empty output for $q"
    exit 1
  fi
  echo "gate_smoke_ok query=$q output=$out"
done

echo "mvp_gates=ok"
