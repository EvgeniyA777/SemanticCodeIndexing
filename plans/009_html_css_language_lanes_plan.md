---
title: "HTML and CSS Language Lanes Plan"
doc_type: "implementation_plan"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Implementation Plan: HTML and CSS Language Lanes

## Goal

Add HTML and CSS as first-class language lanes in `semidx` so the indexer can
detect, activate, parse, persist, and retrieve HTML/CSS context alongside the
existing Clojure, Java, Elixir, Python, TypeScript, and Lua lanes.

## Scope

In:

- Language detection and activation for HTML and CSS.
- Workspace provider catalog entries and freshness compatibility.
- Native regex parsers for useful HTML and CSS semantic units.
- Cross-language selector/reference conventions between HTML and CSS.
- Runtime, MCP, and storage behavior covered through existing index surfaces.
- Onboarding and regression tests.

Out for the first implementation slice:

- Tree-sitter HTML/CSS parser integration.
- Full browser DOM/CSS cascade analysis.
- JavaScript framework component semantics.
- Asset bundler graph resolution beyond direct static references.

## Prerequisites

Fix the currently open workspace freshness review findings before adding the new
lanes:

1. `:pinned_snapshot_id` must return the exact stored snapshot or throw
   `:invalid_request` when missing; it must not enter rebuild/update freshness
   paths.
2. Incremental freshness must respect `:language_policy` and must not index
   disabled or non-allowed languages.
3. Incremental update must preserve activation metadata such as
   `:detected_languages`, `:active_languages`, and `:language_fingerprint`.

These fixes are required because adding HTML/CSS expands the blast radius of the
current lifecycle bugs.

## Boundaries

### `semidx.runtime.language-activation`

Responsibility: supported language list, source discovery, language policy
normalization, activation state, and language fingerprinting.

Changes:

- Add `"html"` and `"css"` to `supported-language-order`.
- Confirm `discover-languages`, `resolve-activation`, and `active-source-paths`
  handle the new languages through the existing `adapters/language-by-path`
  contract.
- Add manifest hints only when useful. Source file detection is sufficient for
  the first slice; common hints can be added later for `index.html` or web
  manifests if they improve empty/early project guidance.

### `semidx.runtime.adapters`

Responsibility: path-to-language classification and parser dispatch.

Changes:

- Map `.html` and `.htm` to `"html"`.
- Map `.css` to `"css"`.
- Dispatch `"html"` to `semidx.runtime.languages.html/parse-file`.
- Dispatch `"css"` to `semidx.runtime.languages.css/parse-file`.

### `semidx.runtime.languages.html`

Responsibility: HTML parsing only.

Extract:

- A file/document unit.
- Elements with `id`.
- Elements with meaningful `class` values.
- Links, scripts, stylesheets, images, forms, buttons, and inputs.
- Static references from `href`, `src`, `action`, and related attributes.
- Selector reference tokens for CSS lookup, such as `.button` and `#main`.

### `semidx.runtime.languages.css`

Responsibility: CSS parsing only.

Extract:

- Selector units for `.class`, `#id`, tag selectors, and simple compound
  selectors.
- At-rule units for `@media`, `@supports`, and `@keyframes`.
- CSS custom properties such as `--spacing-sm`.
- Static dependencies from `@import` and `url(...)`.
- Symbols that align with HTML selector reference tokens.

### `semidx.runtime.workspace-state`

Responsibility: provider catalog and freshness fingerprint compatibility.

Changes:

- Add `html-native` and `css-native` provider catalog entries.
- Bump `provider-registry-version` when the catalog changes.
- Ensure provider metadata participates in `workspace_fingerprint` as it does
  for existing languages.

## Contracts

### Parsed File Shape

Client: `semidx.runtime.adapters/parse-file` and
`semidx.runtime.semantic-ir/finalize-parsed-file`.

Shape:

- Parser returns a map compatible with existing language parsers.
- Units include stable `:kind`, `:symbol`, `:path`, `:start_line`, `:end_line`,
  `:signature`, `:calls`, and `:call_tokens` where applicable.
- Diagnostics follow existing parser diagnostic conventions.

### Selector Symbol Convention

Client: retrieval, ranking, impact analysis, and cross-language context
selection.

Rules:

- HTML class usage emits `.class-name` call/reference tokens.
- CSS class selector units use `.class-name` symbols.
- HTML id usage emits `#id-name` call/reference tokens.
- CSS id selector units use `#id-name` symbols.
- CSS custom property units use `--token-name` symbols.
- Asset references should keep normalized relative paths where possible.

### Language Policy Contract

Client: language activation, freshness, index lifecycle, and public create-index
surfaces.

Rules:

- `:allow_languages` and `:disable_languages` must apply equally to full builds,
  storage reuse checks, and incremental freshness updates.
- HTML/CSS files excluded by policy must not appear in `:files`, `:units`, or
  workspace freshness deltas that feed `update-index`.

## Dependency Direction

- Index orchestration depends on the existing parser contract, not on HTML/CSS
  parser internals.
- HTML/CSS parser details plug into `adapters/parse-file`.
- Workspace freshness depends on provider metadata and language policy, not on
  parser implementation details.
- Retrieval consumes normalized semantic units and call tokens; it should not
  need HTML/CSS-specific branches in the first slice.

## Risks

### High: Selector conventions drift between HTML and CSS

Why it matters: HTML and CSS relate through selectors rather than imports. If
HTML emits `button` but CSS indexes `.button`, retrieval will miss the
relationship.

Mitigation: define and test `.class`, `#id`, and `--custom-property` conventions
before broad parser work.

### High: Lifecycle bugs leak disabled web files into incremental indexes

Why it matters: web repos often contain many HTML/CSS files, so a policy leak
would be more visible after adding these lanes.

Mitigation: complete the prerequisite lifecycle fixes and add policy regression
tests with HTML/CSS fixtures.

### Medium: Regex parser scope grows into a browser parser

Why it matters: full HTML parsing, CSS cascade, and framework semantics are large
domains.

Mitigation: keep the first parser native and shallow: semantic units, selectors,
and static references only. Defer Tree-sitter and framework-aware behavior.

## Implementation Sequence

### Stage 0 — Fix lifecycle prerequisites

- Fix pinned snapshot exact-reuse behavior.
- Fix language-policy filtering in workspace freshness and incremental update.
- Preserve activation metadata through incremental update.
- Add regression tests for all three findings.
- Verification: `clojure -M:test`.

### Stage 1 — Detection and provider metadata

- Update `language-by-path` for `.html`, `.htm`, and `.css`.
- Add `"html"` and `"css"` to supported language order.
- Add workspace provider catalog entries and bump provider registry version.
- Add tests proving detection, activation, and provider metadata.
- Verification: targeted onboarding tests plus `clojure -M:test`.

### Stage 2 — HTML parser MVP

- Add `src/semidx/runtime/languages/html.clj`.
- Parse document units, ids, classes, forms, buttons, links, scripts,
  stylesheets, and static asset references.
- Wire parser dispatch through `adapters/parse-file`.
- Add `test/semidx/html_onboarding_test.clj`.
- Verification: targeted HTML onboarding test.

### Stage 3 — CSS parser MVP

- Add `src/semidx/runtime/languages/css.clj`.
- Parse selector units, at-rules, keyframes, custom properties, imports, and
  `url(...)` references.
- Wire parser dispatch through `adapters/parse-file`.
- Add `test/semidx/css_onboarding_test.clj`.
- Verification: targeted CSS onboarding test.

### Stage 4 — Cross-language retrieval coverage

- Add a fixture with `index.html` and `styles.css`.
- Assert queries for a styled element retrieve both the HTML usage and CSS
  selector definition.
- Assert `impact_analysis` or related retrieval surfaces connect HTML class/id
  references to CSS selector units.
- Verification: targeted retrieval tests plus `clojure -M:test`.

### Stage 5 — Freshness and public-surface regression coverage

- Add create-index/update-index tests for HTML/CSS add, change, delete, and
  unchanged reuse.
- Add language-policy tests ensuring excluded HTML/CSS files do not enter full
  or incremental indexes.
- Check MCP `create_index`, `repo_map`, `resolve_context`, and
  `fetch_context_detail` behavior with HTML/CSS projects.
- Verification: `clojure -M:test` and, if contract surfaces changed,
  `./scripts/validate-contracts.sh`.

### Stage 6 — Optional Tree-sitter follow-up

- Add HTML/CSS grammar setup only after regex MVP is stable.
- Keep native regex parser as fallback.
- Add parser opts and fallback diagnostics mirroring existing Tree-sitter lanes.
- Verification: language onboarding scripts and full test suite.

## Expected Deliverables

- `src/semidx/runtime/languages/html.clj`
- `src/semidx/runtime/languages/css.clj`
- Updates to:
  - `src/semidx/runtime/adapters.clj`
  - `src/semidx/runtime/language_activation.clj`
  - `src/semidx/runtime/workspace_state.clj`
  - `src/semidx/test_runner.clj`
- Tests:
  - `test/semidx/html_onboarding_test.clj`
  - `test/semidx/css_onboarding_test.clj`
  - targeted lifecycle/policy regression tests
- Optional docs update after implementation if public runtime API examples are
  expanded for HTML/CSS.
