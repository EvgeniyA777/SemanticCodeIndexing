---
title: "Passive Session Telemetry — Stage 2 Offline Join"
doc_type: "progress_log"
lifecycle: "active"
status: "completed"
agent_action: "reference_for_context"
updated: "2026-09-06"
---

# Stage 2: The Offline Join, Measured On Real Transcripts

Companion log for
[`plans/022`](../plans/022_passive_session_telemetry_activation_plan.md).
Continues [`reports/026`](./026_passive_session_telemetry_stage0_inventory.md).

Stage 2 asked whether the join both halves of the measurement depend on holds up
on real data. It does, mechanically. What does **not** hold up is the verdict
rule `ideas/016` sketched on top of it, and that is the finding.

No verdict is emitted here, by design.

## What was built

`semidx.runtime.session-telemetry`, read-only, in two layers because
`ideas/016` requires one adapter per client while the rules stay shared:

- `parse-transcript` / `timeline` / `session-calls` know the Claude Code
  `.jsonl` shape;
- `classify-followup`, `boundary-comparison`, `follow-up-summary`, and
  `join-events` know only the neutral shape those produce.

## The join holds

Every input it needs exists on real data.

- **The returned selection is recoverable.** Transcripts retain the full MCP
  result per call, so `focus[].path` gives the selection the usage event does
  not carry (`reports/026`, Finding 2). The load-bearing rule — *was the file
  the agent read afterwards inside the returned selection?* — is computable
  today, with no new instrumentation.
- **Host token spend is there**, including the split semidx can never see:
  `input_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`,
  `output_tokens`.
- **Follow-up behaviour is there**, and the commands are readable, which matters
  because `Bash` is the general-purpose tool in these sessions: `rg` is a
  fallback, `clojure -M:test` is ordinary work.

## The join breaks: attribution has no boundary

> Superseded by the follow-up at the end of this report: the boundary was found
> rather than invented. The measurement below is why an arbitrary window was
> rejected, and is kept for that reason.

`ideas/016` says to read "what the agent did next" and never says where *next*
ends. Measured over the four sessions in this repository that used semidx, the
count of tool calls between one retrieval and the following one is:

```
07ab4279:  3, 72, 90
241fd815: 28, 90, 62
80ede42c:  9, 64
b87a418c:  0, 429
```

Attributing 429 calls to one retrieval describes the session, not the retrieval.
So the tool takes an explicit `:window`, and reports how much the answer moves
with it:

| session | window 3 | window 10 |
| --- | --- | --- |
| 241fd815 | `in_selection_read 3`, `lexical_search 1` | `in_selection_read 4`, `lexical_search 6`, **`out_of_selection_read 2`** |
| 07ab4279 | `lexical_search 2`, `other_bash 6` | `lexical_search 6`, `other_bash 15` |
| b87a418c | `out_of_selection_read 2` | `out_of_selection_read 3`, `other_tool 6` |

The same session reads as a miss or not depending on a number nobody has
justified. `out_of_selection_read` appears in 241fd815 only once the window
widens; `lexical_search` triples everywhere.

**Consequence: no verdict rule can be written until the attribution window is
decided, and it cannot be decided from four sessions.** Choosing it by looking
at which value produces a flattering distribution is exactly the failure
`ideas/016` warned about — rules invented after seeing the data fit it instead
of testing it.

## The volume is far below what a rule needs

Ten retrievals across four sessions. `ideas/016` measured 72 across 15 sessions
in August; the older transcripts have since rotated away. Either number is a
sample of behaviour, not a dataset, and this stage does not treat it as one.

## Re-query stays unresolved, as the plan required

Both remaining ambiguities are recorded rather than resolved:

- **Re-query.** Two of ten retrievals were followed immediately by another
  (`0` and `3` intervening calls). That is either "the first query missed" or
  "the agent moved to a second sub-question", and the data cannot separate them.
  It is classified as `:requery` and **never scored as a miss**.
- **`out_of_selection_read`.** Without a settled window it cannot be read as a
  ranking failure either.

## The database half is not yet exercisable

`join-events` pairs transcript retrievals with usage events positionally within
a session — there is no shared identifier, because the host does not send its
own session id and the events carry the server's — and it reports
`:join_basis "positional_within_session"` so a reader can see what the pairing
rests on. It is covered by tests.

It has **not** been run against real paired data, for a straightforward reason:
telemetry is only recorded when the MCP server is started with
`SEMIDX_USAGE_METRICS_JDBC_URL`, and the host that produced these transcripts
was not. Producing one truly paired session requires the owner to restart their
MCP server with the variable set; everything else is ready for it.

## Verification

- `clojure -M:test -n semidx.runtime.session-telemetry-test`: 7 tests, 27
  assertions, 0 failures. Driven by a synthetic transcript in the real shape, so
  the rules are tested rather than one machine's history.
- The measurements above come from the four real transcripts under
  `~/.claude/projects/-Users-ae-workspaces-semidx/`, read only.
- No query text, prompt, or source code is reproduced in this report.

## What this stage does not claim

That semidx helped, or did not. Stage 2 produces a working join and a
distribution; the verdict policy is a separate decision that now has two named
prerequisites: an attribution window with a basis, and enough sessions to test
it on.

## Recommended next step

Owner decision, not implementation:

1. **Start recording.** Restart the MCP server with
   `SEMIDX_USAGE_METRICS_JDBC_URL` set, so paired sessions accumulate. Nothing
   further can be validated without them.
2. **Set a collection window before analysing**, so the pacing is stated in
   advance rather than chosen once the numbers are visible.
3. Only then decide the attribution window and write
   `trace_verdict_policy_v1` against data that exists.

---

# Stage 2 Follow-up: The Turn Boundary (2026-09-06)

The original Stage 2 reported that attribution had no boundary and left it open.
It is now answered by taking an observed boundary instead of inventing one, and
by noticing that one question was standing for two.

## Staged continuation is linked, not windowed

`expand_context` and `fetch_context_detail` carry the `selection_id` the
retrieval returned. That is a reference, like a span to its parent, so the
staged flow needs no window at all — and the original heuristic was actively
wrong about it: a fixed window of 5 reported **two** staged continuations in a
session that had **one**, because the window reached into a neighbouring
retrieval.

## Everything else stops at the next user turn

The retrieval was made in service of one request; work after the next user
prompt answers a different one. The turn is separable in the transcript — one
session had 37 real user prompts against 481 tool-result records and 6 meta
records — and, unlike a declared task, it is not drawn by the agent.

Effect on the four real sessions, calls attributed per retrieval:

| session | unbounded (to next retrieval) | turn boundary |
| --- | --- | --- |
| 07ab4279 | 3, 72, 90 | 52, 48, 11 |
| 241fd815 | 28, 90, 62 | 81, 52, 37 |
| 80ede42c | 9, 64 | 74, 64 |
| b87a418c | 0, 453, 20 | 26, 25, 4 |

Range narrows from 0–453 to 4–81. The turn is not always smaller — a short gap
between two retrievals can sit inside a long turn — but it is always *the same
question*, which the unbounded count was not.

## Why the fixed windows are still computed

`boundary-comparison` keeps them as evidence for the choice, not as a knob. At
window 1 a session shows no out-of-selection read; at window 10 it shows two.
That sensitivity is the argument against any arbitrary number, so it stays
visible rather than being replaced by the new default.

## What this does not settle

The turn is a boundary, not a judgement. Who authors the verdict remains open,
and so does volume. A turn still holds 4–81 calls, so attribution inside it is
coarse — narrowing further (for example to the first edit) would be another
heuristic and is deliberately not done.

## Verification

- `clojure -M:test`: 638 tests, 3418 assertions, 0 failures.
- Figures above measured over the four real transcripts, read only.
