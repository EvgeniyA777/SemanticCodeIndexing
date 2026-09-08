---
title: "parser_mode fallback Now Means Two Different Things, And Features Read The Wrong One"
doc_type: "bug_report"
lifecycle: "active"
status: "fixed"
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

## Resolution (2026-09-08)

`provider-authority` no longer writes `parser_mode`. The evidence tier stays on
`:authority`, the file still carries the `provider_authority_degraded`
diagnostic, and `parser_mode` means what it always meant. Verified on the same
entity fixture: under `:authority` the units now keep `parser_mode "full"`,
carry `authority "heuristic"`, and the state-invariant packet comes back
complete with its entity candidates.

The default flip landed on the second attempt the same day. Suite 681 tests /
3636 assertions / 0 failures, benchmarks 31/31, contracts ok.

Two things came out of the fix and are worth carrying forward.

**A snapshot must not contain a clock.** With the pipeline on by default, two
identical builds differed, because the authority summary carried
`project_elapsed_ms`. That would surface in `snapshot-diff` as a change where
nothing changed, against ADR-046's determinism driver. Timing was removed from
the summary; the project tier's duration is still measured in `build-context`
and the whole build's latency is already on the create_index usage event. The
shadow summary still carries `total_elapsed_ms` and has the same flaw, but
shadow is opt-in and was left alone rather than changed in passing.

**The confidence reduction from decision 1 is restored (owner decision,
2026-09-08: teach the shared gate the difference).**

`impact-seed-degradations` no longer treats a low confidence level as an absence
of structure. It asks the question it was always meant to ask — is there
structure to reason about — and answers it from structural signals: no seed, a
seed the parser could not extract (`parser_mode "fallback"`), an ambiguous seed,
an unresolved requested symbol, a stale index. A low level over units the parser
did extract, where every unit rests on heuristic evidence, no longer blocks
blast-radius analysis: the callers, relations and state-invariant packet are all
computed and sitting there, and weaker evidence is what the confidence level is
for.

With that separated, `selected-language-strengths` lowers a wholly heuristic
selection one step below its lane's static strength — Java `medium` → `low`,
TypeScript `low` → `low`. The static number describes a lane with its structural
parser available; a selection that had only the lexical tier should not claim it.

Verified: on the entity fixture, the state-invariant packet stays complete while
the ceiling drops. The ladder now reads java heuristic-only `low`, java without
recorded evidence `medium`, java mixed `medium`, java wholly exact `high`,
clojure untouched at `high`.

**One consequence to know about**: `low` carries the guardrail with it, so a Java
retrieval on a machine with no semantic toolchain now reports
`autonomy_blocked`. That is the same posture every other lane with a low ceiling
already reported — Lua has always been there — and it reverses as soon as a
semantic tier is available. Three Java retrieval fixtures were updated to expect
it, with the reason recorded in each.
