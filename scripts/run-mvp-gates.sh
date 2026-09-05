#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

TMP_BASE="${TMPDIR:-.tmp}"

mkdir -p "$TMP_BASE"

# Contract validation is deliberately not a stage here. It is owned by the
# `Contracts Validation` workflow (scripts/validate-contracts.sh), which exists
# to give a fast standalone signal on contract changes; running it again as a
# stage of the gates made the same check appear four times per push and made
# "which run failed" harder to answer than it should be. There is no coverage
# gap: `semidx.contracts.validator` requires only `semidx.contracts.schemas`, so
# nothing outside contracts/, fixtures/, src/semidx/contracts/, and deps.edn can
# break it — and that is exactly the path filter the workflow triggers on. Run
# ./scripts/validate-contracts.sh separately when validating contracts locally.
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
