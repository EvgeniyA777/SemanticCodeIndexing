---
title: "Uniform Language Onboarding Completeness Progress Log"
doc_type: "progress_log"
lifecycle: "active"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-09-08"
---

# Progress Log: Uniform Language Onboarding Completeness

Companion to [`plans/023`](../plans/023_uniform_language_onboarding_completeness_plan.md).
Executed 2026-09-08 in one pass.

## Starting state

`./scripts/validate-language-onboarding.sh <lane> --skip-gates` across all ten
lanes: typescript, javascript, lua, zig, html and css at 0 errors; clojure 9,
python 9, java 10, elixir 11. The split was exact — the six passing lanes are the
ones `scripts/new-language-adapter.sh` scaffolded, and the four failing ones all
predate the checklist.

## Stage 0 — correct the validator where it was wrong

Two checks tested a spelling rather than a property, and both were corrected
rather than satisfied by changing working code:

- **parse-file branch.** Accepted a direct call or a `parse-<lang>` helper.
  Elixir uses a named wrapper, `parse-elixir-language-file`, which is a
  legitimate third shape. Now accepted.
- **Adapter reachability.** Required a static `:as <lang>-language` require.
  Elixir is reached lazily through `requiring-resolve` on purpose. Now accepted
  as an equal alternative, because what matters is that the facade reaches the
  module, not when it loads it.

Elixir went 11 → 9, matching clojure and python. No other lane changed.

## Stage 1 — documentation parity

`docs/language-onboarding/java.md` written; it was the only lane with no
onboarding document at all. The clojure, python and elixir documents were
`Status: placeholder` stubs that invited expansion — they are now real onboarding
documents describing language key, extensions, static strength, module wiring,
fixtures, tests, and next steps, each citing ADR-022 in the canonical form.

The elixir document records the lazy adapter wiring explicitly, since that is the
detail that made its validation look broken.

## Stage 2 — onboarding regression tests

Added `test/semidx/integration/{clojure,java,elixir,python}_onboarding_test.clj`.
Each seeds a temporary repository, indexes it, resolves a target, and asserts the
language, the parser mode, the module, the symbol spellings, and that retrieval
returns the requested unit. Symbol spellings were read off an actual index rather
than assumed.

Two assertions failed on the first run — a regex escaped twice during generation,
looking for a literal backslash before `$arity1`. The assertion was wrong, not
the code; fixed and re-run.

## Stage 3 — retrieval fixtures, and the defect they uncovered

While reading the benchmark corpus to write honest Java fixtures, three of its
four Java units turned out to carry a fabricated owner:
`com.acme.text.UnknownClass#normalize`. `java-class-re` accepted only a bare
`public` modifier, so `public final class Normalizer` was never recognised as a
class and its methods were attributed to `UnknownClass`.

Writing the fixture around that would have recorded a defect as expected output,
so it was fixed first: the regex now accepts any legal modifier sequence, with a
regression test in the Java onboarding test asserting that `public final`,
`public abstract` and package-private `final` classes all own their methods.
Recorded as [`bugs/004`](../bugs/004_java_class_modifiers_produce_unknownclass_owner.md).

**Why it had gone unnoticed is the point of this plan**: the Java lane had no
happy-path or ambiguity fixture, and the one Java fixture that existed asserted
paths only, never symbols.

Eight fixtures were then written — happy path and ambiguity for each of the four
lanes — over sources the benchmark repository already seeds. Every expectation
was measured on an actual run before being written down, and each fixture asserts
something a lane could plausibly get wrong:

| Fixture | What it holds the lane to |
| --- | --- |
| clojure happy | namespace-qualified definition stays top authority |
| clojure ambiguity | a same-named test is a related-test neighbour, not a replacement for the definition |
| java happy | package-and-class-qualified method, with arity and signature in the unit id |
| java ambiguity | two same-name overloads stay distinct rather than merging into one identity |
| python happy | module-qualified function plus the module it calls |
| python ambiguity | a module function and a same-named method both survive, with `target_ambiguous` reported |
| elixir happy | module-qualified function with its arity |
| elixir ambiguity | a `defdelegate` and its target both stay in the top band |

## Verification

| Check | Result |
| --- | --- |
| `validate-language-onboarding.sh` × 10 lanes | 0 errors each |
| `./scripts/run-benchmarks.sh` | 31 fixtures, 31 passed |
| `./scripts/validate-contracts.sh` | ok, 80 files (was 72) |
| `clojure -M:test` | pass |

## Follow-ups left open

- `bugs/002` (rebuild-reason whitelist) is untouched and still open.
- Java records are not recognised as a declaration form; noted in `bugs/004` as a
  separate feature rather than part of that defect.
- The integration-onboarding-test requirement is now met by every lane, so the
  checklist is uniform in fact and not only on paper.
