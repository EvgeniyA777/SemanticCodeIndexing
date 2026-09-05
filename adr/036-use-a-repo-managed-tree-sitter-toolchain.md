---
file_type: adr
decision_id: ADR-036
title: Use A Repo-Managed Tree-Sitter Toolchain
status: superseded
date: 2026-07-20
deciders:
  - project owner
tags:
  - architecture
  - parser-boundary
  - tree-sitter
summary: Historical mixed decision that coupled a repo-managed tree-sitter toolchain with a regex-default parser policy; its authority policy is superseded by ADR-046 and its toolchain boundary is restated by ADR-047.
agent_summary: Historical reference only. Do not use this ADR for current parser authority: ADR-046 makes fresh semantic providers primary and regex degraded fallback. Use ADR-047 for the retained repo-managed tree-sitter toolchain boundary.
supersedes: []
superseded_by:
  - ADR-046
  - ADR-047
links:
  - plans/013_open_gaps_closure_program.md
  - reports/014_open_gaps_closure_program_progress_log.md
  - adr/035-split-language-lanes-out-of-adapter-facade.md
  - adr/046-prefer-semantic-evidence-providers-over-structural-and-lexical-fallbacks.md
  - adr/047-retain-repo-managed-tree-sitter-toolchain-for-structural-providers.md
---

# ADR-036: Use A Repo-Managed Tree-Sitter Toolchain

**Status**: Superseded
**Date**: 2026-07-20
**Deciders**: project owner

---

## Supersession Note

This ADR is historical. Its regex-default parser-authority policy is superseded
by ADR-046. Its repository-managed tree-sitter executable and grammar-resolution
boundary is retained and restated by ADR-047. The original text below records
the 2026-07-20 decision and must not be read as current parser policy.

---

## Context

Stage 1 split parser implementation into dedicated language lanes and moved
generic tree-sitter helpers into `semidx.runtime.languages.shared`.

The current shared helper path still shells out to the ambient command:

- `tree-sitter-available?` probes `tree-sitter --version`.
- `tree-sitter-cst` runs `tree-sitter parse --cst ...`.
- Grammar paths are supplied by parser options or environment variables.
- Clojure, Java, and TypeScript lanes delegate through the shared helpers and
  already keep regex parsing as their fallback path.

That means tree-sitter mode depends on a tool installed outside the repository
and available on `PATH`. The grammar bootstrap script pins grammar refs, but the
runtime command itself is still ambient host state.

## Decision Drivers

- Runtime behavior must not require users or agent environments to install a
  global `tree-sitter` executable before basic indexing works.
- Pinned grammar reproducibility must stay intact.
- Regex parser modes must remain the guaranteed default and safety net.
- When an accelerated parser mode is requested but unavailable, the result must
  degrade explicitly with a diagnostic, not silently pretend the accelerated
  path ran.
- The implementation should fit the lane/shared-helper boundary accepted in
  `ADR-035` without moving parser internals back into the adapter facade.

## Considered Options

### Option 1. Embeddable parser binding now

Replace CLI invocation with a JVM-accessible tree-sitter binding and load
compiled grammars directly from the runtime.

### Option 2. Repo-managed tree-sitter toolchain boundary

Keep the CLI execution model for now, but resolve the executable through an
explicit toolchain boundary: parser options, environment variables, and a
repository-local managed location produced by the bootstrap script. The ambient
`PATH` command may be retained only as a compatibility fallback, not as the
required runtime source.

### Option 3. Regex fallback only, tree-sitter as external opt-in forever

Document regex parsers as the only supported default and leave tree-sitter mode
dependent on a user-installed external CLI.

## Decision

We accept Option 2: repo-managed tree-sitter toolchain boundary.

After this decision:

- Regex parsing remains the guaranteed default for all supported lanes.
- Tree-sitter remains an optional accelerated parser mode.
- `semidx.runtime.languages.shared` owns resolution of the tree-sitter
  executable and grammar paths.
- Runtime resolution must prefer explicit configuration over ambient host
  state:
  1. parser option such as `:tree_sitter_cli_path`
  2. environment variable such as `SEMIDX_TREE_SITTER_CLI_PATH`
  3. repository-local managed tool location created by
     `scripts/setup-tree-sitter-grammars.sh`
  4. optional `PATH` fallback for developer convenience only
- `scripts/setup-tree-sitter-grammars.sh` should become the single bootstrap
  entrypoint for both pinned grammars and the managed CLI/tool location.
- If tree-sitter mode is requested and the resolved executable or grammar is
  unavailable, the lane returns regex parsing with an explicit diagnostic.

Option 1 loses for this stage because it front-loads native/JVM binding and
grammar loading complexity before the project has isolated the operational
toolchain boundary. It can be reconsidered later if CLI process overhead or
distribution risk becomes material. Option 3 loses because it leaves the
runtime dependency on external host state exactly where the stage intends to
remove it.

## Consequences

### Positive

- Basic indexing no longer depends on a globally installed tree-sitter CLI.
- Tree-sitter acceleration becomes reproducible through a repository-managed
  bootstrap path.
- The implementation is narrow: most changes land in shared helpers and the
  bootstrap script, while lane modules keep their parser policy.
- Existing regex fallback behavior remains the safety net for unavailable or
  failed accelerated parsing.

### Negative

- The project still invokes a CLI process for tree-sitter mode; this decision
  removes the external installation requirement, not the process model.
- The bootstrap script now owns more operational responsibility and must be
  tested on supported developer/CI platforms.
- Tool resolution order must be documented carefully so explicit parser
  options, environment overrides, managed local tools, and optional `PATH`
  fallback do not produce confusing behavior.

### Follow-Up

- Implement CLI path resolution and availability probing in
  `semidx.runtime.languages.shared`.
- Extend `scripts/setup-tree-sitter-grammars.sh` to provision or verify the
  managed tool location alongside pinned grammars.
- Add tests for forced unavailable tree-sitter mode and managed CLI resolution.
- Update runtime/MCP docs that mention grammar bootstrap or tree-sitter parser
  configuration.

## Status Changes

None.

## References

- `plans/013_open_gaps_closure_program.md` - Stage 2
- `adr/035-split-language-lanes-out-of-adapter-facade.md` - accepted
  lane/shared-helper boundary
- `src/semidx/runtime/languages/shared.clj` - tree-sitter helper ownership
- `scripts/setup-tree-sitter-grammars.sh` - pinned grammar bootstrap

## Definition Of Done

Tree-sitter mode can run from explicit parser configuration or a
repository-managed toolchain without requiring a globally installed
`tree-sitter` executable; regex mode remains the default; requested but
unavailable acceleration emits explicit diagnostics; Clojure, Java, and
TypeScript parser tests pass with tree-sitter available and forced unavailable;
MVP gates, benchmarks, semantic quality reporting, and CCC checks pass after
the implementation.
