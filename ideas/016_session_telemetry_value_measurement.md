---
title: "Session Telemetry as the Value Measurement"
doc_type: "idea"
lifecycle: "concept"
status: "draft"
agent_action: "use_as_input_for_future_plan_only"
updated: "2026-08-28"
---

# Session Telemetry as the Value Measurement

Replacement core idea for how semidx proves its worth. It supersedes the
measurement approach of
[`plans/020`](../plans/020_retrieval_value_benchmark_harness_plan.md), which is
paused (see [`reports/021`](../reports/021_retrieval_value_benchmark_harness_progress_log.md)).

## The idea

Measure semidx during real work instead of staging an experiment beside it.

Every session already spends tokens. While the work happens, record what each
semidx call cost, whether it actually helped, and which task and process it
belonged to. Aggregate by task and by process, then analyse the accumulated
record. semidx worked — count the tokens. semidx did not work — count the
tokens anyway. The evidence is the sum of real sessions, not a controlled run.

The unit of observation is the **task attempt inside a real session**, not a
corpus entry. Nothing is authored for the purpose of being measured.

## Why this instead of the four-arm harness

- **The corpus problem disappears.** A frozen 9-task suite measures what its
  author chose to include; a live stream measures what the repository actually
  gets asked. Corpus bias was the paused plan's own listed risk.
- **No synthetic agent is required.** The paused design needed a purpose-built
  agent to be a credible baseline, which put an LLM tool loop and a provider
  client inside a code-indexing library. Passive telemetry needs neither.
- **No provider spend to get started.** The four-arm design cannot produce a
  single number without paying for baseline arms.
- **Most of the machinery already exists.** `semantic_usage_events` records per
  stage cost and confidence, `semantic_usage_feedback` records outcomes,
  rollups and the weekly review report already aggregate them, and the staged
  cost semantics were fixed in `plans/020` Stage 1 (selection carries
  `estimated_tokens`; expand and detail carry measured `returned_tokens`).

## What must be recorded

| Signal | Source | State |
| --- | --- | --- |
| semidx packet cost per stage | `semantic_usage_events.payload` | exists |
| retrieval confidence, result status, degradations | `semantic_usage_events` | exists |
| task and session identity | usage context (`session_id`, `task_id`) | exists, but real sessions must actually set it |
| worked / did not work | harvested from session traces, written through `semantic_usage_feedback` | sink exists; the harvester does not |
| failure shape when it did not work | `retrieval_issue_codes` | vocabulary exists; needs to reflect real failures |
| the agent's own token spend | host session transcript, not semidx | **missing** |

Two questions decided whether this idea works at all. One is now answered, the
other is measured below.

**Who says it worked — decided (2026-08-28).** Nobody is asked. An agent
already leaves traces of how the index behaved: what it found, what it missed,
and where the agent stumbled afterwards. Those traces are harvested, stored, and
processed. See "Harvesting the verdict from traces" below.

**Whose tokens.** semidx can only see the tokens it returned; it cannot see what
the host model was billed. Total session cost lives in the host's transcript
(for Claude Code, the session `.jsonl` usage records that the `ccbox` skill
already reads). Correlating semidx events with host session usage is the piece
that turns "packet tokens" into "what the session actually cost".

## Harvesting the verdict from traces

The verdict is reconstructed from what the session already recorded. Three
layers of trace exist, in decreasing reliability.

**1. semidx's own account (structured, already stored).** Every stage event
carries `confidence_level`, `result_status`, degradations, warnings,
`truncation_count`, `fallback_units`, `raw_fetch_level`, and the
`selected_unit_ids` / `selected_paths` it actually returned. This says how the
retrieval went mechanically, and it is the ground the other layers are read
against.

**2. What the agent did next (behavioural, derivable from the session log).**
The tool sequence after a semidx call is the honest verdict:

| Trace after a semidx call | Reading |
| --- | --- |
| answered or edited with no further search | hit |
| read a file **that was in the selection** | hit — this is the prescribed flow, not a fallback |
| read files **outside** the selection to find the answer | miss: ranking put the wrong thing first |
| ran a lexical search over the same question | miss: the packet did not answer it |
| re-queried semidx with a reformulated intent | the earlier query missed |
| empty result, error, or degraded status, then manual work | mechanical failure |

The second row is the trap. This project's own rules tell an agent to locate
with semidx and then read the exact lines it is about to patch, so a naive
"a file read after retrieval means semidx failed" rule would score the designed
workflow as failure. Whether the read path was inside the returned selection is
what separates a hit from a miss.

**3. What the agent said (natural language, noisy).** Agents narrate their own
stumbles — "semidx did not find it, reading manually", "confidence low, falling
back". Too unreliable to score with, but it is usually the only place the
*reason* is recorded, so it feeds the failure taxonomy rather than the verdict.

### Consequences

- **The rules are a versioned policy, not a script.** They must be written down
  and versioned (`trace_verdict_policy_v1`) before harvesting, because a changed
  rule silently rewrites the meaning of everything already collected, and rules
  invented after looking at the data fit it instead of testing it.
- **One adapter per client.** Claude Code, Antigravity, and Codex leave
  differently shaped traces; the verdict rules are shared, the extraction is not.
  A local substrate already exists for the Claude Code shape (the `ccbox`
  session-log skills).
- **This finally feeds machinery that has been idle.** `record-feedback!`,
  `retrieval_issue_codes`, and the calibration report were built for a feedback
  source that real sessions never produced. Harvested traces are that source.
- **The most valuable single output is calibration, not a win rate**: when
  semidx reported `confidence: high`, did the agent still go around it? A
  confident-but-bypassed retrieval is a ranking defect the current metrics
  cannot see.

## Feasibility probe (measured 2026-08-28)

Read-only pass over the 15 local Claude Code transcripts for this repository,
to check whether the traces this idea depends on actually exist rather than
assuming they do:

| Question | Measured |
| --- | --- |
| Is host token spend recorded? | yes — 3152 usage-bearing records |
| Are semidx calls recorded with their arguments? | yes — 72 semidx tool calls, 36 of them `resolve_context` |
| Are semidx **results** retained? | yes — all 72, paired to the call by `tool_use_id`, full JSON including `snapshot_id` and the returned selection |
| What does the agent do next? | another `resolve_context` 22×, `fetch_context_detail` 16×, `Bash` 16×, `Read` 11× |

Three consequences follow directly.

- **The join needed for both halves — cost and verdict — is available offline
  today.** Because results are retained, the load-bearing rule ("was the file
  the agent read afterwards inside the returned selection?") is computable from
  existing logs with no new instrumentation. Collection can start on history,
  not only on future sessions.
- **The volume is thin.** 72 semidx calls across 15 sessions is not a dataset.
  Any value claim needs either far heavier usage or a long collection window,
  and that pacing should be stated before anyone waits on a verdict.
- **The most common follow-up is a re-query**, which the draft rules above would
  read as "the first query missed". It may equally be legitimate exploration of
  a second sub-question. The rules cannot ship until those two are separated —
  for example by comparing the intents and targets of consecutive queries. This
  is the first concrete thing `trace_verdict_policy_v1` has to get right, and it
  is now a question about observed data rather than a hypothetical.

`Bash` as a follow-up is ambiguous for the same reason: it is the
general-purpose tool in these sessions (588 calls), so the rule must read the
command — a lexical search is a fallback, a test run or a git command is not.

## What this can and cannot answer

It can answer, from real work:

- what semidx costs per task, and how that distribution behaves in the tail;
- how often it helps, and the taxonomy of how it fails;
- whether either trend improves as the product changes.

It cannot answer, on its own: **what the same work would have cost without
semidx.** There is no counterfactual in an observational record, and the
comparison is confounded — semidx gets asked about the tasks somebody thought it
would suit, and those are not average tasks. This is the honest cost of dropping
the arms.

### Randomised A/B on tasks

The causal claim is recovered by withholding the index. Each task is assigned to
one of two arms: the agent works **with** semidx, or the agent works **without**
it and solves the task by ordinary means. Both arms are real work in real
sessions, and the benchmark is the accumulated comparison of what each arm
spent. No second corpus, no second agent, no staged run.

The assignment unit is the **task, not the query**. Withholding retrieval for a
sampled fraction of queries inside one task only measures query-level cost and
leaves the agent half-equipped; withholding it for the whole task is what
produces two comparable ways of doing the same kind of work.

Design constraints, all of which have to hold for the comparison to mean
anything:

- **Assignment is random and recorded before the work starts.** The arm is part
  of the event stream, not reconstructed afterwards, or the sample selects
  itself — the hard tasks quietly end up in the arm somebody thought needed the
  index.
- **Each arm gets its own clone of the repository.** Two checkouts of the same
  revision, the same task, the same wording. The working tree of one arm is
  never what the other arm sees, so the same task genuinely can be run twice and
  the comparison becomes **paired**. Pairing is what removes the dominant
  confounder: spend varies far more between tasks than between arms, and a
  paired difference cancels the task out instead of having to out-sample it.
  The isolation machinery for this already exists —
  `harness/prepare-attempt-workspace!` clones a repository at a revision into a
  fresh temporary workspace per attempt.
- **Contamination moves from the tree into the operator.** With clones the files
  are clean, but knowledge is not: whoever runs the second arm may already know
  the answer. For an LLM agent this is cheaper to close than it looks (see
  "Isolating agent memory" below); for a human operator it cannot be closed at
  all, only diluted by randomising arm order and by running enough tasks.
- **The model is not deterministic**, so one run per arm measures noise as much
  as arms. Repeats per arm with recorded seeds are part of the design; the
  paused harness already carries `seed` and `sequence_index` for exactly this.
- **There is no blinding.** The operator knows which arm is running and may
  work a no-index task harder, or abandon it sooner. This cannot be removed
  from self-experimentation; it can only be recorded and kept in view.
- **The no-index arm needs its own success signal.** The trace-based verdict
  above is semidx-centric and simply does not exist when semidx was not used, so
  task success in that arm has to come from the outcome of the work itself. This
  is where a ground truth per task becomes unavoidable: comparing two arms
  requires the same acceptance check for both. That is a corpus by another name,
  and it should be admitted rather than worked around.

  The cheap way to build one without authoring puzzles is to take tasks from the
  repository's **own history**: a real commit or pull request becomes the task,
  its message or issue becomes the wording, the parent revision becomes the
  starting clone, and the tests that commit added or changed become the
  acceptance check. The tasks are then real work that actually happened, and the
  success verdict is objective and arm-neutral.

### Isolating agent memory

Cloning to two distinct paths does most of this work by itself, because the
agent's persistent state is namespaced by working directory. Claude Code keys
both its auto-memory directory and its session transcripts by the project path
with separators replaced by dashes — `~/.claude/projects/-Users-ae-workspaces-semidx/`
for this repository, one directory per project (16 of them on this machine at
the time of writing). Two clones at different paths therefore get separate
memory and separate transcripts without any extra setup.

The rest follows from the clones:

- `MEMORY.md`, the rule files, and any handoff document live inside the clone,
  so one arm's writes are invisible to the other. Both arms start from identical
  copies, and identical is a constant, not a contamination.
- The user-level global instruction file is shared by both arms, which likewise
  makes it a constant that cannot favour one arm.
- If stricter isolation is wanted, the agent's config directory can be pointed
  somewhere per-arm for the duration of a run. That is a belt-and-braces
  measure, not a prerequisite.

No second machine is required. What a second machine would isolate — the human
running both arms — is exactly the channel it cannot fix.

Withholding is cheap to implement: a task-level switch that makes the semidx
tools unavailable for that task and records the assignment as an event. The
expensive part is the discipline around it, not the code.

What this design does **not** need is the part of `plans/020` that caused the
pause: no purpose-built agent and no provider client inside semidx. The agent is
whichever real agent the owner already works with, and its token spend is read
afterwards from its own session transcript, which the probe above confirms is
recorded and joinable. What the paused work does still contribute is workspace
cloning, run and attempt identity, provider usage normalisation, and the
aggregator.

A weaker and free alternative is a **cutover comparison** — the same repository
and task mix before and after a capability lands — which controls nothing but
costs nothing.

`SPEC.md` §5.1 currently states its success and failure signals comparatively
(arm A against a competent `rg` baseline). If session telemetry becomes the
primary evidence path, that section has to be rewritten to state what
observational evidence would count as a pass — otherwise the project keeps a
verdict rule that no planned measurement can produce. That rewrite is a
decision of record and belongs to the owner.

## Smallest useful slice

1. Make real sessions set `session_id` and `task_id` so events group into task
   attempts at all.
2. Write `trace_verdict_policy_v1` down before harvesting: which trace patterns
   mean hit, miss, and mechanical failure, including the in-selection read rule.
3. Correlate one host session transcript with its semidx events end to end, and
   see whether the join holds up on real data.
4. Only then aggregate, and only report distributions, never a verdict.

## Open questions for the owner

- Which trace signals are in scope for `trace_verdict_policy_v1`, and does a
  disagreement between semidx's self-reported confidence and the behavioural
  verdict count as a defect to act on?
- How is a task boundary declared in a real session, since both the telemetry
  grouping and the A/B assignment depend on it?
- What counts as success for a task run **without** semidx, where no retrieval
  trace exists to read?
- What task volume is acceptable before a comparison is reported, given that
  between-task variance will dominate early?
- Which surfaces count: only the MCP path used interactively, or library and
  HTTP consumers as well?
- Do the paused four-arm artefacts stay available as a fallback, or does the
  comparative approach get dropped outright?

## References

- [`plans/020`](../plans/020_retrieval_value_benchmark_harness_plan.md) — paused comparative design.
- [`reports/021`](../reports/021_retrieval_value_benchmark_harness_progress_log.md) — what was delivered before the pause.
- [`SPEC.md`](../SPEC.md) §5.1 — the value hypothesis and its current verdict rule.
- `src/semidx/runtime/usage_metrics.clj` — events, feedback, rollups, reports.
