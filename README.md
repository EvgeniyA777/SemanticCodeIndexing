# Semantic Code Indexing

Library-first architecture for structure-aware code retrieval and context packaging for AI development systems.

## Purpose

`Semantic Code Indexing` is a Clojure-first retrieval subsystem designed to be injected into larger AI orchestration systems as a library.

The project defines how a host system should request code context, how retrieval results should be packaged, and how confidence/guardrail signals should be emitted for safe downstream automation.

## What This Project Does

- defines external contracts for retrieval (`JSON Schema`)
- defines canonical request/response examples and diagnostics examples
- defines retrieval fixture corpus for behavior-based validation
- mirrors contracts in Clojure (`malli`) for runtime validation
- provides a local and CI gate to prevent contract drift
- provides a working in-memory MVP runtime for `create-index`, `update-index`, `repo-map`, `resolve-context`, `expand-context`, `fetch-context-detail`, `resolve-context-detail`, `impact-analysis`, `skeletons`
- now also provides exact `literal-file-slice`, semantic `snapshot-diff`, and offline `semantic-quality-report` evaluation surfaces
- provides a Clojure-first `Code Context Compressor` lane for bounded architecture summaries, dependency-graph export, committed repo context docs, and pre-push/CI drift checks
- includes parser adapters for `Clojure + Java + Elixir + Python + TypeScript` and emits diagnostics/guardrails outputs
- supports versioned retrieval policy overrides plus emitted capability metadata for replayable ranking behavior
- supports optional persistence adapters (`in-memory`, `PostgreSQL`) with snapshot + graph projection storage for PostgreSQL
- supports optional usage metrics adapters (`in-memory`, `PostgreSQL`) for library, HTTP, gRPC, and MCP adoption/usefulness telemetry
- supports structured retrieval feedback plus offline query replay scoring for quality-loop evaluation
- includes retrieval benchmark suite aligned with fixture corpus (`ADR-014`)

## What This Project Does Not Do (Yet)

- does not implement production-grade deep semantic parsing (full compiler-level resolution)
- does not implement full compiler-grade interprocedural resolution across all languages
- does not expose production API server endpoints yet

Current scope is contract architecture plus a working MVP runtime implementation.

## Repository Layout

- `adr/` - architecture decisions (`ADR-001` .. `ADR-025`)
- `docs/` - runtime API and operational docs
- `docs/roadmap-status.md` - canonical in-repo roadmap status checklist
- `contracts/schemas/` - JSON Schema contracts (external source of truth)
- `contracts/examples/` - canonical examples for contract families
- `docs/semantic-stabilization-plan.md` - next internal semantic architecture tranche
- `fixtures/retrieval/` - retrieval fixture corpus (behavior bands)
- `src/semidx/contracts/` - Clojure `malli` mirror and validator CLI
- `src/semidx/mcp/` - MCP core plus stdio and HTTP transports over the core library API
- `scripts/` - local validation entrypoints

## Contract Validation

- Local: `./scripts/validate-contracts.sh`
- CI: `.github/workflows/contracts-validation.yml`

## Runtime Validation and Smoke

Canonical retrieval flow is compact-first staged retrieval:

- `resolve_context` returns a compact selection artifact (`selection_id`, `snapshot_id`, `focus`, `next_step`)
- `expand_context` widens that selection with skeletons and optional impact hints
- `fetch_context_detail` produces the rich detail payload on the exact retained selection artifact
- MCP `resolve_context` accepts a flat top-level `intent` string (simplest: `{"index_id": "...", "intent": "your task"}`), a `query.intent` shorthand, or the full structured `query` object, and returns `compact_continuation` so later MCP stages can reuse `selection_id` + `snapshot_id` instead of growing the prompt
- `resolve-context-detail` remains a convenience helper for callers that still need a one-shot rich result
- Unit/integration tests: `clojure -M:test`
- Setup tree-sitter grammars (optional; Clojure/Elixir/Java/TypeScript): `./scripts/setup-tree-sitter-grammars.sh`
- Scaffold new language adapter onboarding: `./scripts/new-language-adapter.sh <language> --ext .ext1,.ext2`
- Validate language onboarding checklist and gates: `./scripts/validate-language-onboarding.sh <language>` (`--skip-gates` for fast checks)
- Retrieval benchmarks: `./scripts/run-benchmarks.sh` (`--fixture-prefix retrieval_elixir_ --elixir-engine regex` for the Elixir regex baseline subset)
- Offline replay evaluation: `clojure -M:eval --root . --dataset path/to/dataset.json --out "${TMPDIR:-.tmp}/sci-eval.json"`
- Score a policy over a replay dataset: `clojure -M:eval score-policy --root . --dataset path/to/dataset.json --policy-file path/to/policy.edn --out "${TMPDIR:-.tmp}/sci-score.json"`
- Compare baseline vs candidate policy: `clojure -M:eval compare-policies --root . --dataset path/to/dataset.json --baseline-policy-file path/to/baseline.edn --candidate-policy-file path/to/candidate.edn --out "${TMPDIR:-.tmp}/sci-compare.json"`
- Review all `shadow` policies against the current `active` policy and optionally persist review metadata into the registry: `clojure -M:eval shadow-review --root . --dataset path/to/dataset.json --registry path/to/policy-registry.edn --write-registry --out "${TMPDIR:-.tmp}/sci-shadow-review.json"`
- Promote a registry-backed candidate policy if protected metrics do not regress; add `--manual-approval` when a replay-eligible `manual_approval_required` candidate has been reviewed by an operator: `clojure -M:eval promote-policy --root . --dataset path/to/dataset.json --registry path/to/policy-registry.edn --candidate-policy-id heuristic_v1_candidate --candidate-version 2026-03-11 --dry-run --manual-approval --out "${TMPDIR:-.tmp}/sci-promote.json"`
- Harvest a replay dataset from recorded usage events and feedback: `clojure -M:eval harvest-replay-dataset --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index --out "${TMPDIR:-.tmp}/sci-harvest.json"`
- Build a calibration report from recorded usage events and feedback: `clojure -M:eval calibration-report --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index --out "${TMPDIR:-.tmp}/sci-calibration.json"`
- Build a weekly review artifact linking query, selected context, feedback, and outcome: `clojure -M:eval weekly-review-report --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index --out "${TMPDIR:-.tmp}/sci-weekly-review.json"`
- Convert a weekly review artifact into a protected replay dataset: `clojure -M:eval protected-replay-dataset --weekly-review "${TMPDIR:-.tmp}/sci-weekly-review.json" --out "${TMPDIR:-.tmp}/sci-protected-replay.json"`
- Build an offline semantic-quality report over expected snapshot-diff cases: `clojure -M:eval semantic-quality-report --dataset fixtures/semantic-quality/report-dataset.json --out "${TMPDIR:-.tmp}/semantic-quality-report.json"`
- Run the Phase 5 batch loop from usage metrics into weekly review, protected replay dataset, and `shadow-review`: `clojure -M:eval policy-review-pipeline --root . --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index --registry path/to/policy-registry.edn --out "${TMPDIR:-.tmp}/sci-policy-review-pipeline.json"`
- Run the regularized Phase 5 review cycle with artifact retention: `clojure -M:eval scheduled-policy-review --root . --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index --registry path/to/policy-registry.edn --artifacts-dir "${TMPDIR:-.tmp}/policy-review" --retention-runs 8 --write-registry --out "${TMPDIR:-.tmp}/sci-scheduled-policy-review.json"`
- Run the closed-loop governance cadence with optional auto-promotion, best-candidate selection, history-aware selection, streak, and cooldown controls: `clojure -M:eval scheduled-governance-cycle --root . --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index --registry path/to/policy-registry.edn --artifacts-dir "${TMPDIR:-.tmp}/policy-review" --retention-runs 8 --write-registry --auto-promote --select-best-candidate --history-aware-selection --required-candidate-streak-runs 2 --promotion-cooldown-runs 1 --out "${TMPDIR:-.tmp}/sci-scheduled-governance-cycle.json"`
- Run the full retained Phase 5 orchestration cycle as one top-level artifact stream: `clojure -M:eval scheduled-phase5-cycle --root . --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index --registry path/to/policy-registry.edn --artifacts-dir "${TMPDIR:-.tmp}/policy-review" --retention-runs 8 --write-registry --auto-promote --limit 20 --out "${TMPDIR:-.tmp}/sci-scheduled-phase5-cycle.json"`
- Summarize retained governance runs over time: `clojure -M:eval governance-history-report --artifacts-dir "${TMPDIR:-.tmp}/policy-review" --limit 20 --out "${TMPDIR:-.tmp}/sci-governance-history.json"`
- Emit the derived Phase 5 operator follow-up queue from retained review/governance artifacts: `clojure -M:eval phase5-review-queue --artifacts-dir "${TMPDIR:-.tmp}/policy-review" --limit 20 --out "${TMPDIR:-.tmp}/sci-phase5-review-queue.json"`
- Emit the aggregate Phase 5 status report over retained review, governance, and queue artifacts: `clojure -M:eval phase5-status-report --artifacts-dir "${TMPDIR:-.tmp}/policy-review" --limit 20 --out "${TMPDIR:-.tmp}/sci-phase5-status-report.json"`
- Resolve context from query file: `clojure -M:runtime --root . --query contracts/examples/queries/symbol-target.json --out "${TMPDIR:-.tmp}/sci.json"`
- Run stdio MCP server without root restrictions: `clojure -M:mcp`
- Initialize committed code-context artifacts plus pre-push hook: `clojure -M:ccc init --root .`
- Refresh code-context artifacts only when relevant files changed: `clojure -M:ccc refresh --root . --changed`
- Check whether committed code-context artifacts are stale: `clojure -M:ccc check --root .`
- Print bounded code-context summary to stdout: `clojure -M:ccc summary --root .`
- Export code-context artifact on demand: `clojure -M:ccc export --root . --format markdown|json|edn|dot --out path/to/file`
- Run MCP over local Streamable HTTP + SSE without root restrictions: `clojure -M:mcp-http --host 127.0.0.1 --port 8791`
- Enable MCP usage metrics persistence: `SCI_USAGE_METRICS_JDBC_URL=jdbc:postgresql://localhost:5432/semantic_index clojure -M:mcp`
- Enable HTTP/gRPC usage metrics persistence: `SCI_USAGE_METRICS_JDBC_URL=jdbc:postgresql://localhost:5432/semantic_index clojure -M:runtime-http` or `clojure -M:runtime-grpc`
- Summarize compact normalized MCP retrieval memory from a usage-metrics sink in library code: `(sci/compact-mcp-query-memory usage-metrics-sink {:surface "mcp"})`
- MCP does not enforce a `root_path` allowlist; any existing directory visible to the MCP process may be indexed, and stale `SCI_MCP_ALLOWED_ROOTS` launcher config is ignored
- Client-facing MCP responses do not expose internal root restriction state; `health` omits `allowed_roots`
- MCP HTTP server defaults to `127.0.0.1` and supports `--transport-mode dual|streamable|sse`; Streamable HTTP uses `POST /mcp` with `Mcp-Session-Id`, while legacy SSE uses `GET /mcp/sse` plus `POST /mcp/messages`
- Run minimal HTTP edge: `clojure -M:runtime-http --host 127.0.0.1 --port 8787`
- Run minimal gRPC edge: `clojure -M:runtime-grpc --host 127.0.0.1 --port 8789`
- Optional service auth boundary flags: `--api-key <token> --require-tenant` (or env `SCI_RUNTIME_API_KEY`, `SCI_RUNTIME_REQUIRE_TENANT=true`)
- Optional host-integrated authz policy file: `--authz-policy-file /path/to/authz-policy.edn` (or env `SCI_RUNTIME_AUTHZ_POLICY_FILE`)
- Optional runtime policy registry file for HTTP/gRPC: `--policy-registry-file /path/to/policy-registry.edn` (or env `SCI_RUNTIME_POLICY_REGISTRY_FILE`)
- Optional language activation policy file for HTTP/gRPC: `--language-policy-file /path/to/language-policy.edn` (or env `SCI_RUNTIME_LANGUAGE_POLICY_FILE`)
- MCP server accepts any existing directory visible to the MCP process for `create_index`. `SCI_MCP_MAX_INDEXES` defaults to `8`.
- MCP optionally accepts `SCI_MCP_POLICY_REGISTRY_FILE` for active-policy defaults and selector-based `resolve_context` lookup.
- gRPC edge now uses dedicated runtime protobuf request/response messages for unary methods.
- Full MVP gates: `./scripts/run-mvp-gates.sh`
- Advisory semantic-quality report: `./scripts/run-semantic-quality-report.sh`
- CI runtime gates: `.github/workflows/mvp-runtime.yml`
- Runtime API docs: [docs/runtime-api.md](docs/runtime-api.md)
- MCP docs: [docs/mcp-api.md](docs/mcp-api.md)
- Agent MCP prompts: [docs/mcp-agent-prompts.md](docs/mcp-agent-prompts.md)
- Roadmap status checklist: [docs/roadmap-status.md](docs/roadmap-status.md)
- ADR for projection profiles and advisory semantic-quality gates: [adr/029-standardize-projection-profiles-and-advisory-semantic-quality-gates.md](adr/029-standardize-projection-profiles-and-advisory-semantic-quality-gates.md)
- Compact-first staged retrieval execution plan: [docs/compact-first-staged-retrieval-plan.md](docs/compact-first-staged-retrieval-plan.md)
- Post-roadmap semantic deepening plan (delivered tranche): [docs/post-roadmap-semantic-deepening-plan.md](docs/post-roadmap-semantic-deepening-plan.md)
- Semantic stabilization plan (active tranche): [docs/semantic-stabilization-plan.md](docs/semantic-stabilization-plan.md)

## Agent Limit Policy

- If execution/model/tool limits are exhausted, the agent MUST stop work immediately.
- No retries, no fallback execution, no bypass attempts.
- The agent MUST send a short status message: `limit reached, waiting for user instruction`.
- After that message, the agent MUST wait for explicit user instruction before continuing.
- This local policy applies to this repository workflow.

## Service Restart Policy

- Before running integration tests that depend on services (PostgreSQL or any other local server), first check whether an instance is already running.
- If an instance is running, stop/shutdown it.
- Start a fresh instance with the required test configuration.
- Run tests only after the clean restart.

## Agent Onboarding

If an IDE or coding agent supports MCP plus repo-local instructions, this repo is intended to be used in `MCP-first` mode, not manual file-first mode.

Agents should not begin with directory listing, wildcard search, or broad file crawling if the `semidx` MCP server is available and healthy.
A successful `create_index` should be followed by `repo_map`, not by switching back to manual repository inspection.

Recommended first-pass behavior:

1. `create_index`
2. `repo_map`
3. `resolve_context`
4. optional `expand_context`
5. optional `fetch_context_detail`

For `resolve_context`, the simplest call is a flat top-level `intent` string: `{"index_id": "...", "intent": "your task"}`. MCP also accepts `query.intent` shorthand and the full structured `query` object for advanced use. After a successful `resolve_context`, agents should keep the context compact by reusing `selection_id` and `snapshot_id`.

Canonical English prompt snippets for Antigravity-style IDEs, Codex, Claude-style agents, and generic MCP-capable clients live in [docs/mcp-agent-prompts.md](docs/mcp-agent-prompts.md).

Repo-local pickup points:

- Codex / agents that read repository instructions: [AGENTS.md](AGENTS.md)
- Claude-style clients that read root project instructions: [CLAUDE.md](CLAUDE.md)

## Current Contract Strategy

1. `JSON Schema` is the external contract source of truth.
2. `malli` is the Clojure-side runtime mirror.
3. `examples/fixtures` are shared verification artifacts across languages.
4. Staged retrieval is the canonical public contract line: compact selection first, detail later (`ADR-024`).

## Status

Roadmap status is tracked separately in [docs/roadmap-status.md](docs/roadmap-status.md).

- contract layer established (`contracts/schemas`)
- canonical example set established (`contracts/examples`)
- seed fixture corpus established (`fixtures/retrieval`)
- Clojure validation gate implemented (`src/semidx/contracts`)
- MVP runtime implemented (`src/semidx/core.clj`, `src/semidx/runtime/*`)
- Clojure retrieval uses `clj-kondo` as primary parser with fallback path
- Clojure semantic-core now includes alias-aware fallback call resolution, top-level-aware fallback parsing for macro/comment wrappers, namespace-linked `related_tests` hints for direct and helper-mediated test namespaces, dispatch-aware `defmethod` unit identities, dispatch-sensitive multimethod ranking, literal-dispatch caller targeting onto specific `defmethod` implementations, first-class `defprotocol` method units, same-name var disambiguation across aliased namespaces, and recursive graph-level macro-generated ownership for syntax-quote, list-built, top-level helper-generated, threading-macro-generated, and common composed macro expansions such as `concat`, `apply list`, `into`, and conditional branches, with ambiguous branch-only and ambiguous threaded generated calls held back conservatively instead of over-claiming ownership
- Clojure semantic-core now also respects lexical local bindings more accurately in fallback extraction, so params, destructured locals, `when-let` / comprehension bindings, `as->` locals, and `letfn` helper names no longer leak false same-name caller edges toward namespace vars
- Elixir, Python, and TypeScript retrieval paths are implemented in the same runtime adapter pipeline
- Elixir semantic-core now resolves `import`/`use` targets more accurately, expands unqualified imported calls conservatively, propagates implicit imports emitted by `__using__/1` macros, prefers same-module local definitions over imported/use-expanded collisions, does that shadowing arity-sensitively for same-name functions, treats pipeline calls and local captures such as `&normalize/1` as arity-aware calls, resolves `__MODULE__.foo(...)` and `__MODULE__.Nested.foo(...)` back to local module ownership instead of imported collisions, links `defdelegate` units back to their target module functions, and surfaces ExUnit file linkage in `related_tests`
- Java semantic-core now uses arity-aware call resolution for overloads and constructors, respects static-import/class ownership when linking method calls, preserves superclass ancestry for inherited targeting, and resolves `super.` / inherited unqualified calls / `super::method` references more accurately, including same-name local-method vs static-import collisions, explicit `this.`-qualified local ownership, constructor-target disambiguation on `new ClassName(...)`, and lambda bodies that call inherited methods
- Python semantic-core now resolves imported symbols, relative imports, and module aliases more accurately, prefers local module/class ownership over imported-symbol collisions while preserving explicit module-alias calls, links `self`/`cls` and local class-qualified method calls back to class-owned methods, keeps decorated `@classmethod` / `@staticmethod` ownership intact, suppresses false edges from nested local defs/classes through immediate-scope local modeling, keeps methods inside local nested classes from leaking tail-token collisions, preserves conservative `@property` access behavior, and surfaces Python test-file linkage in `related_tests`
- TypeScript semantic-core now lives on a dedicated language module, resolves named, namespace, and default-import ownership more accurately, preserves local `this.` and class-qualified method targeting, recognizes exported function-expression bindings alongside named functions and arrow bindings, and now keeps object-literal methods, class field arrow methods, `export default foo`, and direct re-export alias surfaces aligned across both regex and tree-sitter paths while the public confidence ceiling intentionally remains `low`
- parsed files now also carry additive `semantic_pipeline` metadata so extraction can be stabilized internally without changing the public retrieval contracts
- language entry namespaces now exist for `Clojure`, `Java`, `Elixir`, `Python`, and `TypeScript`, while `runtime/adapters` remains the thin public facade over those parser entrypoints
- multi-language call/symbol resolution has module/class-aware normalization for Java, Elixir, Python, TypeScript
- import-aware and owner-aware disambiguation is applied when resolving ambiguous call targets
- optional tree-sitter extraction path is available for Clojure/Elixir/Java/TypeScript (grammar-path configured)
- tiered structural-first ranking and non-compensating confidence model implemented
- capabilities now emit per-language strength plus a capability-driven `confidence_ceiling`, and guardrails/scorecards account for that ceiling during retrieval evaluation
- index lifecycle metadata now emits TTL/staleness/provenance signals (`index_lifecycle`, stale pinned snapshots, rebuild reasons) across library/runtime surfaces
- library/HTTP/gRPC/MCP now share canonical machine-readable error taxonomy fields (`error_code`, `error_category`; gRPC via trailers, MCP via tool-error details)
- library/HTTP/gRPC/MCP now also share `Language Lane Activation`: first-pass language discovery happens before indexing, unsupported repos return structured core-language guidance, discovery/indexing both ignore common shadow roots such as `.git`, `node_modules`, `.venv`, `venv`, `target`, `dist`, and `build`, service edges keep canonical per-project activation state, and explicit refresh is required before a newly added language lane becomes active
- usage metrics now support SLO-facing summaries (`slo-report`) for index latency, retrieval latency, cache hit ratio, degraded rate, fallback rate, and policy version distribution
- ranking policy is now explicit, versioned, and replayable via `:retrieval_policy`
- offline policy governance now supports registry lifecycle states (`draft`, `shadow`, `active`, `retired`), fixed replay scorecards, side-by-side policy comparison, promotion gates, and registry-backed governance tiers for `auto_promotable`, `manual_approval_required`, and `blocked` candidates, including direct `promote-policy` enforcement plus explicit manual approval for reviewed restricted-tier candidates
- shadow-vs-active operational workflow is available via `shadow-review`, which evaluates every `shadow` policy against the current `active` policy and can persist `:shadow_review` metadata back into the registry
- library retrieval can now resolve the active registry policy or a registry-backed `policy_id`/`version` selector during `resolve-context`
- HTTP, gRPC, and MCP surfaces can now use configured registries for active-policy defaults; `resolve_context` on those surfaces also accepts optional `retrieval_policy` selectors
- replay datasets can now mark `protected_case` queries, and promotion gates reject newly failed protected cases
- late raw-code escalation stage is implemented and controlled by query options/constraints
- PostgreSQL persistence adapter stores snapshots plus unit/call-edge graph projections
- optional usage metrics sinks capture normalized `library`, `http`, `grpc`, and `mcp` usage events plus structured feedback for relevance tracking
- MCP-first retrieval hardening now makes `resolve_context` self-describing for first-contact agents, accepts a flat top-level `intent` string (simplest) or the `query.intent` shorthand ingress, returns repair-oriented invalid-query guidance, exposes compact continuation artifacts for `selection_id`/`snapshot_id` handoff, and retains normalized query summaries that can be read back through `sci/compact-mcp-query-memory` on in-memory or PostgreSQL usage metrics sinks
- Phase 5 now has a full retained self-improvement loop: `resolve_context` usage events retain query/outcome snapshots, replay datasets can be harvested automatically from usage metrics plus structured feedback, difficult cases become `protected_case`, real-feedback calibration reports are available, weekly review artifacts link `query -> selected context -> feedback -> outcome`, those review artifacts can be converted back into protected replay datasets for governance, `policy-review-pipeline` can bundle that flow into a single review artifact plus `shadow-review` handoff, `scheduled-policy-review` can retain timestamped review bundles plus standalone weekly/protected/shadow artifacts and a rolling manifest for recurring runs, `scheduled-governance-cycle` can turn that cadence into retained promotion decisions with optional auto-promotion, deterministic best-candidate selection, history-aware selection, streak gating, cooldown gating, and governance-tier allow/block constraints, `governance-history-report` can summarize promotion/skipped trends across retained runs, Phase 5 exposes a derived operator queue plus an aggregate status report over those retained artifacts, and `scheduled-phase5-cycle` packages the full retained orchestration loop into one first-class artifact stream
- queryable graph access API is available via storage adapters (`query-units`, `query-callers`, `query-callees`)
- fixture-driven retrieval benchmarks are integrated into local and CI gates
- HTTP/gRPC edges now support tenant-aware host authz checks via pluggable `authz_check` contract or EDN policy file
- HTTP/gRPC now also propagate tenant and correlation context consistently: `x-trace-id`, `x-request-id`, `x-session-id`, `x-task-id`, and `x-actor-id` flow into usage events; HTTP echoes them back as `x-sci-*` response headers and gRPC attaches them on error trailers
- HTTP/gRPC keep isolated project contexts per canonical `root_path` (tenant-qualified when a tenant is present), support optional server-level `language_policy`, and return `project_context` activation metadata alongside create/retrieval responses
- when a project activation is already rebuilding, HTTP/gRPC now return transient `language_activation_in_progress` guidance with retry metadata instead of silently starting duplicate builds
- MCP now shares one transport-agnostic core across stdio, Streamable HTTP, and SSE, with session-scoped index caching and consistent tool payloads across transports
- projection profiles are now standardized across structural, summary, selection, widened API-shape, detail, literal-slice, and diff outputs, while semantic-quality CI reporting is available as an advisory artifact lane

## License

Apache License 2.0. See [LICENSE](LICENSE).
