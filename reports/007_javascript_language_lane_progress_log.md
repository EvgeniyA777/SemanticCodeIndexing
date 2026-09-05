---
title: "JavaScript Language Lane Progress Log"
doc_type: "progress_log"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Progress Log: JavaScript Language Lane

Companion progress log for [010_javascript_language_lane_plan.md](../plans/010_javascript_language_lane_plan.md).

Implementation extends the existing TypeScript lane (shared parser core) rather
than adding a new parser. See the plan's "Design Decision" section.

## Status Summary

- [x] **Commit 1 (Detection, activation, provider metadata)**: Implemented
- [x] **Commit 2 (JavaScript lane wired to shared core)**: Implemented
- [x] **Commit 3 (Freshness & language-policy regression coverage)**: Implemented
- [x] **Commit 4 (Cross-language retrieval coverage)**: Implemented
- [ ] **Commit 5 (Optional tree-sitter follow-up)**: Deferred

## Details of Changes

### Commit 1 — Detection, activation, and provider metadata
- **Status**: Implemented
- **Summary**: `.js`/`.jsx`/`.mjs`/`.cjs` now map to `"javascript"` in
  `language-by-path`; `"javascript"` is part of supported language activation;
  `javascript-native` was added to the workspace provider catalog; provider
  registry version was bumped from `"2"` to `"3"`.
- **Changed files**:
  - `src/semidx/runtime/adapters.clj`
  - `src/semidx/runtime/language_activation.clj`
  - `src/semidx/runtime/workspace_state.clj`
  - `src/semidx/runtime/semantic_id.clj`
  - `src/semidx/runtime/retrieval_policy.clj`
  - `test/semidx/runtime_test.clj`
  - `test/semidx/runtime_http_test.clj`
  - `test/semidx/project_context_test.clj`
  - `test/semidx/workspace_state_test.clj`
- **Verification**: covered by `clojure -M:test`.

### Commit 2 — JavaScript lane wired to the shared core
- **Status**: Implemented
- **Summary**: added `semidx.runtime.languages.javascript` as a thin wrapper
  around the TypeScript parser core that returns `:language "javascript"`;
  wired `adapters/parse-file`; extended test-path classification for JavaScript
  test/spec suffixes; registered `semidx.javascript-onboarding-test`.
- **Changed files**:
  - `src/semidx/runtime/languages/javascript.clj`
  - `src/semidx/runtime/languages/typescript.clj`
  - `src/semidx/runtime/adapters.clj`
  - `src/semidx/test_runner.clj`
  - `test/semidx/javascript_onboarding_test.clj`
- **Verification**:
  - `./scripts/validate-language-onboarding.sh javascript --skip-gates`:
    `validation_checks=ok`
  - `clojure -M:test`: passed

### Commit 3 — Freshness and language-policy regression coverage
- **Status**: Implemented
- **Summary**: added JavaScript freshness regression coverage for unchanged
  reuse, content change, add, and delete deltas; added Clojure-only
  language-policy coverage proving JavaScript files are excluded from both full
  and freshness-driven incremental indexes.
- **Changed files**:
  - `test/semidx/javascript_onboarding_test.clj`
- **Verification**: `clojure -M:test`: passed

### Commit 4 — Cross-language retrieval coverage
- **Status**: Implemented
- **Summary**: added JS-to-TS ESM import coverage proving a JavaScript caller is
  linked to a TypeScript callee through extensionless module identity; added
  JavaScript retrieval fixtures and corpus entries.
- **Changed files**:
  - `test/semidx/javascript_onboarding_test.clj`
  - `docs/language-onboarding/javascript.md`
  - `fixtures/retrieval/javascript-happy-path.json`
  - `fixtures/retrieval/javascript-ambiguity.json`
  - `fixtures/retrieval/corpus.json`
- **Verification**:
  - `./scripts/validate-contracts.sh`: `checked_json_files=59`,
    `contracts_validation=ok`
  - `clojure -M:test`: passed

### Commit 5 — Optional tree-sitter follow-up
- **Status**: Deferred
- **Planned**: wire `SEMIDX_TREE_SITTER_JAVASCRIPT_GRAMMAR_PATH` and route the
  tree-sitter engine through the `javascript` grammar; keep regex core as default
  and fallback.
- **Verification (planned)**: `./scripts/validate-language-onboarding.sh javascript`
  and the full suite.

## Notes

- Pre-existing gap identified during planning: `ts-test-path?` matched only
  `.ts`/`.tsx`, so JavaScript test files routed through the shared core would be
  classified as `function` instead of `test`. Fixed as part of Commit 2 in both
  the dedicated TypeScript namespace and the legacy adapter fallback.
- `package.json` manifest hint remains mapped to `"typescript"` for the first
  slice; JS-only detection relies on source files (tracked as a Medium risk in
  the plan).
- Implementation used the low-risk wrapper variant: JavaScript delegates to the
  TypeScript parser and overrides the parsed file `:language` to `"javascript"`.
  No parser core extraction was needed for this slice.

## Verification Summary

- `./scripts/validate-language-onboarding.sh javascript --skip-gates`: passed
  (`validation_checks=ok`; gates intentionally skipped for the structural check)
- `clojure -M:test`: passed (`205` tests, `1419` assertions, `0` failures,
  `0` errors)
- `./scripts/validate-contracts.sh`: passed (`checked_json_files=59`,
  `contracts_validation=ok`)

## Review Findings

Independent review of the (uncommitted) implementation on 2026-07-13 against
`plans/010_javascript_language_lane_plan.md`.

Verdict: implementation is clean and faithful to the plan, with strong,
behavior-focused test coverage. No High/Medium correctness defects found. The
thin-wrapper design (`languages/javascript` delegates to the TS core and
overrides `:language "javascript"`) is verified — `finalize-parsed-file` takes
`javascript` from the parsed map, and the onboarding test asserts every unit is
tagged `javascript`.

Verification (reviewer, independent re-run):
- `clojure -M:test`: passed. 205 tests, 1419 assertions, 0 failures, 0 errors
  (matches the implementer's Verification Summary above).

### L1 — [Low, tech-debt] Extension→language mapping is duplicated
- **Status**: Fixed by
  [011_language_lane_registry_dedup_plan.md](../plans/011_language_lane_registry_dedup_plan.md).
- **Evidence**: the extension→language mapping lives in both
  `adapters/language-by-path` and `src/semidx/runtime/semantic_id.clj`, and the
  test-suffix list is duplicated in `languages/typescript.clj` and the legacy
  `parse-typescript` path in `adapters.clj`. All copies were updated consistently
  for JavaScript this time, but the duplication invites future drift.
- **Decision**: do not fix in the JavaScript lane commit. Consolidating language
  metadata is a cross-lane refactor and should land as a focused follow-up.
- **Fix summary**: plan 011 added `semidx.runtime.language-registry` and routed
  adapter detection, semantic-ID fallback inference, supported language order,
  provider catalog, and JS/TS test suffix classification through it. Verification
  passed in `reports/008_language_lane_registry_dedup_progress_log.md`.

### L2 — [Low, edge case] `.ts`/`.js` module-identity collision
- **Status**: Accepted known limitation.
- **Evidence**: `ts-strip-ext`/`ts-module-name` strip both `.ts` and `.js`, so
  `foo.ts` and `foo.js` in the same directory collapse to module `foo` and their
  units merge. This is the flip side of the extensionless ESM resolution the plan
  frames as a feature; it pre-exists for `.ts`/`.tsx`.
- **Decision**: no fix required for this slice. Keep it documented as a
  limitation for mixed TS/JS directories.

### L3 — [Low, cosmetic] JS dispatch lacks the TS inner fallback; tree-sitter diag text
- **Status**: Deferred.
- **Evidence**: the `"javascript"` case in `adapters/parse-file` calls
  `parse-javascript` without the inner `try`→regex fallback the `"typescript"`
  case has (the outer `parse-file` `catch` still protects). Under
  `tree_sitter_enabled`, a JS file routed through the TS core emits diagnostics
  text mentioning "TypeScript". Both are harmless; tree-sitter is off by default
  and the JS grammar stage is deferred (Commit 5).
- **Decision**: leave as optional polish for a tree-sitter follow-up; current
  regex lane behavior is covered by tests.

Note: the JavaScript lane implementation is currently uncommitted (working-tree
changes at review time); this record reflects the reviewed working tree.
