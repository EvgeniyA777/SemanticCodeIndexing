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
- Clojure fallback parsing now rewrites alias-qualified calls (`order/validate-order` -> `my.app.order/validate-order`), ignores nested defs inside wrapper forms such as `comment`, links test namespaces back to source namespaces for stronger `related_tests` hints including one helper-namespace hop inside `test/`, emits dispatch-aware `defmethod` unit identities, can rank the correct multimethod implementation from dispatch hints in the query text, and adds recursive graph-level inherited caller edges for custom macros across syntax-quote, list-built, local-helper-generated, top-level-helper-generated, and common composed expansions without leaking plain macro helper functions or unioning conflicting branch-only generated calls.
- Java parser path supports regex mode and optional tree-sitter extraction mode.
- Elixir/Python parser paths are regex-based with class/module-aware symbol and call normalization.
- TypeScript parser supports regex mode and optional tree-sitter extraction mode.
- Elixir parser now supports alias-aware call token expansion (`alias Foo.Bar, as: Baz` -> `Baz.fn()` -> `Foo.Bar.fn` token).
- Elixir extraction now distinguishes form operators (`def`, `defp`, `defmacro`, `defdelegate`) and uses `do/end` balancing for tighter unit boundaries.
- Elixir alias parsing now covers brace aliases and alias-chains (`alias Foo.{Bar,Baz}`, nested alias prefixes, `as:` single-target overrides).
- Elixir semantic-core now also expands unqualified imported calls toward imported modules, propagates implicit imports emitted by `__using__/1`, records per-function arity for same-name identities, prefers same-module local definitions over imported or `use`-expanded collisions with arity awareness, links `defdelegate` units to delegated targets, and surfaces ExUnit test-file linkage through `related_tests`.
- Java semantic-core now uses arity-aware overload resolution for caller/callee linking, handles static-import/class ownership more accurately when matching method calls, suppresses false same-name edges on qualified/static-import collisions, and keeps explicit `this.` / `super.` calls local to the caller class.
- Python semantic-core now expands imported symbols and module aliases toward owning modules, rewrites `self`/`cls` calls toward class-owned methods, preserves explicit module-alias ownership under same-name collisions, supports local class-qualified ownership (for example `OrderService.handle(...)`), and surfaces Python test-file linkage through `related_tests`.
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
- Promotion governance now includes policy-level approval tiers and allow/block auto-promotion constraints, and direct `promote-policy` enforcement now requires explicit manual approval for `manual_approval_required` policies while still refusing `blocked` policies and replay-gate regressions.
- Capabilities are now language-strength-aware: retrieval emits `:selected_language_strengths` plus a derived `:confidence_ceiling`, final confidence is capped by that ceiling after raw-fetch upgrades, guardrails surface `capability_ceiling` signals, and governed replay scorecards include `confidence_ceiling_distribution`.
- Index lifecycle now emits `:index_lifecycle` metadata with TTL-aware stale detection, snapshot provenance, snapshot pinning on the library/storage path, and rebuild reasons; `resolve-context` surfaces stale snapshot state through capabilities and guardrails via `stale_index`.
- Error handling is now taxonomy-backed across library, HTTP, gRPC, and MCP: normalized `ExceptionInfo` ex-data carries `:error_code` / `:error_category`, HTTP responses emit the same fields, gRPC emits them in trailers (`x-sci-error-code`, `x-sci-error-category`), and MCP tool errors expose them in `structuredContent.details`.
- Usage metrics now support SLO-facing rollups through `slo-report`, covering index latency, retrieval latency, cache hit ratio, degraded rate, fallback rate, and policy version distribution for both in-memory and PostgreSQL-backed sinks.
- HTTP/gRPC operational consistency is now tighter: both surfaces accept tenant/correlation metadata (`x-tenant-id`, `x-trace-id`, `x-request-id`, `x-session-id`, `x-task-id`, `x-actor-id`), feed that context into normalized usage events, and preserve correlation markers back to callers (`x-sci-*` headers for HTTP, `x-sci-*` trailers on gRPC errors).
- Phase 5 now includes a closed-loop governance cadence: `resolve_context` usage events retain query/outcome snapshots, `harvest-replay-dataset` can build replay corpora automatically from usage metrics plus structured feedback, difficult cases are promoted into harvested `protected_case` entries, `calibration-report` measures confidence calibration against real feedback outcomes, `weekly-review-report` emits linked `query -> selected context -> feedback -> outcome` artifacts, those review artifacts can be converted back into protected replay datasets for governance, `policy-review-pipeline` can bundle weekly review generation plus registry-backed `shadow-review`, `scheduled-policy-review` can persist timestamped review bundles with manifest-driven review windows and retention, `scheduled-governance-cycle` can retain promotion decisions, emit deterministic `candidate_ranking`, and auto-promote either a single eligible shadow policy or a best-ranked candidate when explicitly allowed, with optional history-aware selection plus `required_candidate_streak_runs` and `promotion_cooldown_runs`, and `governance-history-report` can summarize promoted/skipped trends across retained governance runs.
- `scheduled-policy-review` now also retains standalone `weekly-review-*`, `protected-replay-dataset-*`, and `shadow-review-*` artifacts alongside each bundle and records direct manifest pointers to the latest copies, so Phase 5 review outputs can be reused without reopening the aggregate bundle first.
- Phase 5 orchestration now also maintains retained review/governance indexes plus derived `phase5-review-queue` and `phase5-status-report` outputs, so operators can see pending review work and recent loop state without scanning raw retained artifacts by hand.
- Phase 5 now also has a top-level retained orchestration command, `scheduled-phase5-cycle`, which snapshots the current governance run, queue state, and aggregate status into one first-class artifact stream plus `phase5-run-index.json`.
- Canonical in-repo roadmap status checklist now lives in `docs/roadmap-status.md`, with a dated rationale and status snapshot stored under `notes/`.
- Product roadmap progress is now effectively through the main Phase 4 slices: governed quality loop, language-priority semantic-core deepening, capabilities/calibration, index lifecycle, unified error taxonomy, SLO-facing metrics, tenant/trace consistency, and governance-tier enforcement are in place; the next major tranche is Phase 5 self-improvement automation plus any deeper compiler-grade semantic follow-up.

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
- Language-priority roadmap tail remains open after the current Clojure, Elixir, Java, and Python pushes: TypeScript remains regression-only by design, and Clojure/Elixir/Java/Python still have room for deeper compiler-grade ownership, dataflow, and targeting beyond the current collision and generated-form slices.
- Runtime hardening is now effectively complete for the main roadmap scope; any remaining ops work is incremental polish rather than a missing Phase 4 primitive.
- Real self-improvement loop is now operationally complete for the current roadmap scope: replay harvesting, difficult-case capture, calibration reports, weekly review artifacts, protected replay dataset conversion, retained review/governance runs, queue/status reporting, and top-level retained Phase 5 orchestration all exist.
- Compact-first staged retrieval is now fully aligned as the canonical public flow: `resolve_context` is compact-first, `expand_context` / `fetch_context_detail` are the explicit later stages, selection artifacts are snapshot-bound, and the implementation/docs/examples line is captured by `ADR-024` plus the completed `docs/compact-first-staged-retrieval-plan.md`.

## Next Execution Priorities

1. Keep tightening operational/docs alignment so roadmap, ADRs, examples, and runtime surfaces continue to describe the same canonical flow.
2. Put the next serious engineering pass into genuinely deeper compiler-grade semantic-core tails rather than more Phase 5 infrastructure.
3. Treat further runtime/ops work as incremental polish unless a concrete production gap appears.

## Update Rule

Update this file when any of the following changes:

- runtime behavior materially changes
- new invariants are introduced
- priorities or known gaps change
- integration assumptions change
