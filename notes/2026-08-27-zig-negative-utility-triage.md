---
title: "Zig Negative-Utility Triage"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-08-27"
---

# Zig Negative-Utility Triage

## Status

This is a triage note from session-log measurements, not yet a complete
reproducible defect report. The exact MCP query bodies, selection ids, response
diagnostics, and original Zig repository snapshot are still required before
source fixes should be implemented from this note alone.

## Reported Evidence

- Language: Zig.
- Public confidence ceiling: `low`.
- Provider posture: ZLS document symbols are authoritative only for definitions
  and ownership; imports and calls remain bounded lexical facts.
- Reported snapshot: an older snapshot id was reused after roughly 5,700 new
  lines, so stale-index behavior may be mixed with retrieval quality.

## Reported Failures

1. API-surface/signature extraction:
   - Actual: `skeletons` returned a materially larger payload than targeted
     lexical extraction.
   - Expected: signature-only use cases should have a compact API-surface
     projection, or guidance should direct callers to the cheaper lexical
     baseline for low-confidence lanes.

2. Container/config field discovery:
   - Actual: `resolve_context` plus `expand_context` did not answer a query for
     `ActorEngine.Config` fields and init/start behavior.
   - Expected: either field/container facts are indexed, or the response clearly
     reports that this Zig lane does not provide those facts.

3. Blast-radius discovery:
   - Actual: `impact_analysis` reportedly seeded from unrelated Ollama-adapter
     symbols for a `ControlledRuntime` query.
   - Expected: impact analysis should refuse or degrade when exact seed
     resolution is missing, especially on a low-confidence language lane.

4. Snapshot freshness:
   - Actual: the agent continued from a stale snapshot after substantial edits.
   - Expected: benchmark and agent workflow evidence must separate stale
     snapshot reuse from ranking/retrieval defects.

## Required Repro Data

For each case, capture:

- exact `create_index` request, root path, active languages, and snapshot id;
- exact `resolve_context`, `skeletons`, or `impact_analysis` request body;
- relevant response fields: `selection_id`, `confidence_level`,
  `result_status`, `budget_summary`, diagnostics, guardrails, and selected
  paths/symbols;
- expected unit ids/paths/symbols and the competent lexical baseline query;
- whether a structured retry was attempted.

## Planned Routing

- `plans/020`: add these cases to the negative-utility benchmark corpus.
- `impact_analysis`: add a seed-confidence guardrail before returning blast
  radius hints from an ambiguous or unrelated focus.
- `plans/019`: consider an API-surface/signature-only projection.
- Provider authority/Zig follow-up: add missing field/container/reference facts
  only after the benchmark fixtures isolate the failing behavior.
