---
title: "parser_mode fallback Now Means Two Different Things, And Features Read The Wrong One"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-09-08"
---

# parser_mode fallback Now Means Two Different Things, And Features Read The Wrong One

Severity: high, and it is the single thing blocking the `plans/018` authority
default flip. Found by attempting that flip on 2026-09-08 and reverting it the
same day.

## Summary

`parser_mode "fallback"` had one meaning: **the parser could not extract
structure**, and the file is represented by a generic section unit with a
`parser_fallback` diagnostic.

`plans/018` Stage 6.2 gave it a second meaning: **the evidence behind this unit
is heuristic**, which for Java and TypeScript is the ordinary result of a
successful regex parse on a machine with no semantic toolchain.

The two are not the same. A regex parse that produced real methods, fields, calls
and relations is not a parse that failed. But every consumer keyed to the first
meaning now reads the second.

## What it breaks

Measured on a two-file Java entity fixture, identical inputs, only the pipeline
mode differing:

| | `provider_pipeline: off` | default (`:authority` during the attempted flip) |
| --- | --- | --- |
| units | 3 | 3 |
| relations | 5 | 5 |
| unit `parser_mode` | `full` | `fallback` |
| unit `authority` | absent | `heuristic` |
| `state_invariants` packet | full packet | **empty** |
| `entity_candidates` | the entity file | **none** |

The chain: every unit labelled `fallback` makes
`retrieval-policy/coverage-level` report `fallback_only`, which caps
`confidence-ceiling` at `low`, which makes
`retrieval/impact-seed-degradations` classify the selection as degraded, which
makes `impact-analysis` return its degraded stub and never call
`state-invariants/assemble`.

So on the common configuration — Java with no SCIP or LSP toolchain installed —
impact analysis and the whole state-invariant feature stop answering. Nothing
about the underlying extraction changed; only the label did.

The suite caught this as 20-odd failures across `runtime_test`, `http_test` and
`grpc_test` the moment the default flipped.

## Why the labelling decision is not the problem

The owner approved unconditional degradation labelling on 2026-09-06, and that
decision stands: a heuristic-only Java file should say so and should not carry a
confident ceiling. What was not approved, and what nobody could have foreseen
from the wording, is that the chosen *field* already had a meaning other features
depend on.

## Suggested fix

Separate the two meanings rather than weaken either:

- `parser_mode` keeps its original meaning — extraction failure, generic section
  units, the `parser_fallback` diagnostic. Stage 6.2 stops rewriting it.
- The evidence tier stays on `:authority`, which every merged unit already
  carries, alongside the `provider_authority_degraded` file diagnostic.
- `retrieval-policy/coverage-level` and `selected-language-strengths` read
  `:authority` for the heuristic case, so the confidence ceiling still drops for
  a heuristic-only selection — the effect the owner asked for — without telling
  `impact-seed-degradations` that the parser failed.

The upward half of Stage 6.2 already works this way: `evidence-strength` reads
`:authority`, not `parser_mode`. The downward half should match it.

Once that lands, the flip is one line again, and `bugs/005` closes with it.

## Kept from the attempt

One genuine defect was found and fixed while the flip was up:
provider-supplied units carried an empty `:signature`, which fails the context
packet contract (`internal_contract_error`, "should be at least 1 character") as
soon as such a unit reaches retrieval. Fixed in
`semidx.runtime.provider-authority/unit-from-fact`, which now falls back to the
symbol. That fix is independent of the flip and stays.
