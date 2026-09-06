---
title: "Passive Session Telemetry — Stage 0 Inventory and Stage 1a"
doc_type: "progress_log"
lifecycle: "active"
status: "completed"
agent_action: "reference_for_context"
updated: "2026-09-05"
---

# Stage 0 Inventory: What Real Sessions Actually Record

Companion log for
[`plans/022`](../plans/022_passive_session_telemetry_activation_plan.md).

Stage 0 asked one question: with the existing sink switched on, what does a real
session actually write? Everything below is measured against a live PostgreSQL
instance and a real MCP server, not read off the source.

## What was done

- Local PostgreSQL 17 started; database `semidx_telemetry` created.
- `mcp/http_server.clj` now builds a sink from the environment like the other
  surfaces (the gap named in the plan), plus a regression test.
- A real `clojure -M:mcp` stdio server was driven over JSON-RPC through the
  canonical flow: `initialize` → `create_index` → `resolve_context` (twice, once
  with a client-supplied `trace` and once without) → `expand_context` →
  `fetch_context_detail`.
- A real `clojure -M:mcp-http` server was driven over Streamable HTTP through
  `initialize` → `create_index`.

Both servers were started with only `SEMIDX_USAGE_METRICS_JDBC_URL` set. No
schema was written by hand: `init-usage-metrics!` created
`semantic_usage_events`, `semantic_usage_feedback`, and
`semantic_usage_daily_rollups` on first write.

Honest limit: the client was a script driving the real server, not a live agent
session. Everything about the **server** path is real; what a specific host
populates can only be confirmed by that host.

## Field population, measured

| Operation | events | session_id | task_id | trace_id | request_id | actor_id | root_path_hash | confidence_level |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `server_start` | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `create_index` | 1 | 1 | 0 | 0 | 0 | 1 | 1 | 0 |
| `resolve_context` | 2 | **1** | 1 | 2 | 2 | 2 | 2 | 2 |
| `expand_context` | 1 | 1 | 0 | 0 | 0 | 1 | 1 | 0 |
| `fetch_context_detail` | 1 | 1 | 0 | 0 | 0 | 1 | 1 | 1 |

Daily rollups populated themselves correctly, and the feedback table stayed
empty — expected, since nothing produces feedback yet.

## Finding 1 (High) — `resolve_context` loses the session id

The single most important operation is the one that drops session identity.

Measured values in one session:

| operation | session_id |
| --- | --- |
| `create_index` | `0db78c74-…` (server session) |
| `resolve_context` **without** a client `trace` | **null** |
| `resolve_context` **with** a client `trace` | `telemetry-probe-session` |
| `expand_context` | `0db78c74-…` |
| `fetch_context_detail` | `0db78c74-…` |

The query's `trace` **overwrites** the server session id rather than filling it
in, so a client that sends no trace leaves the field empty for exactly the calls
that matter, while its neighbours in the same session carry the server id.

Consequence: events from one session do not group today, and this is not fixed
by supplying `task_id` alone — the grouping key itself is inconsistent between
operations. This lands in Stage 1, and it is a defect rather than a design
choice: the trace should refine identity, not erase it.

## Finding 2 (High) — the selection is not in the telemetry

`plans/022` Stage 2 rests on one rule: *was the file the agent read afterwards
inside the returned selection?* The plan assumed `selected_paths` /
`selected_unit_ids` were on the event payload, because they exist in
`core.clj` and `retrieval.clj`.

They are not on the MCP path. The full `resolve_context` payload is:

```
index_id, snapshot_id, selection_id, policy_id, policy_version,
estimated_tokens, requested_tokens, query_normalized, query_ingress_mode,
recommended_action, continuation_artifact, normalized_query_summary
```

The library path records the selected ids; the MCP tool event does not. So the
join rule is **not computable from the database alone**.

It is still computable overall: `ideas/016` measured that host transcripts retain
the full MCP result for every call, paired by `tool_use_id`. The selection comes
from the transcript, and telemetry supplies cost, confidence, and status. Stage 2
must be written against that shape rather than the assumed one.

## Finding 3 (Medium) — the raw intent text is recorded by default

`normalized_query_summary.details` stores the user's query verbatim. From this
run:

```json
"normalized_query_summary": {
  "details": "where is the provider overlay failure taxonomy defined",
  "purpose": "code_understanding",
  "target_keys": ["diff_summary"],
  "token_budget": 3200,
  "include_tests": false
}
```

No source code is recorded anywhere: `expand_context` and
`fetch_context_detail` payloads carry only identifiers and counts
(`estimated_tokens`, `warning_count`, `degradation_count`,
`impact_related_tests`), despite both operations returning code to the caller.

So the privacy answer is specific: **prompt text yes, source code no**. Against
the owner's stated constraint — no full prompt or code by default — the intent
text is a finding to decide on before wider collection. Options are to hash it,
truncate it, or gate it behind an explicit opt-in; that is a decision, not a
cleanup.

## Finding 4 (fixed) — the MCP HTTP transport recorded nothing

Confirmed and fixed in this stage. `mcp/http_server.clj` built no sink, so a host
on the Streamable HTTP transport produced no telemetry at all — silently, because
an absent sink is indistinguishable from a quiet session.

After the fix, verified live: `server_start` is recorded with
`payload.transport = "http"`, and a session-scoped `create_index` carries the MCP
session id and the client's `clientInfo` name as `actor_id`. Covered by
`http-sessions-record-usage-metrics-test`.

## What was not done, deliberately

No `task_id` mechanics, no cost or price fields, no verdict, no provider summary,
and no schema widened "just in case" — per the owner's Stage 0 boundary.

## Enabling it

```bash
brew services start postgresql@17
createdb semidx_telemetry
SEMIDX_USAGE_METRICS_JDBC_URL='jdbc:postgresql://localhost:5432/semidx_telemetry' \
  clojure -M:mcp
```

`SEMIDX_USAGE_METRICS_DB_USER` and `SEMIDX_USAGE_METRICS_DB_PASSWORD` are
optional. Without the JDBC URL nothing is recorded, on every surface.

## Verification

- `clojure -M:test -n semidx.mcp.http-server-test`: 3 tests, 56 assertions, 0
  failures.
- Live evidence is the database contents quoted above.
- Interactive behaviour was unchanged: every tool call in both sessions returned
  its normal result with the sink enabled.

## Recommended next step

Stage 1, but its scope is now larger than "add `task_id`": Finding 1 must be
fixed first, because supplying a task id on top of an inconsistent session id
would produce grouped events inside a session that does not group. Finding 3
needs an owner decision before collection widens beyond this machine.

---

# Stage 1a: Session Identity And Privacy Hardening (2026-09-05)

Status: **complete**. Owner turned the two open findings into an input gate for
task identity, so both were fixed before any `task_id` work.

## Finding 1, fixed — session id precedence

`record-mcp-event!` merged the request's fields over the session's own, so the
`nil`s that `usage-fields-for-query` produces for a query without a trace
overwrote what the server already knew, and a client-supplied `session_id`
replaced the server's outright.

Two rules now hold, both stated in the function:

- fields the request did not supply are dropped before merging, so a trace
  **refines** identity and never erases it;
- the **server session id wins outright**, because it is the identity of the MCP
  session itself.

Verified on the live database by re-running the same session:

| operation | session_id | task_id |
| --- | --- | --- |
| `create_index` | `19fbee28-…` | |
| `resolve_context` (no trace) | `19fbee28-…` | |
| `resolve_context` (trace with its own session id) | `19fbee28-…` | `telemetry-probe-task` |
| `expand_context` | `19fbee28-…` | |
| `fetch_context_detail` | `19fbee28-…` | |

All five events share one session id, and the client's trace fields survive.
Before the fix, the two `resolve_context` rows carried `null` and
`telemetry-probe-session` respectively while their neighbours carried the server
id.

## Finding 3, fixed — query text redacted by default

Telemetry now stores `details_hash` and `details_chars` in place of the user's
words, keeping `purpose`, `target_keys`, `token_budget`, and `include_tests`.
That is enough to tell queries apart, spot repeats, and correlate with a host
transcript that does hold the text.

Raw text is opt-in through `SEMIDX_USAGE_METRICS_CAPTURE_QUERY_TEXT=1`.

Redaction applies to telemetry only. The `normalized_query_summary` returned
**to the caller** is unchanged — a client asking what its query normalized to
still gets an answer — and a test asserts exactly that, because the tempting
implementation redacts both.

Verified live: `normalized_query_summary.details` is empty for every recorded
event, with `details_chars` 54 and 46 and distinct hashes.

## Tests

`semidx.mcp.usage-identity-test`, 8 tests / 27 assertions, covering the three
precedence cases the owner specified, redaction defaults, the opt-in, an
end-to-end event carrying no query text, and the caller's own summary staying
intact.

Two test defects were caught by writing them: the `status` assertion revealed
that a canonical query with a non-UUID `trace_id` and no `targets` is rejected,
so the identity tests had been exercising the error path rather than the success
path.

## Verification

- `clojure -M:test`: **623 tests, 3370 assertions, 0 failures, 0 errors**
  (was 615 / 3343).
- Live re-run of the same MCP session against PostgreSQL, quoted above.

## Not done, deliberately

`task_id` mechanics (Stage 1b), `selected_paths` on the runtime event (owner
decision: it stays a Stage 2 offline-join concern), cost, and verdicts.
