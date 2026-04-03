#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

TMP_BASE="${TMPDIR:-.tmp}"
DATASET_PATH="${1:-fixtures/semantic-quality/report-dataset.json}"
OUT_PATH="${2:-$TMP_BASE/semantic-quality-report.json}"
SUMMARY_PATH="${3:-$TMP_BASE/semantic-quality-report-summary.md}"
OUT_DIR="$(dirname "$OUT_PATH")"
SUMMARY_DIR="$(dirname "$SUMMARY_PATH")"

mkdir -p "$OUT_DIR" "$SUMMARY_DIR"
rm -f "$OUT_PATH" "$SUMMARY_PATH"

tmp_report="$(mktemp "$OUT_DIR/semantic-quality-report.XXXXXX.json")"
tmp_summary="$(mktemp "$SUMMARY_DIR/semantic-quality-report-summary.XXXXXX.md")"

cleanup() {
  rm -f "$tmp_report" "$tmp_summary"
}
trap cleanup EXIT

set +e
clojure -M:eval semantic-quality-report --dataset "$DATASET_PATH" --out "$tmp_report"
cli_status=$?
set -e

if [[ ! -s "$tmp_report" ]]; then
  echo "semantic_quality_runner_failed: missing report output at $tmp_report" >&2
  exit "${cli_status:-1}"
fi

python3 - "$tmp_report" "$tmp_summary" "$DATASET_PATH" <<'PY'
import json
import sys

out_path, summary_path, dataset_path = sys.argv[1:4]
with open(out_path, "r", encoding="utf-8") as fh:
    report = json.load(fh)

gate = bool(report.get("gate_decision", {}).get("eligible?", False))
summary = report.get("summary", {})
metrics = summary.get("metrics", {})

lines = [
    "# Semantic Quality Report",
    "",
    f"- dataset: `{dataset_path}`",
    f"- gate_eligible: `{str(gate).lower()}`",
    f"- cases: `{summary.get('cases')}`",
    f"- expected_change_match_rate: `{metrics.get('expected_change_match_rate')}`",
    f"- identity_stability_rate: `{metrics.get('identity_stability_rate')}`",
    f"- move_rename_recovery_rate: `{metrics.get('move_rename_recovery_rate')}`",
    f"- implementation_vs_meaning_accuracy: `{metrics.get('implementation_vs_meaning_accuracy')}`",
    f"- unmatched_rate: `{metrics.get('unmatched_rate')}`",
]

with open(summary_path, "w", encoding="utf-8") as fh:
    fh.write("\n".join(lines) + "\n")

status = "eligible" if gate else "advisory_failure"
print(f"semantic_quality_gate={status}")
print(f"semantic_quality_report={out_path}")
print(f"semantic_quality_summary={summary_path}")
PY

if [[ "$cli_status" -ne 0 ]] && ! python3 - "$tmp_report" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as fh:
    report = json.load(fh)

if "gate_decision" not in report or "summary" not in report:
    raise SystemExit(1)
PY
then
  echo "semantic_quality_runner_failed: semantic quality CLI exited nonzero without a valid report" >&2
  exit "$cli_status"
fi

mv "$tmp_report" "$OUT_PATH"
mv "$tmp_summary" "$SUMMARY_PATH"

exit 0
