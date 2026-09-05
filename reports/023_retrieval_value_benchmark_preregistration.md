---
title: "Retrieval Value Benchmark Pre-registration (Stage 0)"
doc_type: "report"
lifecycle: "active"
status: "blocked"
agent_action: "do_not_use_for_current_work"
updated: "2026-08-28"
---

# Retrieval Value Benchmark Pre-registration (Stage 0)

> **Paused by the owner on 2026-08-28** together with the whole `plans/020`
> track. The calibration pilot and the final threshold lock were never run and
> no live provider call was ever made. Nothing recorded here has been used to
> score anything. See `reports/021` for the pause reason and the state at pause.

This document satisfies the pre-registration sub-gate of Stage 0 for
`plans/020_retrieval_value_benchmark_harness_plan.md`. It formally freezes the
benchmark experimental design and schemas before the calibration pilot begins.
The final threshold lock will occur after the pilot.

**Key distinction:** The *executor model* (the model running inside Antigravity
to build and operate the harness code) is entirely separate from the
*evaluated provider model* (the model that runs as the agent inside each
benchmark `TaskAttempt`). The price schedule in §4 covers only evaluated
provider models. The executor model is governed by the routing recommendation in
the companion progress log.

## 1. Arm Definitions

| Arm | Name | Versioned Policy / Tooling | Verdict Role |
| --- | --- | --- | --- |
| **A** | semidx staged | Canonical staged semidx flow (`v1`). One-shot adapters (`plans/019`) may be registered as `A2` in future runs. | Candidate |
| **B** | Competent lexical | `rg` (ripgrep) queries plus bounded targeted file reading. No semantic tools. | Primary comparator |
| **C** | Language navigation | Arm B tools plus LSP/SCIP definition/reference navigation where available. | Diagnostic control |
| **D** | Native no-index | The agent's native repository-browsing policy (full shell/filesystem access). See §2 Arm D Forbidden Tools for the canonical exclusion list and audit rule. | Ecological control |

*Rule:* Arm B is the primary comparator. C and D cannot be silently folded into
B or used to rescue a primary verdict. For Arm C, if LSP/SCIP is unavailable on
the target repo, the attempt outcome must be recorded as `not_applicable` with a
required `not_applicable_reason` string explaining why.

## 2. Experimental Controls and Cache Protocol

These policy IDs represent the immutable definitions for the `v1` benchmark
suite.

### Task Prompt Policy (`task_prompt_policy_id: agent_default_v1`)

```text
You are an autonomous AI agent assigned to resolve an issue in this repository.
Use your available tools to explore the codebase, analyze the problem, and implement a solution.
```

### Arm Policy Bundle (`arm_policy_bundle_id: harness_v1`)

- **Arm A** allowed tools: `resolve_context`, `expand_context`, `fetch_context_detail`.
- **Arm B** allowed tools: `grep_search`, `list_dir`, `view_file`.
- **Arm C** allowed tools: Arm B tools + `lsp_definition`, `lsp_references`.
- **Arm D** allowed tools: `bash`, `grep_search`, `list_dir`, `view_file`.

### Arm D Tool Policy (canonical lists)

Arm D's allowed tool IDs are the finite allowlist declared above: `bash`,
`grep_search`, `list_dir`, and `view_file`. Any other tool ID fails the attempt
with `arm_d_forbidden_tool_violation`.

For the allowed `bash` tool, the following command/service prefixes are the
finite semantic-navigation denylist. This same list governs the command-log
audit rule.

- `semidx`
- `scip`
- `sourcegraph` and `sg`
- `codeql`
- `clojure -M:mcp` and `clojure -M:mcp-http`

**Audit rule:** After each Arm D attempt, the harness scans tool IDs against the
allowlist and `bash` command records against the denylist. A match sets the
attempt outcome to `error` with the reason `"arm_d_forbidden_tool_violation"`.

### Execution Budget (`execution_budget_policy_id: budget_v1`)

- Max wall-clock time per task attempt: 300 seconds.
- Max tool calls per task attempt: 30.

### Cache Protocol (`cache_protocol_id: implicit_cache_observed_v1`)

- **Explicit caching is forbidden:** The harness must not create explicit Gemini
  `CachedContent` objects, Anthropic extended-TTL cache entries, or any other
  provider-managed cache objects during a benchmark attempt.
- If a provider returns implicit cache-read tokens (e.g., OpenAI automatic
  prompt caching, or Gemini implicit context caching), those tokens are recorded
  in `input_cache_read` and billed at the Cache Read rate from §4. Because no
  explicit cache object is created, `input_cache_write_5m` and
  `input_cache_write_1h` are zero and no storage cost accrues.
- The harness preserves the cache-read observation per response and randomizes
  or counterbalances arm order. Implicit cache variation is therefore visible in
  the recorded cost rather than misrepresented as a cold-start invariant.

## 2.5 Negative-Utility Calibration Cases

The v1 suite must include cases that are expected to expose semidx losses as
well as wins. These cases are diagnostic calibration inputs: they verify that
the harness records false negatives, wrong context, and excess cost against a
competent baseline instead of optimizing only for favorable examples.

Required initial cases:

| Case | Language | Primary failure mode | Comparator requirement | Scoring rule |
| --- | --- | --- | --- | --- |
| `zig_api_surface_signatures_v1` | Zig | `skeletons` returns more context than needed for public signatures | Arm B must use targeted `rg` patterns for public declarations, not whole-file reads | Arm A wins only if returned context is compact and sufficient; otherwise excess cost is recorded |
| `zig_struct_config_fields_v1` | Zig | Missing container/config field facts | Arm B may combine targeted lexical search with bounded reads around the struct and init/start functions | Missing required fields is a false negative |
| `zig_controlled_runtime_blast_radius_v1` | Zig | `impact_analysis` or staged selection seeds an unrelated module | Arm B must produce exact references or bounded source links for the named runtime symbol | Unrelated seed selection is a failure, even if impact output is non-empty |
| `stale_snapshot_after_edit_v1` | Any supported language with a compact fixture | A pre-edit snapshot is reused after material file changes | Arm B reads the current working tree | Stale snapshot reuse is recorded separately from ranking quality |

These cases do not promote Zig above its current low confidence ceiling. They
exist to isolate missing facts, ranking defects, stale-snapshot behavior, and
projection-size problems before any later Zig value-recovery work.

## 3. Schemas (Run and Attempt Identity)

```text
BenchmarkRun {
  benchmark_run_id: string
  suite_version: string
  started_at: timestamp
  repo_key: string
  repo_revision: string
  dirty_state: boolean
  task_prompt_policy_id: string
  arm_policy_bundle_id: string
  execution_budget_policy_id: string
  cache_protocol_id: string
  price_schedule_id: string
  harness_version: string
}

TaskAttempt {
  benchmark_run_id: string
  task_id: string
  task_attempt_id: string
  arm: string ("A" | "B" | "C" | "D")
  arm_policy_id: string
  sequence_index: int
  seed: int
  agent_id: string
  agent_build_id: string
  evaluated_provider: string
  evaluated_api_surface: string
  evaluated_model: string
  evaluated_model_revision: string
  evaluated_service_tier: string
  outcome: string ("success" | "failure" | "error" | "not_applicable")
  not_applicable_reason: string | null  ; required when outcome = "not_applicable"; null otherwise
}
```

Field names use `evaluated_*` prefix to make clear these describe the model
under test, not the executor model building the harness.

Aggregation keys on `task_attempt_id` first, then rolls up to
`(benchmark_run_id, task_id, arm)`.

## 4. Evaluated Provider Model Price Schedule

This schedule covers only models eligible to serve as the evaluated agent in
benchmark attempts. The executor model (e.g., Gemini 3.6 Flash running inside
Antigravity to build the harness) is not listed here and is not billed through
this schedule.

**Price Schedule ID:** `2026-08-03-eligible-v1`
**Currency:** USD
**Token Unit:** Per 1,000,000 tokens (1M tokens)
**Capture Time:** 2026-08-03T01:02:00Z
**Eligible Until:** 2026-10-16 (Gemini 2.5 shutdown date; see the official
[Gemini deprecation schedule](https://ai.google.dev/gemini-api/docs/deprecations?hl=en))

### 4.1. Eligible Model Price Table (Locked as of 2026-08-03)

*(An evaluated_model_revision must exactly match a row in this table; otherwise
the attempt gets `pricing_status: unresolved` and is excluded from the cost
verdict until resolved.)*

| Provider | API Surface | Model Revision | Service Tier | Context Tier | Input (Uncached) | Input (Cache Read / implicit) | Output (incl. reasoning) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Google | `generate-content` | `gemini-2.5-pro` | `on-demand` | `<= 200k` | $1.25 / 1M | $0.125 / 1M | $10.00 / 1M |
| Google | `generate-content` | `gemini-2.5-pro` | `on-demand` | `> 200k` | $2.50 / 1M | $0.25 / 1M | $15.00 / 1M |
| Google | `generate-content` | `gemini-2.5-flash` | `on-demand` | `any` | $0.30 / 1M | $0.03 / 1M | $2.50 / 1M |

*(Official Source: [Google AI pricing](https://ai.google.dev/gemini-api/docs/pricing) — fetched 2026-08-03)*

**Cache billing under `implicit_cache_observed_v1`:** Because explicit cache
objects are forbidden (§2), there is no Cache Write / Storage column. The
"Cache Read / implicit" column covers only provider-initiated implicit caching.
If a future run policy permits explicit caching, a new price schedule version
must add Cache Write and Storage columns with exact rates and TTL rules before
that run is eligible for the cost verdict.

No calibration pilot or scoring run may start on or after the eligible-until
date above. A later run must preregister a new eligible price schedule before
admission.

### 4.2. Historical-Only Reference Rates (not eligible for v1 verdict)

The following rates are recorded for reference only. These model revisions are
legacy and will not be used as the evaluated model in v1 benchmark runs. Any
attempt using them receives `pricing_status: historical_only` and is excluded
from the primary cost verdict.

| Provider | API Surface | Model Revision | Input | Output | Note |
| --- | --- | --- | --- | --- | --- |
| Anthropic | `messages` | `claude-3-5-sonnet-20240620` | $3.00 / 1M | $15.00 / 1M | Legacy; superseded by Claude 3.5+ |
| OpenAI | `chat` / `responses` | `gpt-4o-2024-05-13` | $5.00 / 1M | $15.00 / 1M | Legacy; superseded by GPT-4o+ |

### 4.3. Usage Adapter Mapping (`adapter_version: v1`)

| Adapter / Provider | Uncached Input | Cache Read (implicit) | Visible Output | Reasoning Output | Unclassified |
| --- | --- | --- | --- | --- | --- |
| `gemini-generate-content` | `promptTokenCount` - `cachedContentTokenCount` | `cachedContentTokenCount` | `candidatesTokenCount` | `thoughtsTokenCount` | `0` |

Additional adapters (`anthropic-messages`, `openai-chat`, `openai-responses`)
may be added to a future price schedule version when eligible model revisions
for those providers are locked and priced.

*Rule:* If the raw response cannot distinguish a billing-relevant class, the
adapter emits `pricing_status: unresolved` and the attempt is excluded from the
cost verdict until resolved.

## 5. Pilot and Final Lock Rule

Before the actual scoring run, a **Calibration Pilot** will be conducted:
- **Scope:** 5–10 tasks.
- **Measurements:** Cost of the competent baseline (Arm B) and the
  success-metric noise floor.
- **Action:** No verdict is produced here. The pilot exists solely to calibrate
  the "noise" and "baseline cost".

**Final Lock Rule:**
Immediately after the pilot and *before* scoring, the final threshold must be
locked. It will be defined as:
> *Arm A is cheaper than Arm B by more than the measured noise floor at parity
> success.*
(Provisionally: >=50% lower cost, success within 5 percentage points,
wall-clock <= 1.5x).
