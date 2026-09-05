---
title: "Test Discovery and Mirroring Plan"
doc_type: "implementation_plan"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Implementation Plan: Test Discovery and Mirroring

Execution status: implemented on 2026-07-13 (commits `4919b82`, `108bbbd`). See
[009_test_discovery_and_mirroring_progress_log.md](../reports/009_test_discovery_and_mirroring_progress_log.md)
for the record and verification results.

Prepare the test suite for the upcoming large refactor by removing the
manual-test-list footgun, giving tests a 1:1 mapping to their source, and
recording the resulting convention in `RULES.md`.

## Motivation

- `src/semidx/test_runner.clj` hand-maintains the list of test namespaces **twice**
  (once in `:require`, once in the `run-tests` call). Adding a test requires
  editing both; forgetting means a test silently never runs and a "green" suite
  lies. This session already edited that file twice. It also rubs against the
  workspace-canon principle "keep one canonical source of truth for each durable
  rule" (`_policy/AGENTS.md`).
- The test tree is flat (`test/semidx/*_test.clj`, namespaces `semidx.*-test`) and
  does not mirror `src` (`semidx.runtime.*`, `semidx.mcp.*`). At 22 files this
  already weakens source↔test locality; a large refactor will make it worse.
- A big refactor is coming. Doing discovery + mirroring first gives the refactor a
  clean, self-maintaining baseline where moving/renaming namespaces does not
  require test-runner bookkeeping.

The workspace canon (`GLOBAL_ARCHITECTURE.md`, `_policy/CONVENTIONS.md`) does not
regulate intra-project Clojure test layout, so this is a project-local decision
recorded in `RULES.md`, not in the workspace canon.

## Constraints

- `clojure -M:test` is the documented entry command and is invoked by
  `scripts/run-mvp-gates.sh`, `scripts/validate-language-onboarding.sh`, and
  `scripts/new-language-adapter.sh`. **This command must keep working unchanged**
  so those scripts need no edits.
- No change to test contents, assertions, or coverage in this plan — only
  discovery mechanism, file locations/namespaces, and one documentation entry.

## Sequencing (order matters)

Discovery must land **before** mirroring: with a manual list, moving and renaming
22 namespaces means editing the list 22 times, and the refactor would force it
again. With discovery, files move freely and the runner finds them.

## Design Decision — auto-discovery while keeping `-m semidx.test-runner`

Two options were considered:

- **(A, chosen) Rewrite `semidx.test-runner` to auto-discover.** Keep the
  `:test` alias entry point (`-m semidx.test-runner`) so `clojure -M:test` and all
  three scripts are untouched. Internally, discover every `*-test` namespace on the
  test paths (via `org.clojure/tools.namespace` `find-namespaces`), require them,
  run `clojure.test`, and exit non-zero on failures. Lowest blast radius; no script
  edits; one small dev dependency.
- **(B) Point `:test` at `cognitect.test-runner`.** More "standard", but changes
  the alias `:main-opts`, adds a runner dependency, and risks changing how the
  three scripts invoke tests. Rejected for higher blast radius.

Either way the runner must fail the build (non-zero exit) on any failure/error,
matching current behavior.

## Implementation Sequence

### Step 1 — Auto-discovery (no file moves yet)

- Add `org.clojure/tools.namespace` to the `:test` alias deps (new dependency).
- Rewrite `src/semidx/test_runner.clj` `-main` to:
  - find all namespaces under the test paths whose name ends in `-test`,
  - `require` them,
  - `run-tests` over the discovered set,
  - `System/exit` non-zero on `(+ fail error) > 0`.
- Remove the two hand-maintained namespace lists.
- **Order-independence guard**: confirm the suite passes when namespaces run in a
  non-fixed order (shuffle the discovered set, or run twice with different seeds).
  If a flake appears, it reveals hidden cross-namespace state/order coupling —
  fix that coupling before proceeding. The current runner does no shared setup, so
  the risk is moderate but must be verified, not assumed.
- Verification: `clojure -M:test` discovers and runs all current tests
  (assertion count ≥ the current 208 tests / 1513 assertions); green in a
  non-fixed order.

### Step 2 — Mirror test namespaces to `src`

- Move each test so its path/namespace mirrors the code it covers:
  - `semidx.runtime.X` → `test/semidx/runtime/X_test.clj`
    (namespace `semidx.runtime.X-test`)
  - `semidx.mcp.X` → `test/semidx/mcp/X_test.clj`
  - genuinely cross-cutting/integration suites (e.g. freshness regression,
    onboarding, evaluation) keep a clear top-level home
    (e.g. `test/semidx/` or `test/semidx/integration/`) — decide one rule and
    apply it consistently.
- No `test_runner.clj` edits are needed (Step 1 made discovery automatic).
- Update the few inter-test `:require`s if any test references another test ns.
- Do this as a **single dedicated commit** immediately before the refactor to keep
  the rename diff isolated and reviewable.
- Verification: `clojure -M:test` green with the same assertion count; every moved
  test namespace matches its file path.

### Step 3 — Record the convention in `RULES.md`

- `Repository Shape`: test namespaces mirror `src` paths
  (`semidx.runtime.X` → `test/semidx/runtime/X_test.clj`); cross-cutting suites
  live under the chosen integration location.
- `Testing And Verification`: tests are auto-discovered — a new `*-test` namespace
  needs no runner registration; keep tests order-independent (no reliance on
  namespace run order or shared mutable state).
- Do **not** copy workspace-wide conventions into `RULES.md`; those stay in
  `_policy/`. Only the project-local test rule is added here.

## Risks

- **Hidden test-order coupling surfaced by discovery.** Mitigation: the Step 1
  order-independence guard; fix coupling before landing.
- **Large rename churn in Step 2.** Mitigation: isolated commit, timed immediately
  before the refactor; discovery means no runner edits.
- **New dependency (`tools.namespace`).** Small, official, dev/test-only scope.
- **Scripts invoking `clojure -M:test`.** Mitigation: Option A keeps the entry
  command identical; verify the three scripts still pass after Step 1.

## Out of Scope

- The large refactor itself.
- Changing test assertions or adding/removing coverage.
- Splitting unit vs integration by directory or tags (possible follow-up).
- Switching to kaocha or another framework (Option B).

## Acceptance Criteria

- Adding a `*-test` namespace requires **zero** test-runner bookkeeping.
- `clojure -M:test` discovers and runs all tests; count ≥ current 208 tests /
  1513 assertions; the three scripts that call it still pass.
- Suite passes in a non-fixed namespace order.
- Every test namespace mirrors its `src` path (or lives in the agreed integration
  location); the rule is documented in `RULES.md`.

## Deliverables

- `src/semidx/test_runner.clj` (auto-discovery rewrite)
- `deps.edn` (`:test` alias gains `org.clojure/tools.namespace`)
- Relocated/renamed files under `test/semidx/**` (Step 2)
- `RULES.md` (`Repository Shape` + `Testing And Verification` additions)
- Companion progress log under `reports/` created at the first implementation step.
