---
title: "Language Lane Registry Deduplication Progress Log"
doc_type: "progress_log"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Progress Log: Language Lane Registry Deduplication

Companion progress log for
[011_language_lane_registry_dedup_plan.md](../plans/011_language_lane_registry_dedup_plan.md).

## Status Summary

- [x] **Step 1 (Registry skeleton)**: Implemented
- [x] **Step 2 (Replace detection call sites)**: Implemented
- [x] **Step 3 (Deduplicate JS/TS test suffixes)**: Implemented
- [x] **Step 4 (Verification)**: Passed

## Details of Changes

### Step 1 — Registry Skeleton
- **Status**: Implemented
- **Summary**: added `semidx.runtime.language-registry` with data-first language
  lane metadata: language order, extension mapping, provider catalog, and
  ECMAScript-family test suffix helper.
- **Changed files**:
  - `src/semidx/runtime/language_registry.clj`
  - `test/semidx/language_registry_test.clj`

### Step 2 — Replace Detection Call Sites
- **Status**: Implemented
- **Summary**: routed `adapters/language-by-path`, `adapters/source-path?`,
  `semantic_id` fallback language inference, `language_activation` supported
  ordering, and `workspace_state` provider catalog through the registry while
  preserving existing public facade vars/functions.
- **Changed files**:
  - `src/semidx/runtime/adapters.clj`
  - `src/semidx/runtime/semantic_id.clj`
  - `src/semidx/runtime/language_activation.clj`
  - `src/semidx/runtime/workspace_state.clj`

### Step 3 — Deduplicate JS/TS Test Suffixes
- **Status**: Implemented
- **Summary**: replaced duplicated JS/TS test suffix checks in both the
  dedicated TypeScript parser and legacy adapter fallback with
  `language-registry/ecmascript-test-path?`. The helper now treats both nested
  `/test/` directories and root `test/...` paths as tests.
- **Changed files**:
  - `src/semidx/runtime/languages/typescript.clj`
  - `src/semidx/runtime/adapters.clj`
  - `test/semidx/language_registry_test.clj`

### Step 4 — Verification
- **Status**: Passed
- **Commands**:
  - `clojure -M -e "(require 'semidx.runtime.language-registry 'semidx.runtime.adapters 'semidx.runtime.languages.typescript 'semidx.runtime.semantic-id 'semidx.runtime.language-activation 'semidx.runtime.workspace-state) :ok"`:
    passed
  - `clojure -Sdeps '{:paths ["src" "test"]}' -M -e "(require 'clojure.test 'semidx.language-registry-test) ..."`:
    passed (`3` tests, `94` assertions)
  - `./scripts/validate-language-onboarding.sh javascript --skip-gates`: passed
    (`validation_checks=ok`)
  - `./scripts/validate-language-onboarding.sh typescript --skip-gates`: passed
    (`validation_checks=ok`)
  - `clojure -M:test`: passed (`208` tests, `1513` assertions, `0` failures,
    `0` errors)

## Notes

- This is a focused tech-debt refactor from JavaScript lane review finding L1.
- Module identity semantics and provider IDs/versions are intentionally left
  unchanged.
- TypeScript adapter dispatch was reshaped into a `parse-typescript` wrapper so
  the onboarding validator recognizes the branch while behavior remains the
  dedicated TypeScript lane with legacy fallback.

## Review Findings

- L1 from
  [007_javascript_language_lane_progress_log.md](../reports/007_javascript_language_lane_progress_log.md)
  is fixed by this implementation.

### Independent review verdict (2026-07-13)

Verdict: disciplined, behavior-preserving refactor. No High/Medium defects.
Dedup goal achieved (no duplicated extension `cond` remains in `src/`); the
registry is a leaf namespace (only `clojure.string`), so no dependency cycles.

Behavior preservation confirmed:
- `language-by-path`/`source-path?` delegate to the registry with identical lane
  order and extensions.
- `ts-strip-ext` rewrite (regex → `some ends-with`) is equivalent, including the
  `.tsx`/`.jsx`/`.mjs` non-collision and `index.js`/`foo.min.js` cases.
- `provider-catalog` values are byte-identical, so `workspace_fingerprint` is
  unchanged and `provider-registry-version` stays `"3"` (no mass rebuild).
- `supported-language-order`, `semantic_id` fallback (`… "unknown"`), and the
  `parse-typescript` try→legacy-fallback dispatch are all preserved.

Verification (reviewer, independent re-run):
- `clojure -M:test`: passed. 208 tests, 1513 assertions, 0 failures, 0 errors.

Findings (both Low, non-blocking):
- **O1 — [Low, intentional]** `ecmascript-test-path?` broadens test-path
  classification by adding a root `test/` prefix rule (original matched only
  nested `/test/`). Files directly under a root `test/` dir without a test suffix
  now get `:kind "test"`. Documented above and explicitly tested; mild scope
  expansion vs. the plan's "Out" clause, but plausibly an improvement. No action
  required unless the broader behavior is undesired.
- **O2 — [Low, cosmetic]** the shared helper normalizes backslashes (`\` → `/`),
  which the original predicate did not — a harmless Windows-path improvement.
