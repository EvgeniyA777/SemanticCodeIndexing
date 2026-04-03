# resolve_context Implementation Plan

## Goal

Improve `resolve_context` so shorthand code-understanding queries are less likely to drift into vendored or irrelevant paths and can benefit from inferred structural anchors without pretending they are exact targets.

## Scope

This plan covers:

- MCP shorthand query normalization
- retrieval candidate hygiene
- inferred anchor support
- `suspected_symbols` scoring
- confidence and next-step behavior for low-evidence shorthand queries
- regression coverage in runtime, MCP, and retrieval benchmarks

This plan does not cover:

- ML reranking
- schema-breaking API changes
- major staged retrieval redesign

## Assumptions

- The canonical public flow remains `resolve_context -> expand_context -> fetch_context_detail`.
- MCP query schema stays backward-compatible.
- Exact structured queries already work well enough and should not be destabilized.
- The first pass should optimize for correctness and explainability over aggressive recall.

## Boundaries

### 1. Query Anchor Inference

Responsibility:

- derive candidate `symbols`, `modules`, `paths`, and hint-level preferences from shorthand intent text

Knows about:

- shorthand intent text
- lightweight naming heuristics

Does not know about:

- retrieval weights
- graph expansion
- MCP transport details

Primary files:

- `src/semidx/runtime/query_anchors.clj` (new)
- `src/semidx/mcp/core.clj`

### 2. Candidate Hygiene And Ranking

Responsibility:

- decide which units are eligible for lexical seeding
- prioritize source-like paths over vendored and fixture paths
- incorporate inferred hints into ranking without upgrading them to exact evidence

Knows about:

- units
- path classes
- parser mode
- retrieval policy

Does not know about:

- HTTP/stdin MCP transport

Primary files:

- `src/semidx/runtime/retrieval.clj`
- `src/semidx/runtime/retrieval_policy.clj`

### 3. Quality Regression Coverage

Responsibility:

- lock expected ranking behavior for shorthand queries
- prevent regression back to vendored-path contamination

Primary files:

- `src/semidx/runtime/benchmarks.clj`
- `fixtures/retrieval/corpus.json`
- new files under `fixtures/retrieval/`
- `test/semidx/retrieval_quality_test.clj` (new)
- `test/semidx/mcp_server_test.clj`
- `test/semidx/mcp_http_server_test.clj`

## Contracts

### Contract: inferred anchor payload

Client:

- `src/semidx/mcp/core.clj`

Shape:

```clojure
{:targets {:symbols [...]
           :modules [...]
           :paths [...]}
 :hints {:preferred_paths [...]
         :preferred_modules [...]
         :suspected_symbols [...]}}
```

Rules:

- inferred anchors are optional
- inferred anchors must never be treated as exact target resolution
- empty inference is valid

### Contract: path classification

Client:

- `src/semidx/runtime/retrieval.clj`

Shape:

```clojure
{:path_class "source|test|fixture|vendored|generated|other"
 :lexical_eligible? true|false}
```

Rules:

- explicit path targets still win
- default broad code-understanding queries should not give vendored paths equal footing with source paths

## Dependency Direction

- `mcp/core.clj` should depend on a narrow anchor-inference helper, not on retrieval internals.
- `retrieval.clj` should depend on policy values and small local helper functions, not on MCP-specific request handling.
- `retrieval_policy.clj` should remain the owner of weights, caps, and thresholds.
- benchmark fixtures should validate observable ranking outcomes, not internal implementation details.

## File-Level Plan

### 1. Add `src/semidx/runtime/query_anchors.clj`

Purpose:

- isolate shorthand intent parsing from MCP transport code

First-pass functions:

- `infer-anchors`
- `qualified-symbol-candidates`
- `module-candidates`
- `path-prefix-candidates`

Behavior:

- extract likely qualified symbols like `semidx.mcp.core/tool-resolve-context`
- extract likely modules/namespaces from dotted names
- infer likely path prefixes from terms like `mcp`, `runtime`, `core`
- return bounded lists

### 2. Update `src/semidx/mcp/core.clj`

Change points:

- `normalize-mcp-query`
- possibly `normalized-query-summary` if richer target keys should be surfaced

Changes:

- call `query-anchors/infer-anchors` for shorthand queries
- merge inferred targets and hints into the normalized query
- keep user-supplied `:targets` and `:hints` authoritative when present
- stop relying on `{:paths ["."]}` as the only meaningful default anchor

### 3. Update `src/semidx/runtime/retrieval.clj`

Change points:

- `query-visible-units`
- `lexical-seed-units`
- `apply-global-boosts`
- `build-confidence`
- `next-step`
- optionally helper functions near candidate collection

New helper ideas:

- `path-class`
- `source-like-path?`
- `lexical-path-eligible?`
- `suspected-symbol-match-reasons`
- `inferred-anchor-reasons`

Changes:

- classify paths
- suppress or heavily penalize lexical seeds from vendored and fixture paths for broad code-understanding queries
- sort lexical seeds by quality and path class before path order
- add scoring for `suspected_symbols`
- add bounded scoring for inferred symbol/module/path matches
- keep inferred evidence below exact target evidence
- change continuation guidance for shorthand queries with `zero tier1` plus ambiguity

### 4. Update `src/semidx/runtime/retrieval_policy.clj`

Add weights for new signals, for example:

- `hint_suspected_symbol_exact`
- `hint_suspected_symbol_segment`
- `inferred_module_match`
- `inferred_path_match`
- `source_path_prior`

Add caps or thresholds only if needed after fixture validation.

Do not weaken:

- `:no_tier1_max`
- `:top_authority_min`

unless the benchmark data clearly justifies it.

### 5. Update `src/semidx/runtime/benchmarks.clj`

Extend benchmark repo generation with controlled noise such as:

- vendored `.tree-sitter-grammars/...` files
- fixture-like files
- symbol names that overlap with real authority units

Purpose:

- make the retrieval benchmark corpus capable of reproducing the observed failure mode

### 6. Add New Retrieval Fixtures

Recommended new fixture files:

- `fixtures/retrieval/shorthand-vendored-noise.json`
- `fixtures/retrieval/shorthand-suspected-symbols-recovery.json`
- `fixtures/retrieval/shorthand-ambiguous-low-evidence.json`

Update:

- `fixtures/retrieval/corpus.json`

Expected assertions:

- source paths included
- vendored paths absent from top authority results
- ambiguity remains explicit when exact evidence is missing
- `suspected_symbols` improves ranking without claiming exact resolution

## Test Plan

### 1. Add `test/semidx/retrieval_quality_test.clj`

Purpose:

- focused behavior tests for shorthand retrieval quality without transport noise

Test cases:

- shorthand query favors `src/...` over `.tree-sitter-grammars/...`
- `suspected_symbols` improves result ranking for near-miss intent
- inferred anchors improve path/module targeting without producing fake exact-target confidence
- ambiguous shorthand remains `medium` or `low` confidence and returns narrowing-oriented guidance

### 2. Update `test/semidx/mcp_server_test.clj`

Add or change tests for:

- shorthand normalization can emit richer target keys than only `paths`
- shorthand query over a noisy temp repo still selects source authority units
- `query_normalized` remains true and schema behavior remains stable

### 3. Update `test/semidx/mcp_http_server_test.clj`

Add one transport regression for:

- shorthand query over HTTP in a noisy repo still avoids vendored authority drift

Keep this file narrow. Do not duplicate all ranking logic from runtime tests.

### 4. Benchmark Validation

Run retrieval benchmarks and assert:

- new shorthand fixtures pass
- no existing exact-symbol or diff-centered fixtures regress

## Risks

### 1. Over-inference From Intent

Why it matters:

- bad anchor inference could create a new class of false positives

Mitigation:

- keep inferred evidence below exact evidence
- cap inferred candidates
- validate against benchmark fixtures

### 2. Over-filtering Non-Standard Project Layouts

Why it matters:

- some repos keep real code outside `src/`

Mitigation:

- penalize by default only for broad code-understanding paths
- do not block explicit targets or `allowed_path_prefixes`

### 3. Brittle Rank-Order Tests

Why it matters:

- small tuning changes can break exact ordering assertions

Mitigation:

- assert presence, absence, rank bands, and confidence classes instead of full ranking order

## Implementation Sequence

### Phase 1. Introduce Shorthand Anchor Inference

Files:

- `src/semidx/runtime/query_anchors.clj` (new)
- `src/semidx/mcp/core.clj`

Outcome:

- shorthand queries gain bounded structural hints
- no ranking semantics changed yet except query enrichment

### Phase 2. Add Path Hygiene To Lexical Seeding

Files:

- `src/semidx/runtime/retrieval.clj`
- `src/semidx/runtime/retrieval_policy.clj`

Outcome:

- vendored and fixture noise no longer wins lexical fallback by default

### Phase 3. Wire `suspected_symbols` Into Scoring

Files:

- `src/semidx/runtime/retrieval.clj`
- `src/semidx/runtime/retrieval_policy.clj`

Outcome:

- clients can provide symbol-level hints that materially improve ranking

### Phase 4. Add Regression Fixtures And Tests

Files:

- `src/semidx/runtime/benchmarks.clj`
- `fixtures/retrieval/corpus.json`
- new `fixtures/retrieval/*.json`
- `test/semidx/retrieval_quality_test.clj`
- `test/semidx/mcp_server_test.clj`
- `test/semidx/mcp_http_server_test.clj`

Outcome:

- observed failure mode becomes reproducible and locked

### Phase 5. Tighten Confidence And Guidance

Files:

- `src/semidx/runtime/retrieval.clj`

Outcome:

- shorthand low-evidence retrieval returns more honest continuation guidance
- `impact_analysis` behavior can be revisited on top of improved selection quality

## Definition Of Done

- shorthand queries no longer drift into vendored paths in the new regression fixtures
- exact structured-query fixtures still pass
- `suspected_symbols` affects ranking in measurable, tested ways
- low-evidence shorthand results produce conservative guidance
- MCP shorthand transport tests continue to pass
