#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

TMP_BASE="${TMPDIR:-.tmp}"
DATASET_PATH="${1:-fixtures/semantic-quality/report-dataset.json}"
OUT_PATH="${2:-$TMP_BASE/semantic-quality-report.json}"
SUMMARY_PATH="${3:-$TMP_BASE/semantic-quality-report-summary.md}"

exec clojure -M:eval semantic-quality-runner \
  --dataset "$DATASET_PATH" \
  --out "$OUT_PATH" \
  --summary-out "$SUMMARY_PATH"
