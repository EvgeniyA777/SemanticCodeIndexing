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
- Clojure fallback parsing now rewrites alias-qualified calls (`order/validate-order` -> `my.app.order/validate-order`), ignores nested defs inside wrapper forms such as `comment`, links test namespaces back to source namespaces for stronger `related_tests` hints, emits dispatch-aware `defmethod` unit identities, can rank the correct multimethod implementation from dispatch hints in the query text, and adds recursive graph-level inherited caller edges for custom macros across syntax-quote, list-built, and common composed expansions without leaking plain macro helper functions.
- Java parser path supports regex mode and optional tree-sitter extraction mode.
- Elixir/Python parser paths are regex-based with class/module-aware symbol and call normalization.
- TypeScript parser supports regex mode and optional tree-sitter extraction mode.
- Elixir parser now supports alias-aware call token expansion (`alias Foo.Bar, as: Baz` -> `Baz.fn()` -> `Foo.Bar.fn` token).
- Elixir extraction now distinguishes form operators (`def`, `defp`, `defmacro`, `defdelegate`) and uses `do/end` balancing for tighter unit boundaries.
- Elixir alias parsing now covers brace aliases and alias-chains (`alias Foo.{Bar,Baz}`, nested alias prefixes, `as:` single-target overrides).
- Retrieval uses structural-first tiered scoring with non-compensating confidence ceilings.
- Raw-code escalation stage is implemented (opt-in, late, bounded by query constraints).
- Semantic resolution includes import-aware and owner-aware call target disambiguation.
- Optional persistence adapters exist: in-memory and PostgreSQL (snapshots + unit/call-edge projections + query API).
- Retrieval benchmark suite exists and is integrated into gates (`scripts/run-benchmarks.sh`).
- Retrieval fixtures/benchmarks now include multi-language ambiguity scenarios (Python, Java, Elixir).
- Retrieval fixtures/benchmarks now include TypeScript baseline and ambiguity onboarding scenarios.
- Postgres integration smoke exists in tests (enabled by `SCI_TEST_POSTGRES_URL`) and CI service job.
- Reproducible tree-sitter grammar bootstrap script exists (`scripts/setup-tree-sitter-grammars.sh`) with pinned grammar refs (Clojure/Java/TypeScript).
- CI runtime gates now install tree-sitter CLI + grammars before running tests.
- Minimal HTTP runtime edge exists (`clojure -M:runtime-http`) and boundary ADR is documented (`ADR-018`).
- HTTP boundary conformance tests exist and run in standard `clojure -M:test` gates (`semantic-code-indexing.runtime-http-test`).
- Minimal gRPC runtime edge exists (`clojure -M:runtime-grpc`) with parity tests in standard `clojure -M:test` gates (`semantic-code-indexing.runtime-grpc-test`).
- Service-mode policy boundary is documented in `ADR-019` and implemented as optional API-key + tenant gate on HTTP/gRPC edges.
- gRPC transport now uses dedicated runtime protobuf envelope messages defined in `proto/semantic_code_indexing/runtime/grpc/v1/runtime.proto`.
- Host-integrated authz policy contract is implemented on HTTP/gRPC edges via `:authz_check` callback and optional EDN policy adapter (`--authz-policy-file`, `ADR-021`).
- Language onboarding automation scripts now scaffold and validate adapter integration steps (`scripts/new-language-adapter.sh`, `scripts/validate-language-onboarding.sh`, `ADR-022`).
- Java method unit identities are signature/arity-sensitive (`...$arityN$sigXXXX`) to disambiguate overloads.
- Offline policy governance now supports registry lifecycle states (`draft`, `shadow`, `active`, `retired`), replay scorecards, side-by-side policy comparison, and promotion gates via `clojure -M:eval`.
- Replay datasets can now mark `protected_case` queries, and promotion gates reject candidate policies that introduce newly failed protected cases.
- Shadow-vs-active operational workflow now exists via `shadow-review`, which evaluates all `shadow` policies against the current `active` registry policy and can persist `:shadow_review` metadata back into the registry.
- Canonical in-repo roadmap status checklist now lives in `docs/roadmap-status.md`, with a dated rationale and status snapshot stored under `notes/`.

## Hard Invariants

- JSON Schema is the external contract source of truth.
- `malli` is a runtime mirror, not a competing source of truth.
- Outputs must remain bounded and contract-valid (`context_packet`, diagnostics, guardrails, events).
- If limits are exhausted, stop immediately and wait for explicit user instruction.
- Before any service-backed tests (PostgreSQL or other servers): detect running instance -> shutdown if running -> start fresh with required config -> only then run tests.

## Known Gaps

- No deep compiler-grade semantic resolution yet.
- gRPC message classes are still descriptor-built at runtime; generated Java/Kotlin stubs are not wired yet.
- No dynamic external policy backend integration yet (current authz adapter is local file/callback based).
- HTTP/gRPC/MCP surfaces now support server-configured registries and selector-based `resolve_context` policy lookup, but broader online policy-management/control-plane APIs are still intentionally absent.
- Rate limiting is delegated to ingress/proxy/host layer and not implemented in runtime edges.
- Tree-sitter path still depends on external CLI availability, though grammar bootstrap is now scripted and pinned.
- Persistence graph queries are retrieval-oriented and not yet a full semantic graph query language.
- Automatic replay dataset harvesting from real usage traces and feedback is not implemented yet.
- Language-priority roadmap tail remains open after the current Clojure push: Elixir needs deeper `import/use` and ownership resolution, Java needs stronger import/class ownership plus overload targeting, and Python remains mostly baseline ownership/import resolution.
- Capabilities are not yet language-strength-aware enough to drive per-language confidence ceilings and guardrails.
- Runtime hardening still lacks TTL/stale detection, provenance, snapshot pinning policy, unified machine-readable error taxonomy, and a complete SLO-facing operational metrics set.
- Real self-improvement loop is not implemented beyond usage metrics and explicit feedback sinks.

## Next Execution Priorities

1. Deepen semantic-core for the next roadmap language priority: Elixir first, then Java, then Python, while keeping TypeScript in no-regression coverage.
2. Extend capabilities and retrieval calibration so language strength can influence confidence ceilings, guardrails, and governed scorecards.
3. After language-priority work materially improves retrieval quality, implement Phase 4 runtime hardening: index lifecycle policy, unified error taxonomy, and fuller SLO-facing metrics.

## Update Rule

Update this file when any of the following changes:

- runtime behavior materially changes
- new invariants are introduced
- priorities or known gaps change
- integration assumptions change
