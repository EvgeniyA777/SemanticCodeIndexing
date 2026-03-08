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
- Clojure parser path supports `clj-kondo` primary with regex fallback and optional tree-sitter extraction mode.
- Java parser path supports regex mode and optional tree-sitter extraction mode.
- Elixir/Python parser paths are regex-based with class/module-aware symbol and call normalization.
- Elixir parser now supports alias-aware call token expansion (`alias Foo.Bar, as: Baz` -> `Baz.fn()` -> `Foo.Bar.fn` token).
- Elixir extraction now distinguishes form operators (`def`, `defp`, `defmacro`, `defdelegate`) and uses `do/end` balancing for tighter unit boundaries.
- Elixir alias parsing now covers brace aliases and alias-chains (`alias Foo.{Bar,Baz}`, nested alias prefixes, `as:` single-target overrides).
- Retrieval uses structural-first tiered scoring with non-compensating confidence ceilings.
- Raw-code escalation stage is implemented (opt-in, late, bounded by query constraints).
- Semantic resolution includes import-aware and owner-aware call target disambiguation.
- Optional persistence adapters exist: in-memory and PostgreSQL (snapshots + unit/call-edge projections + query API).
- Retrieval benchmark suite exists and is integrated into gates (`scripts/run-benchmarks.sh`).
- Retrieval fixtures/benchmarks now include multi-language ambiguity scenarios (Python, Java, Elixir).
- Postgres integration smoke exists in tests (enabled by `SCI_TEST_POSTGRES_URL`) and CI service job.
- Reproducible tree-sitter grammar bootstrap script exists (`scripts/setup-tree-sitter-grammars.sh`) with pinned grammar refs.
- CI runtime gates now install tree-sitter CLI + grammars before running tests.
- Minimal HTTP runtime edge exists (`clojure -M:runtime-http`) and boundary ADR is documented (`ADR-018`).
- HTTP boundary conformance tests exist and run in standard `clojure -M:test` gates (`semantic-code-indexing.runtime-http-test`).
- Minimal gRPC runtime edge exists (`clojure -M:runtime-grpc`) with parity tests in standard `clojure -M:test` gates (`semantic-code-indexing.runtime-grpc-test`).
- Service-mode policy boundary is documented in `ADR-019` and implemented as optional API-key + tenant gate on HTTP/gRPC edges.
- gRPC transport migrated from JSON strings to typed protobuf `google.protobuf.Struct` messages (`ADR-020`).
- Host-integrated authz policy contract is implemented on HTTP/gRPC edges via `:authz_check` callback and optional EDN policy adapter (`--authz-policy-file`, `ADR-021`).
- Java method unit identities are signature/arity-sensitive (`...$arityN$sigXXXX`) to disambiguate overloads.

## Hard Invariants

- JSON Schema is the external contract source of truth.
- `malli` is a runtime mirror, not a competing source of truth.
- Outputs must remain bounded and contract-valid (`context_packet`, diagnostics, guardrails, events).
- If limits are exhausted, stop immediately and wait for explicit user instruction.
- Before any service-backed tests (PostgreSQL or other servers): detect running instance -> shutdown if running -> start fresh with required config -> only then run tests.

## Known Gaps

- No deep compiler-grade semantic resolution yet.
- gRPC still does not use dedicated domain-specific generated proto messages; it uses typed `Struct` as an intermediate step.
- No dynamic external policy backend integration yet (current authz adapter is local file/callback based).
- Rate limiting is delegated to ingress/proxy/host layer and not implemented in runtime edges.
- Tree-sitter path still depends on external CLI availability, though grammar bootstrap is now scripted and pinned.
- Persistence graph queries are retrieval-oriented and not yet a full semantic graph query language.

## Next Execution Priorities

1. Design dedicated domain proto messages for runtime gRPC contracts beyond generic `Struct`.
2. Add reference integration for dynamic external authz backend (tenant policy service/PDP) using the `:authz_check` contract.

## Update Rule

Update this file when any of the following changes:

- runtime behavior materially changes
- new invariants are introduced
- priorities or known gaps change
- integration assumptions change
