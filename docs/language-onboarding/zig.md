# Zig Onboarding Status

Created as a first-class language lane following `ADR-022`; `ADR-049`
supersedes the initial regex-first decision in `ADR-048`.

## Current State

- Language key: `zig`
- File extension: `.zig`
- Parser function: `semidx.runtime.languages.zig/parse-file`
- Primary definition provider: ZLS over LSP stdio (`textDocument/documentSymbol`)
- Provider id/version: `zig-zls` / `2`
- Public strength and confidence ceiling: `low`
- Fixture files:
  - `fixtures/retrieval/zig-happy-path.json`
  - `fixtures/retrieval/zig-ambiguity.json`
- Current semantic coverage:
  - path-based module identity
  - static `@import("...")` dependency and alias extraction
  - top-level and multiline functions from fresh ZLS document symbols
  - functions owned by named containers from ZLS symbol hierarchy
  - Zig `test` blocks and test-to-source module linkage
  - imported, local, and receiver-style call tokens
  - one bounded ZLS workspace session per indexing operation
  - bounded regex fallback behavior through the shared adapter facade

## Provider Configuration

ZLS is the default Zig engine. The runtime resolves `zls` from `PATH`, or accepts:

- `:zls_command` / `:zig_lsp_command` for an explicit executable path or command vector
- `SEMIDX_ZLS_COMMAND` as the process-level executable override
- `:zig_lsp_timeout_ms` for bounded initialize and request waits (default: 5000 ms)
- `:zig_engine :regex` to explicitly bypass ZLS
- `:zig_lsp_fact_source` for a host-managed, exact-current-source fact provider

Successful files emit `zig_zls_active`. Missing startup dependencies emit
`zig_zls_unavailable`; request failures emit `zig_zls_fallback`; explicit regex
selection emits `zig_regex_selected`. All failure paths preserve indexing.

## Deliberate Limits

- ZLS document symbols are authoritative only for definitions and ownership.
- Imports and calls remain bounded lexical facts; definition/reference,
  call-hierarchy, generic/comptime evaluation, inferred receiver dispatch, and
  dynamic imports are not yet compiler-grade semantic facts.
- The `low` confidence ceiling remains intentional until capability and evidence
  reporting can distinguish operations and runtime fallback use.

## Next Steps (ADR-022)

1. Expand fixtures from real Zig repositories when concrete parsing gaps appear.
2. Evaluate ZLS reference and call-hierarchy facts independently before raising
   the public strength.
3. Keep running `./scripts/validate-language-onboarding.sh zig`.
