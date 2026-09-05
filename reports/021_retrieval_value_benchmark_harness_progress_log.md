---
title: "Retrieval Value Benchmark Harness Progress Log"
doc_type: "progress_log"
lifecycle: "active"
status: "blocked"
agent_action: "do_not_use_for_current_work"
updated: "2026-08-28"
---

# Progress Log: Retrieval Value Benchmark Harness

Companion log for
[`plans/020_retrieval_value_benchmark_harness_plan.md`](../plans/020_retrieval_value_benchmark_harness_plan.md).

## Stage Status

| Stage | Status |
| --- | --- |
| 0 — Pre-register arms/run identity/metric, pilot-then-lock threshold | pre-registration sub-gate completed (see `reports/023_retrieval_value_benchmark_preregistration.md`); calibration pilot and final-lock pending |
| 1 — Fidelity fix (`returned_tokens`) | completed |
| 2 — Task suite + four-arm harness | completed: corpus and harness delivered (2026-08-27), live evaluated-model runner delivered (2026-08-28) |
| 3 — Aggregator command | completed (2026-08-28) |
| 4 — First real-repo run + evidence write-back | blocked: Stage 0 calibration pilot and threshold lock (needs an evaluated-provider API key and budget approval) |

## Stage Routing Amendment (2026-08-03)

- `plans/020` now records a recommended executor, primary model, and effort for
  every stage. High effort is allowed and recommended for preregistration,
  aggregation, and evidence-verdict decisions where an error could invalidate
  the experiment or trigger the wrong kill decision.
- Every future stage closure must record a
  `NextStageRoutingRecommendation` after reading the candidate next stage,
  dependent-plan gates, progress/MEMORY/SPEC state, completed diff and checks,
  file ownership, and current model/quota constraints. A high-effort
  recommendation must include a concrete `effort_justification`.
- Stage 1 completed before this protocol. No retrospective routing result is
  invented; Stage 0 closure or Stage 2 admission produces the first handoff.

### Routing amendment verification

- `git diff --check`: passed.
- English-only scan over all revised documents: passed (no Cyrillic matches).
- Runtime tests were skipped because this amendment changes documentation and
  execution routing only; no source, contract artifact, or generated output was
  modified.
- CCC artifacts were not refreshed because the amendment does not require new
  compression output and `RULES.md` forbids routine per-task refreshes.

## Architecture Review Findings (accepted and fixed in plan documents)

The cross-plan review is recorded in
[`reports/022_latest_active_plans_architecture_review.md`](./022_latest_active_plans_architecture_review.md).

| Finding | Disposition | Plan-level fix |
| --- | --- | --- |
| `plans/019` and `plans/020` duplicated corpus/harness ownership | accepted, fixed | `plans/020` is the exclusive benchmark-substrate owner; `plans/019` supplies one-shot adapters only |
| Benchmark arms, cost terminology, and repeated-run identity were not preregistration-safe | accepted, fixed | A/B/C/D arms, B comparator, `BenchmarkRun`, `TaskAttempt`, and attempt-first aggregation are explicit |
| Arm D and the per-arm policy boundary were underspecified | accepted, fixed | arm roles, shared task prompt, versioned arm-policy bundle, per-attempt policy identity, isolation, cache protocol, and within-run controls are explicit |
| Provider usage adapters could misprice cache writes or reasoning output | accepted, fixed | versioned API/model adapters, cache TTL classes, visible/reasoning/unclassified output, tool charges, raw usage, and immutable price schedules are required |
| One-shot budgets and telemetry could double count | accepted, fixed in dependent plan | `plans/019` now has one top-level response ledger and aggregate/stage accounting scopes |
| Provider arbitration lacked a provider-neutral fact key | accepted, fixed in dependent plan | `plans/018` now gates arbitration on `CanonicalFactKey` and cross-provider identity fixtures |
| Active-plan metadata and continuation guidance were stale | accepted, fixed | `plans/020` is `in_progress`; `plans/007` points at current continuations |

### Architecture review verification

- `git diff --check`: passed.
- English-only scan over all revised documents: passed (no Cyrillic matches).
- `clojure -M:test` before the documentation-only revision: 289 tests, 1947
  assertions, 0 failures, 0 errors; no runtime source changed afterward.
- `clojure -M:ccc check --root .`: reported existing CCC artifacts as stale.
  Regeneration was skipped because this review does not require new compression
  output and `RULES.md` explicitly forbids routine per-task refreshes.

## Stage 1 — `returned_tokens` fidelity fix (completed)

### Finding (verified in REPL)

`semidx.core/resolve-context` recorded the selection-stage event payload with
`:returned_tokens (get-in result [:budget_summary :estimated_tokens])` — it
duplicated the estimate under a name that implies a measured return, while
`expand-context` records the real measured `[:budget_summary :returned_tokens]`.

Runtime inspection confirmed the selection-stage `result` (and its metadata)
exposes no separately measured returned figure: `:budget_summary` carries only
`requested_tokens`, `estimated_tokens`, `within_budget`, `remaining_tokens`, and
`reserved_budget`. So the honest figure at the selection stage is
`estimated_tokens`; there is no true measured return to record.

### Change

- `src/semidx/core.clj` — selection payload now reads `:returned_tokens` from the
  same real key `expand`/`detail` use (`[:budget_summary :returned_tokens]`),
  which is absent (`nil`) at the selection stage, instead of duplicating
  `estimated_tokens`. `estimated_tokens` remains the selection-stage cost.
  Comment added explaining the semantics.
- `test/semidx/runtime/usage_metrics_test.clj` — added
  `resolve-context-does-not-fabricate-returned-tokens-test`: selection records a
  positive `estimated_tokens` and a `nil` `returned_tokens`; expand records a
  non-negative measured `returned_tokens`.

### Semantics after fix

`returned_tokens` present ⟺ a stage produced a measured return (expand, detail).
`returned_tokens` absent/`nil` ⟺ selection stage; use `estimated_tokens` as its
cost. The benchmark aggregator (Stage 3) must consume `estimated_tokens` for the
selection stage and `returned_tokens` for expand/detail.

### Verification

- REPL (`clojure -M:nrepl`, port 49244): confirmed post-fix selection event
  payload has `returned_tokens = nil`, `estimated_tokens = 153`,
  `reserved_tokens = 180`.
- New test: 3 assertions, 0 failures.
- Existing `library-usage-metrics-flow-test`: 29 assertions, 0 failures.
- Full suite `clojure -M:test`: 289 tests, 1947 assertions, 0 failures, 0 errors.

### Notes / follow-up

- A deeper option — surfacing the pipeline's real measured selection-packet
  tokens (computed at MCP projection but not in the core `result`) into
  `retrieval.clj`'s result — was deliberately not taken. It is a larger change to
  a large file and is unnecessary: `estimated_tokens` is an honest selection-stage
  cost. Revisit only if the benchmark needs measured (not estimated) selection
  cost.

## Stage 0 — Pre-registration and Lock (in progress)

### Sub-gate: pre-registration document

Completed. The pre-registration is locked in
`reports/023_retrieval_value_benchmark_preregistration.md`.

Key decisions recorded:

- A/B/C/D arm definitions and verdict roles locked.
- `executor_model` vs `evaluated_provider_model` distinction explicit throughout.
- `BenchmarkRun` and `TaskAttempt` schemas locked; `TaskAttempt` fields use
  `evaluated_*` prefix.
- Cache protocol `implicit_cache_observed_v1`: explicit cache objects forbidden;
  implicit cache reads recorded at Cache Read rate; arm order is randomized or
  counterbalanced and cache variation is observable rather than labeled cold.
- Price schedule `2026-08-03-eligible-v1` covers only eligible evaluated models
  (`gemini-2.5-pro`, `gemini-2.5-flash`). Legacy models (`claude-3-5-sonnet-20240620`,
  `gpt-4o-2024-05-13`) moved to historical-only reference, excluded from v1 verdict.
- Arm D forbidden tools defined once and referenced by both policy and audit rule.
- Calibration pilot and final threshold lock pending.

### Iteration 5 contract amendment

- `plans/020` and this pre-registration now share the same `evaluated_*`
  `TaskAttempt` identity fields; executor-model routing remains separate.
- The cache protocol was renamed from a misleading cold-start claim to
  `implicit_cache_observed_v1`; explicit cache objects remain forbidden.
- Arm D uses a finite tool allowlist plus one finite command-prefix denylist for
  `bash`, and the audit consumes those same lists.
- The Gemini 2.5 eligible schedule expires on 2026-10-16. Pilot/scoring after
  that date requires a new preregistered schedule.

### Iteration 5 verification

- `git diff --check`: passed.
- English-only scan over the amended plan, pre-registration, progress log, and
  `MEMORY.md`: passed (no Cyrillic matches).
- Markdown links added by the amendment point to the official Gemini
  deprecation schedule; existing plan/report links remain unchanged.
- Runtime tests: skipped; this amendment changes plan and benchmark-admission
  documentation only, with no runtime source or contract artifact changes.
- CCC artifacts: not refreshed; `RULES.md` forbids routine per-task refreshes.

### Sub-gate: calibration pilot and final lock

Not started. Must complete before scoring (Stage 4).

### Iteration 4 verification

- `git diff --check`: passed.
- English-only scan (`grep -rnP '[\x{0400}-\x{04FF}]'` over both changed
  files): passed (no Cyrillic matches).
- Runtime tests: skipped; no runtime source was changed.
- CCC artifacts: not refreshed; `RULES.md` forbids routine per-task refreshes.

### NextStageRoutingRecommendation

```text
completed_stage: Stage 0 pre-registration sub-gate
recommended_next_stage: Stage 2 — Task suite + four-arm harness
recommended_executor: Antigravity
recommended_model:
  mechanical_implementation: Gemini 3.6 Flash (medium effort)
  contract_validation: Gemini 3.1 Pro (high effort)
  fallback: Gemini 3.5 Flash
effort: medium (default), high (contract validation only)
effort_justification: >
  High effort is required only for the contract validation pass:
  the cost-normalization adapter must correctly map implicit cache-read
  tokens from each provider's usage response to the Cache Read rate
  in the price schedule, and the aggregation roll-up
  (benchmark_run_id, task_id, arm) is contract-critical for the Phase 1
  verdict. A miscount silently produces wrong cost_usd values.
rationale: >
  plans/020 routing table (line 264) assigns Gemini 3.6 Flash / medium
  for Stage 2. These are executor models inside Antigravity and are
  unrelated to the evaluated_provider_model in TaskAttempt. The price
  schedule does not need to list executor models.
prerequisites_or_blockers: >
  Stage 2 harness may be built now. Stage 4 scoring must not start
  until the calibration pilot (Stage 0 sub-gate 2) is completed and
  the final threshold is locked.
file_ownership_and_conflict_risk: >
  Stage 2 will provisionally create files under benchmark/ or
  src/semidx/runtime/benchmark/. It will also need to read
  src/semidx/runtime/usage_metrics.clj and src/semidx/core.clj to
  conform to existing event schemas. Whether Stage 2 requires writes
  to those files is not yet determined — it depends on whether the
  existing usage-metrics sink can host benchmark events without
  schema changes. This is a risk to surface during Stage 2 admission,
  not a claim resolved here.
fallback_executor_or_model: Gemini 3.5 Flash (per plans/020 line 270)
model_availability_checked_at: >
  deferred — Gemini 3.6 Flash and Gemini 3.1 Pro availability inside
  Antigravity cannot be confirmed by checking ai.google.dev pricing.
  Actual availability will be confirmed at Stage 2 admission by the
  executor. If unavailable, defer to Gemini 3.5 Flash as fallback.
confidence: high (for the routing recommendation itself)
```

## Stage 2 — Task suite + four-arm harness (2026-08-27)

Status: harness and corpus delivered. A live evaluated-model runner binding is
explicitly **not** part of this stage; see "Known gaps" below.

### Delivered

| Artifact | Role |
| --- | --- |
| `fixtures/benchmark/task_suite_v1.edn` | frozen v1 corpus: 9 tasks, 9 task types, 2 repositories |
| `src/semidx/runtime/benchmark_suite.clj` | suite loading plus corpus invariants |
| `src/semidx/runtime/benchmark_usage.clj` | usage adapters, price schedule, matrix rows, attempt aggregation |
| `src/semidx/runtime/benchmark_harness.clj` | run/attempt identity, arm policies and audit, budget, workspace isolation, scoring, feedback write-back |
| `test/semidx/runtime/benchmark_{suite,usage,harness}_test.clj` | 55 tests, 180 assertions covering the above |

The corpus includes the external repository `aegis-zig`
(`/Users/ae/workspaces/Zig/aegis`, overridable via `SEMIDX_BENCH_AEGIS_PATH`),
so the suite is not measured only on semidx itself. All four required
negative-utility calibration cases from `reports/023` section 2.5 are present,
and their ground truth was read out of the actual Zig sources rather than
assumed (`Config` fields in `src/engine/actor.zig`, `ControlledRuntime`
definition and references across `src/control/` and `src/app/process.zig`).

### Decisions taken in this stage

- **Repository revisions are resolved at run time**, not pinned in the fixture.
  `new-benchmark-run` reads `git rev-parse HEAD` plus dirty state of the
  checkout and records them in the `BenchmarkRun`, so a run always reports the
  revision that was actually measured.
- **Attempt tagging** uses the existing usage-metrics context: `session_id` is
  the `benchmark_run_id` and `task_id` is the `task_attempt_id` on every semidx
  event of an attempt. This keys `semantic_usage_events` to the attempt that
  produced them without a many-to-many `task_id` join.
- **`attempt-trace` deliberately omits `agent_id`.** The retrieval query
  contract's `trace` (`semidx.contracts.schemas/trace-ref`) is a closed map, so
  an `agent_id` key would make every Arm A query fail validation instead of
  being measured. The agent is carried as `actor_id`. A regression test
  validates the emitted trace against `trace-ref` directly.
- **Scoring lives in the harness, never in a runner.** All arms are scored
  against the same task ground truth, so an arm cannot win by answering a
  different question. A runner may only report `error` or `not_applicable`
  (the latter requires its reason, matching the Arm C rule).
- **Audit and budget outcomes override a self-reported success.** A forbidden
  tool or a denylisted Arm D command sets the attempt to `error` with the
  preregistered reason `arm_d_forbidden_tool_violation`; other arms use
  `arm_tool_policy_violation`. Budget breaches use `execution_budget_exceeded`.
- **Excess context cost does not fail a correct answer**; it is recorded as
  `excess_context_cost` so the cost metric, not the success metric, carries it.
  **Stale snapshot reuse does fail the attempt** but is recorded separately
  (`stale_snapshot_reuse`) from ranking quality, as the plan requires.
- **Cost eligibility is enforced, not assumed.** A run started on or after the
  price schedule's eligible-until date is refused; a cache write under
  `implicit_cache_observed_v1` is `unresolved`; a historical-only model is
  `historical_only`; and one unresolved turn excludes the whole attempt from the
  cost verdict while preserving the reason.
- **One `BenchmarkRun` per repository**, because the preregistered schema binds
  a run to one repository revision. No field was added to the frozen schema;
  cross-repository pooling keys on suite version, arm-policy bundle, cache
  protocol, and price schedule.
- **Workspace isolation is mandatory for mutating tasks.** A task declaring a
  `workspace_mutation` refuses to run without isolation rather than silently
  degrading the freshness case into an ordinary retrieval case.

### Arm runner contract

One attempt is executed by an `ArmRunner`. `process-arm-runner` is the binding
point for a real evaluated model: the attempt context is written to the agent
process stdin as JSON and the result is read back from stdout as JSON.

Context (harness to agent): `benchmark_run_id`, `task_attempt_id`, `arm`,
`arm_policy_id`, `allowed_tools`, `command_denylist`, `prompt`,
`workspace_path`, `execution_budget`, `cache_protocol_id`, `usage_context`,
`trace`, plus the task and attempt identity blocks.

Result (agent to harness):

```json
{"outcome": "success | failure | error | not_applicable",
 "not_applicable_reason": "required when outcome is not_applicable",
 "wall_clock_ms": 1234,
 "tool_calls": [{"tool_id": "bash", "command": "rg Foo src"}],
 "turns": [{"turn_index": 0, "adapter_id": "gemini-generate-content",
            "raw_usage": {"promptTokenCount": 900, "candidatesTokenCount": 120},
            "response_meta": {"stop_reason": "stop"},
            "tool_charges_usd": 0}],
 "answer": {"paths": [], "symbols": [], "facts": [], "answer_text": "",
            "snapshot_id": "", "context_tokens": 0, "confidence_level": "high"}}
```

### Known gaps (not delivered by Stage 2)

- **No live evaluated-model runner.** The repository ships `scripted-arm-runner`
  (tests and dry runs, never contacts a provider) and `process-arm-runner` (the
  JSON bridge above). A concrete agent process implementing each arm policy must
  be delivered before any Stage 4 scoring run. Scoring also remains gated on the
  Stage 0 calibration pilot and final threshold lock.
- **No aggregator command.** Attempt-level aggregation exists
  (`aggregate-attempt-usage`); the roll-up to `(benchmark_run_id, task_id, arm)`
  and the stop-rule evaluation are Stage 3.
- **Zig calibration cases are defined but not yet measured.**
  `notes/2026-08-27-zig-negative-utility-triage.md` stays open; the harness now
  makes those failures recordable, it has not recorded them.
- **PostgreSQL payload round-trip unexercised.** The in-memory sink path is
  verified; Stage 3 must confirm the benchmark payload survives the `jsonb`
  round-trip, since one attempt payload carries its whole usage matrix.

### Stage 2 verification

- `clojure -M:test`: 387 tests, 2365 assertions, 0 failures, 0 errors.
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files.
- Compile probes after every source edit: passed.
- End-to-end tagging probe against a temporary fixture repository using the real
  `create_index` / `resolve_context` / `expand_context` path: every recorded
  event carried `surface=benchmark`, `session_id=<benchmark_run_id>`, and
  `task_id=<task_attempt_id>`. This probe is what surfaced the closed-`trace`
  finding above.
- `git diff --check`: passed.
- English-only scan over the new sources, fixture, and this log: passed.
- CCC artifacts were not refreshed; `RULES.md` forbids routine per-task
  refreshes and no compression output is required by this stage.

### NextStageRoutingRecommendation

```text
completed_stage: Stage 2 — Task suite + four-arm harness
recommended_next_stage: Stage 3 — Aggregator command
recommended_executor: Antigravity
recommended_model: Gemini 3.1 Pro
effort: high
effort_justification: >
  The aggregator decides the Phase 1 verdict. It must aggregate on
  task_attempt_id before joining feedback outcomes, must read
  estimated_tokens for the selection stage and returned_tokens for
  expand/detail (Stage 1 semantics), must aggregate on cost_usd rather
  than grand_total, and must drop attempts whose pricing_status is
  unresolved or historical_only instead of pricing them at zero. Each of
  those is a silent-wrong-number risk rather than a crash risk, so a
  cheaper pass would not be detectably wrong.
rationale: >
  Matches the plans/020 routing table for Stage 3. Stage 2 delivered the
  attempt-level aggregation and the feedback payload shape the aggregator
  consumes, so the remaining work is the roll-up, the in-memory sink path,
  and the Stage 0 stop rule.
prerequisites_or_blockers: >
  None for Stage 3 itself. Stage 4 scoring stays blocked on two separate
  gates: the Stage 0 calibration pilot plus final threshold lock, and a
  live evaluated-model runner implementing the arm runner contract above.
  The price schedule expires 2026-10-16; the harness now refuses to start
  a run on or after that date.
file_ownership_and_conflict_risk: >
  Stage 3 owns a new command in src/semidx/runtime/evaluation.clj and
  reads src/semidx/runtime/benchmark_usage.clj and usage_metrics.clj. The
  Stage 2 benchmark namespaces should not need edits; if the aggregator
  needs a new field, it must be added to the payload rather than to the
  frozen BenchmarkRun/TaskAttempt schemas.
fallback_executor_or_model: Gemini 3.5 Flash for mechanical parts only; the
  join and cost semantics should not be delegated to a fallback model.
model_availability_checked_at: >
  not checked in this session — executor/model availability inside
  Antigravity cannot be verified from this repository. Confirm at Stage 3
  admission.
confidence: high (for the routing recommendation itself)
```

## Stage 3 — Aggregator command (2026-08-28)

Status: delivered. The aggregator produces the per-arm success-per-cost report
and applies the Stage 0 stop rule. It does **not** produce a Phase 1 verdict:
the threshold lock is still a separate Stage 0 sub-gate.

### Delivered

| Artifact | Role |
| --- | --- |
| `src/semidx/runtime/benchmark_report.clj` | attempt-first aggregation, roll-up, per-arm success-per-cost, paired A/B comparison, pooling check, statistical floor, stop rule, semidx-internal token diagnostics |
| `src/semidx/runtime/evaluation.clj` | `benchmark-report` CLI command plus its argument parsing and the offline in-memory record path |
| `src/semidx/runtime/usage_metrics.clj` | `sink-events` / `sink-feedback` promoted to public readers |
| `test/semidx/runtime/benchmark_report_test.clj` | 26 tests, 86 assertions (plus a PostgreSQL round-trip test gated on `SEMIDX_TEST_POSTGRES_URL`) |
| `docs/runtime-api.md`, `README.md` | command documentation |

CLI:

```bash
clojure -M:eval benchmark-report --benchmark-records <records.json> --out <report.json>
clojure -M:eval benchmark-report --usage-metrics-jdbc-url <jdbc-url> --benchmark-run-id <id>
```

### Decisions taken in this stage

- **Aggregation lives in its own namespace, not in `evaluation.clj`.** The
  evaluation namespace is already ~2000 lines and owns policy governance;
  Stage 3 gives it only the CLI command. The frozen Stage 2 benchmark
  namespaces were not edited, as the Stage 2 handoff required.
- **The sink readers were made public instead of duplicated.** `sink-events`
  and `sink-feedback` already dispatch across the in-memory and PostgreSQL
  backends; the aggregator reads through them, so PostgreSQL stays optional and
  the in-memory path is first-class rather than a test-only shortcut.
- **Cost is re-derived from the stored response/usage matrix**, never read from
  the harness-recorded `usage_totals`. Raw usage plus versioned adapters and the
  immutable price schedule are the source of truth, so a wrong recorded total
  cannot silently become a verdict input. A disagreement is reported in
  `inputs.usage_totals_mismatches` instead of being resolved silently.
- **One recorded attempt is one observation.** Aggregation keys on
  `task_attempt_id`; a repeated record for the same attempt collapses to the
  latest occurrence and is counted, while a record with no `task_attempt_id` is
  rejected rather than folded into an arbitrary attempt. Roll-up keeps
  `benchmark_run_id` in the key so two runs of one task never merge.
- **Unpriceable attempts are excluded, not zero-priced.** `unresolved` and
  `historical_only` attempts leave the cost verdict with their reason retained,
  and an arm with no eligible attempt reports `total_cost_usd: null` rather
  than `0.0`. An attempt with no turns therefore cannot enter the cost verdict.
- **`not_applicable` leaves the success denominator.** It is the preregistered
  representation of an unavailable Arm C capability, so counting it as a loss
  would fabricate a failure; it is reported separately with its reasons.
- **Success-per-cost uses one attempt set for numerator and denominator.**
  Successes are counted over the cost-eligible attempts that produced the cost,
  so an arm cannot report successes that its denominator does not contain.
- **The primary comparison is paired per task.** Only tasks where both A and B
  produced a cost-eligible attempt enter it, and each task contributes the mean
  of its own attempts, so unequal seed counts and arm-specific task coverage
  cannot manufacture a cost win.
- **Pooling identities are verified, not assumed.** Suite version, harness
  version, prompt policy, arm-policy bundle, budget policy, cache protocol, and
  price schedule must agree; a mismatch blocks the verdict instead of silently
  pooling incomparable observations.
- **A verdict is withheld until the threshold is locked.** Without a locked
  threshold the report emits `verdict: "pending_threshold_lock"` plus a
  `provisional_signal`, and it names the blockers. An unmet statistical floor or
  a wall-clock breach yields `indeterminate`, never `failure`: SPEC 5.1 defines
  the kill criterion on cost and success only, so a small suite must not trigger
  it.
- **The semidx-internal token diagnostic preserves Stage 1 semantics.** The
  selection stage contributes `estimated_tokens` (it has no measured return) and
  expand/detail contribute measured `returned_tokens`. It is a diagnostic for
  where an arm's cost went; the scored denominator remains the agent's
  `cost_usd`.

### Known gaps (not delivered by Stage 3)

- **Stage 4 remains blocked on two independent gates**: the Stage 0 calibration
  pilot plus final threshold lock, and a live evaluated-model runner
  implementing the Stage 2 arm-runner contract. The aggregator enforces the
  first gate mechanically by refusing a verdict.
- **The Zig negative-utility cases are still unmeasured.** The report now
  surfaces `stale_snapshot_reuse` and `excess_context_cost` per arm, but no run
  has recorded them; `notes/2026-08-27-zig-negative-utility-triage.md` stays
  open.
- **Pre-existing failure discovered, not fixed**: with PostgreSQL enabled,
  `semidx.integration.runtime-test/postgres-storage-roundtrip-test` fails —
  a reload with `:load_latest true` returns 0 units against 163 in the fresh
  index (`test/semidx/integration/runtime_test.clj:2695`). It reproduces on a
  clean worktree at `22263d2` with no Stage 3 changes present, so it is not
  caused by this stage and was left alone as out of scope. It needs its own
  investigation: either a real PostgreSQL reload defect or an environment
  assumption the test does not state.

### Stage 3 verification

- `clojure -M:test`: 413 tests, 2451 assertions, 0 failures, 0 errors
  (Stage 2 baseline was 387 / 2365).
- `clojure -M:test` with `SEMIDX_TEST_POSTGRES_URL` pointed at a throwaway
  PostgreSQL 17.7 cluster: 413 tests, 2464 assertions, 1 failure — the
  pre-existing `postgres-storage-roundtrip-test` described above. The same
  single failure reproduces at `22263d2` (387 tests, 2371 assertions), which is
  how it was attributed. The benchmark payload round-trip test passed, closing
  the Stage 2 `jsonb` round-trip gap: the usage matrix survives the round trip
  and the PostgreSQL-backed report matches the in-memory report on
  `cost_ratio` and `tasks_compared`.
- REPL verification (`clojure -M:test:nrepl`, port 54669): inspected a full
  report for an A/B pair — `cost_reduction_pct` 86.7, `cost_ratio` 7.5,
  `success_delta_pp` 0.0, statistical floor failing at 2 of 30 tasks, and the
  verdict correctly withheld.
- CLI smoke: exported harness records through
  `clojure -M:eval benchmark-report --benchmark-records ...`; the JSON report
  rendered with the arm cost blocks, the paired comparison, and
  `external_repository_source: "suite"` resolved from the frozen fixture.
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files.
- Compile probes after every source edit: passed.
- `git diff --check`: passed.
- English-only scan over the new and changed sources and documents: passed.
- CCC artifacts were not refreshed; `RULES.md` forbids routine per-task
  refreshes and this stage requires no compression output.

### NextStageRoutingRecommendation

```text
completed_stage: Stage 3 — Aggregator command
recommended_next_stage: Stage 0 sub-gate 2 — calibration pilot and threshold
  lock (Stage 4 cannot start before it), and in parallel the live
  evaluated-model arm runner left open by Stage 2
recommended_executor: Antigravity
recommended_model: Gemini 3.1 Pro
effort: high
effort_justification: >
  The remaining work is the irreversible half of the experiment. The
  calibration pilot fixes the noise floor and the final threshold, and a
  threshold chosen or adjusted after seeing scored results falsifies
  nothing. The arm runner is equally load-bearing: an Arm B agent that is
  not genuinely competent turns the entire cost result into an artifact,
  which the plan names as the main threat to validity. Neither error would
  crash anything, so a cheaper pass would not be detectably wrong.
rationale: >
  Stage 3 closed the measurement path end to end, so nothing further can be
  learned from harness code alone. The aggregator already refuses a verdict
  while the threshold is unlocked, which makes the Stage 0 sub-gate the
  binding constraint rather than a formality.
prerequisites_or_blockers: >
  The price schedule 2026-08-03-eligible-v1 expires 2026-10-16 and the
  harness refuses to start a run on or after that date; a pilot run must
  either happen before it or preregister a new eligible schedule. The pilot
  also needs the live runner, since the scripted runner never contacts a
  provider and therefore cannot measure a baseline cost.
file_ownership_and_conflict_risk: >
  The pilot writes reports/023 (threshold lock section) and this log. The
  runner is new code behind the existing ArmRunner protocol and should not
  need edits to benchmark_harness.clj, benchmark_usage.clj, or
  benchmark_report.clj. If the runner needs a new recorded field, it belongs
  in the feedback payload, not in the frozen BenchmarkRun/TaskAttempt
  schemas.
fallback_executor_or_model: Gemini 3.5 Flash for mechanical runner plumbing
  only; the threshold lock must not be delegated to a fallback model.
model_availability_checked_at: >
  not checked in this session — executor availability inside Antigravity
  cannot be verified from this repository. Confirm at admission.
confidence: high (for the routing recommendation itself)
```

## Stage 3 Review Findings (2026-08-28)

External review of `22263d2` (Stage 2) and `1cda47b` (Stage 3). All three
findings were reproduced in the REPL before any change was made, and all three
are accepted and fixed.

| # | Severity | Finding | Disposition |
| --- | --- | --- | --- |
| 1 | high | a freshness-required task could pass without proving a fresh snapshot | accepted, fixed |
| 2 | high | required facts could be satisfied by an arbitrary substring | accepted, fixed |
| 3 | medium | the paired comparison weighted success by attempt count while weighting cost by task | accepted, fixed |

### Finding 1 — freshness passed without evidence

Reproduced: a freshness-required task with a correct path and symbol but no
`answer.snapshot_id` scored `outcome "success"` with
`stale_snapshot_reuse false`. The same held when the harness supplied no
`current_snapshot_id`. `score-answer` only flagged reuse when both ids were
present and unequal, so the absence of evidence read as evidence of freshness.

Fix (`src/semidx/runtime/benchmark_harness.clj`):

- an answer that reports no `snapshot_id` on a freshness-required task now fails
  with the distinct issue code `missing_snapshot_evidence`, kept separate from
  `stale_snapshot_reuse` because an absent snapshot is not reuse of a stale one;
- a run that cannot supply `current_snapshot_id` for such a task is refused with
  `benchmark_missing_current_snapshot_for_freshness_task` rather than scored.
  This follows the existing rule that a freshness case must not silently degrade
  into an ordinary retrieval case, and it blames the run configuration instead of
  the arm;
- `missing_snapshot_evidence` is recorded in the attempt payload and surfaced per
  arm in the Stage 3 report signals.

### Finding 2 — substring fact matching

Reproduced: required fact `ids` (a real entry of
`fixtures/benchmark/task_suite_v1.edn`) was satisfied by the answer text
"This merely forbids unsafe operations."

Fix (`src/semidx/runtime/benchmark_harness.clj`): `token-covered?` now matches
answer text on token boundaries instead of a bare substring. An explicit
`:symbols` / `:facts` entry still counts as an exact token. Qualified facts such
as `ActorEngine.init` still match in prose but are no longer satisfied by
`ActorEngine.initialize`. Prose answers stay viable for the lexical arms, which
matters because scoring must be uniform across arms.

### Finding 3 — success weighted by attempts, cost weighted by tasks

Reproduced: ten successful A attempts on one task and one failed A attempt on
another reported an A success rate of 0.909 where task-paired averaging gives
0.5, so the docstring's claim that unequal seed counts cannot weight one task
more held for cost but not for success.

Fix (`src/semidx/runtime/benchmark_report.clj`): `per-task-arm-stats` now carries
a per-task `success_rate`, and the paired comparison reports `success_rate` as
the mean of the per-task rates. `success_delta_pp` uses that task-weighted rate,
matching the cost weighting; `attempt_success_rate` retains the unweighted view
and carries the Wilson interval, whose trial count is the attempt count. The
comparison records `success_weighting: "task_mean"` so the weighting is explicit
in the artifact.

### Review-fix verification

- `clojure -M:test`: 420 tests, 2474 assertions, 0 failures, 0 errors
  (Stage 3 pre-fix baseline was 413 / 2451).
- New regression tests: 5 in `benchmark_harness_test.clj` (missing snapshot
  evidence, refusal without a current snapshot, the passing freshness case, token
  boundary matching for short and qualified facts) and 2 in
  `benchmark_report_test.clj` (task-weighted success with unequal seeds, and the
  per-arm `missing_snapshot_evidence` signal).
- Compile probes after every source edit: passed.
- `git diff --check`: passed.
- CCC artifacts were not refreshed; `RULES.md` forbids routine per-task
  refreshes.

## Stage 2 completion — live evaluated-model arm runner (2026-08-28)

Status: delivered. This closes the last Stage 2 gap and removes one of the two
gates in front of Stage 4. The remaining gate is the Stage 0 calibration pilot
and threshold lock, which needs an evaluated-provider API key and spend
approval and is therefore a decision, not a code task.

### Delivered

| Artifact | Role |
| --- | --- |
| `src/semidx/runtime/benchmark_agent.clj` | live agent: arm-scoped tool declarations, tool execution, budgeted model loop, provider usage capture, answer parsing, `ArmRunner` record, stdin/stdout process entry point |
| `deps.edn` | `:benchmark-agent` alias for the out-of-process contract |
| `test/semidx/runtime/benchmark_agent_test.clj` | 16 tests, 47 assertions, all driven by a stub provider that never reaches the network |
| `docs/runtime-api.md`, `README.md` | runner documentation and environment contract |

### Decisions taken

- **The agent owns answering, nothing else.** Identity, policy audit, scoring,
  budget enforcement, and cost normalization stay in the harness, so replacing
  the agent cannot change how an arm is judged.
- **Arms differ only by declared tools.** The model is offered exactly the tool
  declarations of its arm, so a breach needs a hallucinated function name rather
  than an available one. If it happens anyway the call is refused, still
  reported, and the harness audit fails the attempt. The runner never absorbs a
  policy breach.
- **An arm that cannot be run competently is refused before any provider call**:
  a lexical arm without `rg` (a strawman baseline is the plan's main validity
  threat), Arm C without `SEMIDX_BENCH_LSP_COMMAND` (the preregistered
  `not_applicable` case), and an attempt with no `evaluated_model_revision`
  (cost could not be priced). None of these spend tokens.
- **Cost-bearing facts are observed, not self-reported.** The answer's
  `snapshot_id` is the snapshot the runner actually retrieved and
  `context_tokens` is what the runner actually consumed; values the model puts
  in its JSON for those two fields are discarded. Raw `usageMetadata` is
  recorded per turn for the price schedule.
- **Tool output is bounded and workspace-contained.** Every path is resolved
  against the attempt workspace and a path that escapes it is refused, so one
  arm cannot read another attempt's tree.
- **Budget stops the loop rather than overrunning it.** At the tool-call or
  wall-clock limit the next request withdraws the tools so the model must
  answer; failing to answer is recorded as
  `execution_budget_exhausted_without_answer`.

### Known gaps

- **No live provider run has happened.** Every test drives a stub; the code has
  never contacted Gemini. The first real call is part of the calibration pilot
  and needs `GEMINI_API_KEY` plus explicit spend approval.
- **Arm C is effectively unavailable** until a language server command is
  configured. Until then Arm C attempts are `not_applicable` by design, which is
  preregistered but leaves the diagnostic control empty.
- **Index build cost is inside Arm A's wall clock.** The runner builds the index
  lazily on the first `resolve_context` call of an attempt, so a cold build is
  charged to the arm. In production use that cost is amortized across many
  queries. This must be stated when the wall-clock guardrail is read, and it is a
  candidate control for the pilot.
- **`lsp_definition` / `lsp_references` return an unavailable-capability result**
  rather than driving `semidx.runtime.lsp-client`; wiring them is only worth
  doing once a language server is actually configured for the corpus.

### Correction to the 2026-08-28 review fix

The first fix for review finding 1 required `snapshot_id` evidence from every
arm on a freshness task. That was wrong: Arms B, C, and D read the working tree
and have no snapshot to report, so the frozen `stale_snapshot_after_edit_v1`
task would have failed all three automatically while the corpus expects Arm B to
solve it by reading the current tree.

Corrected rule, now in `score-answer`:

- snapshot evidence is required only from snapshot-bearing arms (those whose
  policy includes `resolve_context` / `expand_context` / `fetch_context_detail`),
  and an unknown arm fails closed;
- a volunteered snapshot is checked against the current one for **every** arm, so
  an arm that reports a stale snapshot still fails;
- the refusal for a missing `current_snapshot_id` now fires only when scoring a
  snapshot-bearing arm.

`run-attempt!` passes the attempt's arm into scoring. Regression coverage:
`freshness-evidence-is-required-only-from-snapshot-bearing-arms-test`.

### Verification

- `clojure -M:test`: 437 tests, 2533 assertions, 0 failures, 0 errors
  (previous baseline 420 / 2474).
- Process-contract smoke: an Arm C attempt context piped into
  `clojure -M:benchmark-agent` returned
  `{"outcome":"not_applicable","not_applicable_reason":"no language server configured for arm C..."}`
  on stdout, with no provider call.
- Harness integration test: `live-arm-runner` driven by a stub provider through
  `harness/run-attempt!` produced a scored `success`, a two-row usage matrix
  priced as `resolved` with a positive `cost_usd`, and a feedback record keyed to
  the attempt.
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files.
- Compile probes after every source edit: passed. `git diff --check`: passed.
- English-only scan over new sources and documents: passed.

### NextStageRoutingRecommendation

```text
completed_stage: Stage 2 residual — live evaluated-model arm runner
recommended_next_stage: Stage 0 sub-gate 2 — calibration pilot (5-10 tasks,
  Arm B cost and success noise floor) followed by the final threshold lock
recommended_executor: human decision first, then Antigravity
recommended_model: Gemini 3.1 Pro
effort: high
effort_justification: >
  The pilot fixes the noise floor and the threshold that decide the Phase 1
  verdict, and a threshold adjusted after scoring falsifies nothing. The
  first live run is also the first time the arm policies meet a real model,
  so the failure modes that matter (Arm B competence, tool-call loops,
  answer format compliance) surface here rather than in scoring.
prerequisites_or_blockers: >
  Blocked on a human decision, not on code: an evaluated-provider API key
  (GEMINI_API_KEY) and approval to spend on real provider calls. The price
  schedule 2026-08-03-eligible-v1 expires 2026-10-16 and the harness refuses
  runs on or after that date. Arm C stays not_applicable until
  SEMIDX_BENCH_LSP_COMMAND is configured.
file_ownership_and_conflict_risk: >
  The pilot writes reports/023 (threshold lock section) and this log, and may
  tune prompt or budget constants in benchmark_agent.clj. Prompt or budget
  changes made after the pilot must be treated as a new arm-policy bundle
  version, not as a silent edit.
fallback_executor_or_model: none for the threshold lock; it must not be
  delegated to a fallback model.
model_availability_checked_at: >
  not checked in this session.
confidence: high (for the routing recommendation itself)
```

## Track Paused (2026-08-28)

The owner paused the whole `plans/020` track after the arm runner landed.

Reason, in the owner's terms: the value question should be answered by counting
tokens during real working sessions — semidx helped or it did not, count the
tokens either way, aggregate by task and by process, and analyse the data
afterwards — rather than by a staged four-arm experiment driven by a
purpose-built agent. The staged design answers a comparative question under
controlled conditions; the owner wants observational evidence from actual work.
Which of those the project should invest in is an open question the owner is
thinking through, so no successor stage is recommended here.

State at pause:

- Stages 1, 2, and 3 are delivered and committed (`22263d2`, `1cda47b`,
  `cabf19e`, `2a946bd`); the full suite is green at 437 tests / 2533 assertions.
- Stage 0's calibration pilot and threshold lock were never run, and no live
  provider call has ever been made.
- No benchmark run, scored or unscored, exists. Nothing has been written back
  into `SPEC.md` 5.1, and the Phase 1 verdict remains unproven.
- The delivered code is inert unless a runner is invoked deliberately; it is
  left in place rather than reverted.

Do not resume from the stage table above without the owner. The
`NextStageRoutingRecommendation` blocks recorded earlier describe what the
paused plan would have done next; they are not an instruction to continue.
