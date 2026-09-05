---
title: "Change Detection For Agent Exploration"
doc_type: "architecture_note"
lifecycle: "superseded"
agent_action: "historical_reference_only"
updated: "2026-08-02"
superseded_by:
  - plans/007_semidx_extension_architecture_resolution_plan.md
  - plans/008_stage_0_1_workspace_freshness.md
file_type: working-note
topic: agent-change-detection-for-exploration
created_at: 2026-03-27T01:15:00-0700
author: claude
language: en
status: superseded
follows: notes/2026-03-27-agent-exploration-protocol.md
---

# Working Note: Change Detection for Agent Exploration

Follows from the exploration protocol note. That note proposed a Tier 0 `wc -l` scan at the start of every conversation. This note challenges that and proposes a smarter mechanism.

## Problem With "Scan Every Conversation"

The previous note assumed file sizes are mostly stable and a full `wc -l` scan every conversation is overkill. That assumption is wrong when AI agents are actively working on the repo. An agent can generate hundreds or thousands of lines in minutes. A file that was 500 lines an hour ago may be 1500 lines now.

But the opposite extreme — blind full scan every time — is also wasteful when nothing changed.

The real question: **how does the agent know whether the repo changed, what changed, and by how much, before deciding how to explore?**

## Git Is Already a Change Detection System

Git stores content hashes for every tracked file. Comparing states is a built-in operation, not something that needs to be invented.

### Available mechanisms

| Command | What it tells you | Cost |
|---|---|---|
| `git status` | Uncommitted modified/untracked files | ~50 tokens, already runs at conversation start |
| `git diff --stat HEAD` | Uncommitted changes with per-file line deltas | ~50–100 tokens, 1 call |
| `git diff --stat <commit>` | All changes since a known commit | ~50–100 tokens, 1 call |
| `git diff --stat HEAD~N -- src/ test/` | Source-only changes in last N commits, sorted by magnitude | ~50–150 tokens, 1 call |
| `git diff --name-only HEAD` | Just filenames that changed, no counts | ~30 tokens, 1 call |
| `wc -l` full scan | Absolute line counts for all source files | ~200 tokens, 1 call |

Key insight: `git diff --stat` gives **deltas** (what grew, what shrunk, by how much). `wc -l` gives **absolutes** (total size of each file). Both are useful for different decisions.

### Why not custom hashing?

Git already hashes every file's content. Running `md5` or `sha256` separately would duplicate what `git diff` already does for free. The only case for separate hashing is untracked files that git does not know about — but `git status` already lists those.

## Proposed Tier 0: Two-Phase Size Awareness

### Phase 1: Baseline (once per repo, or after significant time gap)

```bash
find src test -type f \( -name '*.clj' -o -name '*.ex' -o -name '*.exs' -o -name '*.py' -o -name '*.ts' -o -name '*.java' \) -exec wc -l {} + | sort -rn
```

This gives the full absolute size map. The agent learns which files are large and require careful access. Cost: 1 call, ~200 tokens.

When to run:
- first conversation in a repo
- after a long gap (days) where multiple agents or humans may have changed the codebase significantly
- after the agent learns that a previously-small file is now large (Read error)

### Phase 2: Delta check (every subsequent conversation)

```bash
git diff --stat HEAD~5 -- src/ test/ | sort -t'|' -k2 -rn
```

This tells the agent what changed recently and by how much. Cost: 1 call, ~50–100 tokens.

Decision rules after delta check:

| Delta result | Agent action |
|---|---|
| No changes in src/test | Baseline still valid, skip rescan |
| Small changes (<50 lines) in known-small files | Baseline still valid |
| Large changes (>200 lines) in any file | Re-check that file with `wc -l <file>` (1 targeted call) |
| New files appeared | `wc -l` on new files only |
| Major refactor (many files, large deltas) | Full `wc -l` rescan |

### Phase 0 (free): git status

Claude Code already injects `git status` at conversation start. This tells the agent about uncommitted changes. If `git status` shows modified source files, the agent already knows those files were touched and should check their size before reading.

## What This Replaces

The previous exploration protocol note proposed:

> Tier 0: `wc -l` scan at start of every conversation, before any Read.

This note refines that to:

> Tier 0: git-based delta check every conversation (~50 tokens). Full `wc -l` scan only when the delta indicates significant changes or on first encounter.

The saving is not just tokens — it is about correctness. A full scan tells you "adapters.clj is 3062 lines." A delta check tells you "adapters.clj grew by 400 lines since last known state" — which is more actionable because it tells you the file is changing and your prior assumptions about it may be stale.

## Interaction With MCP

SCI MCP `create_index` already re-indexes the repo on every call (unless cached). The index contains per-file line counts internally but does not expose them in `repo_map`.

Two complementary improvements on the MCP side:

1. **Expose `line_count` per file in `repo_map` response.** The data already exists at index time. This would give the agent file sizes for free as part of the Tier 1 `repo_map` call, making the `wc -l` baseline scan unnecessary when MCP is available.

2. **Expose a `changed_since` or `delta` field** based on the index cache. If `create_index` detects a cache hit (repo unchanged), it could say so explicitly. If it rebuilds, it could report which files were added/removed/resized. This would subsume the git-based delta check for repos where SCI MCP is the primary tool.

These are SCI server enhancements, not agent-side changes. They would make the git-based Tier 0 a fallback rather than the primary mechanism.

## Open Questions

1. What is the right `HEAD~N` depth for the delta check? Too shallow (HEAD~1) misses multi-agent work. Too deep (HEAD~20) returns noisy output. Should this be time-based instead (e.g., `git diff --stat @{1.hour.ago}`)?
2. Should the baseline `wc -l` result be cached somewhere (e.g., a dotfile or memory) so future conversations can compare against it without re-running?
3. For repos with heavy uncommitted work (agents writing but not committing), `git diff --stat HEAD` captures the delta. But if the agent itself is the one making changes, it already knows what it changed — is the delta check redundant in that case?
