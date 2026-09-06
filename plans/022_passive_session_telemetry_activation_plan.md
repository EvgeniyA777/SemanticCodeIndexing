---
title: "Passive Session Telemetry Activation Plan"
doc_type: "implementation_plan"
lifecycle: "active"
status: "planned"
agent_action: "reference_for_context"
updated: "2026-09-05"
---

# Plan: Passive Session Telemetry Activation

Turns [`ideas/016`](../ideas/016_session_telemetry_value_measurement.md) from a
concept into staged work. That document stays the reasoning of record; this plan
owns the execution.

Relationship to the paused comparative track: `plans/020` remains paused and its
artefacts stay untouched. This plan does **not** resume it, does not build an
agent, and does not make a provider call. What it borrows from `plans/020` is
only already-delivered machinery (staged cost semantics, rollups, aggregation).

## Goal

Make semidx's own retrieval cost and outcome observable during real work, using
the machinery that already exists, and find out on data — not by assumption —
what is actually missing before building anything new.

## Why this shape

The measurement infrastructure is largely built and switched off. Verified in
the working tree on 2026-09-05:

| Capability | Where | State |
| --- | --- | --- |
| Sink abstraction | `runtime/usage_metrics.clj`, `UsageMetricsSink` | exists |
| No-op / in-memory / PostgreSQL sinks | same namespace | exists |
| Default-off activation by env | `mcp/server.clj` builds a sink only when `SEMIDX_USAGE_METRICS_JDBC_URL` is set | exists |
| Failure isolation | `safe-record-event!` / `safe-record-feedback!` | exists |
| Event identity | `semantic_usage_events`: `session_id`, `task_id`, `trace_id`, `request_id`, `root_path_hash`, `confidence_level`, `result_status`, `latency_ms`, counts, `payload jsonb` | exists |
| Returned selection | `selected_unit_ids` / `selected_paths` in the payload (`core.clj`, `retrieval.clj`) | exists |
| Rollups, SLO, calibration, weekly review, replay harvest | `runtime/usage_metrics.clj` | exists |

So the first useful action is not to write code. It is to switch the existing
path on and look at what a real session actually produces.

## What semidx cannot measure, and what follows

semidx is an MCP server. It observes the tokens **it returned**, never what the
host model was billed: cache reads, cache writes, reasoning tokens, and priced
cost belong to the host session, not to this process.

Consequently a runtime event must not carry a `cost`, a cache split, or a
`price_schedule_id` as if semidx had measured them. Those fields are produced by
an **offline join** against the host transcript, which `ideas/016` measured to be
available and joinable for Claude Code sessions. Recording a number semidx did
not observe would be the same defect this repository has already recorded three
times in another form.

This also puts `SPEC.md` §5.1 in scope as an owner decision, not a task: its
North Star is stated as provider-priced task success per unit cost against a
preregistered baseline, which no observational record can produce on its own.

## Scope

In:

- activating the existing PostgreSQL usage sink on the interactive path;
- an inventory of what a real session actually writes;
- task identity, so events group into task attempts;
- one end-to-end offline join of a host transcript against semidx events;
- the surface gap found while planning (below).

Out:

- resuming `plans/020`, building an agent, or making any provider call;
- randomised A/B withholding (`ideas/016` describes it; it needs its own plan and
  a ground-truth decision);
- a value verdict of any kind — this plan produces distributions and a working
  join, never a pass/fail claim;
- the provider summary, which belongs to `plans/018` (see Stage 3);
- changing retrieval behaviour, ranking, or confidence.

## Verified gap found while planning

Five surfaces build a sink from `SEMIDX_USAGE_METRICS_JDBC_URL`:
`mcp/server.clj` (stdio), `runtime/http.clj`, `runtime/grpc.clj`, and the two
offline tools. **`mcp/http_server.clj` does not.** A host using the MCP
Streamable HTTP transport therefore produces no telemetry at all, silently. This
is a one-line-shaped gap, but it decides which sessions are observable, so it is
Stage 0 work rather than a footnote.

## Stages

### Stage 0. Activate and observe

Goal: find out what the existing path records during real work, before changing
anything.

Deliverables:

- A local PostgreSQL instance and the documented environment for enabling the
  sink, written down so a second machine can reproduce it.
- The MCP HTTP transport builds a sink like the other surfaces.
- One or more **real** working sessions run with the sink enabled.
- An inventory report under `reports/`: how many events, from which surfaces and
  operations, which identity fields were actually populated (especially
  `session_id` and `task_id`), what the `payload` contains, and what a
  `resolve_context` event looks like in full.
- An explicit privacy check: whether any payload carries prompt text or source
  code, and what is written by default.

Exit criteria:

- Events from a real session are present in `semantic_usage_events`.
- The inventory names, per field, whether it is populated, empty, or absent —
  measured, not assumed.
- The privacy check has an answer, and if raw text is recorded, that is a
  finding to fix before any wider collection.
- Nothing about retrieval behaviour changed.

Stop condition: if enabling the sink perturbs the interactive path in any
observable way — latency, errors, output — stop and report rather than tuning
around it.

Commit boundary: the MCP HTTP sink wiring, docs, and the inventory report.

### Stage 1. Task identity

Goal: make events group into task attempts, which is the unit of observation the
whole idea rests on.

Prerequisite: Stage 0's inventory, because the design depends on whether
`task_id` arrives today and from where.

Deliverables:

- A decision, recorded in this plan, on how a task boundary is declared in a
  real session — this is one of `ideas/016`'s open questions and cannot be
  guessed.
- Whatever minimal mechanism that decision implies, on the MCP path.
- Tests that events carry the identity, and that its absence degrades to
  ungrouped events rather than failing a request.

Exit criteria:

- Events from one real session group into task attempts.
- A session that sets nothing still works exactly as before.

Commit boundary: identity plumbing only; no aggregation, no verdict.

### Stage 2. Offline join, one session end to end

Goal: prove the join that both halves of the measurement depend on, on real
data, before building anything on top of it.

Deliverables:

- A read-only tool that takes one host session transcript plus the telemetry for
  that session and produces the joined record: per semidx call, what it returned
  and what the session spent around it.
- The load-bearing rule stated and applied: **was the file the agent read
  afterwards inside the returned selection?** `selected_paths` makes this
  computable today.
- A written report of where the join holds and where it breaks.

Exit criteria:

- One session is joined end to end and the failure modes are named.
- The re-query ambiguity `ideas/016` measured — the most common follow-up — is
  either separated or explicitly recorded as unresolved. It must not be scored
  as a miss by default.
- No verdict is emitted. `trace_verdict_policy_v1` may only be written after
  this stage shows what the data supports.

Stop condition: if the join does not hold on real data, stop and report. The
rest of the idea rests on it.

Commit boundary: the offline tool and its report; no runtime change.

### Stage 3. Provider summary — owned by `plans/018`

Recorded here for coordination only. The provider state, facts, gaps, conflicts,
latency, and reason codes belong to the provider pipeline and are Stage 6a work
in [`plans/018`](./018_semantic_provider_authority_migration_plan.md). This plan
is a **consumer**: once that summary exists, it becomes payload on the same
events, needing no new transport.

Deliberately not started here, so one owner keeps the provider contract.

## Verification

Per stage, narrowest first:

- `clojure -M:test` for any code change;
- `./scripts/validate-contracts.sh` if any recorded shape changes;
- for Stage 0, evidence is the inventory report, not a passing test;
- PostgreSQL work follows `RULES.md`: check for a running instance, restart
  cleanly, then run.

## Risks

### [High] Recording numbers semidx did not measure

Mitigation: cost, cache splits, and prices enter only through the offline join,
never as runtime event fields.

### [High] A verdict rule invented after looking at the data

Mitigation: `trace_verdict_policy_v1` is written and versioned before any
scoring, and Stage 2 explicitly produces no verdict.

### [Medium] Thin volume

`ideas/016` measured 72 semidx calls across 15 sessions. That is not a dataset,
and any pacing expectation should be set before anyone waits on a number.

### [Medium] Privacy

Mitigation: the Stage 0 inventory answers what is written; anything carrying
prompt text or source code by default is a finding, not a feature.

## Open owner decisions

1. How a task boundary is declared in a real session (blocks Stage 1).
2. Whether `SPEC.md` §5.1 is rewritten to state what observational evidence
   counts as a pass, since its current North Star cannot be produced without the
   comparative arms.
3. Which surfaces are in scope for collection: interactive MCP only, or library,
   HTTP, and gRPC as well.
4. Whether the paused `plans/020` artefacts stay available as a fallback.
