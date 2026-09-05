---
name: semidx-code-exploration
description: "Explore semidx source, callers, tests, and blast radius with CCC and Semantic Code Indexing before manual search. Use for cold-start orientation, symbol lookup, impact analysis, test discovery, or preparation for multi-file changes."
---

# semidx Code Exploration

Use the global `semidx` skill and MCP tools as the canonical retrieval contract.
This repository skill adds semidx-specific bootstrap and evidence requirements.

## Workflow

1. Read `RULES.md`.
2. Before first-pass exploration, read `docs/code-context.md` and `.ccc/state.edn`
   when present; run `./scripts/agent-bootstrap.sh` only if CCC artifacts are
   missing.
3. Run the semantic flow with an absolute root:

   ```text
   create_index -> repo_map -> resolve_context
   -> expand_context -> fetch_context_detail
   ```

4. Verify reported root path, snapshot id, active languages, lifecycle state,
   confidence, and diagnostics.
5. Refine broad results with concrete paths, symbols, modules, tests, and
   `freshness: current_snapshot` before concluding context is thin.
6. For a change, inspect relevant definitions, callers, callees, related tests,
   contracts, fixtures, provider descriptors, storage/runtime edges, and
   documentation ownership.
7. Use manual `rg` or file reads only after semantic refinement is insufficient,
   the target is outside indexed source, or an MCP tool returns an explicit
   error. Record the fallback reason.

## Required Output Before Non-Trivial Edits

- relevant definitions and ownership boundaries;
- inbound and outbound dependencies;
- related tests, fixtures, and missing test seam;
- contract, provider-authority, storage, runtime, and documentation impacts;
- confidence, limitations, snapshot id, and exact files needing direct
  inspection.

Do not stop after only `create_index` or `resolve_context`. Low confidence for a
language with a low ceiling is not a tool failure.
