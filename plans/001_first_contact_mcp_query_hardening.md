---
title: "First-Contact MCP Query Hardening"
doc_type: "implementation_plan"
lifecycle: "archived"
agent_action: "historical_reference_only"
updated: "2026-08-02"
file_type: working-note
topic: first-contact-mcp-query-hardening
created_at: 2026-03-11T15:30:00-0700
author: ae
language: en
status: cancelled
---

# Archived Plan: First-Contact MCP Query Hardening

Superseded by the delivered implementation now reflected in the runtime, tests, and canonical docs. This note remains only as a historical checkpoint for the original implementation sketch.

Current source-of-truth references:

- [README.md](../README.md)
- [docs/mcp-api.md](../docs/mcp-api.md)
- [docs/mcp-agent-prompts.md](../docs/mcp-agent-prompts.md)
- [AGENTS.md](../AGENTS.md)
- [CLAUDE.md](../CLAUDE.md)

Related delivered commits:

- `985efdc` `Make resolve_context self-describing for first-contact agents`
- `1f9ba6c` `Add narrow MCP shorthand normalization for resolve_context`
- `b2585fb` `Add repair-oriented resolve_context query errors`
- `9e453c8` `Tighten compact staged continuation for MCP retrieval`
- `90ed0be` `Persist compact MCP query summaries in usage metrics`
- `c526c8c` `Sync docs for first-contact MCP query hardening`

## Archived Scope

This note originally proposed a short implementation slice to improve first-contact MCP usability for `resolve_context` while keeping the context window compact.

The intended outcomes were:

- make `resolve_context` self-describing from MCP metadata
- add a narrow shorthand adapter for first-pass IDE agents
- return repair-oriented invalid-query errors
- preserve compact staged continuation through `selection_id` and `snapshot_id`
- retain normalized query summaries in optional Postgres-backed usage metrics

## Delivered Outcome

The delivered implementation now provides:

- rich MCP `inputSchema` for `resolve_context`
- narrow MCP-only shorthand normalization around `query.intent`
- additive normalization metadata:
  - `query_normalized`
  - `query_ingress_mode`
  - `normalized_query_summary`
- repair-oriented invalid-query details with a minimal skeleton and retry guidance
- compact continuation artifacts for staged retrieval handoff
- a compact MCP query-memory helper over usage metrics:
  - `semidx.core/compact-mcp-query-memory`

## Why This Note Is Archived

This file is no longer the active execution plan because the repository now has a delivered implementation and synchronized public/operator-facing documentation.

Treat this note as historical context only, not as current backlog or source of truth.
