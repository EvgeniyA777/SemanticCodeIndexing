# Project Memory

## Purpose

This file stores lightweight operational memory for the project:

- current implementation reality
- key non-ADR decisions
- active assumptions and constraints
- near-term next steps

Use this as a fast session bootstrap before deep-diving into ADRs and code.

## Current State

- Contract layer is established (`contracts/schemas`, `contracts/examples`, `fixtures/retrieval`).
- Clojure-side contract mirror is implemented with `malli`.
- MVP runtime is implemented with public API in `semantic-code-indexing.core`.
- Clojure parser path is `clj-kondo` primary with regex fallback.
- Java/Elixir/Python parser paths are lightweight regex-based with class/module-aware symbol and call normalization.
- Retrieval uses structural-first tiered scoring with non-compensating confidence ceilings.
- Raw-code escalation stage is implemented (opt-in, late, bounded by query constraints).
- Optional persistence adapters exist: in-memory and PostgreSQL (snapshots + unit/call-edge projections).
- Retrieval benchmark suite exists and is integrated into gates (`scripts/run-benchmarks.sh`).
- Postgres integration smoke exists in tests (enabled by `SCI_TEST_POSTGRES_URL`) and CI service job.

## Hard Invariants

- JSON Schema is the external contract source of truth.
- `malli` is a runtime mirror, not a competing source of truth.
- Outputs must remain bounded and contract-valid (`context_packet`, diagnostics, guardrails, events).
- If limits are exhausted, stop immediately and wait for explicit user instruction.
- Before any service-backed tests (PostgreSQL or other servers): detect running instance -> shutdown if running -> start fresh with required config -> only then run tests.

## Known Gaps

- No deep compiler-grade semantic resolution yet.
- No production API server boundary yet (library-first only).
- No full tree-sitter/AST extraction path yet (only optional availability probe/diagnostics).
- Persistence model is still retrieval-oriented and not yet a full queryable semantic graph engine.

## Next Execution Priorities

1. Add tree-sitter-backed structural extraction path (beyond availability probe) for Java/Clojure.
2. Improve language-specific semantic resolution (imports, callsite ownership, ambiguous target disambiguation).
3. Expand fixture corpus for stale/degraded and multi-language ambiguity scenarios.
4. Keep production API boundary as separate next phase (library-first remains canonical).

## Update Rule

Update this file when any of the following changes:

- runtime behavior materially changes
- new invariants are introduced
- priorities or known gaps change
- integration assumptions change
