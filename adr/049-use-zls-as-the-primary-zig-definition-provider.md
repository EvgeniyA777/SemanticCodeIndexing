---
file_type: adr
decision_id: ADR-049
title: "Use ZLS as the Primary Zig Definition Provider"
doc_type: adr
lifecycle: accepted
status: accepted
agent_action: reference_for_context
date: 2026-08-26
updated: 2026-08-26
deciders:
  - project owner
tags:
  - architecture
  - language-adapter
  - lsp
  - zig
summary: "Use fresh ZLS document symbols as the primary Zig definition and ownership facts while retaining the bounded regex parser as a failure-safe fallback and supplemental call/import extractor."
agent_summary: "Start one bounded ZLS stdio session per indexing operation, send the exact indexed text through didOpen, use documentSymbol for Zig definitions and ownership, and degrade to regex with explicit diagnostics when ZLS is unavailable or fails."
supersedes:
  - ADR-048
superseded_by: null
links:
  - ADR-022
  - ADR-046
  - ADR-048
---

# ADR-049: Use ZLS as the Primary Zig Definition Provider

**Status**: Accepted

**Date**: 2026-08-26

**Deciders**: project owner

## Context

ADR-048 introduced a useful regex-first Zig lane, but its declaration matching
cannot reliably represent multiline functions and only infers container
ownership lexically. ZLS exposes the Language Server Protocol over stdio and can
provide document symbols for the exact source text being indexed. The repository
did not previously contain an LSP client or a Zig semantic-provider lifecycle.

## Decision Drivers

- Zig indexing must use ZLS when it is available, rather than only detecting it.
- Provider facts must describe the exact in-memory source passed to the indexer.
- Process startup, requests, shutdown, and failure behavior must be bounded.
- A missing or unhealthy external executable must not make indexing unavailable.
- Capability metadata must remain honest about the lane's overall retrieval
  quality, including operations that ZLS document symbols do not cover.

## Considered Options

### Option 1. Use ZLS document symbols as primary facts with regex fallback

Start one ZLS process for an indexing operation, send each current Zig document
with `textDocument/didOpen`, request `textDocument/documentSymbol`, and retain the
existing regex parser for failure fallback and supplemental imports/calls.

### Option 2. Keep the regex-only lane

Avoid a process dependency but retain known multiline and ownership limitations.

### Option 3. Require ZLS and fail indexing when it is unavailable

Maximize provider consistency at the cost of making an optional language tool a
hard runtime dependency.

## Decision

We accept Option 1.

`semidx.runtime.lsp-client` owns bounded JSON-RPC framing and the LSP stdio
lifecycle. The Zig parser context starts one ZLS workspace session per full or
incremental parse operation. For each file, it sends the exact text being indexed
through `textDocument/didOpen`, requests hierarchical document symbols, and closes
the document after the response. ZLS ranges and hierarchy are authoritative for
function/test definitions and container ownership.

The existing Zig regex logic remains:

- the fallback when ZLS cannot start, times out, exits, or returns an error;
- the supplemental extractor for static imports and bounded call tokens after a
  successful ZLS definition request;
- explicitly selectable with `:zig_engine :regex` for constrained environments.

Runtime diagnostics distinguish `zig_zls_active`, `zig_zls_unavailable`,
`zig_zls_fallback`, and `zig_regex_selected`. The public provider id becomes
`zig-zls` at provider version `2`, and the provider-registry compatibility version
is bumped. The public Zig strength and confidence ceiling remain `low`: document
symbols improve definition and ownership evidence, but references, dispatch,
imports, and calls are not yet fully semantic, and deployments may use fallback.

The parser also accepts an injected `:zig_lsp_fact_source`. This gives host
integrations and deterministic tests the same fresh-document fact boundary
without requiring process ownership inside the parser itself.

## Consequences

### Positive

- ZLS is actually queried during normal Zig indexing when available.
- Multiline functions and hierarchical container ownership become stable units.
- The LSP source version is exact because the indexed text is sent with `didOpen`.
- One process is shared across the files in an indexing operation.
- ZLS failures remain isolated to the Zig provider and preserve useful indexing.

### Negative

- The runtime now contains a small generic LSP client and owns a child process by
  default for Zig workspaces.
- Definition authority is stronger than call/reference authority, so the lane
  cannot yet claim a higher overall confidence ceiling.
- ZLS startup adds latency to Zig index creation.

## Follow-Up

- Add operation-scoped capability reporting before raising Zig strength.
- Evaluate ZLS definition/reference and call-hierarchy methods as separate fact
  sources instead of treating document symbols as proof of those relationships.
- Keep real-ZLS smoke verification alongside deterministic protocol/fact-source
  tests when changing the client.

## References

- `src/semidx/runtime/lsp_client.clj`
- `src/semidx/runtime/languages/zig.clj`
- `test/semidx/runtime/lsp_client_test.clj`
- `test/semidx/runtime/zig_language_test.clj`
- `docs/language-onboarding/zig.md`

## Definition Of Done

The decision is implemented when normal Zig indexing uses a bounded ZLS session,
document symbols drive definition and ownership units from exact current source,
regex fallback remains functional, provider compatibility metadata is bumped,
tests cover protocol framing and provider arbitration, a live ZLS smoke succeeds,
and the full Zig onboarding validation passes.
