---
title: "snapshot_diff Is Dropped by the Host Because a Property Key Ends in a Question Mark"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-09-10"
---

# snapshot_diff is dropped by the host because a property key ends in `?`

**Confirmed defect:** the published input schema for `snapshot_diff` declares a
property named `include_unchanged?`. The host's tool-schema validation rejects
that key, so the tool is excluded from the session while every other semidx tool
loads. `snapshot_diff` is therefore uncallable from this client, and no runtime
error is ever reached — the failure happens at tool-list load time.

## Environment

- Host: Claude Code on the Anthropic API, MCP server registered as `semidx`.
- Consumer session: a Java/Python index over an application repository;
  substitute any checkout root when replaying, the defect is independent of the
  indexed project.
- Local semidx checkout HEAD when filing: `0b81e03`. The running MCP process's
  source revision was not established.
- All other semidx tools in the same session loaded and worked, including
  `create_index`, `repo_map`, `resolve_context` and `fetch_context_detail`.

## Observed behavior

The host reported the exclusion while loading the server's tools:

```text
"snapshot_diff" (MCP server "semidx"): property key include_unchanged? does not
match /^[a-zA-Z0-9_.-]{1,64}$/
```

Expected: `snapshot_diff` appears in the session's tool list and is callable.
Actual: the tool is silently unavailable for the whole session; the only signal
is that one load-time message, which an agent may never surface to the user.

## Reproduction

1. Register the semidx MCP server with a host that validates tool input schemas
   against `/^[a-zA-Z0-9_.-]{1,64}$/` for property names — the Anthropic API
   does.
2. Start a session and list tools.
3. `snapshot_diff` is missing; the remaining semidx tools are present.

No index or query is needed: the rejection is a property of the published
schema, not of any request.

## Impact and focused follow-up

The whole snapshot-comparison capability is unreachable from this client, which
also means the documented "compare current index snapshot to a baseline" step in
the agent-facing skill cannot be followed at all.

Renaming the property to `include_unchanged` satisfies the pattern. The wider
follow-up is worth more than the single rename: a trailing `?` is idiomatic in
Clojure and is a natural name for a boolean option, so every externally
published schema under `contracts/schemas/` and every MCP `tools/list` payload
should be checked for the same shape. `RULES.md` already requires verifying both
`tools/list` output and runtime handler behavior when tool schemas change; a
host-side key-pattern check is the constraint that rule does not yet name.

A regression guard belongs at the contract level: assert that every published
property name matches `^[a-zA-Z0-9_.-]{1,64}$`, so an idiomatic Clojure name
cannot reach the wire again.

## Verification limits

The exclusion message is reported by the host, not by semidx, and was observed
once in one session. No semidx source was changed, no semidx tests were run, and
the fix was not attempted. Whether other hosts apply the same pattern was not
investigated.
