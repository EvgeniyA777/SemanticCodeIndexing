---
title: "Language Lane Registry Deduplication Plan"
doc_type: "implementation_plan"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Implementation Plan: Language Lane Registry Deduplication

Execution status: implemented on 2026-07-13. See
[008_language_lane_registry_dedup_progress_log.md](../reports/008_language_lane_registry_dedup_progress_log.md)
for the implementation record and verification results.

## Goal

Consolidate duplicated language-lane metadata into one small registry so future
language additions do not require updating extension detection, semantic-ID
fallback inference, and test-file suffix classification in multiple places.

This plan follows review finding L1 from
[007_javascript_language_lane_progress_log.md](../reports/007_javascript_language_lane_progress_log.md).

## Scope

In:

- Create a shared runtime language registry for:
  - language key
  - source file extensions
  - provider metadata
  - test/spec suffixes for ECMAScript-family lanes
- Route `adapters/language-by-path` through that registry.
- Route `semantic_id` fallback language inference through that registry or a
  shared helper.
- Route TypeScript/JavaScript test-path classification through one helper used
  by both `semidx.runtime.languages.typescript` and the legacy adapter fallback.
- Keep existing public language ordering stable.
- Add regression tests that prove supported extensions and JS/TS test suffixes
  stay aligned.

Out:

- Changing module identity semantics for extensionless ESM resolution.
- Refactoring parser internals beyond replacing duplicated suffix predicates.
- Adding new language lanes.
- Changing `package.json` manifest heuristics.

## Proposed Design

Add a small namespace, for example `semidx.runtime.language-registry`, with
plain data and helper functions:

- `language-by-extension`
- `language-by-path`
- `source-path?`
- `supported-language-order`
- `test-path?` for language families that need it
- optional provider metadata lookup if it meaningfully reduces duplication

Keep the registry data intentionally simple. The goal is drift prevention, not a
large plugin abstraction.

## Implementation Sequence

### Step 1 — Registry Skeleton

- Add the shared registry namespace.
- Move extension lists into registry data.
- Add focused tests for `.clj`, `.java`, `.ex`, `.py`, `.ts`, `.tsx`, `.js`,
  `.jsx`, `.mjs`, `.cjs`, `.lua`, `.html`, `.htm`, and `.css`.

### Step 2 — Replace Detection Call Sites

- Update `adapters/language-by-path` to delegate to the registry.
- Update `semantic_id` fallback language inference to use the same helper.
- Keep `language_activation/supported-language-order` stable, either by reading
  the registry or by asserting it matches the registry order.

### Step 3 — Deduplicate JS/TS Test Suffixes

- Move ECMAScript test-path suffix classification into the registry or a small
  shared helper.
- Use the helper from both `semidx.runtime.languages.typescript` and the legacy
  TypeScript parser fallback in `adapters.clj`.
- Add tests covering `.test.js`, `.spec.jsx`, `_test.mjs`, `.test.ts`, and
  `_test.tsx`.

### Step 4 — Verification

- Run `clojure -M:test`.
- Run `./scripts/validate-language-onboarding.sh javascript --skip-gates`.
- Run `./scripts/validate-language-onboarding.sh typescript --skip-gates`.

## Risks

- Registry abstraction can become too broad. Keep it data-first and limited to
  current duplication.
- Provider-catalog migration may trigger freshness rebuilds if provider metadata
  shape changes. Avoid changing provider IDs or versions unless required.

## Acceptance Criteria

- Adding a new source extension requires updating one registry location.
- Semantic-ID fallback inference and adapter path detection agree for every
  supported extension.
- JS/TS test-file classification has a single suffix list.
- Full test suite remains green.
