---
title: "semidx MCP Smoke Report"
doc_type: "smoke_report"
lifecycle: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
file_type: working-note
topic: semidx-mcp-smoke-report
created_at: 2026-04-02T23:24:24-0700
author: codex
language: en
status: completed
reason: Created after an interactive smoke test of the new semidx MCP flow on the semidx repository itself to preserve concrete observations about protocol strictness, retrieval quality, and runtime validation.
---

# Working Note: semidx MCP Smoke Report On 2026-04-02

## Summary

Ran an interactive smoke test of the new `semidx` MCP flow against this repository on `2026-04-02`.

High-level result:

- the MCP server is healthy
- the core MCP-first flow works end to end
- schema validation is much stricter and generally useful
- retrieval quality is still uneven for exact code-understanding queries
- the existing runtime test suite passes cleanly

## Repository Context

Before the smoke test:

- `docs/code-context.md` existed
- `.ccc/state.edn` existed
- the git worktree was clean

That meant the repo was already in a valid MCP-first bootstrap state and did not require rerunning `./scripts/agent-bootstrap.sh`.

## MCP Smoke Flow Executed

The following flow was exercised directly against the live MCP server:

1. `health`
2. `create_index`
3. `repo_map`
4. multiple `resolve_context` variants
5. `skeletons`
6. `fetch_context_detail`
7. `impact_analysis`

## Concrete Results

### 1. Server health and indexing are working

`health` reported a live `semidx-mcp` server with version `0.1.0`.

`create_index` succeeded for `/Users/ae/workspaces/semidx` and returned:

- `91` indexed files
- `2056` units
- active languages:
  - `clojure`
  - `java`
  - `elixir`
  - `python`
  - `typescript`
- recommended core language: `clojure`

This confirms that the MCP entrypoint, parser activation path, and index construction path are functioning on the repository itself.

### 2. `repo_map` works and returns useful structural context

`repo_map` completed normally and surfaced the expected core modules, including:

- `semidx.mcp.core`
- `semidx.mcp.server`
- `semidx.core`
- `semidx.runtime.*`
- `semidx.*-test`

This was sufficient to continue through the intended MCP-first flow without falling back to broad manual browsing.

### 3. Retrieval query contracts are now strict

The new MCP layer rejected malformed requests quickly and with useful diagnostics.

Observed validation failures included:

- `intent.purpose` must be an allowed enum such as `code_understanding`
- `trace.trace_id` must match the required UUID format
- `fetch_context_detail.detail_level` only accepts:
  - `enclosing_unit`
  - `local_neighborhood`
  - `none`
  - `target_span`
  - `whole_file`
- `impact_analysis` expects the full structured retrieval query shape rather than a short ad hoc payload

This is a real improvement in contract clarity. The server is no longer permissive about malformed retrieval payloads, which should make client behavior easier to stabilize.

### 4. Retrieval quality is still uneven for exact code understanding

The main weakness observed during the smoke test was result ranking quality rather than transport or schema behavior.

Examples:

- a query asking for MCP tool dispatch and the implementation path for `create_index`, `repo_map`, and `resolve_context` returned `contracts/*` symbols instead of MCP handlers
- an intent-shorthand query without strong path constraints drifted into vendored `.tree-sitter-grammars/*` Python files
- even after constraining `allowed_path_prefixes` to `src/semidx` and `test/semidx`, ranking still preferred `contracts/schemas` over the exact MCP dispatch points
- targeting `modules` and `symbols` improved the result set somewhat, but still did not reliably resolve the exact authority symbols

Representative diagnostics from the tool output:

- confidence stayed at `medium`
- warnings included `no_tier1_evidence`
- warnings included `target_ambiguous`
- exact target resolution was reported as missing

This suggests the present issue is retrieval/ranking quality, not server correctness.

### 5. `skeletons` is currently more useful than broad retrieval for navigation

While `resolve_context` was noisy for this task, `skeletons` was much more effective.

It accurately exposed the relevant function surface in:

- `src/semidx/mcp/core.clj`
- `src/semidx/mcp/server.clj`
- `src/semidx/core.clj`
- `test/semidx/mcp_server_test.clj`

In particular, `skeletons` clearly surfaced:

- `tool-create-index`
- `tool-repo-map`
- `tool-resolve-context`
- `tool-handlers`
- `handle-tools-call`
- `start-server-loop!`
- `create-index`
- `repo-map`
- `resolve-context`

Practical conclusion:

- for now, the most reliable operator flow for code understanding is:
  - `create_index`
  - `repo_map`
  - `skeletons`
  - then targeted detail fetches

That is more dependable than expecting a single `resolve_context` call to land exactly on the intended MCP dispatch symbols.

### 6. `fetch_context_detail` works, but the interface is strict

`fetch_context_detail` behaved as expected once called with a supported `detail_level`.

It returned useful enclosing-unit details for runtime functions such as:

- `semidx.core/create-index`
- `semidx.core/repo-map`
- `semidx.core/resolve-context`

The response also emitted detailed diagnostics and guardrails, including:

- stage-level timings
- warnings about degraded raw fetch
- confidence scoring
- plan-safe guardrail posture

This is useful output, but it only becomes productive after the caller has already found the right units.

### 7. `impact_analysis` appears weak or incomplete in this scenario

An initial short-form request was rejected because the tool required the full structured query schema.

A second call using a proper `change_impact` retrieval query succeeded structurally but returned:

- `impact_hints: null`

That result is likely too weak to be useful for real change planning, at least for this tested path. It may indicate either a current implementation gap or a ranking / evidence pipeline that is not yet populating this tool reliably.

## Runtime Validation

After the interactive MCP smoke test, the repository test suite was executed with:

```bash
clojure -M:test
```

Result:

- `177` tests
- `1194` assertions
- `0` failures
- `0` errors

This matters because it confirms that:

- the runtime MCP surfaces are still passing their existing automated coverage
- the stricter query validation paths observed interactively are consistent with repository test expectations
- the current issues are about retrieval quality and ergonomics, not a broken server

## Practical Assessment

### What looks good

- MCP server startup is healthy
- index creation is reliable on the repo itself
- `repo_map` works
- schema and payload validation are much clearer than before
- `skeletons` is already genuinely useful
- the full automated test suite passes

### What still needs work

- `resolve_context` ranking for exact code-understanding tasks
- resistance to lexical drift into vendored or irrelevant files
- stronger exact-symbol authority resolution
- more useful `impact_analysis` output

## Recommended Follow-Up

If the next step is product improvement rather than documentation, the most useful follow-up work would be:

1. build a small benchmark set of exact code-understanding queries against MCP entrypoints
2. measure when ranking drifts into `contracts/*` or vendored grammar files
3. adjust retrieval policy or authority-target heuristics so exact symbol/module matches win more decisively
4. separately verify whether `impact_analysis` is missing evidence generation or simply returning an underspecified result shape

## Bottom Line

The new `semidx` MCP stack is operational and contractually much stronger, but the retrieval layer still underperforms on precise developer-navigation tasks.

Today, the server looks ready for iterative use if clients lean on:

- strict structured queries
- path constraints
- `skeletons`
- targeted detail fetches

It does not yet look strong enough to trust broad `resolve_context` queries as the primary exact-navigation mechanism for nontrivial repository questions.
