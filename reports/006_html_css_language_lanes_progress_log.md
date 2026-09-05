---
title: "HTML and CSS Language Lanes Progress Log"
doc_type: "progress_log"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Progress Log: HTML and CSS Language Lanes

This implementation log is closed. Preliminary unresolved review items were
moved to `reports/019_legacy_workspace_and_html_css_followups.md` for explicit
triage outside the completed plan.

Companion progress log for [009_html_css_language_lanes_plan.md](../plans/009_html_css_language_lanes_plan.md).

## Status Summary

- [x] Stage 0 - Lifecycle prerequisites verified from prior fixes.
- [x] Stage 1 - Detection and provider metadata.
- [x] Stage 2 - HTML parser MVP.
- [x] Stage 3 - CSS parser MVP.
- [x] Stage 4 - Cross-language retrieval coverage.
- [x] Stage 5 - Freshness and public-surface regression coverage.
- [ ] Stage 6 - Optional Tree-sitter follow-up.

## Execution Notes

### Stage 0 - Lifecycle prerequisites

- **Status**: Completed before this implementation pass.
- **Evidence**: Commit `ba3bd77` fixed pinned snapshot reuse, language-policy freshness filtering, and activation metadata preservation. The follow-up review found no remaining high-severity issues for H1-H3.
- **Verification**: Prior review ran `clojure -M:test` with 197 tests and 1344 assertions passing.

### Stage 1 - Detection and provider metadata

- **Status**: Completed.
- **Changes**:
  - Added `.html`, `.htm`, and `.css` language detection in `src/semidx/runtime/adapters.clj`.
  - Added `"html"` and `"css"` to `supported-language-order` in `src/semidx/runtime/language_activation.clj`.
  - Added `html-native` and `css-native` provider catalog entries in `src/semidx/runtime/workspace_state.clj`.
  - Bumped `provider-registry-version` from `"1"` to `"2"` because provider catalog compatibility changed.
  - Added HTML/CSS inference in `src/semidx/runtime/semantic_id.clj`.
- **Verification**: Covered by `semidx.html-onboarding-test`, `semidx.css-onboarding-test`, and full `clojure -M:test`.

### Stage 2 - HTML parser MVP

- **Status**: Completed.
- **Changes**:
  - Added `src/semidx/runtime/languages/html.clj`.
  - Parser extracts document units, id/class-bearing elements, links, scripts, forms, buttons, images, landmark tags, static references, and selector calls.
  - HTML element symbols are neutral line-based symbols so selector calls resolve to CSS selector units instead of same-file HTML elements.
  - Added adapter-local `parse-html` wrapper for the existing language-onboarding validator.
- **Verification**: `semidx.html-onboarding-test` checks detection, provider metadata, element/document units, and selector caller edges into CSS.

### Stage 3 - CSS parser MVP

- **Status**: Completed.
- **Changes**:
  - Added `src/semidx/runtime/languages/css.clj`.
  - Parser extracts stylesheet units, class/id/tag selector units, `@media`, `@supports`, `@keyframes`, CSS custom properties, `@import`, and `url(...)` references.
  - Added adapter-local `parse-css` wrapper for the existing language-onboarding validator.
- **Verification**: `semidx.css-onboarding-test` checks detection, provider metadata, selectors, custom properties, at-rules, imports, and URL dependencies.

### Stage 4 - Cross-language retrieval coverage

- **Status**: Completed.
- **Changes**:
  - HTML selector usages are emitted as `:calls` tokens such as `.cta-button` and `#hero`.
  - CSS selector definitions use matching `:symbol` values.
  - Added retrieval fixtures:
    - `fixtures/retrieval/html-happy-path.json`
    - `fixtures/retrieval/html-ambiguity.json`
    - `fixtures/retrieval/css-happy-path.json`
    - `fixtures/retrieval/css-ambiguity.json`
  - Added those fixtures to `fixtures/retrieval/corpus.json`.
- **Verification**: `semidx.html-onboarding-test` validates `query-callers` from CSS selector units back to HTML units.

### Stage 5 - Freshness and public-surface regression coverage

- **Status**: Completed.
- **Changes**:
  - Added language-policy regression coverage for HTML/CSS excluded by a Clojure-only policy.
  - Added CSS freshness coverage for a changed stylesheet flowing through `incremental_update`.
  - Updated no-supported-language tests to include HTML/CSS in supported language guidance.
  - Updated workspace-state compatibility test for the provider registry version bump.
  - Registered HTML/CSS onboarding tests in `src/semidx/test_runner.clj`.
  - Added onboarding docs:
    - `docs/language-onboarding/html.md`
    - `docs/language-onboarding/css.md`
- **Verification**: Full test suite and validators listed below.

### Stage 6 - Optional Tree-sitter follow-up

- **Status**: Deferred.
- **Reason**: The first implementation slice intentionally uses native regex parsers. Tree-sitter HTML/CSS integration remains a future enhancement after the MVP parser behavior has enough evidence.

## Verification Log

- `clojure -M -e "(require '[semidx.runtime.adapters :as adapters] '[semidx.runtime.languages.html] '[semidx.runtime.languages.css] :reload) ..."`: Passed.
- `./scripts/validate-language-onboarding.sh html --skip-gates`: Passed (`validation_checks=ok`, gates skipped).
- `./scripts/validate-language-onboarding.sh css --skip-gates`: Passed (`validation_checks=ok`, gates skipped).
- `./scripts/validate-contracts.sh`: Passed (`checked_json_files=57`, `contracts_validation=ok`).
- `clojure -M:test`: Passed. Ran 201 tests containing 1382 assertions, 0 failures, 0 errors.

## Review Findings (in progress — 2026-07-13)

Reviewer pass over the committed implementation (commit `556cfd4`) against
`plans/009_html_css_language_lanes_plan.md`. **This review is incomplete** — it
was interrupted before finishing `narrow-targets` analysis and before an
independent full-suite re-run. Findings below are preliminary and not all
verified; severities may change after verification. The implementer's own
verification log reports `clojure -M:test` green (201 tests, 1382 assertions).

Reviewed so far: `languages/html.clj`, `languages/css.clj` (full); the
`adapters.clj` / `language_activation.clj` / `semantic_id.clj` diffs; and the
HTML→CSS linking path (`build-call-token-index` + `symbol-call-tokens`).

### RF1 — [Preliminary High — NEEDS VERIFICATION] Generic CSS selector/tail tokens may pollute the cross-language call graph
- **Status**: Open, unverified (analysis interrupted at `narrow-targets`).
- **Evidence**: CSS tag selectors produce very generic `:symbol` values (`a`,
  `button`, `nav`, `main`, `body`, `form`) in `src/semidx/runtime/languages/css.clj:50-57`.
  `symbol-call-tokens` (`src/semidx/runtime/index.clj`) additionally indexes each
  symbol under its bare tail token (e.g. `.button` → also `button`). So a caller
  in another language whose call token resolves to `button`/`a`/`form` could gain
  a spurious edge to a CSS selector unit.
- **Why it matters**: Cross-language false edges would degrade impact analysis and
  ranking on mixed web+backend repos.
- **Open question**: whether `narrow-targets` filters by language/module/import
  and thereby suppresses these edges. Must be confirmed before assigning final
  severity. If `narrow-targets` filters cross-file/cross-language candidates, this
  may drop to Low.
- **Suggested direction (pending verification)**: scope selector tokens so tag
  selectors and tail-token expansion do not leak generic identifiers into the
  shared token index, or confirm `narrow-targets` already isolates them.

### RF2 — [Preliminary Medium-Low] HTML `href`/`src` file references are emitted as `:calls` but never resolve
- **Status**: Open, unverified.
- **Evidence**: `languages/html.clj:87,99` put normalized `href`/`src`/`action`
  refs (e.g. `"styles.css"`) into a unit's `:calls`, but the CSS/JS module symbol
  is `module/stylesheet` (or `module/document`), not the file path — so these
  tokens resolve to nothing (dead tokens). File-level HTML→CSS linking claimed by
  the plan's asset-reference contract is not actually wired.
- **Why it matters**: The plan lists static asset references as a linking signal;
  in practice only `.class`/`#id` selector linking works.
- **Suggested direction**: either map file refs to the target module symbol, or
  keep refs only in `:imports`/module graph and drop them from `:calls`.

### RF3 — [Preliminary Low] Unstable HTML element symbols; dead parameter
- **Status**: Open, unverified.
- **Evidence**: `languages/html.clj:70-71` builds element symbols as
  `module/tag-line-ordinal`, so every line shift changes symbols (index churn).
  `element-symbol` also takes `attrs` but never uses it.
- **Why it matters**: Line-based symbols are noisy across edits; low impact.

### Review verification status
- Independent full-suite re-run: **not run** (interrupted). Implementer reports
  201 tests / 1382 assertions green in the Verification Log above.
- `narrow-targets` cross-language filtering: **not yet analyzed** — gates RF1.

## Known Follow-ups

- Complete the interrupted review: analyze `narrow-targets`, then verify or
  downgrade RF1, and re-run `clojure -M:test` independently.
- Tree-sitter HTML/CSS support remains deferred.
- The generic `validate-language-onboarding.sh` gates were run with `--skip-gates` because full gates include benchmarks and MVP gates beyond the narrow implementation slice; full `clojure -M:test` and contract validation were run separately.
