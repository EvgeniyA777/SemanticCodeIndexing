---
title: "JavaScript Language Lane Plan"
doc_type: "implementation_plan"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Implementation Plan: JavaScript Language Lane (extend the TypeScript lane)

Execution status: implemented on 2026-07-13. See
[007_javascript_language_lane_progress_log.md](../reports/007_javascript_language_lane_progress_log.md)
for the implementation record and verification results.

## Goal

Add JavaScript as a first-class language lane in `semidx` so the indexer can
detect, activate, parse, persist, and retrieve JavaScript context alongside the
existing Clojure, Java, Elixir, Python, TypeScript, Lua, HTML, and CSS lanes.

This is an **extension** task, not a new parser: the existing TypeScript parser
(`semidx.runtime.languages.typescript`) already recognizes JavaScript syntax and
already strips `.js`/`.jsx`/`.mjs`/`.cjs` extensions in module naming
(`ts-strip-ext`). The plan reuses that parser core and adds a thin JavaScript
lane on top of it.

## Scope

In:

- Path classification for `.js`, `.jsx`, `.mjs`, and `.cjs`.
- Language detection, activation, and language-policy support for `"javascript"`.
- Workspace provider-catalog entry and freshness fingerprint compatibility.
- A thin `languages/javascript` lane that delegates to the shared TypeScript
  parser core and tags units as `"javascript"`.
- JavaScript test-file classification (`.test.js`, `.spec.js`, `_test.js`, and
  the `.jsx` variants).
- Onboarding, freshness, language-policy, and retrieval regression tests.

Out for the first slice:

- A separate/duplicated JavaScript parser implementation.
- Dedicated tree-sitter JavaScript grammar wiring (the regex engine is the
  default; tree-sitter is an optional follow-up stage).
- JSX/TSX-aware semantic extraction beyond what the shared regex core provides.
- Framework component semantics (React/Vue/Svelte), bundler graph resolution,
  and Node.js `require()`/CommonJS dependency graphing beyond static `import`.

## Design Decision — reuse the TypeScript core, do not duplicate

The TypeScript parser already handles JavaScript constructs (functions, arrow
functions, classes, methods, object methods, ESM `import`/`export`). Two viable
shapes were considered:

- **Alias JS into the `"typescript"` language.** Cheapest, but reports JS files
  as `typescript` in detection, provider catalog, and unit `:language`, which is
  semantically wrong and pollutes language activation/fingerprints. Rejected.
- **Add a distinct `"javascript"` language that reuses the TS core.** Correct
  detection and reporting with near-zero parser duplication. **Chosen.**

Constraint that forces a thin wrapper (not a raw dispatch to
`ts-language/parse-file`): `semantic-ir/finalize-parsed-file` resolves the unit
language as `(or (:language parsed*) language "unknown")` — the parser's own
`:language` wins over the dispatch-level language. Since the TS parser returns
`:language "typescript"`, dispatching `.js` straight to it would tag JavaScript
units as `typescript`. The JavaScript lane must therefore return
`:language "javascript"`.

Preferred refactor: extract the shared body of
`semidx.runtime.languages.typescript/parse-file` into an internal core that
accepts a language label, then have both the TypeScript and JavaScript lanes call
it. If a low-risk minimal change is preferred first, `languages/javascript`
may instead call `ts-language/parse-file` and `assoc :language "javascript"` on
the result, deferring the shared-core extraction. Either way, no `defn-` in the
TypeScript namespace becomes public just to serve JavaScript.

## Boundaries

### `semidx.runtime.adapters`

Responsibility: path-to-language classification and parser dispatch.

Changes:

- `language-by-path`: map `.js`, `.jsx`, `.mjs`, `.cjs` to `"javascript"`.
- `parse-file`: add a `"javascript"` dispatch case calling
  `js-language/parse-file` with the same try/fallback shape used for
  `"typescript"`.
- `add-tree-sitter-diag`: add `"javascript"` to the tree-sitter-aware language
  set `#{"clojure" "elixir" "java" "typescript"}` (only relevant once the
  optional tree-sitter stage lands; harmless before then).
- Grammar env map (`parser-grammar-path` `case lang`): add
  `:javascript "SEMIDX_TREE_SITTER_JAVASCRIPT_GRAMMAR_PATH"` for the optional
  tree-sitter stage.

### `semidx.runtime.languages.javascript` (new)

Responsibility: the JavaScript lane. Thin delegation to the shared TypeScript
parser core with a `"javascript"` language label. No independent regex/CST
implementation in the first slice.

### `semidx.runtime.languages.typescript`

Responsibility: shared parser core for the ECMAScript family.

Changes:

- Extend `ts-test-path?` (currently matches only `.ts`/`.tsx`) to also classify
  `.test.js`, `.spec.js`, `_test.js` and their `.jsx`/`.mjs`/`.cjs` variants, so
  JavaScript test files get `:kind "test"` instead of `"function"`. This is a
  real existing gap for JS files routed through the core.
- If the shared-core refactor is taken, thread the language label through so the
  returned `:language`, module naming, and diagnostics reflect the caller.

Note: `ts-strip-ext` and `ts-module-name` already handle `.js`/`.jsx`/`.mjs`/
`.cjs`; no change needed there.

### `semidx.runtime.language-activation`

Responsibility: supported language list, discovery, policy normalization,
activation state, and fingerprinting.

Changes:

- Add `"javascript"` to `supported-language-order`.
- Confirm `discover-languages`, `resolve-activation`, and `active-source-paths`
  handle JavaScript through the existing `adapters/language-by-path` contract
  (no per-language branches expected).
- Manifest hints: `package.json` currently maps to `"typescript"`. Leave manifest
  hints unchanged in the first slice (source-file detection is primary); the
  ambiguity is captured under Risks.

### `semidx.runtime.workspace-state`

Responsibility: provider catalog and freshness fingerprint compatibility.

Changes:

- Add a `"javascript"` provider-catalog entry (`javascript-native`,
  `:classification "source"`).
- Bump `provider-registry-version` (currently `"2"`) in the same commit as the
  catalog change, so existing snapshots fall back to an honest full rebuild.

## Contracts

### Parsed File Shape

Client: `adapters/parse-file` and `semantic-ir/finalize-parsed-file`.

- The JavaScript lane returns a map compatible with existing language parsers:
  `:language "javascript"`, `:module`, `:imports`, `:units`, `:diagnostics`,
  `:parser_mode`.
- Units keep the stable shape (`:kind`, `:symbol`, `:path`, `:start_line`,
  `:end_line`, `:signature`, `:calls`, `:call_tokens`).

### Language Policy Contract

Client: language activation, freshness, index lifecycle, public create-index.

- `:allow_languages` / `:disable_languages` apply equally to full builds, storage
  reuse checks, and incremental freshness updates (already fixed by the H2
  freshness work in `reports/005`).
- JavaScript files excluded by policy must not appear in `:files`, `:units`, or
  workspace freshness deltas feeding `update-index`.

### Cross-Language Module Resolution

Client: retrieval, ranking, impact analysis.

- ESM `import` specifiers resolve extensionlessly via the existing
  `ts-resolve-import-path` + `ts-module-name`, so a `.js` file importing a `.ts`
  module (and vice versa) resolves to the same module identity. No JS-specific
  branch is required.

## Dependency Direction

- Index orchestration depends on the parser contract, not on JavaScript internals.
- The JavaScript lane depends on the shared TypeScript core, never the reverse.
- Workspace freshness depends on provider metadata and language policy, not on
  parser implementation details.
- Retrieval consumes normalized units and call tokens; no JavaScript-specific
  retrieval branch is expected in the first slice.

## Risks

### Medium: `package.json` detection ambiguity between JS and TS

Why it matters: `package.json` currently hints `"typescript"`. A pure-JS repo
with no `.ts` files still activates correctly via source-file detection, but the
manifest hint is misleading.

Mitigation: rely on source-file detection for the first slice; keep the manifest
hint change out of scope. Consider a later heuristic (`tsconfig.json` present →
typescript; otherwise package.json → javascript).

### Medium: JS test files were misclassified before this change

Why it matters: `ts-test-path?` matched only `.ts`/`.tsx`, so `.test.js` files
routed through the shared core would be typed as `function`, not `test`.

Mitigation: extend `ts-test-path?` and add explicit JS test-classification
assertions in the onboarding test.

### Low: JSX bodies are treated as opaque text by the regex core

Why it matters: JSX return bodies are parsed heuristically (call-token
extraction), not structurally.

Mitigation: acceptable for the MVP; defer structural JSX handling to the optional
tree-sitter stage (which needs the `tsx` grammar, not plain `javascript`).

### Low: shared-core refactor could regress TypeScript

Why it matters: extracting a shared core touches the live TS lane.

Mitigation: the full existing TypeScript onboarding suite must stay green; if the
minimal wrapper variant is chosen first, the TS namespace is not restructured at
all.

## Implementation Sequence

Small, independently testable commits.

### Commit 1 — Detection, activation, and provider metadata

- `language-by-path`: `.js`/`.jsx`/`.mjs`/`.cjs` → `"javascript"`.
- Add `"javascript"` to `supported-language-order`.
- Add the `javascript-native` provider-catalog entry; bump
  `provider-registry-version`.
- Tests: detection (`language-by-path`), activation (`discover-languages` /
  `active-source-paths`), and provider metadata presence.
- Verification: `clojure -M:test`.

### Commit 2 — JavaScript lane wired to the shared core

- Add `src/semidx/runtime/languages/javascript.clj` (thin delegation returning
  `:language "javascript"`).
- Add the `"javascript"` dispatch case in `adapters/parse-file`.
- Extend `ts-test-path?` for JavaScript test/spec extensions.
- Register the new onboarding test namespace in `src/semidx/test_runner.clj`.
- Tests: `test/semidx/javascript_onboarding_test.clj` — functions, arrow
  functions, classes/methods, object methods, ESM imports, default export, and
  JS test-file classification.
- Verification: `./scripts/validate-language-onboarding.sh javascript`
  (add `--skip-gates` for a fast structural check) and `clojure -M:test`.

### Commit 3 — Freshness and language-policy regression coverage

- `create-index` / `update-index` tests for JavaScript add, change, delete, and
  unchanged-reuse.
- Language-policy tests ensuring excluded JavaScript files never enter full or
  incremental indexes (mirror `freshness_regression_test`).
- Verification: `clojure -M:test`.

### Commit 4 — Cross-language retrieval coverage

- Add a fixture with interlinked `.js`/`.ts` modules (or a small JS-only module
  set) and assert `resolve_context` / `impact_analysis` connect a JavaScript
  caller to its callee across the ESM import edge.
- Verification: targeted retrieval tests plus `clojure -M:test`. If a contract
  surface changed (it should not — all additions are additive), also run
  `./scripts/validate-contracts.sh`.

### Commit 5 — Optional tree-sitter follow-up (deferred)

- Wire a JavaScript tree-sitter grammar path
  (`SEMIDX_TREE_SITTER_JAVASCRIPT_GRAMMAR_PATH`) and route the tree-sitter engine
  through the `javascript` grammar (not the `typescript` grammar).
- Keep the regex core as the default and the fallback.
- Verification: `./scripts/validate-language-onboarding.sh javascript` and the
  full suite.

## Expected Deliverables

- `src/semidx/runtime/languages/javascript.clj`
- Updates to:
  - `src/semidx/runtime/adapters.clj`
  - `src/semidx/runtime/languages/typescript.clj` (test-path detection; optional
    shared-core extraction)
  - `src/semidx/runtime/language_activation.clj`
  - `src/semidx/runtime/workspace_state.clj`
  - `src/semidx/test_runner.clj`
- Tests:
  - `test/semidx/javascript_onboarding_test.clj`
  - targeted freshness/language-policy regression tests
  - a cross-language retrieval fixture and test
- Companion progress log under `reports/` created before/at the first commit,
  per the project progress-log rules.
