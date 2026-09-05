---
file_type: adr
decision_id: ADR-048
title: "Add Zig as a Separate Semantic Search Language"
status: superseded
lifecycle: superseded
agent_action: historical_reference_only
updated: 2026-08-26
date: 2026-08-26
deciders:
  - project owner
tags:
  - architecture
  - language-adapter
summary: "Expose Zig as a first-class low-strength language lane with bounded regex parsing and standard onboarding governance."
agent_summary: "Zig is an accepted public language lane. Keep its v1 regex parser bounded, preserve the low confidence ceiling, and use ADR-022 fixtures, tests, docs, and gates for future changes."
supersedes: []
superseded_by: ADR-049
links:
  - ADR-004
  - ADR-014
  - ADR-022
  - ADR-035
---

# ADR-048: Add Zig as a Separate Semantic Search Language

**Status**: Superseded by ADR-049

**Date**: 2026-08-26

**Deciders**: project owner

## Context

Zig repositories use a distinct module, declaration, container, and test syntax.
Treating `.zig` as an unknown file prevents language activation, symbol targeting,
and import/caller navigation even though the runtime already supports bounded
regex-first language lanes. ADR-022 requires every new lane to ship with public
metadata, parser dispatch, fixtures, tests, documentation, and quality gates.

## Decision Drivers

- Zig must appear honestly in discovery and capability metadata.
- The initial implementation must be small, explainable, and failure-bounded.
- Common `@import`, function, container-method, call, and `test` surfaces provide
  useful retrieval value without claiming compiler-grade resolution.
- Onboarding fixtures and gates must cover the lane from its first accepted slice.

## Considered Options

### Option 1. Add a separate regex-first Zig lane

Register `zig`, parse a bounded semantic subset, and expose a low confidence
ceiling until stronger evidence providers exist.

### Option 2. Wait for a compiler or tree-sitter provider

Keep Zig unsupported until deeper structural or compiler evidence is integrated.

### Option 3. Detect `.zig` files without semantic units

Expose Zig in activation metadata but emit only fallback file sections.

## Decision

We accept Option 1: add Zig as a separate regex-first language lane.

The v1 lane owns `.zig` discovery, path-based modules, static `@import` aliases,
top-level functions, functions owned by named containers, `test` blocks, bounded
call tokens, test-target linkage, and fallback through the shared adapter facade.
Its public strength and confidence ceiling remain `low`. Tree-sitter, Zig compiler
integration, generic/comptime evaluation, inferred method dispatch, and dynamic
import resolution are not part of this decision.

## Consequences

### Positive

- Zig repositories can be indexed and queried as Zig.
- Common imported-function and test-to-source relationships are available to
  retrieval and caller analysis.
- Capability, activation, fixture, benchmark, and documentation surfaces remain
  aligned with the implementation.

### Negative

- Brace and declaration extraction is lexical and can be confused by uncommon
  multiline declarations or braces embedded in complex literals.
- Overloads, generic instantiations, inferred receiver dispatch, and compiler
  semantics remain unresolved.
- The low confidence ceiling blocks autonomous high-confidence outcomes even for
  exact matches until a stronger provider is adopted.

### Follow-Up

- Add a structural or compiler-backed provider only when real-repository evidence
  shows that the bounded regex lane is insufficient.
- Keep `./scripts/validate-language-onboarding.sh zig` and Zig fixture benchmarks
  green when the lane evolves.

## References

- `docs/language-onboarding/zig.md`
- `fixtures/retrieval/zig-happy-path.json`
- `fixtures/retrieval/zig-ambiguity.json`
- `test/semidx/integration/zig_onboarding_test.clj`

## Definition Of Done

The decision is implemented when Zig is registered in public contracts, `.zig`
files produce stable semantic units and links through a dedicated lane, onboarding
fixtures and tests pass, documentation is current, and standard language-onboarding
validation succeeds.
