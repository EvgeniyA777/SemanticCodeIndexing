---
title: "Latest Active Plans Architecture Review"
doc_type: "architecture_review"
lifecycle: "completed"
status: "final"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Architecture Review: Latest Active Plans

## Scope

This review covered the active architecture queue in
[`plans/007`](../plans/007_semidx_extension_architecture_resolution_plan.md),
[`plans/018`](../plans/018_semantic_provider_authority_migration_plan.md),
[`plans/019`](../plans/019_llm_one_shot_context_delivery_and_evaluation_plan.md),
and
[`plans/020`](../plans/020_retrieval_value_benchmark_harness_plan.md), checked
against the current implementation, [`SPEC.md`](../SPEC.md), accepted ADRs, and
project memory.

Recommendation: proceed after the plan revisions recorded below. `plans/020`
remains the next execution priority; its Stage 2 starts only after Stage 0 locks
the corrected run, arm, adapter, and price-schedule contracts. `plans/018` and
`plans/019` remain at their admission/baseline stages.

## Findings And Resolutions

| Severity | Finding | Resolution | Status |
| --- | --- | --- | --- |
| High | `plans/019` and `plans/020` both claimed ownership of the comparative corpus and harness. | `plans/020` now exclusively owns the corpus, run/attempt schemas, cross-strategy harness, usage normalization, price schedules, and aggregation. `plans/019` owns only one-shot delivery fixtures and strategy adapters. | fixed |
| High | The benchmark could not be preregistered coherently: the LSP/SCIP baseline was absent from the named arms, cost/token language conflicted, and repeated task attempts lacked immutable identity. | `SPEC.md` and `plans/020` now define A/B/C/D arms, B as the preregistered comparator, explicit C unavailability, `BenchmarkRun`/`TaskAttempt`, and success-per-cost aggregation by attempt before rollup. | fixed |
| High | Provider usage normalization could underprice cache writes and reasoning/thinking output or omit the OpenAI Responses surface. | `plans/020` now requires versioned provider/API/model adapters, separate cache-write TTL classes, visible/reasoning/unclassified output, provider tool charges, immutable price schedules, raw usage retention, and an unresolved-pricing state instead of guessing. | fixed |
| High | Arm D was not distinguishable from the competent lexical comparator, and one run-level tool policy could not represent four arm-specific navigation protocols. | `plans/020` now defines each arm's verdict role, separates the shared task prompt from a versioned arm-policy bundle, records the per-attempt arm policy, and holds provider/model/repository/budgets constant within a run. | fixed |
| High | `plans/019` exposed both nested staged budgets and a top-level one-shot budget, and `format: both` could duplicate content beyond the cap. | The top-level budget is now authoritative, nested staged budgets conflict explicitly, structured/Markdown/diagnostic allocations are disjoint, and usage events have aggregate versus stage accounting scopes. | fixed |
| High | Multi-provider arbitration in `plans/018` assumed facts already shared an identity even though provider-native symbols and overload spellings can differ. | `plans/018` now requires a provider-neutral `CanonicalFactKey` before arbitration, excludes mutable evidence/source identity from the key, and gates implementation on cross-provider overload/re-export identity fixtures. | fixed |
| Medium | Active-document state was misleading: `plans/020` remained draft after Stage 1 and `plans/007` still called completed freshness work the next executable boundary. | `plans/020` is now `in_progress`; `plans/007` marks its first boundary historical and points to the current `018`/`019`/`020` continuations. | fixed |

## Remaining Admission Gates

### plans/020

- Freeze the A/B/C/D arm policies, suite/run/attempt schemas, shared task prompt,
  execution-budget policy, cache protocol, adapter versions, and price schedule
  before the calibration pilot.
- Select the actual provider API surfaces used by the harness and prove every
  scored attempt has `pricing_status: resolved`.
- Implement Stage 2 without a many-to-many `task_id` join.

### plans/018

- Add deterministic cross-provider identity fixtures for Java overloads,
  TypeScript re-exports, and dispatch-sensitive facts.
- Approve the `CanonicalFactKey` normalization contract.
- Confirm TypeScript as the first SCIP vertical slice unless Stage 0 toolchain
  evidence justifies reversing it.

### plans/019

- Accept the one authoritative response-budget ledger and accounting-scope
  semantics.
- Map the delivery fixture overlay onto the task and strategy-result contracts
  from `plans/020`; do not create a second corpus.
- Keep the first slice additive under ADR-024.

## Verification

- `clojure -M:test` before the documentation-only revisions: 289 tests, 1947
  assertions, 0 failures, 0 errors.
- `git diff --check` passed, and the English-only scan found no Cyrillic text in
  the revised documents.
- `clojure -M:ccc check --root .` reported existing CCC artifacts as stale.
  They were not regenerated because this documentation review did not require
  new compression output and `RULES.md` forbids routine per-task refreshes.
- Current implementation inspection confirmed that `plans/018` and `plans/019`
  have no source implementation yet; `plans/020` Stage 1 is implemented.
- No runtime source, contract schema, or generated artifact changed in this
  review-resolution commit.
