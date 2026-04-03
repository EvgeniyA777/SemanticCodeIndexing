---
file_type: working-note
topic: resolve-context-architecture-review
created_at: 2026-04-02T23:56:33-0700
author: codex
language: en
status: active
reason: Created after an architecture review of resolve_context, shorthand MCP normalization, lexical fallback, graph expansion, and related retrieval-quality behavior in the semidx repository.
---

# Working Note: resolve_context Architecture Review On 2026-04-02

## Summary

Reviewed the current `resolve_context` pipeline in `semidx` after an observed failure mode where shorthand intent retrieval drifted into `.tree-sitter-grammars/...` instead of the intended `src/...` code.

High-level conclusion:

- the diagnosis is materially correct
- the main problem is retrieval architecture, not MCP transport correctness
- `resolve_context` is currently reliable as a compact selector when the query is already structurally anchored
- `resolve_context` is not yet a dependable natural-language code navigator from zero
- the strongest fixes are path hygiene, shorthand anchor inference, and real use of `suspected_symbols`

## Review Slice

The review focused on:

- `src/semidx/mcp/core.clj`
- `src/semidx/runtime/retrieval.clj`
- `src/semidx/runtime/retrieval_policy.clj`
- `impact_analysis`
- `skeletons`
- existing MCP and runtime tests

The review also checked the staged retrieval contract in:

- `adr/024-make-compact-first-staged-retrieval-the-canonical-public-flow.md`

## Reproduced Behavior

Using the live repo and `semidx` MCP flow:

- a loose `resolve_context` intent shorthand selected `.tree-sitter-grammars/...` units
- a structured query with exact `paths` and `symbols` returned the correct authority units

This confirms the key distinction:

- the current system is strong when explicit structural targets exist
- the current system is weak when it must infer those targets from natural-language intent alone

## Findings

### 1. Shorthand normalization does not create useful structural anchors

Relevant code:

- `src/semidx/mcp/core.clj:471`
- `src/semidx/mcp/core.clj:603`
- `src/semidx/runtime/retrieval.clj:141`

`normalize-mcp-query` converts shorthand intent into a canonical-shaped retrieval query, but the default target is effectively:

```clojure
{:paths ["."]
 ...}
```

That shape satisfies schema requirements, but it does not provide meaningful structural seeds for `structural-seed-reasons`.

Practical effect:

- shorthand looks richer than it really is
- retrieval falls through to lexical fallback too easily

### 2. Lexical fallback is too broad and path-order-sensitive

Relevant code:

- `src/semidx/runtime/retrieval.clj:63`
- `src/semidx/runtime/retrieval.clj:74`
- `src/semidx/runtime/retrieval.clj:161`
- `src/semidx/runtime/retrieval.clj:270`

Current lexical behavior:

- tokens come mostly from `intent.details` and `diff_summary`
- lexical match is a simple substring test over `signature`, `summary`, and `symbol`
- lexical seed units are sorted by `:path` and `:start_line`
- only the first `6` lexical seeds are kept

This makes ranking fragile when vendored or irrelevant paths contain token overlap and happen to sort earlier or cluster more tightly than the real authority units.

### 3. Graph expansion amplifies bad seeds instead of correcting them

Relevant code:

- `src/semidx/runtime/retrieval.clj:229`

Once seed units are wrong, `expand-graph-score-map` adds callers, callees, same-module neighbors, and same-file neighbors around those wrong seeds.

Practical effect:

- graph evidence becomes internally coherent
- but the coherence is often built around the wrong starting point

### 4. The schema exposes hints that retrieval does not yet honor

Relevant code:

- `src/semidx/mcp/core.clj:52`
- `src/semidx/runtime/retrieval.clj:212`

The MCP schema includes:

- `preferred_paths`
- `preferred_modules`
- `suspected_symbols`

But `apply-global-boosts` currently uses only:

- `preferred_paths`
- `preferred_modules`
- parser fallback state

`suspected_symbols` is present in the contract but is not a real scoring input.

### 5. Confidence is diagnostically honest but operationally weak

Relevant code:

- `src/semidx/runtime/retrieval.clj:493`
- `src/semidx/runtime/retrieval.clj:960`
- `src/semidx/runtime/retrieval.clj:1252`
- `src/semidx/runtime/retrieval.clj:1312`

The system correctly reports warnings such as:

- `no_tier1_evidence`
- `target_ambiguous`

However, those warnings do not substantially change the retrieval strategy. The pipeline still returns the top-ranked set and recommends continuation, even when the evidence quality is weak.

`impact_analysis` inherits the same weakness because it simply chains:

1. `resolve-context`
2. `expand-context`

If the original selection is noisy or budget-limited, `impact_analysis` can return weak or omitted hints.

### 6. Policy intentionally prevents fallback-heavy retrieval from looking authoritative

Relevant code:

- `src/semidx/runtime/retrieval.clj:91`
- `src/semidx/runtime/retrieval_policy.clj:7`
- `src/semidx/runtime/retrieval_policy.clj:299`

The current policy is internally consistent:

- `:no_tier1_max` is `89`
- `:top_authority_min` is `120`

This means retrieval without tier1 evidence is intentionally prevented from looking authoritative.

This is a good safety property. The problem is not the cap itself. The problem is how often shorthand retrieval fails to generate tier1 evidence in the first place.

## Architectural Assessment

The current architecture matches `ADR-024` if interpreted strictly:

- `resolve_context` is a compact selector
- `expand_context` is a bounded structural expansion
- `fetch_context_detail` is the rich detail stage

That staged model is sound.

The gap is product positioning and query ingress behavior:

- the API surface suggests a stronger natural-language starting point than the retrieval engine actually supports
- shorthand query handling does not yet have a dedicated “intent to structural anchors” stage

## What Is Working Well

- staged retrieval is clear and coherent
- `selection_id` and `snapshot_id` form a stable contract
- exact structured queries work well
- `skeletons` is reliable because it requires explicit scope
- the confidence model is conservative rather than misleading

## What Needs Work

### Priority 1: path hygiene and corpus priors

The retrieval layer should stop treating all visible units as equally eligible for lexical fallback in `code_understanding` and `review_support`.

Strong candidates for default exclusion or heavy penalty:

- `.tree-sitter-grammars/`
- vendored bindings
- generated code
- fixtures
- examples not under declared source roots

### Priority 2: shorthand anchor inference

Shorthand intent should not normalize only to `{:paths ["."]}`.

The system should derive candidate anchors from intent text, such as:

- qualified symbol guesses
- module guesses
- namespace guesses
- likely path prefixes

These should enter scoring as inferred structural signals, not as fake exact matches.

### Priority 3: make `suspected_symbols` real

The current schema already has the right extension point.

Recommended scoring categories:

- exact symbol match
- exact unqualified symbol match
- namespace prefix match
- symbol-segment match

### Priority 4: degraded-mode behavior

When all of the following are true:

- shorthand ingress
- no tier1 evidence
- ambiguous top ranks

the system should steer the caller toward narrowing the search instead of acting as if the current selection is probably good enough.

### Priority 5: benchmark and regression coverage

The repo already has retrieval benchmark infrastructure in `src/semidx/runtime/benchmarks.clj`, but the review did not find an obvious regression case for:

- shorthand intent drifting into vendored paths
- poor anchoring with no exact targets
- `suspected_symbols` as a quality improvement signal

These should be added as fixtures before making scoring changes.

## Recommended Fix Sequence

1. Add path-class priors or exclusion rules for lexical fallback candidate generation.
2. Add shorthand anchor inference in the MCP normalization or pre-retrieval stage.
3. Wire `suspected_symbols` into retrieval scoring.
4. Adjust degraded guidance for ambiguous shorthand results.
5. Add benchmark fixtures covering shorthand anchoring and vendored-path contamination.

## External Best-Practice Direction

The review of established retrieval systems points in the same direction:

- candidate hygiene and filtering should happen before reranking
- symbol-aware retrieval should be treated differently from plain text overlap
- hybrid retrieval works best when multiple signals are fused over a plausible candidate set, not over a polluted one

Useful references:

- Sourcegraph symbol search docs: <https://sourcegraph.com/docs/code-search/types/symbol>
- Azure AI Search hybrid search overview: <https://learn.microsoft.com/en-us/azure/search/hybrid-search-overview>
- Azure semantic ranking overview: <https://learn.microsoft.com/en-us/azure/search/semantic-search-overview>
- Elastic learning to rank overview: <https://www.elastic.co/docs/solutions/search/ranking/learning-to-rank-ltr>

## Bottom Line

The present `resolve_context` implementation should be treated as:

- a good compact selector when the query is already structurally grounded

and not yet as:

- a robust natural-language code-navigation mechanism from an unanchored prompt

The smallest high-value correction is not a full reranker rewrite.

The smallest high-value correction is:

1. cleaner candidate pool
2. better shorthand anchoring
3. real use of symbol hints
