---
title: "fetch_context_detail Repeats Four Diagnostic Blocks in One Response"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-09-10"
---

# fetch_context_detail repeats four diagnostic blocks in one response

**Confirmed defect:** a single `fetch_context_detail` response carries the same
diagnostic material twice — the stage table, `capabilities`, `confidence`, and
the retrieval-policy/query summary each appear once under the top level or
`context_packet` and again under `diagnostics_trace`. The per-stage
`budget_summary` repeats the same five figures in every stage record.

**Additional accounting observation:** `budget.returned_tokens` reports 1226 for
a response whose code payload is 148 tokens, and the serialized JSON delivered
to the client is larger than either number. Whether `returned_tokens` is meant
to describe the payload or the whole response is not established here; as it
reads today it describes neither.

## Environment

- Consumer: a Java/Python application repository, 170 files, 1094 units.
  Substitute any checkout root when replaying.
- Index: `6afe5254-fc21-42db-968f-945c8a2767f5`.
- Snapshot: `629b1109-760f-4652-accd-7f51eb081c0a`.
- `api_version` in the response: `1.0`. Local semidx checkout HEAD when filing:
  `0b81e03`; the running MCP process's source revision was not established.
- Provider state in that index: `scip-java` `result: "failed"`,
  `uncovered: 169`, all 1086 facts `heuristic`. That degradation is reported
  separately and is not claimed to cause this response shape.

## Query and result

```json
{
  "index_id": "6afe5254-fc21-42db-968f-945c8a2767f5",
  "selection_id": "9edb7d7f-da6c-4eed-aee5-c877bbed61f0",
  "snapshot_id": "629b1109-760f-4652-accd-7f51eb081c0a",
  "detail_level": "enclosing_unit",
  "unit_ids": ["<two method unit ids from the retained selection>"]
}
```

The response's own counters:

| Field | Value |
| --- | --- |
| `raw_fetch_snippets` | 2 |
| `raw_fetch_bytes` | 591 |
| `raw_fetch_required_tokens` | 148 |
| `budget.estimated_tokens` | 1176 |
| `budget.returned_tokens` | 1226 |

Duplicated blocks in that one response:

1. **Stage records.** Top-level `stage_events[]` and `diagnostics_trace.stages[]`
   both list the same six stages — `query_validation`,
   `candidate_generation`, `ranking`, `context_packet_assembly`,
   `raw_code_fetch`, `result_finalization` — with the same statuses, counters
   and durations.
2. **`capabilities`.** `context_packet.capabilities` and
   `diagnostics_trace.capabilities`.
3. **`confidence`.** `context_packet.confidence` and
   `diagnostics_trace.confidence`, including the same `reasons`, `warnings` and
   `missing_evidence` arrays.
4. **`retrieval_policy` and the query summary.** Once under `context_packet`,
   once under `diagnostics_trace`.

In addition, every entry of `stage_events[]` embeds a full `budget_summary`
carrying the same five numbers, so the budget figures appear six more times.

Expected: each diagnostic block appears once, or the diagnostic envelope is
suppressible by the caller.
Actual: four blocks duplicated, and roughly an order of magnitude more envelope
than payload for a two-span fetch.

## Impact and focused follow-up

The cost lands on exactly the path the staged-retrieval contract wants agents to
use: compact selection first, detail fetch late and narrow. A caller that
follows the contract precisely still pays for the same stage table twice.

`RULES.md` already tells agents not to route narrow reads through
`fetch_context_detail` for this reason, and
`notes/2026-08-29-guard-blocks-non-code-under-src-and-repo-map-envelope-cost.md`
item 3 records the same class of cost for `repo_map`. This report is separable
from both: the duplication is internal to one response and does not depend on
how the tool is used.

Suggested follow-up, in order of value:

1. A request parameter — for example `diagnostics: "off" | "summary" | "full"`,
   defaulting to `"summary"` — so a caller can ask for the spans alone.
2. One canonical home for stage records. If `diagnostics_trace` owns them, drop
   the top-level `stage_events`, or the reverse.
3. Hoist `budget_summary` to the response level instead of repeating it inside
   every stage record.
4. Decide what `budget.returned_tokens` measures and make it match, or rename it
   to say that it is payload-only. Today it understates what the caller pays,
   which makes the tool's own token accounting unusable for budgeting.

## Verification limits

Observed once, in one session, with `detail_level: "enclosing_unit"` and two
`unit_ids`. Whether the duplication scales with `unit_ids`, or differs at other
`detail_level` values or with `include_impact_hints` disabled, was not tested.
No semidx source was changed and no semidx tests were run.
