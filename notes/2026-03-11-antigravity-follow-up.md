---
file_type: working-note
topic: antigravity-mcp-follow-up
created_at: 2026-03-11T15:50:00-0700
author: ae
language: en
status: active-follow-up
---

# Working Note: Antigravity MCP Follow-Up

## Current Status

Antigravity first-contact MCP behavior is now meaningfully improved.

Confirmed in a live run:

- it stayed on the MCP path for the first pass
- it successfully used:
  - `create_index`
  - `repo_map`
  - `resolve_context`
- it did not drift into manual directory listing, wildcard search, or broad file browsing during that first-pass flow

This is enough to treat the original first-contact usability problem as largely fixed.

## What Is Still Not Proven

The remaining open check is staged continuation.

We still need one explicit follow-up run proving that Antigravity:

- continues from `resolve_context` into `expand_context`
- then continues into `fetch_context_detail`
- reuses `selection_id` and `snapshot_id`
- does not switch back to filesystem/manual browsing between those stages
- produces more evidence-grounded output after the detail stage than after the compact selection stage

## Next Validation Prompt

Use this prompt on the next Antigravity pass:

```text
Stay on the semantic-code-indexing MCP flow only.
Do not browse files manually unless an MCP tool fails.

Find the main orchestration flow in this repository.
After resolve_context succeeds, call expand_context and then fetch_context_detail for the most relevant units.
Explain the real entrypoints, authoritative modules, and control flow based only on MCP results.
```

## Acceptance Criteria

Treat the staged-continuation check as passed only if Antigravity:

- uses `resolve_context`
- uses `expand_context`
- uses `fetch_context_detail`
- avoids manual browsing before an MCP failure
- bases the final explanation on the staged MCP results instead of broad repo summarization

## Canonical References

- [MEMORY.md](../MEMORY.md)
- [docs/mcp-api.md](../docs/mcp-api.md)
- [docs/mcp-agent-prompts.md](../docs/mcp-agent-prompts.md)
