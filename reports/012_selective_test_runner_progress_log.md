---
title: "Selective Test Runner Progress Log"
doc_type: "progress_log"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Selective Test Runner Progress Log

## Stage 1 — Namespace Selectors

- **Status:** Done
- **Summary:** Added narrow `-n` / `--namespace` parsing to `semidx.test-runner`, preserving full namespace auto-discovery when no selectors are provided. Added focused tests for default discovery, short and long namespace selectors, repeated selector order, unknown arguments, and missing namespace values.
- **Changed Files:**
  - `src/semidx/test_runner.clj`
  - `test/semidx/test_runner_test.clj`
  - `plans/015_selective_test_runner_plan.md`
  - `MEMORY.md`
- **Verification:**
  - `clojure -M:test -n semidx.test-runner-test`: passed, 6 tests, 7 assertions.
  - `clojure -M:test -n semidx.runtime.capabilities-test`: passed, 2 tests, 13 assertions.
  - `clojure -M:test`: passed, 224 tests, 1587 assertions.
- **Notes:** A REPL probe via the canonical `:nrepl` alias could not load `semidx.test-runner` because `clojure.tools.namespace.find` is available on the `:test` classpath, not the plain `:nrepl` classpath. Verification therefore used the project test entrypoint itself.
