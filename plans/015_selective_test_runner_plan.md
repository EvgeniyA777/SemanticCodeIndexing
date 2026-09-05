---
title: "Selective Test Runner Plan"
doc_type: "implementation_plan"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Selective Test Runner Plan

## Goal

Allow agents and developers to run a focused subset of Clojure tests through the existing project test entrypoint without losing the current zero-registration full-suite behavior.

The target command is:

```bash
clojure -M:test -n semidx.runtime.capabilities-test
```

## Problem

`semidx.test-runner/-main` currently ignores all CLI arguments and always discovers every `*-test` namespace under `test/`. A command that looks selective, such as `clojure -M:test -n semidx.runtime.capabilities-test`, still runs the full suite.

That makes review loops slower and can mislead agents into reporting a narrow test run when the local runner actually executed all tests.

## Scope

In scope:

- Add test namespace selection to `src/semidx/test_runner.clj`.
- Preserve full auto-discovery when no selector is provided.
- Add focused tests for argument parsing and namespace selection.
- Document the supported CLI shape in this plan and, if useful during implementation, in the test runner namespace docstring or project rules.

Out of scope:

- Changing test discovery rules for full-suite runs.
- Adding tag-based, regex-based, file-based, or test-var-level selection.
- Replacing `clojure.test` or introducing a new test runner dependency.
- Coupling this work to the capability self-description plan or progress log.

## Proposed CLI

Supported selectors:

```bash
clojure -M:test -n semidx.runtime.capabilities-test
clojure -M:test --namespace semidx.runtime.capabilities-test
clojure -M:test -n semidx.runtime.capabilities-test -n semidx.runtime.http-test
```

Behavior:

- No args: discover and run all `*-test` namespaces under `test/`.
- One or more `-n` / `--namespace` args: require and run only the selected namespaces, in the order given.
- Unknown arguments: print a clear error and exit non-zero before running tests.
- Missing namespace value after `-n` / `--namespace`: print a clear error and exit non-zero before running tests.

## Implementation Steps

1. Add argument parsing to `semidx.test-runner`.
   - Introduce a small private parser that returns selected namespace symbols.
   - Keep parsing intentionally narrow: only `-n` and `--namespace`.
   - Throw `ex-info` with structured data for unknown args and missing values.

2. Update `-main`.
   - Change `-main` from ignoring args to accepting and parsing them.
   - If selectors are present, use them directly.
   - If selectors are absent, call `discover-test-namespaces`.
   - Print clear execution mode:
     - `Discovered N test namespaces` for full suite.
     - `Selected N test namespace(s)` for selective runs.

3. Add tests.
   - Create `test/semidx/test_runner_test.clj`.
   - Cover `-n`, `--namespace`, repeated namespace selectors, unknown args, and missing values.
   - Keep tests focused on parser/selection behavior rather than running the whole suite recursively.

4. Verify with the new selective runner.
   - Run `clojure -M:test -n semidx.test-runner-test`.
   - Run `clojure -M:test -n semidx.runtime.capabilities-test` to confirm only that namespace runs.
   - Run `clojure -M:test` to confirm full-suite discovery still works.

## Acceptance Criteria

- `clojure -M:test` still discovers and runs all test namespaces.
- `clojure -M:test -n semidx.runtime.capabilities-test` runs only `semidx.runtime.capabilities-test`.
- Repeated `-n` selectors run exactly the requested namespaces.
- Invalid CLI usage exits before tests run and gives a useful error message.
- The new test runner tests pass via the selective command.

## Notes For Future Agents

This is an independent test-infrastructure task. Do not treat it as part of the capability self-description implementation. It can be used later to re-check capability findings more quickly, but it should be implemented and reviewed as its own change.
