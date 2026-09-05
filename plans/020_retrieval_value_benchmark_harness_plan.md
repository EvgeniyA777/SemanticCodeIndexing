---
title: "Retrieval Value Benchmark Harness Plan"
doc_type: "architecture_plan"
lifecycle: "active"
status: "blocked"
agent_action: "do_not_use_for_current_work"
updated: "2026-08-28"
---

# Architecture Plan: Retrieval Value Benchmark Harness

> **Paused by the owner on 2026-08-28.** Do not start a stage from this plan.
> The staged four-arm experiment is not the measurement approach the owner
> currently wants: the intent is passive telemetry of real working sessions
> (count tokens as work happens, record whether semidx helped or not, aggregate
> by task and process, analyse afterwards) rather than a synthetic A/B/C/D run
> with a purpose-built agent. What to build instead is an open question the
> owner is thinking through. Stages 1-3 and the arm runner are delivered and
> committed; see `reports/021` for their state.

This plan operationalizes the falsifiable value hypothesis in
[`SPEC.md`](../SPEC.md) §5.1: turn one-off token measurements into a
reproducible, pre-registered benchmark that compares semidx retrieval against
cheap baselines on real repositories, measuring **task success per unit of
normalized cost and time**.

It is the reusable measurement substrate. It does not itself claim semidx is
better; it makes that claim testable.
The latest independent review and resolved plan findings are recorded in
[`reports/022`](../reports/022_latest_active_plans_architecture_review.md).

## Decision Boundary

- [`SPEC.md`](../SPEC.md) §5.1 is the decision of record for what "worth it"
  means. This plan builds the evidence pipeline for it; it does not set the win
  threshold (owner-set, pre-registered per Stage 0).
- Boundary vs [`plans/019`](./019_llm_one_shot_context_delivery_and_evaluation_plan.md):
  019 owns the one-shot `get_context` delivery surface and its comparison
  strategy adapters and delivery-specific fixtures; **020 exclusively owns the
  real-repo task corpus, benchmark-run schema, cross-strategy harness, usage
  normalization, price schedules, and success/cost aggregation substrate.**
  019's comparison is one consumer of 020's substrate and must not create a
  second corpus or aggregation contract. Where 019 measures one-shot vs staged,
  020 measures semidx (staged or one-shot) vs non-semidx baselines.
- Additive only. No change to public retrieval contracts. Reuses the existing
  usage-metrics sink, feedback surface, and evaluation-command patterns.

## Goal

Convert observations such as "~40k tokens on a cold repo without the index vs
~3.4–3.7k with it" into reproducible, falsifiable evidence:

- four strategy arms — **A** semidx, **B** a competent `rg` + targeted-read
  agent, **C** an `rg` + LSP/SCIP navigation agent where available, and **D** a
  native no-index agent;
- measured on task success, false negatives, wall-clock, tool calls, normalized
  cost, and diagnostic token counts;
- against a fixed real-repo task suite;
- with the success/failure threshold fixed **before** the first run.

## Arm Contract And Experimental Controls

| Arm | Versioned navigation policy | Verdict role |
| --- | --- | --- |
| A — semidx | Canonical staged semidx flow; a later registered variant may use the `plans/019` one-shot adapter | candidate |
| B — competent lexical | Preregistered `rg` queries plus targeted bounded file reads; no semidx, LSP, or SCIP | primary comparator |
| C — language navigation | The B policy plus registered LSP/SCIP definition/reference navigation | diagnostic control; `not_applicable` when unavailable |
| D — native no-index | The agent's versioned default repository-browsing policy with ordinary filesystem/shell tools, but no semidx or external semantic-navigation service | ecological diagnostic control |

B and D may expose overlapping low-level tools, but they answer different
questions: B is the stable, auditable comparator; D measures the agent's native
no-index workflow. D must not replace B in the primary verdict.

Within a scored benchmark run, arms use the same task wording, repository
revision and initial state, **evaluated** provider/API/model revision, service
tier, seed schedule, agent build, and wall-clock/tool budget. They differ only
by the registered arm policy. The harness executor model is not an evaluated
model and is never a price-schedule key. Arm order is randomized or
counterbalanced, each attempt uses an isolated workspace, and the preregistered
cache protocol is recorded with every attempt. A change to an evaluated
provider/model, agent build, or shared protocol creates a new
`benchmark_run_id`; results from incompatible runs are not pooled for the
primary verdict.

## Verified current state (grounded)

Metric collection on the semidx side already exists and persists; the gap is the
harness and aggregation layer.

| Capability | Status | Evidence |
| --- | --- | --- |
| Per-op token cost recorded into event `:payload` | present | `src/semidx/core.clj:207` (`resolve-context`), `:267` (`expand-context`) |
| Event schema (first-class cols + `payload`) | present | `src/semidx/runtime/usage_metrics.clj:54` (`normalize-event`) |
| Postgres persists `payload` as `jsonb` (round-trips) | present | `usage_metrics.clj:248` (`PostgresUsageMetrics`, `semantic_usage_events.payload jsonb`), read at `:434` (`postgres-events`) |
| Daily rollups (counts, latency, outcomes) — **no tokens** | present, no tokens | `usage_metrics.clj:248` (`semantic_usage_daily_rollups`) |
| Task success / ground truth capture hook | present, data external | `record-feedback!` → `semantic_usage_feedback` (`feedback_outcome`, `ground_truth_unit_ids/paths`) |
| Agent total session tokens (e.g., 40k / 3.5k) | **missing** | not observable by the runtime; host/harness only |
| Four-arm A/B/C/D orchestration | **missing** | — |
| Success-per-cost aggregator across arms | **missing** | — |

Stage 1 fixed the known fidelity bug: selection no longer duplicates
`estimated_tokens` into `returned_tokens`. Selection has no separately measured
return, so its honest cost is `estimated_tokens`; expand/detail use measured
`returned_tokens`. The aggregator must preserve that distinction.

## Scope

In:

- A benchmark harness driving the same task on the same pinned repository
  revision through A semidx / B competent `rg`+read / C `rg`+LSP/SCIP where
  available / D no-index. An unavailable C arm is recorded as `not_applicable`
  with a reason; it is never silently folded into B.
- Capture of agent-side totals the runtime cannot see: total session tokens,
  wall-clock, tool-call count — supplied by the harness/host.
- Per-attempt outcome recording via `record-feedback!` with shared
  `benchmark_run_id`, `task_id`, and `task_attempt_id` plus ground-truth unit
  ids/paths.
- Normalization of each agent's raw `usage` into a provider-independent
  response/usage matrix (provider/API adapters), preserving raw payloads.
- Versioned benchmark-run and task-attempt identity, including repository
  revision, seed, shared task prompt, per-arm policy, evaluated provider/API
  surface/model revision, and price schedule. The executor model remains a
  routing concern rather than an evaluated-attempt field.
- An aggregator evaluation command that first aggregates usage by
  `task_attempt_id`, then joins `semantic_usage_feedback` outcomes and computes
  success-per-cost per arm without a many-to-many `task_id` join.
- Fix of the `returned_tokens` fidelity bug plus a regression test.
- A real-repo task-suite definition, including at least one external repository
  (not semidx itself) to avoid self-tuning bias.
- Negative-utility calibration cases that can show when semidx is worse than a
  competent baseline, not only when it wins. The initial required cases are:
  Zig API-surface/signature extraction, where `skeletons` must be compared
  against targeted `rg` patterns rather than naive whole-file reads; Zig
  container/config field discovery, where missing struct-field facts must be
  recorded as a false negative; Zig blast-radius discovery for a named runtime
  symbol, where `impact_analysis` must be scored as failed if the seed selection
  resolves to an unrelated module; and stale-snapshot-after-edit behavior, where
  any reuse of a pre-edit snapshot must be surfaced separately from retrieval
  ranking quality.

Out:

- No new public retrieval contract, and no change to ADR-024 staged semantics.
- No LLM as source of truth; no general-purpose RAG.
- No inference of hidden model reasoning. Visible task prompts and arm policies
  are versioned inputs; provider-reported usage and task outcomes are captured.
- No promotion of `SPEC.md` §5.1 to `[committed]` until real evidence passes.

## Benchmark run identity

Every observation belongs to an immutable benchmark run and task attempt:

```text
BenchmarkRun {
  benchmark_run_id, suite_version, started_at,
  repo_key, repo_revision, dirty_state,
  task_prompt_policy_id, arm_policy_bundle_id,
  execution_budget_policy_id, cache_protocol_id,
  price_schedule_id, harness_version
}

TaskAttempt {
  benchmark_run_id, task_id, task_attempt_id,
  arm, arm_policy_id, sequence_index, seed,
  agent_id, agent_build_id,
  evaluated_provider, evaluated_api_surface,
  evaluated_model, evaluated_model_revision,
  evaluated_service_tier,
  outcome, not_applicable_reason
}
```

`task_id` identifies the stable task definition. `task_attempt_id` identifies
one execution of that task under one arm and seed. Aggregation keys on
`task_attempt_id` first and only then rolls up to
`(benchmark_run_id, task_id, arm)`; repeated runs must never collapse merely
because they share `task_id`. `not_applicable_reason` is required only when an
attempt outcome is `not_applicable`, which is the sole permitted representation
of an unavailable Arm C capability.

## Response/usage matrix

Different agents (semidx-driven, `rg`-baseline, no-index — and different LLM
providers behind them) emit usage in incompatible shapes *and* semantics. Fields
with the same name mean different things: Anthropic `input_tokens` **excludes**
cached input, while OpenAI `prompt_tokens` **includes** it. Raw usage numbers are
therefore not summable or comparable across arms. The harness normalizes every
response into one matrix before aggregation.

### Canonical usage record (provider-independent)

Per response, map raw usage to unambiguous fields:

- `input_uncached` — full-rate input tokens;
- `input_cache_read` — cache-read tokens priced by the immutable provider/model
  schedule (0 if unsupported);
- `input_cache_write_5m` and `input_cache_write_1h` — distinct cache-write
  classes when the provider exposes them; never assume one write multiplier;
- `output_visible`, `output_reasoning`, and `output_unclassified` — visible and
  reasoning/thinking output separated when exposed; an API that reports only a
  combined output total uses `output_unclassified` rather than pretending the
  total is visible output;
- `tool_charges_usd` — non-token provider tool charges, otherwise 0;
- derived: `input_total`, `output_total`, `grand_total`, and `cost_usd` via an
  immutable `price_schedule_id` keyed by evaluated provider, API surface, model
  revision, service tier, and cache class.

The price schedule is a retained artifact, not a live lookup during aggregation.
It records currency, token unit, exact per-class rates, effective/capture time,
and official source references so a historical run remains reproducible.

### Provider/API adapter (raw → canonical)

Each provider/API surface owns an adapter that maps raw usage to the canonical
record. Official provider response and pricing contracts are authoritative; the
table is the initial mapping and every adapter must be verified against the
actual API surface used by the harness:

| Adapter | uncached | cache read/write | visible output | reasoning/thinking | unclassified output |
| --- | --- | --- | --- | --- | --- |
| anthropic-messages | `input_tokens` | `cache_read_input_tokens`; split cache creation by TTL when exposed | provider split when exposed | provider split when exposed | otherwise `output_tokens` |
| openai-chat | `prompt_tokens − cached` | `prompt_tokens_details.cached_tokens`; write 0 | `completion_tokens − reported reasoning` | `completion_tokens_details.reasoning_tokens` when present | 0 when the split is complete |
| openai-responses | `input_tokens − cached` | `input_tokens_details.cached_tokens`; write 0 | `output_tokens − reported reasoning` | `output_tokens_details.reasoning_tokens` | 0 when the split is complete |
| gemini-generate-content | `promptTokenCount − cached` | `cachedContentTokenCount`; write 0 | `candidatesTokenCount` | `thoughtsTokenCount` | 0 when the split is complete |

If the raw response cannot distinguish a billing-relevant class (for example a
cache-write TTL), the adapter emits `pricing_status: unresolved` and the attempt
is excluded from the cost verdict until resolved. It must not guess a cheaper
class. A combined output may remain `output_unclassified` only when all possible
output classes share the same price; otherwise pricing is unresolved.
Provider-specific tool charges are retained separately from token cost.

### Cache protocol contract

The v1 protocol is `implicit_cache_observed_v1`: explicit provider cache objects
are prohibited, while provider-initiated implicit cache reads are observed and
recorded through `input_cache_read`. Therefore
`input_cache_write_5m` and `input_cache_write_1h` are zero for this protocol;
no cache-storage charge is eligible. Arms are randomized or counterbalanced and
each response retains its cache-read observation, so provider cache variation is
visible rather than mislabeled as a cold run. A future protocol that permits an
explicit cache requires a new `cache_protocol_id` and `price_schedule_id` with
the applicable cache-write/storage classes before it can contribute to the
primary verdict.

The current eligible Gemini 2.5 schedule is valid only through 2026-10-16.
Calibration or scoring on or after that date is blocked until a newly captured,
versioned eligible price schedule is preregistered.

Session totals are computed by **summing the canonical fields over every turn** —
the LLM API is stateless and exposes no session-total field. The per-turn source
is the response `usage` object (or, for Claude Code, the session `.jsonl`
transcript usage).

### Matrix shape (tidy / long)

One row per `(benchmark_run_id × task_attempt_id × turn_index × evaluated_model_revision)`:

```
benchmark_run_id, task_id, task_attempt_id, arm, seed,
repo_key, repo_revision, task_prompt_policy_id,
arm_policy_bundle_id, arm_policy_id,
execution_budget_policy_id, cache_protocol_id,
sequence_index, agent_id, agent_build_id,
evaluated_provider, evaluated_api_surface,
evaluated_model, evaluated_model_revision, evaluated_service_tier, turn_index,
usage_norm  { input_uncached, input_cache_read,
              input_cache_write_5m, input_cache_write_1h,
              output_visible, output_reasoning, output_unclassified,
              input_total, output_total, grand_total,
              token_cost_usd, tool_charges_usd, cost_usd,
              pricing_status },
raw_usage   { ...provider payload... },
response_meta { stop_reason, tool_call_count, output_chars },
adapter_id, adapter_version, price_schedule_id, schema_version
```

Long format (not wide) so later analysis can pivot by any axis (arm, turn, task
type) without a schema change. Stored harness-side in the `record-feedback!`
payload keyed by `task_attempt_id`; the aggregator first sums `usage_norm` per
task attempt, then rolls up by `(benchmark_run_id, task_id, arm)`.

### Invariants

- **Always store `raw_usage` + adapter/API/model/price versions.** Canonical
  fields are derived; raw is the source of truth. If a mapping or price schedule
  is later found wrong, re-derive from raw rather than re-running the suite.
- **Aggregate on `cost_usd`, not `grand_total`.** Cache reads may be materially
  cheaper, with the exact multiplier varying by provider/model/cache class, so an
  arm that caches aggressively is not necessarily more expensive even when its
  raw token count is higher. Comparing raw tokens across arms with different
  caching would be an artifact.

## Stage Execution Routing And Handoff

The table is the starting routing recommendation, not a permanent model lock.
High effort is explicitly allowed when justified by preregistration
irreversibility, pricing/aggregation ambiguity, benchmark-verdict or kill-rule
risk, conflicting evidence, or repeated verification failures. It does not
require a separate exception when this table or the preceding stage's handoff
recommends it. Bulk harness construction, fixture generation, repeated runs, and
mechanical report production should normally use medium effort.

| Stage | Recommended executor | Recommended primary model | Effort | Why |
| --- | --- | --- | --- | --- |
| 0 — preregistration and lock | Antigravity | Gemini 3.1 Pro | high | arm identities, price semantics, and the verdict rule become immutable before scoring |
| 1 — fidelity fix | Antigravity if reopened | Gemini 3.6 Flash | medium | the delivered regression is narrow and stage-local |
| 2 — task suite and harness | Antigravity | Gemini 3.6 Flash | medium | most work is bounded orchestration, adapters, fixtures, and isolation controls |
| 3 — aggregator | Antigravity | Gemini 3.1 Pro | high | attempt-first joins and normalized cost directly determine the verdict |
| 4 — evidence run/write-back | Antigravity | Gemini 3.1 Pro | high | score interpretation can trigger the Phase 1 success or kill decision |

Gemini 3.6 Flash should perform bulk run orchestration and mechanical report
generation inside Stage 4, while Gemini 3.1 Pro owns interpretation and
write-back. Gemini 3.5 Flash is the fallback for Gemini 3.6 Flash. Claude Sonnet
4.6 (Thinking) may perform a bounded independent review of Stages 0, 3, or 4;
Claude Opus 4.6 (Thinking) is reserved for unresolved validity or pricing
conflicts. GPT-OSS 120B (Medium) may provide an adversarial review but does not
own scoring or critical-path edits.

At the end of every stage, after verification and before the stage-closing
commit, the executing model must read:

1. the candidate next stage and its routing row in this plan, plus applicable
   coordination, preregistration, stop/kill, and dependent-plan gates;
2. the companion progress log, current `MEMORY.md`, and relevant `SPEC.md`
   decision rules;
3. the completed stage diff, verification results, unresolved findings, and
   current file ownership across parallel worktrees;
4. current executor/model availability and quota constraints.

It must then add a `NextStageRoutingRecommendation` to the progress log with:

```text
completed_stage, recommended_next_stage,
recommended_executor, recommended_model, effort,
effort_justification, rationale, prerequisites_or_blockers,
file_ownership_and_conflict_risk,
fallback_executor_or_model,
model_availability_checked_at, confidence
```

The recommendation may retain or override the table default, but any override
must cite stage evidence. A `high` recommendation must contain a concrete
`effort_justification`. If no companion progress log exists, Stage 0 creates it
before recording the recommendation. If a stop/kill rule fires or prerequisites
are absent, the model must recommend `stop` or `defer` instead of auto-starting
work. The handoff is recorded in the same commit as the stage progress update
and never bypasses an owner approval or admission gate. Because Stage 1 predates
this protocol, its first routing recommendation is produced when Stage 0 closes
or before Stage 2 starts; no historical recommendation is fabricated.

## Stages

Each stage ends with a commit and a progress-log update under `reports/`.

### Stage 0 — Pre-register arms, identities, metric, then pilot-then-lock

The provisional *moderate* threshold is recorded in `SPEC.md` §5.1: arm A uses
≥50% lower normalized cost (≥2×) than the competent `rg`+read baseline B at task
success within 5 percentage points of it, wall-clock ≤ 1.5× baseline, over ≥30
tasks including ≥1 external repository.

Before the pilot, freeze the A/B/C/D arm definitions, `BenchmarkRun` and
`TaskAttempt` schemas, suite version, shared task-prompt policy, arm-policy
bundle, execution-budget policy, cache protocol, usage-adapter versions, and
price schedule. C and D are reported as secondary controls; they cannot be
substituted silently for the preregistered B comparator.

Because a threshold chosen after seeing results cannot falsify anything, lock it
in two steps:

1. **Calibration pilot** (5–10 tasks): measure only the competent-baseline
   normalized cost and the success-metric noise floor. This produces no verdict.
2. **Lock**: fix the final threshold as "cheaper than the baseline by more than
   the measured noise at parity success", then run scoring. Never adjust the
   threshold after scoring begins.

### Stage 1 — Fidelity fix (completed)

Delivered: `resolve-context` no longer fabricates `:returned_tokens` from the
selection estimate. Selection records `estimated_tokens` and a nil measured
return; expand/detail record measured `returned_tokens`. Regression coverage
asserts those stage-specific semantics.

### Stage 2 — Task suite + four-arm harness

Define the task suite (≥ a small fixed set spanning task types) and at least one
external repo. Build the harness that runs each task through A/B/C/D, tags every
semidx call and every outcome with `benchmark_run_id` and `task_attempt_id`, and
writes outcomes via `record-feedback!`. For every turn of every arm, normalize
the agent's raw `usage` through its adapter into the response/usage matrix and
store it in the `record-feedback!` payload keyed by `task_attempt_id`, preserving
`raw_usage`.

Stage 2 must include the Zig negative-utility calibration slice before any
Phase 1 verdict run. These tasks are diagnostic, not a language-priority
commitment: they verify that the harness can record semidx losses against a
competent lexical baseline, distinguish low-confidence language ceilings from
ranking defects, and preserve the exact query/response evidence needed for a
later Zig value-recovery plan.

### Stage 3 — Aggregator command

Add an evaluation command (pattern: `run-weekly-review-report-command` in
`src/semidx/runtime/evaluation.clj`) that aggregates the response/usage matrix
per `task_attempt_id` before joining feedback outcomes, rolls up by
`(benchmark_run_id, task_id, arm)`, emits per-arm **success-per-cost**, and
applies the Stage 0 stop rule without a many-to-many join.
In-memory sink path must work without PostgreSQL.

### Stage 4 — First real-repo run and evidence write-back

Run the suite, produce the report, and write the result back into `SPEC.md`
§5.1. If the success signal is met, retag the phase; if the failure signal is
met, record the kill-criterion trigger.

## Metric definition

- **Primary (fix-quality variant)**: at parity task success, the
  percentage **cost** reduction (`cost_usd`, per the response/usage matrix) of arm
  A vs preregistered comparator B — **not** a raw token count, because provider
  caching makes raw tokens non-comparable across arms. C and D are reported as
  secondary controls and cannot silently replace B.
- **Guardrail**: wall-clock of A no worse than the baseline by more than a fixed
  factor (the staged round-trip tax; relieved by one-shot per plans/019).
- **Statistical floor**: significance across the suite (≥ N tasks × seeds;
  non-overlapping confidence, or an agreed test).
- **Cost source**: `usage_norm.cost_usd` in the response/usage matrix, summed per
  `task_attempt_id` and then rolled up by `(benchmark_run_id, task_id, arm)` —
  normalized from each agent's raw `usage` via its adapter, never from raw
  provider fields directly.
- **Success source**: `feedback_outcome` in `semantic_usage_feedback`.
- **semidx-internal cost** (packet tokens) stays available from
  `semantic_usage_events.payload` for diagnosing *where* an arm's cost went, but
  the scored denominator is the agent's total `cost_usd`.

## Stop / kill rules

Directly inherit `SPEC.md` §5.1:

- **Success signal**: A beats preregistered comparator B on success-at-parity
  per normalized cost by the pre-registered margin, above the statistical floor;
  C and D remain reported controls.
- **Failure signal**: A does not meet the preregistered cost/success gate against
  B → SPEC §5.1 kill-criterion (narrow to Clojure-first). C and D may strengthen
  the diagnosis but cannot rescue or redefine the primary verdict after scoring.

## Risks

- **Strawman baseline.** The 40k figure is likely a cold broad-read, not a
  competent `rg`+read agent. Arm B must be genuinely competent or a win is an
  artifact. This is the main threat to validity.
- **Positive-only corpus.** A suite that includes only tasks where structural
  retrieval is expected to win will overstate product value. Keep explicit
  negative-utility cases, including low-confidence language lanes, so failure
  modes are measured instead of hidden.
- **Self-repo bias.** Measuring only on semidx's own repo overstates the result;
  at least one external repo is required.
- **Fidelity.** Selection-stage cost is an estimate by design; expand/detail
  return measured values. Aggregation must not relabel either semantic.
- **Provider/pricing drift.** Usage schemas, cache classes, reasoning fields,
  service tiers, and prices can change. Raw usage plus immutable adapter and
  price-schedule versions are required for replayable cost calculations.
- **PostgreSQL optionality.** The aggregator must not require Postgres; the
  in-memory sink path is first-class.

## Definition Of Done

- The arm definitions, run/attempt identity, provisional threshold, metric,
  adapter versions, and price schedule are recorded before the pilot; the final
  threshold is locked after calibration and before scoring.
- The task suite includes negative-utility calibration cases for API-surface
  extraction, missing field/container facts, wrong blast-radius seed selection,
  and stale snapshot reuse.
- The `returned_tokens` fidelity bug is fixed with a regression test.
- The harness produces a reproducible A/B/C/D success-per-cost report on at
  least one external real repository, with C explicitly marked when unavailable.
- `SPEC.md` §5.1 can be retagged (pass) or its kill-criterion recorded (fail)
  from that report.

## References

- [`SPEC.md`](../SPEC.md) — value hypothesis §5.1 (decision of record).
- [`plans/018`](./018_semantic_provider_authority_migration_plan.md) — provider
  authority (fact quality).
- [`plans/019`](./019_llm_one_shot_context_delivery_and_evaluation_plan.md) —
  one-shot delivery and its comparison fixtures (consumer of this substrate).
- [`adr/024-make-compact-first-staged-retrieval-the-canonical-public-flow.md`](../adr/024-make-compact-first-staged-retrieval-the-canonical-public-flow.md)
- `src/semidx/core.clj` — `resolve-context` / `expand-context` event payloads.
- `src/semidx/runtime/usage_metrics.clj` — sink, event/feedback tables, rollups.
- `src/semidx/runtime/evaluation.clj` — evaluation-command pattern.
- [Anthropic pricing and cache classes](https://docs.anthropic.com/en/docs/about-claude/pricing)
- [OpenAI Responses usage contract](https://platform.openai.com/docs/api-reference/responses-streaming)
- [Gemini usage metadata](https://ai.google.dev/api/generate-content)
- [Gemini thinking-token pricing](https://ai.google.dev/gemini-api/docs/generate-content/thinking)
