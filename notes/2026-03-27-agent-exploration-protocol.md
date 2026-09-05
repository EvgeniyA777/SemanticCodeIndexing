---
title: "Agent Codebase Exploration Protocol"
doc_type: "policy_note"
lifecycle: "superseded"
agent_action: "do_not_use_for_current_work"
updated: "2026-08-02"
superseded_by:
  - RULES.md
file_type: working-note
topic: agent-exploration-protocol
created_at: 2026-03-27T00:30:00-0700
author: claude
language: en
status: superseded
---

# Working Note: Agent Codebase Exploration Protocol

## Problem Statement

The current CLAUDE.md mandates a strict MCP-first workflow: all first-pass codebase exploration must go through the SCI MCP staged flow (`create_index` → `repo_map` → `resolve_context` → `expand_context` → `fetch_context_detail`). Glob, Grep, and Read are explicitly forbidden for first-pass exploration.

This creates two failure modes observed in practice:

1. **MCP-only mode (current):** The staged flow requires 3–5 sequential tool calls before the agent sees any code. `resolve_context` sometimes returns irrelevant results (e.g., returning Java tree-sitter bindings when querying for Elixir parser logic, because Elixir was not in active languages). The rigid flow cannot be short-circuited — the agent must complete the staged sequence even when a single Grep would answer the question.

2. **Filesystem-only mode (if MCP mandate is relaxed):** The agent defaults to Glob/Grep/Read for everything and ignores MCP semantic retrieval entirely. On large files like `runtime/adapters.clj` (~3500 lines, ~45k tokens), a naive Read consumes the token budget rapidly. The agent has no built-in awareness of file size before reading.

Neither extreme is correct. The question is what goes in between.

## Observed Cost Model

| Operation | Typical token cost | Latency (tool calls) |
|---|---|---|
| `repo_map` (MCP) | ~200 tokens | 2 (create_index + repo_map) |
| `resolve_context` (MCP) | ~300 tokens | 1 |
| `expand_context` (MCP) | ~500 tokens | 1 |
| `fetch_context_detail` (MCP) | ~1000 tokens | 1 |
| Full MCP staged flow | ~2000 tokens | 3–5 calls |
| Grep (pattern match) | ~50–200 tokens | 1 |
| Glob (file listing) | ~50–100 tokens | 1 |
| Read (small file, <200 lines) | ~500–1500 tokens | 1 |
| Read (adapters.clj, no limit) | ~45000 tokens or error | 1 |
| Read (targeted, offset+limit 100 lines) | ~500 tokens | 1 |

MCP is token-cheap per call but latency-expensive (sequential dependency chain). Filesystem tools are single-call but can be token-expensive on large files.

## File Size Awareness

The agent cannot know a file's size before reading it. The built-in tools (Read, Glob, Grep) do not expose file metadata. A rule like "do not Read files >N lines without offset/limit" is unenforceable without a preceding size check.

### Solution: `wc -l` scan as Tier 0

One Bash call returns the full size map for the entire source tree:

```bash
find src test -name '*.clj' -exec wc -l {} + | sort -rn
```

Cost: **1 tool call, ~200 tokens of output.** The agent gets a complete picture:

```
3062  src/semidx/runtime/adapters.clj
1811  src/semidx/runtime/evaluation.clj
1316  src/semidx/runtime/retrieval.clj
1027  src/semidx/mcp/core.clj
1015  src/semidx/runtime/usage_metrics.clj
 671  src/semidx/runtime/languages/typescript.clj
 634  src/semidx/runtime/index.clj
 574  src/semidx/core.clj
  ...everything else under 500 lines
```

After this, the agent knows exactly which files require Grep+targeted Read and which can be Read whole. No guessing, no hardcoded lists, no stale data.

This is better than all previously considered approaches:

| Approach | Problem |
|---|---|
| A. Hardcoded hotspot list in CLAUDE.md | Goes stale, does not generalize |
| B. Grep-first for all files | Extra tool call even for small files |
| C. Accept Read error + retry | Wastes a tool call on every new large file |
| D. Hypothetical `stat` tool | Does not exist |
| E. File sizes in `repo_map` (MCP) | Requires SCI server change |
| F. Grep-then-Read default | Does not help when agent needs holistic view |
| **`wc -l` scan (Tier 0)** | **1 call, ~200 tokens, complete, always current** |

Approach E (expose line counts in `repo_map`) remains a good complementary improvement for the SCI server, since the data is already available at index time. But it is no longer a prerequisite — the `wc -l` scan works today with no code changes.

### Multi-language extension

For repos with mixed languages, the scan extends naturally:

```bash
find src test -type f \( -name '*.clj' -o -name '*.ex' -o -name '*.exs' -o -name '*.py' -o -name '*.ts' -o -name '*.java' \) -exec wc -l {} + | sort -rn
```

Same cost: 1 tool call.

## Proposal: Tiered Exploration Protocol

Replace the current "MCP-first workflow (mandatory)" section in CLAUDE.md with a tiered protocol that routes queries to the right tool based on query type, not a fixed sequence.

### Tier 0 — File size map

Tool: `find src test -name '*.clj' -exec wc -l {} + | sort -rn` (Bash)

When: start of every conversation, before any Read.

Cost: 1 tool call, ~200 tokens.

Why: the agent now knows which files are safe to Read whole (<500 lines) and which require Grep + targeted Read. This decision is data-driven, not guessed.

Threshold: files over 500 lines → always Grep first, then Read with offset/limit.

### Tier 1 — Project structure

Tool: `repo_map` (MCP)

When: starting a new task, need to understand what exists.

Why MCP: compact output, already indexed, ~200 tokens.

### Tier 2 — Locate symbol, pattern, or file

Tool: Grep, Glob

When: looking for a specific function, class, pattern, file path.

Why filesystem: precise, single tool call, returns line numbers for targeted follow-up.

### Tier 3 — Semantic retrieval

Tool: `resolve_context` (MCP)

When: the question is "what code is relevant to task X" and the answer is not obvious from names/patterns.

Why MCP: ranked retrieval across the full index, compact results.

Failure rule: if `resolve_context` returns irrelevant results or errors after 2 attempts, state the failure explicitly and switch to Tier 2.

### Tier 4 — Read code

Tool: Read with offset/limit

When: need to see actual code after locating it via Tier 2 or Tier 3.

Rule: after a token-limit error from Read, use Grep to find relevant line numbers, then re-Read with offset/limit.

### Anti-patterns (forbidden)

- Using `resolve_context` as the only exploration tool for all queries.
- More than 3 sequential MCP calls without useful result before switching to filesystem.
- Silently abandoning MCP without stating why (current rule, keep it).
- Silently falling back to Read-whole after a failed MCP flow without trying Grep first.

## Proposal: Expose File Sizes in repo_map

Add a `line_count` field to each file entry in the `repo_map` MCP response. The data is already available at index time — every parsed file has its lines counted during extraction.

This would let the agent see at Tier 1:

```
adapters.clj  — 3500 lines
languages/elixir.clj — 127 lines
```

And make informed decisions about Read strategy without extra tool calls.

This is an SCI server change, not a CLAUDE.md change.

## Proposal: Skill vs CLAUDE.md

The tiered protocol should live in CLAUDE.md, not in a skill file, because:

- It must apply to every task automatically, not only when explicitly invoked.
- A skill is a prompt fragment loaded on demand — the agent will not use it unless told to.
- CLAUDE.md is always loaded into context.

A skill file would be useful for a different purpose: encoding specific CLI workflows (e.g., "run benchmarks in both modes and compare") that are invoked explicitly. But the exploration routing protocol is not that — it is a standing behavioral rule.

## Open Questions

1. Should the tiered protocol also define a token budget per task (e.g., "first-pass exploration should cost <5k tokens")?
2. Should `resolve_context` failures trigger automatic language activation (e.g., if Elixir is not in active languages, activate it and retry)?
3. Is Approach E (expose line counts in `repo_map`) worth implementing as a complementary improvement, or is the `wc -l` Tier 0 scan sufficient on its own?
4. Should Tier 0 be embedded in a skill (so the scan runs automatically on `/sci-explore`), in CLAUDE.md (so it is a standing rule), or both?
