---
title: "Benchmark Baseline Repair Progress Log"
doc_type: "progress_log"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Benchmark Baseline Repair Progress Log

## Stage 1 — Retrieval Benchmark Baseline Repair

- **Status:** Done
- **Summary:** Restored `./scripts/run-benchmarks.sh` to a green baseline before the planned adapter split. Added JavaScript, HTML, and CSS files to the synthetic benchmark repository; normalized adapter-specific public unit kinds at the retrieval projection boundary; and aligned stale benchmark expectations with current language confidence ceilings, arity-aware Elixir ids, and current ambiguity behavior.
- **Changed Files:**
  - `src/semidx/runtime/benchmarks.clj`
  - `src/semidx/runtime/retrieval.clj`
  - `fixtures/retrieval/*.json`
  - `MEMORY.md`
- **Verification:**
  - `./scripts/run-benchmarks.sh`: passed, 21 fixtures, 0 failures.
  - `clojure -M:test`: passed, 225 tests, 1590 assertions.
  - `./scripts/validate-contracts.sh`: passed, `checked_json_files=61`.
  - `./scripts/run-semantic-quality-report.sh`: exited 0 and produced an advisory failure summary: expected change match rate `0.8333333333333334`, identity stability `1.0`, move/rename recovery `1.0`, implementation-vs-meaning accuracy `0.6666666666666666`, unmatched rate `0.0`.

## Notes

- The semantic quality command is advisory for this repository state; benchmark baseline repair did not attempt to retune semantic-quality fixtures.
