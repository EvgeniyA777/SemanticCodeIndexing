---
title: "Uniform Language Onboarding Completeness Plan"
doc_type: "implementation_plan"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-09-08"
---

# Plan: Uniform Language Onboarding Completeness

Owner decision (2026-09-08): every language lane must meet the same onboarding
requirements, and the repository is to be brought up to that bar rather than the
bar lowered to fit the lanes that predate it.

Discovered while running the `plans/018` Stage 6.5 gates and first recorded as
[`bugs/003`](../bugs/003_java_lane_missing_onboarding_artifacts.md), which this
plan supersedes in scope: the problem is not Java's, it is the checklist's
uneven application.

## The state this starts from

`./scripts/validate-language-onboarding.sh <lane> --skip-gates`, run against all
ten lanes on 2026-09-08:

| Lane | Errors | Note |
| --- | --- | --- |
| typescript, javascript, lua, zig, html, css | 0 | created after the checklist, scaffolded by `scripts/new-language-adapter.sh` |
| clojure | 9 | onboarding test, two fixtures, corpus entries, ADR-022 reference |
| python | 9 | same set |
| java | 10 | same set plus the onboarding doc itself |
| elixir | 11 | same set plus two adapter-shape checks the validator gets wrong |

The split is exact: the six lanes that pass are the ones the scaffold generated,
and the four that fail all predate the checklist. Nothing here is a regression —
`git log --all` shows none of the missing artifacts ever existed.

## Scope

In scope: making the four legacy lanes meet the same checklist, and correcting
the one check that is wrong rather than unmet.

Out of scope: changing what the checklist requires, adding lanes, and any
provider-authority work. `plans/018` Stage 6 waits on this only to the extent its
owner asked — the authority default flip is deliberately after it, so that a
lane-level gate failure at that moment means something.

## Two rules for the work

1. **A fixture must be earned, not matched.** `run-benchmarks.sh` executes every
   fixture in `fixtures/retrieval/corpus.json` against the seeded benchmark repo
   and asserts its `expected` block, and that script runs inside
   `run-mvp-gates.sh`. A fixture written to satisfy a grep would either fail the
   benchmarks or assert nothing worth asserting. Each new fixture states an
   expectation the engine actually meets, over sources already seeded by
   `build-benchmark-repo!`.
2. **A check that is wrong gets fixed, not satisfied.** Elixir is wired into
   `adapters/parse-file` through a `parse-elixir-language-file` wrapper, which is
   a legitimate shape the validator does not recognise. Renaming the wrapper to
   please a regex would be the tail wagging the dog.

## Stages

### Stage 0. Correct the validator where it is wrong

`check_contains_any` for the parse-file branch accepts two spellings. Elixir uses
a third — a named wrapper — and HTML and CSS already use the second. Accept the
wrapper form.

Exit: elixir's two adapter-shape failures disappear without touching
`adapters.clj`; the other nine lanes keep the result they had.

### Stage 1. Documentation parity

- Write `docs/language-onboarding/java.md` — the only lane with no onboarding
  document at all.
- Add the ADR-022 reference to the clojure, elixir, and python documents. The
  checklist asks every onboarding document to cite the ADR that defines the
  onboarding flow; the three legacy documents predate that requirement.

Exit: `docs/language-onboarding/` holds one document per lane, each citing
ADR-022.

### Stage 2. Onboarding regression tests

Add `test/semidx/integration/{clojure,java,elixir,python}_onboarding_test.clj`,
mirroring the shape the passing lanes use: seed a small temp repository, build an
index, resolve a target, and assert the language, the parser mode, and that the
target symbol is in the returned units.

These are the artifacts most worth having on their own merit: they are the only
per-lane end-to-end regression tests, and four lanes have none.

Exit: `clojure -M:test` green with four new namespaces auto-discovered.

### Stage 3. Retrieval fixtures

For each of the four lanes add `<lane>-happy-path.json` and
`<lane>-ambiguity.json` with the ids the checklist expects
(`retrieval_<lane>_happy_path_001`, `retrieval_<lane>_ambiguity_001`), over
sources `build-benchmark-repo!` already seeds:

| Lane | Seeded sources |
| --- | --- |
| clojure | `src/my/app/{order,checkout,payments,fulfillment}.clj`, tests |
| java | `src/com/acme/CheckoutService.java`, `text/Normalizer.java`, `audit/AuditNormalizer.java` |
| python | `app/orders.py`, `app/workflow.py` |
| elixir | `lib/my_app/order.ex`, `lib/my_app/payments/adapter.ex`, `test/my_app/order_test.exs` |

Register each in `fixtures/retrieval/corpus.json`.

Exit: `./scripts/run-benchmarks.sh` passes with the new fixtures included, and
`./scripts/validate-contracts.sh` still passes.

### Stage 4. Close the loop

- All ten lanes report `validation_checks=ok`.
- Full gates: `clojure -M:test`, `validate-contracts`, `run-mvp-gates`.
- `bugs/003` moves to `status: fixed` with a pointer here.
- MEMORY records the new invariant: the onboarding checklist applies uniformly,
  and a lane that fails it is a real signal again.

## Outcome (2026-09-08)

All ten lanes report zero checklist errors. Execution, the defect it uncovered,
and the fixture-by-fixture rationale are recorded in
[`reports/028`](../reports/028_uniform_language_onboarding_progress_log.md).

The stage that mattered most was not on the plan: writing honest Java fixtures
required fixing `java-class-re` first, because three of the four Java units in
the benchmark corpus carried a fabricated `UnknownClass` owner
([`bugs/004`](../bugs/004_java_class_modifiers_produce_unknownclass_owner.md)).
That is the argument for uniform requirements in one line — the lane had no
fixture asserting a symbol, so nothing ever looked at what it produced.

## Verification

Narrowest first, per stage: the validator for the lane being worked on, then
`clojure -M:test` for test additions, then `run-benchmarks.sh` for fixture
additions, then the full gate set once at the end.

## Risks

### [Medium] A fixture that asserts the wrong thing

Mitigation: every fixture is run through `run-benchmarks.sh` before it is
committed, and its expectations are read off an actual run rather than guessed.
A fixture whose expectation had to be weakened to pass is recorded as such rather
than quietly relaxed.

### [Low] The four new onboarding tests slow the suite

Each seeds a temporary repository and builds one small index, in line with the
six that already exist. Measured at the end.
