# Project Memory

## Purpose

This file stores lightweight operational memory for the project:

- current implementation reality
- key non-ADR decisions
- active assumptions and constraints
- near-term next steps

Use this as a fast session bootstrap before deep-diving into ADRs and code.

For Codex continuation handoff, read
[`reports/017_codex_continuation_handoff.md`](reports/017_codex_continuation_handoff.md)
after this memory file.

## Current State

- Contract layer is established (`contracts/schemas`, `contracts/examples`, `fixtures/retrieval`).
- Clojure-side contract mirror is implemented with `malli`.
- MVP runtime is implemented with public API in `semidx.core`.
- Clojure parser path supports `clj-kondo` primary with regex fallback and optional tree-sitter extraction mode.
- **An absent `clj-kondo` degrades, it does not abort (fixed 2026-09-05).** `clojure.java.shell/sh` *throws* `IOException` when a binary is missing rather than returning a non-zero exit, and `parse-clojure-kondo` wrapped only `edn/read-string` in a `try`, so on a machine without clj-kondo the throw escaped `parse-file` and took the whole index build with it. The `sh` call is now guarded: the lane falls back to the regex parser and emits `kondo_unavailable` plus the underlying message. The unavailable branch is checked **before** `(seq units)`, because when clj-kondo yields nothing the fallback's units are still folded in as `supplemental-units` and stamped `"full"` — without that ordering a degraded parse reports `parser_mode "full"`, which ADR-046 forbids. Covered by `semidx.runtime.languages.clojure-test`. Note the distinction that made this hard to reproduce: a *present but failing* clj-kondo degrades gracefully (18 test failures), an *absent* one threw (166 failures / 11 errors locally, 155 / 3 in CI).
- **tree-sitter invocation inherits the environment (fixed 2026-09-05).** `shared/tree-sitter-cst` passed a three-key `:env` map to `clojure.java.shell/sh`, and `:env` **replaces** the child environment rather than adding to it, so `PATH` was dropped. A native tree-sitter binary survives that; the npm-installed CLI does not, because its `#!/usr/bin/env node` shebang then fails with `node: No such file or directory`. Homebrew ships a native Mach-O binary and CI installs the npm wrapper — which is why this only ever broke on CI. The `XDG_CACHE_HOME`/`TMPDIR` overrides are now merged onto the inherited environment. General lesson for any future `sh` call in this runtime: `:env` is a replacement, never an addition.
- **Elixir tree-sitter named every ExUnit test `test-unnamed` (fixed 2026-09-05).** The lane read the test name off the first `string` descendant, but that node carries no value — the text sits on its `quoted_content` leaf. So the tree-sitter lane disagreed with the regex lane on unit identity for **every** test in the corpus (`.../test-unnamed` vs `.../test-process-order-uses-billing-adapter`). `quoted_content` is read first now, with `string` as a fallback. This was the last red CI check: `run-mvp-gates` ran the suite green and then failed `retrieval_elixir_exunit_module_scenario_001` on a rank-band mismatch, because the fixture names the unit id the regex lane produces. The fixture was right; no expectation was weakened. Invisible on both sides until now because CI never installed grammars and the maintainer's machine never had them configured, so the Elixir tree-sitter path had never actually run in either place. **General lesson: an engine that no environment exercises is an engine nobody is testing** — when two engines are meant to agree on identity, assert that directly.
- **CI needs ripgrep for the benchmark agent (fixed 2026-09-05).** `benchmark-agent/ripgrep-available?` gates lexical-arm competence, and the runner refuses an arm whose tool is missing *before* any provider call, so an absent `rg` surfaced as the live-runner contract test erroring with an empty `usage_matrix`, `pricing_status "unresolved"`, and a nil `cost_usd` that then NPE'd. `rg` is now installed in both CI test jobs. Reproduce this class of failure locally by masking the binary with a stub that exits non-zero.
- **Tests must not assert the absence of a toolchain (2026-09-05).** Three tests encoded the machine they were written on and failed once CI had a full toolchain: `provider-execution` asserted an exact batch list that depends on whether a Java tree-sitter grammar exists; `tree-sitter-cli-resolution-...` asserted that `:tree_sitter_grammars_dir` wins when ADR-047 puts `SEMIDX_TREE_SITTER_CLI_PATH` above it and CI exports one; and `elixir-tree-sitter-falls-back-when-grammar-is-missing` asserted the missing-grammar diagnostic unconditionally while CI installs that grammar. All three now assert whichever branch the environment actually exercises. When adding a toolchain-sensitive test, run it both with and without the toolchain.
- **CI installs the full toolchain (fixed 2026-09-05).** `clj-kondo` was never installed by `.github/workflows/mvp-runtime.yml`, which is why CI had been red on `dev` since April: every Clojure fixture degraded, the confidence ceiling dropped from `high` to `low`, and the retrieval, governance, and semantic-quality assertions failed wholesale. It is now pinned at `2026.01.19` (not `latest`) so CI parses the way a developer machine does. The `runtime-gates-postgres` job additionally lacked Node, the tree-sitter CLI, and the grammars, so it was exercising a degraded runtime for reasons unrelated to PostgreSQL; it now gets the same toolchain as `runtime-gates`.
- Clojure fallback parsing now rewrites alias-qualified calls (`order/validate-order` -> `my.app.order/validate-order`), ignores nested defs inside wrapper forms such as `comment`, links test namespaces back to source namespaces for stronger `related_tests` hints including one helper-namespace hop inside `test/`, emits dispatch-aware `defmethod` unit identities, can rank the correct multimethod implementation from dispatch hints in the query text, and adds recursive graph-level inherited caller edges for custom macros across syntax-quote, list-built, local-helper-generated, top-level-helper-generated, and common composed expansions without leaking plain macro helper functions or unioning conflicting branch-only generated calls.
- Java parser path supports regex mode and optional tree-sitter extraction mode.
- Elixir/Python parser paths are regex-based with class/module-aware symbol and call normalization.
- TypeScript parser supports regex mode and optional tree-sitter extraction mode.
- Elixir parser now supports alias-aware call token expansion (`alias Foo.Bar, as: Baz` -> `Baz.fn()` -> `Foo.Bar.fn` token).
- Elixir extraction now distinguishes form operators (`def`, `defp`, `defmacro`, `defdelegate`) and uses `do/end` balancing for tighter unit boundaries.
- Elixir alias parsing now covers brace aliases and alias-chains (`alias Foo.{Bar,Baz}`, nested alias prefixes, `as:` single-target overrides).
- Elixir semantic-core now also expands unqualified imported calls toward imported modules, propagates implicit imports emitted by `__using__/1`, records per-function arity for same-name identities, prefers same-module local definitions over imported or `use`-expanded collisions with arity awareness, links `defdelegate` units to delegated targets, and surfaces ExUnit test-file linkage through `related_tests`.
- Java semantic-core now uses arity-aware overload resolution for caller/callee linking, handles static-import/class ownership more accurately when matching method calls, suppresses false same-name edges on qualified/static-import collisions, and keeps explicit `this.` / `super.` calls local to the caller class.
- Python semantic-core now expands imported symbols, relative imports, and module aliases toward owning modules, rewrites `self`/`cls` calls toward class-owned methods, preserves explicit module-alias ownership under same-name collisions, supports local class-qualified ownership (for example `OrderService.handle(...)`), and surfaces Python test-file linkage through `related_tests`.
- TypeScript semantic-core now expands named, namespace, and default-import ownership toward the right module targets, keeps `this.` and local class-qualified calls attached to their owning class methods, and emits first-class units for exported function-expression bindings in both runtime and onboarding coverage.
- Retrieval uses structural-first tiered scoring with non-compensating confidence ceilings.
- Raw-code escalation stage is implemented (opt-in, late, bounded by query constraints).
- Semantic resolution includes import-aware and owner-aware call target disambiguation.
- Optional persistence adapters exist: in-memory and PostgreSQL (snapshots + unit/call-edge projections + query API).
- Retrieval benchmark suite exists and is integrated into gates (`scripts/run-benchmarks.sh`).
- Retrieval fixtures/benchmarks now include multi-language ambiguity scenarios (Python, Java, Elixir).
- Retrieval fixtures/benchmarks now include TypeScript baseline and ambiguity onboarding scenarios.
- Postgres integration smoke exists in tests (enabled by `SEMIDX_TEST_POSTGRES_URL`) and CI service job.
- Reproducible tree-sitter grammar bootstrap script exists (`scripts/setup-tree-sitter-grammars.sh`) with pinned grammar refs (Clojure/Elixir/Java/TypeScript) and a repo-managed CLI link at `.tree-sitter-grammars/bin/tree-sitter` when an executable source is available.
- CI runtime gates now install tree-sitter CLI + grammars before running tests.
- Minimal HTTP runtime edge exists (`clojure -M:runtime-http`) and boundary ADR is documented (`ADR-018`).
- HTTP boundary conformance tests exist and run in standard `clojure -M:test` gates (`semidx.runtime-http-test`).
- Minimal gRPC runtime edge exists (`clojure -M:runtime-grpc`) with parity tests in standard `clojure -M:test` gates (`semidx.runtime-grpc-test`).
- Runtime process model: MCP stdio, MCP HTTP, runtime HTTP, and runtime gRPC are long-lived once started; `clojure -M:runtime` remains a one-shot CLI that exits after the request. Cross-invocation reuse for short-lived requests is provided by the `:launcher` alias (see below). MCP stdio reuse stays bound to the MCP host process lifetime.
- Retrieval value proof now explicitly requires negative-utility calibration cases before the Phase 1 verdict run. The first required slice is Zig API-surface/signature extraction vs targeted lexical baseline, Zig container/config field discovery, Zig blast-radius seed correctness, and stale-snapshot-after-edit separation. Current routing: `plans/020` Stage 2 plus `notes/2026-08-27-zig-negative-utility-triage.md`; exact MCP repro data is still pending.
- `plans/020` Stage 2 (2026-08-27) delivered the benchmark substrate: `fixtures/benchmark/task_suite_v1.edn` (9 tasks, 9 task types, semidx plus the external Zig repo `aegis-zig`, all four negative-utility calibration cases with source-verified ground truth), `semidx.runtime.benchmark-suite` (corpus invariants), `semidx.runtime.benchmark-usage` (provider usage adapters, immutable price schedule `2026-08-03-eligible-v1`, response/usage matrix rows, attempt-level aggregation), and `semidx.runtime.benchmark-harness` (run/attempt identity, A/B/C/D arm policies with tool and Arm D command audit, execution budget, isolated workspaces and task mutations, uniform ground-truth scoring, `record-feedback!` write-back). Benchmark attempts tag every semidx call through the usage context: `session_id` is the `benchmark_run_id`, `task_id` is the `task_attempt_id`.
- Benchmark harness invariant: the retrieval query `trace` is a closed contract map (`semidx.contracts.schemas/trace-ref`), so the evaluated agent is carried as `actor_id` and never as `agent_id`; an `agent_id` key makes the whole query fail validation. Cost is aggregated on `cost_usd`, never raw tokens; an attempt with an unresolved or historical-only pricing status is excluded from the cost verdict instead of being priced at zero; a run started on or after the price schedule's `eligible_until` (2026-10-16) is refused.
- `plans/020` Stage 3 (2026-08-28) delivered the aggregator: `semidx.runtime.benchmark-report` plus the `clojure -M:eval benchmark-report` command. It aggregates on `task_attempt_id` before any roll-up to `(benchmark_run_id, task_id, arm)`, re-derives cost from the stored response/usage matrix instead of trusting recorded totals (disagreements are reported), excludes `unresolved` and `historical_only` attempts from the cost verdict rather than pricing them at zero, drops `not_applicable` from the success denominator, pairs the primary A-vs-B comparison per task, verifies pooling identities, and reports the semidx-internal packet cost using selection `estimated_tokens` plus expand/detail `returned_tokens`. `usage-metrics/sink-events` and `sink-feedback` are now public readers, so the report runs against the in-memory sink without PostgreSQL; an offline `--benchmark-records` export path replays records through the same sink protocol.
- Benchmark scoring hardening (2026-08-28, review fixes): freshness evidence is required only from snapshot-bearing arms (policies containing `resolve_context`/`expand_context`/`fetch_context_detail`; an unknown arm fails closed) - such an arm reporting no `snapshot_id` fails with `missing_snapshot_evidence`, a volunteered stale snapshot fails any arm, and a run that cannot supply `current_snapshot_id` for a snapshot-bearing arm is refused (`benchmark_missing_current_snapshot_for_freshness_task`); required facts/symbols match answer text on token boundaries, so a short fact such as `ids` is no longer satisfied by `forbids`; the paired A-vs-B comparison weights success per task (`success_weighting: "task_mean"`) exactly as it weights cost, with the unweighted `attempt_success_rate` and its Wilson interval kept alongside.
- Benchmark verdict guard: the aggregator never emits a Phase 1 verdict while the Stage 0 threshold lock is pending (`verdict: "pending_threshold_lock"` plus a provisional signal and named blockers). An unmet statistical floor or a wall-clock breach is `indeterminate`, never `failure`, because SPEC 5.1 defines the kill criterion on cost and success only.
- `plans/020` live arm runner delivered (2026-08-28): `semidx.runtime.benchmark-agent` plus the `:benchmark-agent` alias. It offers the model only its arm's tool declarations, refuses a breach while still reporting it to the harness audit, refuses uncompetent arms before any provider call (no `rg` for a lexical arm, no `SEMIDX_BENCH_LSP_COMMAND` for Arm C -> preregistered `not_applicable`, no `evaluated_model_revision` to price), observes `snapshot_id` and `context_tokens` itself instead of trusting the model's self-report, records raw Gemini `usageMetadata` per turn, and stops at the execution budget. It runs in process (`live-arm-runner`) or out of process through the `process-arm-runner` JSON contract. No live provider call has been made yet: all tests drive a stub. The PostgreSQL `jsonb` round-trip of the benchmark payload is confirmed by a `SEMIDX_TEST_POSTGRES_URL`-gated test added in Stage 3.
- Known pre-existing failure (not caused by `plans/020`): with PostgreSQL enabled, `semidx.integration.runtime-test/postgres-storage-roundtrip-test` fails — a reload with `:load_latest true` returns 0 units against 163 in the fresh index. It reproduces on a clean worktree at `22263d2`; it needs its own investigation.
- `impact_analysis` now degrades instead of returning blast-radius hints when the seed selection is missing, ambiguous, low-confidence, capability-limited, or stale. The degraded packet preserves empty legacy impact lists and adds `result_status`, `degradations`, `confidence`, and `guardrails`.
- `plans/021` is in progress for persistent JVM runtime reuse. Stage 0 selected `runtime-http` as the first reuse profile. Stage 1 delivered `semidx.runtime.launcher`, a pure state/health/process/lock decision kernel. Stage 2 (2026-08-27) shipped the runnable slice behind the `:launcher` alias: `semidx.runtime.launcher-cli` orchestrates `status`, `start`, `stop`, and `request` over injectable roles in `launcher-state` (per-slot state file, exclusive start lock, log file), `launcher-http` (health client, request client), and `launcher-process` (command, liveness, start, stop). Stage 3 (2026-08-28) completed the `mcp-http` profile end to end: launcher-managed start/status/stop for a local MCP Streamable HTTP endpoint, a `profile-services` health guard so a profile cannot adopt the other profile's server on the requested port (`health_service_mismatch`), `request` refused for `mcp-http` (`request_unsupported_for_profile`), and client config docs for HTTP and stdio hosts. Stage 4 (2026-08-28) closed the track: every command reports `timings` (`start` splits `spawn_ms` from `health_wait_ms`, `request` adds `request_ms` on stderr), stale-state recovery is tested against a real killed process and a real occupied port, and `./scripts/run-launcher-benchmark.sh` measures cold CLI vs warm reuse. Measured on this repo with all three paths doing resolve + fetch-detail: cold CLI 11.6s, launcher `request` 1.7s (6.7x), direct HTTP 47ms (247x), start-to-healthy 1.9s; treat these as order-of-magnitude observations, not constants. Two caveats that matter when quoting those numbers: the launcher removes the repeated index build, not the client's own ~1.4s JVM start, and the first request after a start still pays the index build. `plans/021` is completed; a gRPC profile, supervision, and index-aware readiness are unplanned follow-ups.
- Launcher state lives outside the repository at `~/.cache/semidx/runtime/<workspace_key>-<profile>/` with owner-only permissions, overridable via `SEMIDX_RUNTIME_LAUNCHER_HOME`. `request` forwards to `/v1/retrieval/resolve-context` then `/v1/retrieval/fetch-context-detail`, so its output equals the one-shot `clojure -M:runtime` payload plus the additive `project_context` that every runtime HTTP response carries. Measured on this repo: cold launcher request 12.9s, warm reuse 1.4s, one-shot CLI baseline 11.4s.
- Launcher invariant: a healthy runtime already listening on the requested endpoint is adopted with `owned false` rather than duplicated, and `stop` refuses to terminate a runtime the launcher did not start. Two projects sharing the default port `8787` will therefore share one runtime; pass `--port` per project when independent runtimes are wanted. Reuse stays project-safe because every forwarded request carries `root_path` and the runtime HTTP edge keys its project registry by canonical root.
- Service-mode policy boundary is documented in `ADR-019` and implemented as optional API-key + tenant gate on HTTP/gRPC edges.
- gRPC transport now uses dedicated runtime protobuf envelope messages defined in `proto/semidx/runtime/grpc/v1/runtime.proto`.
- Host-integrated authz policy contract is implemented on HTTP/gRPC edges via `:authz_check` callback and optional EDN policy adapter (`--authz-policy-file`, `ADR-021`).
- Language onboarding automation scripts now scaffold and validate adapter integration steps (`scripts/new-language-adapter.sh`, `scripts/validate-language-onboarding.sh`, `ADR-022`).
- Java method unit identities are signature/arity-sensitive (`...$arityN$sigXXXX`) to disambiguate overloads.
- Offline policy governance now supports registry lifecycle states (`draft`, `shadow`, `active`, `retired`), replay scorecards, side-by-side policy comparison, and promotion gates via `clojure -M:eval`.
- Replay datasets can now mark `protected_case` queries, and promotion gates reject candidate policies that introduce newly failed protected cases.
- Shadow-vs-active operational workflow now exists via `shadow-review`, which evaluates all `shadow` policies against the current `active` registry policy and can persist `:shadow_review` metadata back into the registry.
- Promotion governance now includes policy-level approval tiers and allow/block auto-promotion constraints, and direct `promote-policy` enforcement now requires explicit manual approval for `manual_approval_required` policies while still refusing `blocked` policies and replay-gate regressions.
- Capabilities are now language-strength-aware: retrieval emits `:selected_language_strengths` plus a derived `:confidence_ceiling`, final confidence is capped by that ceiling after raw-fetch upgrades, guardrails surface `capability_ceiling` signals, and governed replay scorecards include `confidence_ceiling_distribution`.
- HTTP/gRPC health endpoints and MCP initialization now expose a versioned capabilities contract (`contracts/schemas/capabilities.schema.json`) detailing language coverage and confidence ceilings.
- Index lifecycle now emits `:index_lifecycle` metadata with TTL-aware stale detection, snapshot provenance, snapshot pinning on the library/storage path, and rebuild reasons; `resolve-context` surfaces stale snapshot state through capabilities and guardrails via `stale_index`.
- Error handling is now taxonomy-backed across library, HTTP, gRPC, and MCP: normalized `ExceptionInfo` ex-data carries `:error_code` / `:error_category`, HTTP responses emit the same fields, gRPC emits them in trailers (`x-sci-error-code`, `x-sci-error-category`), and MCP tool errors expose them in `structuredContent.details`.
- Clojure semantic-core now also traces macro-generated ownership through direct threading macros such as `->`, `->>`, `some->`, and `some->>` when they emit real call targets, while keeping ambiguous threaded branches conservative instead of over-linking both sides.
- Java semantic-core now also emits constructor units and resolves `new ClassName(...)` caller edges arity-sensitively, so constructor targeting is no longer collapsed into method-only matching.
- Elixir semantic-core now also expands `__MODULE__.foo(...)` to the owning local module, which closes another common collision path where imported or `use`-expanded helpers previously competed with explicit local-module qualification.
- Usage metrics now support SLO-facing rollups through `slo-report`, covering index latency, retrieval latency, cache hit ratio, degraded rate, fallback rate, and policy version distribution for both in-memory and PostgreSQL-backed sinks.
- HTTP/gRPC operational consistency is now tighter: both surfaces accept tenant/correlation metadata (`x-tenant-id`, `x-trace-id`, `x-request-id`, `x-session-id`, `x-task-id`, `x-actor-id`), feed that context into normalized usage events, and preserve correlation markers back to callers (`x-sci-*` headers for HTTP, `x-sci-*` trailers on gRPC errors).
- Phase 5 now includes a closed-loop governance cadence: `resolve_context` usage events retain query/outcome snapshots, `harvest-replay-dataset` can build replay corpora automatically from usage metrics plus structured feedback, difficult cases are promoted into harvested `protected_case` entries, `calibration-report` measures confidence calibration against real feedback outcomes, `weekly-review-report` emits linked `query -> selected context -> feedback -> outcome` artifacts, those review artifacts can be converted back into protected replay datasets for governance, `policy-review-pipeline` can bundle weekly review generation plus registry-backed `shadow-review`, `scheduled-policy-review` can persist timestamped review bundles with manifest-driven review windows and retention, `scheduled-governance-cycle` can retain promotion decisions, emit deterministic `candidate_ranking`, and auto-promote either a single eligible shadow policy or a best-ranked candidate when explicitly allowed, with optional history-aware selection plus `required_candidate_streak_runs` and `promotion_cooldown_runs`, and `governance-history-report` can summarize promoted/skipped trends across retained governance runs.
- `scheduled-policy-review` now also retains standalone `weekly-review-*`, `protected-replay-dataset-*`, and `shadow-review-*` artifacts alongside each bundle and records direct manifest pointers to the latest copies, so Phase 5 review outputs can be reused without reopening the aggregate bundle first.
- Phase 5 orchestration now also maintains retained review/governance indexes plus derived `phase5-review-queue` and `phase5-status-report` outputs, so operators can see pending review work and recent loop state without scanning raw retained artifacts by hand.
- Phase 5 now also has a top-level retained orchestration command, `scheduled-phase5-cycle`, which snapshots the current governance run, queue state, and aggregate status into one first-class artifact stream plus `phase5-run-index.json`.
- Canonical in-repo roadmap status checklist now lives in `docs/roadmap-status.md`, with a dated rationale and status snapshot stored under `notes/`.
- Versioned git hook sources now live under `scripts/git-hooks/`, with `scripts/install-git-hooks.sh` installing the tracked pre-push hook into `.git/hooks`.
- Pre-push freshness now has two gates: CCC refresh/check for `docs/code-context.md`, and a `MEMORY.md` freshness check that blocks high-signal project changes unless project memory is updated or explicitly bypassed with `SCI_SKIP_MEMORY_FRESHNESS=1`.
- Agent REPL support is repo-local: `deps.edn` provides the canonical `:nrepl` alias for clojure-mcp sessions (`clojure -M:nrepl`), binding to `127.0.0.1`, selecting an available port, and writing the ignored `.nrepl-port` file for discovery.
- The project test runner now supports focused namespace selection through `clojure -M:test -n <namespace>` / `--namespace <namespace>` while preserving full auto-discovery when no selectors are supplied.
- Runnable contract query examples are intentionally unpinned: they omit `constraints.snapshot_id` so `clojure -M:runtime --query contracts/examples/queries/*.json` and `scripts/run-mvp-gates.sh` can resolve them against the current worktree index while stricter snapshot-bound continuation checks remain enforced elsewhere.
- Product roadmap progress is now effectively through the main Phase 5 slices: governed quality loop, language-priority semantic-core deepening, capabilities/calibration, index lifecycle, unified error taxonomy, SLO-facing metrics, tenant/trace consistency, governance-tier enforcement, and retained self-improvement orchestration are in place; the next major tranche is post-roadmap deeper compiler-grade semantic follow-up.
- The post-roadmap semantic deepening tranche tracked in `plans/003_post_roadmap_semantic_deepening_plan.md` is now fully delivered across Stages 1-8.
- A dated architecture review note capturing vertical/horizontal findings plus meta-architect critique now lives in `notes/2026-03-11-architecture-review.md`; the main takeaways are TypeScript parser-mode drift, one local re-export line-mapping defect, Python nested-scope suppression coarseness, Java direct-super-only inheritance narrowing, and the broader need to split `runtime/adapters.clj` before the next major semantic tranche.
- A `plans/013` post-delivery SOLID review closed five findings: the online policy-control-plane `retire` endpoint now refuses retiring the `active` baseline (only `promote` swaps active, keeping "exactly one active" intact) and idempotently refuses an already-`retired` entry, both as `policy_not_eligible` (409); `run-policy-transition!` scopes its `catch` to the persistence boundary only, so a bug escaping a pure transition surfaces as an internal error via `with-handler` instead of being mislabelled `registry_persistence_failed`; and the relation-traversal public fields were renamed `max_paths` -> `max_discovery_paths` and `paths` -> `discovery_paths` across kernel/`malli`/JSON Schema/MCP/HTTP/gRPC/docs/tests (behaviour-preserving: one deterministic shortest first-discovery path per reached node, no multipath enumeration; see the ADR-040 amendment). Defensive notes were added documenting the rate-limiter `locking` invariant and that the relation-id SHA-1 is non-crypto content addressing whose change would require a `relation-schema-version` bump plus PostgreSQL projection backfill.

## Hard Invariants

- JSON Schema is the external contract source of truth.
- `malli` is a runtime mirror, not a competing source of truth.
- `runtime/language_registry.clj` is the single source of truth for language semantic strength.
- Documentation lifecycle is explicit: current work uses active/accepted
  lifecycle plus `reference_for_context`; completed, archived, and superseded
  documents are historical, and document-type status values follow `RULES.md`.
- Outputs must remain bounded and contract-valid (`context_packet`, diagnostics, guardrails, events).
- If limits are exhausted, stop immediately and wait for explicit user instruction.
- Before any service-backed tests (PostgreSQL or other servers): detect running instance -> shutdown if running -> start fresh with required config -> only then run tests.
- Clojure editing policy (2026-08-27, `RULES.md` -> Clojure Editing Rules): tool choice is risk-based, not extension-based. `Edit` is allowed on `.clj`/`.edn` for narrow edits inside an existing form, and the immediate compile probe afterwards is mandatory because it replaces the delimiter safety that `clojure_edit` provides. Verified `clojure-mcp` behavior behind this rule: `clojure_edit` auto-repairs missing trailing delimiters in submitted content, returns an empty response when an edit changes nothing (an empty response means nothing was written, not success), cannot address plain data files such as `deps.edn` (use `clojure_edit_replace_sexp`), and is path-contained by `:allowed-directories`, which defaults to the server's project and is widened in `~/.clojure-mcp/config.edn`.
- Code reading policy (2026-08-27, `RULES.md` -> Code Reading Rules): tool choice is by the question asked. semidx (`resolve_context`, `impact_analysis`) is always the first call and owns "which code matters"; clojure-mcp `read_file` shows the shape of an already-identified file; `Read` with offset/limit gives exact patch lines; `fetch_context_detail` is for a symbol from an existing selection. semidx and `read_file` do not compete. Two anti-patterns are named explicitly: `read_file` must never replace the first semidx call (opening files one by one to orient is the failure mode semidx prevents), and narrow reads must not be routed through semidx, whose retrieval envelope dwarfs the code for a single symbol. Enforcement is by discipline only: the `semidx-first` guard (`~/.claude/hooks/semidx-guard.py`) matches `Grep|Glob|Bash` and cannot see MCP tool calls.

## Known Gaps

- No full compiler-grade interprocedural semantic resolution across all supported languages yet.
- Stage 5 is complete under ADR-042: `runtime.proto` carries all 16 envelope
  messages and all eight unary RPCs; the pinned repo-managed toolchain generates
  and verifies the committed Java sources; ordinary test/runtime starts perform
  offline idempotent javac; and the runtime now uses generated message and
  `RuntimeServiceGrpc` descriptors with the descriptor-built oracle removed. The
  same toolchain also generates `proto/scip/scip.proto` -> `src-generated/java/scip/Scip.java`
  for the plans/018 JVM SCIP reader (`build.clj` `proto-specs` list); the
  `grpc-generate` / `grpc-verify-generated` drift guard covers both protos.
- No dynamic external policy backend integration yet (current authz adapter is local file/callback based).
- HTTP edge now exposes an online policy control-plane (`GET /v1/policies/registry`, `POST /v1/policies/promote`, `POST /v1/policies/retire`). Offline review emits digest/revision-bound promotion decisions; restricted policies require decision-bound approver records; file authz is deny-by-default per policy operation; and serialized transitions atomically replace the registry file before publishing memory state (Stage 6).
- Stage 7 runtime-edge rate limiting is delivered under ADR-044: HTTP and gRPC
  share an optional, default-off, bounded fixed-window limiter with tenant or
  tenant+actor scope, monotonic windows, unified HTTP 429 / gRPC
  `RESOURCE_EXHAUSTED` errors, retry metadata, and decision-based usage/SLO
  metrics. Ingress/proxy/host remains responsible for distributed/global quotas.
- ADR-046 is the current parser-authority decision for Java and TypeScript:
  fresh SCIP/LSP evidence is primary per operation, tree-sitter fills structural
  gaps, and regex is explicitly degraded fallback. The current regex-first code
  path is a legacy migration baseline under plans/018 and must not be treated as
  target policy. ADR-047 retains repo-managed tree-sitter CLI/grammar resolution:
  explicit parser/provider options, environment configuration, the repo bootstrap
  link, then ambient `PATH` only as developer fallback.
- The semantic graph query surface (gap 7) is delivered as a bounded relation-traversal surface, not a general-purpose graph-query language (deliberate, ADR-040): the Stage 3 kernel is exposed on library + MCP (`traverse_relations`) and executable over a forward-only PostgreSQL `semantic_index_relations` projection at parity with the pure kernel. It is now exposed on all four surfaces: library, MCP (`traverse_relations`), HTTP (`POST /v1/retrieval/traverse-relations`), and gRPC (`TraverseRelations`), all reusing the one kernel/contract.
- Phase 3 roadmap closure is now complete across Clojure, Elixir, Java, Python, and TypeScript; remaining semantic work is post-roadmap deepening rather than an open roadmap tail.
- Clojure fallback/tree-sitter call extraction now suppresses false same-name global edges when calls are actually owned by local params, destructured bindings, `when-let` locals, comprehension bindings, `as->` locals, or `letfn` helper names.
- Clojure multimethod/protocol handling now also emits literal dispatch-aware call tokens for `defmulti`/`defmethod` targeting and first-class `defprotocol` method units, while keeping generic multimethod calls from over-linking every implementation.
- Java semantic-core now also carries direct superclass metadata plus method-reference call tokens, so resolver narrowing can keep `super.` calls, inherited unqualified subclass calls, lambda-owned inherited calls, and `super::method` references attached to the parent implementation instead of same-name overrides.
- Python semantic-core now also preserves decorated `@classmethod` / `@staticmethod` ownership while suppressing nested local def/class over-linking and keeping plain `@property` access conservative instead of turning attribute reads into synthetic call edges.
- Elixir semantic-core now also treats pipeline calls and local captures (`&normalize/1`) as arity-aware caller edges and has regression coverage for `__MODULE__.Nested.foo(...)` nested-module linkage without regressing local-vs-import precedence.
- TypeScript semantic-core now also emits object-literal methods, class field arrow methods, `export default foo` aliases, and direct re-export alias units in both runtime and onboarding coverage while still keeping the public confidence ceiling conservative.
- Confidence recalibration pass kept the public ceilings unchanged: Clojure stays `high`, Elixir/Java/Python stay `medium`, and TypeScript stays `low`, with runtime/docs/tests now explicitly aligned on that non-bump.
- The next frontier after the delivered post-roadmap tranche is the ADR-037 Stage 3 relation-first interprocedural/dataflow v1 slice. ADR-038 makes typed relation facts and snapshot indexes the canonical graph for all new graph semantics; Semantic IR remains extraction-only, while legacy calls/imports stay compatibility projections until a parity-gated migration.
- Runtime hardening is now effectively complete for the main roadmap scope; any remaining ops work is incremental polish rather than a missing Phase 4 primitive.
- Real self-improvement loop is now operationally complete for the current roadmap scope: replay harvesting, difficult-case capture, calibration reports, weekly review artifacts, protected replay dataset conversion, retained review/governance runs, queue/status reporting, and top-level retained Phase 5 orchestration all exist.
- Compact-first staged retrieval is now fully aligned as the canonical public flow: `resolve_context` is compact-first, `expand_context` / `fetch_context_detail` are the explicit later stages, selection artifacts are snapshot-bound, and the implementation/docs/examples line is captured by `ADR-024` plus the completed `plans/002_compact_first_staged_retrieval_plan.md`.
- `plans/019` is the planned LLM one-shot delivery track: add an external `get_context` facade over the same snapshot-bound staged state machine, retain ContextPacket as the structured source of truth, make Markdown an optional bounded projection, and contribute staged/one-shot strategy adapters to the benchmark substrate owned exclusively by `plans/020`. Its top-level response budget is authoritative, `structured`/`markdown`/diagnostic allocations are disjoint, and usage telemetry distinguishes aggregate from stage accounting. ADR-024 remains current; changing the documented canonical default requires comparative evidence and a new ADR.
- `plans/020` is in progress and is the exclusive owner of the real-repository task corpus, immutable `BenchmarkRun`/`TaskAttempt` identities, A/B/C/D strategy harness, provider/API/model usage adapters, price schedules, and success-per-cost aggregation. Stage 1 (`returned_tokens` fidelity) is delivered. Stage 0 now separates harness executor models from `evaluated_*` attempt identities, uses `implicit_cache_observed_v1` (no explicit cache objects; implicit reads recorded), and gates the current Gemini 2.5 schedule at 2026-10-16; calibration/final lock remain pending, and Stage 2 has not started.
- `plans/018` is in progress. Stages 0-3 are delivered and committed (see the numbered next-steps section below for the detail). Stage 3 (TypeScript SCIP) is complete: toolchain preflight, JVM SCIP reader, SCIP->CanonicalFactKey normalization, the shadow/default-off SCIP provider adapter (`semidx.runtime.providers.scip-typescript`) with a per-document stale gate, and the SCIP-vs-Stage-2 shadow comparison harness (`semidx.runtime.providers.scip-shadow-compare`) with latency/size metrics. Stage 4 (Java SCIP) is complete (2026-09-05): preflight (which invalidated Variant C's typed-refinement premise for Java and reproduced a same-arity false-exact-identity defect), a repo-managed external toolchain, language bridges in `scip-normalize`, the shared `scip-adapter` boundary, the `scip-java` adapter, and the same-arity overload guard. Stages 5-7 are not started, and the next stage is an owner decision between Stage 5 (LSP overlay) and a consolidation slice. All of it is additive and default-off: `adapters/parse-file` still owns default Java/TypeScript extraction, and the SCIP adapter is a standalone entry point not wired into `providers.clj` / `provider-selection` / `provider-execution` (project-level batch index vs per-file parse; catalog/planner integration is a scheduled open slice). Multi-provider evidence must normalize to a provider-neutral `CanonicalFactKey` before arbitration; provider ids, native symbols, source identity, and mutable evidence are not stable merge keys. Cross-provider Java overload and TypeScript re-export identity fixtures are Stage 0/1 admission gates (executed as goldens).
- `plans/007` remains an active architecture reference, not an executable queue. It closes only after its continuation ownership, freshness/lifecycle, provider-catalog, relation-parity, public-boundary, and documentation-handoff gates have recorded evidence; a future successor must be self-contained rather than merely linked. On closure its frontmatter becomes `completed` / `historical_reference_only`, and active implementation must use the named successor plans and ADRs instead.
- Stage execution routing is explicit in `plans/018`, `plans/019`, and `plans/020`: Claude Code owns the provider-authority track, while Antigravity owns the benchmark and one-shot delivery tracks. High effort is allowed when justified by contract irreversibility, identity/arbitration, freshness, benchmark verdicts, public defaults, conflicting evidence, or repeated verification failures, and a high-effort handoff must record its concrete justification. Every stage-closing model must read the candidate next stage, cross-plan gates, progress/MEMORY/SPEC state, completed diff and checks, file ownership, and current model/quota constraints, then record a `NextStageRoutingRecommendation`; it may recommend `stop` or `defer` and never auto-bypasses an admission gate.
- Dedicated `impact_analysis` now computes impact hints directly from the resolved selection artifact instead of reading `expand_context`'s budget-gated `:impact_hints` field; it must return a non-null map with `:callers`, `:dependents`, `:related_tests`, and `:risky_neighbors` vectors even when `expand_context` omits impact hints for token-budget reasons.
- MCP `create_index` handles are workspace-root isolated: stale cache entries whose entry `:root_path` does not match the embedded index `:root_path`, or whose cache key points at a different requested root, are discarded instead of being reused; a storage-loaded index with an unexpected root is rebuilt for the requested canonical root.
- Intent-only retrieval with `include_tests` now has a test-aware lexical path: file paths participate in lexical matching, `src/test/...` is classified as test code before generic `src/` source code, and `focus_on_tests` boosts already-matched test units without broadening to unrelated tests.
- Retrieval benchmark baselines are aligned with current runtime behavior again: the synthetic benchmark repo includes JavaScript, HTML, and CSS paths used by fixtures, public context-packet unit kinds are normalized to the contract enum, and fixture confidence expectations follow the current per-language capability ceilings.
- Language adapter extraction has moved the Clojure, Java, Python, Lua, Zig, TypeScript, and JavaScript lanes out of `semidx.runtime.adapters`: `semidx.runtime.languages.clojure/parse-file` owns Clojure regex, clj-kondo, and tree-sitter parsing; the Java and TypeScript parser-engine functions own the current legacy regex/tree-sitter implementation baseline pending plans/018; `semidx.runtime.languages.python/parse-file` owns Python module/import/call extraction, class/method ownership, relative imports, test linkage, and nested-scope suppression; `semidx.runtime.languages.lua/parse-file` owns Lua module/import/call extraction, table/method ownership, module return-owner detection, local call suppression, and test linkage; and accepted ADR-049 makes `semidx.runtime.languages.zig/parse-file` ZLS-primary for exact-current-source definition/test/container-ownership facts while retaining its bounded regex logic for imports/calls and unavailable/error fallback. `semidx.runtime.lsp-client` owns bounded stdio JSON-RPC framing and process lifecycle, one ZLS session is shared per full or incremental parse operation, the Zig provider is `zig-zls` version `2`, and the public low confidence ceiling remains unchanged because call/reference semantics and runtime fallback are still approximate. `semidx.runtime.languages.javascript/parse-file` owns JavaScript dispatch via the TypeScript lane with JavaScript language tagging. `adapters/parse-file` currently dispatches directly to those lane namespaces; plans/018 replaces that Java/TypeScript single-parser authority path with provider planning. `semidx.runtime.languages.shared` owns generic line/signature/token and tree-sitter CLI/config/CST helpers for language lane implementations.
- The language-onboarding scaffold and validator target dedicated `semidx.runtime.languages.<language>/parse-file` modules rather than the removed adapter-private parser stubs; Zig is the first lane onboarded through the corrected flow, and the existing Lua structural validation is green under it.
- ADR-036 Stage 2 is implemented: shared tree-sitter helpers resolve the CLI through `:tree_sitter_cli_path` / `:tree-sitter-cli-path`, `SEMIDX_TREE_SITTER_CLI_PATH`, the repo-managed `.tree-sitter-grammars/bin/tree-sitter` link, then ambient `PATH` only as a developer fallback. Availability is cached per resolved CLI path, language lanes pass parser opts through probe/CST calls, and the bootstrap script can write CLI plus grammar env vars for smoke runs.
- ADR-037 scopes `plans/013` Stage 3: add a typed-relation schema/index boundary first, emit only new `dataflow/local-binding-call-result`, `dataflow/returns-call-result`, and `dataflow/passes-argument` facts as relations, ship Clojure then Python producers, and expose relation-backed gains only through bounded retrieval/impact projections without migrating existing `calls`/`imports` or adding a broad graph query API. ADR-038 establishes that this is the canonical graph boundary for all future providers and graph semantics.
- Stage 3 relation substrate plus the Clojure and Python producers are implemented: `semidx.runtime.relations` owns relation normalization, deterministic IDs, schema versioning, and forward/reverse indexes; Clojure and Python parsing now emit `dataflow/local-binding-call-result`, `dataflow/returns-call-result`, and `dataflow/passes-argument` facts into top-level snapshot relations; `semidx.runtime.index` resolves relation `target_key` values to `target_unit_ids` during index construction without changing existing caller/callee retrieval behavior.
- ADR-039 separates relation identity from mutable resolution/evidence and hardens validation: `relation-id-input` (hence `relation_id`) derives only from `relation_type`, `source_unit_id`, `target_key`, and flow payload (`local_name`/`arg_index`) scoped by schema version, so resolving an unresolved fact or attaching richer evidence enriches one edge instead of minting a new one. `relation-errors` is the explicit internal schema (known `relation-types`/`resolution-statuses`/`evidence-qualities`, resolved-requires-targets, evidence-shape checks); `normalize-relations-with-diagnostics` and `index-relations` surface invalid facts as snapshot `:relation_diagnostics` instead of silently dropping them. `valid-relation?` is now an empty `relation-errors`.
- `semidx.runtime.relations/traverse-relations` is a pure, storage-independent bounded traversal kernel over the relation indexes. Requests specify `:direction` (`:downstream`/`:upstream`), `:start_nodes`, a `:relation_types` allow-list, `:resolved_only` (default true, so ambiguous/unresolved edges are skipped), and `:max_depth`/`:max_nodes`/`:max_paths` clamped to `default-traversal-bounds` (depth 4 / 200 nodes / 50 paths). It is breadth-first, cycle-safe (each node discovered once at its shortest depth), deterministic regardless of set iteration order, and returns `{:nodes :edges :paths :truncated :budgets ...}`. It is internal only and no public graph-query API is exposed in Stage 3; its only consumer is the relation-backed impact projection below. Stage 4 refactored the kernel onto a batched frontier seam: `traverse-relations-with` runs the level-by-level BFS and calls a provider `(fn [frontier-nodes direction] -> {node -> relations})` once per depth level so an execution backend can batch neighbor lookups (no N+1), while all eligibility/fan-out/ordering/cycle/budget policy stays in the kernel; `traverse-relations` is now the thin `in-memory-neighbor-provider` wrapper and its output is byte-identical (parity test in `relations-test`).
- Stage 3 relation-backed impact projections are delivered: `semidx.runtime.retrieval/build-impact-hints` now also consumes `traverse-relations` to attach an optional, reason-coded `:relation_support` field to `impact_hints` (shared by `impact_analysis`, detail, and expansion packets). From the selected units it runs the bounded, `:resolved_only true` kernel under a conservative sub-ceiling (`relation-projection-bounds`: depth 2 / 24 nodes / 12 paths) in both directions: `:downstream` dataflow dependencies and `:upstream` dataflow dependents, returned as distinct `path::symbol` strings excluding the selected units, plus `:reasons` codes (`relation_downstream_dataflow`, `relation_upstream_dataflow`, `relation_traversal_truncated`). The field is omitted entirely when no resolved relation-backed unit is found, so the legacy `:callers`/`:dependents`/`:related_tests`/`:risky_neighbors` outputs stay byte-identical; ambiguous and unresolved relations are never surfaced. The `context_packet` and `expansion-result` `impact_hints` contracts now carry an optional `relation_support` object (`{downstream, upstream, reasons}`) in both the JSON schema and the malli mirror. Confidence ceilings are unchanged (documented non-bump: the projection is additive low-weight support, not a ranking/resolution change).
- ADR-040 fixes the Stage 4 public relation-traversal contract: there is one
  bounded graph walk owned by the Stage 3 kernel; Stage 4 exposes library + MCP
  first, reports HTTP/gRPC as `not_exposed`, and lets PostgreSQL optimize batched
  frontier lookup without owning traversal semantics. The JSON schemas, examples,
  catalog mappings, and malli mirrors for the request/result contract are
  delivered. The batched frontier provider seam in the kernel
  (`traverse-relations-with` / `in-memory-neighbor-provider`) is delivered with a
  proven parity checkpoint. The public library + MCP surface is delivered:
  `semidx.core/relation-traversal` (usage-metrics-wrapped) and
  `semidx.runtime.retrieval/relation-traversal` run the kernel on a loaded
  snapshot, return the compact contract result, and build a stored selection over
  the discovered units so the existing `expand_context` / `fetch_context_detail`
  flow delivers code; the MCP `traverse_relations` tool exposes the same on stdio
  and `usage-operation` gained `traverse_relations`. HTTP
  (`POST /v1/retrieval/traverse-relations`) and gRPC (`TraverseRelations`,
  generated protobuf messages with JSON-string envelope fields) now expose the same contract too,
  reusing the one kernel — so all four surfaces (library/MCP/HTTP/gRPC) are
  aligned. The forward-only PostgreSQL `semantic_index_relations` projection
  and PostgreSQL frontier provider are delivered: `init-storage!` creates the
  projection (source/target frontier indexes), `save-index-tx!` writes it
  forward-only (no historical backfill in migration),
  `storage/pg-relation-neighbor-provider` batches one query per depth level, and
  its output is proven byte-identical to the pure in-memory kernel (with-redefs
  parity plus a `SEMIDX_TEST_POSTGRES_URL`-gated real-PostgreSQL round-trip parity
  test, verified locally against an ephemeral PostgreSQL 17 cluster). Stage 4 is
  code-complete across all four surfaces: the HTTP edge
  (`POST /v1/retrieval/traverse-relations`) and the gRPC edge
  (`TraverseRelations`) now expose the same contract and kernel, so the ADR-040
  phased-exposure follow-up is done.
- Detail-stage raw fetch is now budget-adaptive instead of all-or-nothing: `perform-raw-fetch` measures the full requested-level token requirement (`required_tokens`), degrades the fetch level down the `whole_file -> local_neighborhood -> enclosing_unit -> target_span` ladder to fit the raw-fetch byte cap (`raw_fetch_level_degraded`), and slices the front of an oversized chunk into a partial snippet instead of returning an empty `raw_context` (`raw_snippets_truncated`, `raw_fetch_budget_limited`). When the detail payload is truncated or degraded, the context packet budget, perf `budget_summary`, and stage events carry `suggested_token_budget` (computed by inverting the 10/20/70 stage split and the 35% detail structure share, +10% margin), and the detail result exposes a top-level `next_step` with `recommended_action "raise_token_budget"` so clients can retry once with an adequate budget instead of falling back to manual file reads. The zero-detail-budget path (tiny requested budgets) intentionally still returns an empty, `skipped` raw fetch.
- `plans/016` Stage 2 is delivered: `impact_analysis` now conditionally adds a
  bounded `state_invariants` Slice-1 packet for state/lifecycle queries when an
  entity/model candidate is corroborated by selected, call-graph, or import
  facts. The dedicated `runtime/state_invariants` policy surfaces one entity
  representative per path, selected state writers, evidence-prioritized test
  paths, fixture helpers, and a mandatory whole-file-read guardrail. It makes no
  field-level claims; JSON Schema/Malli and MCP exposure are the next Stage 3.
- `plans/016` is fully delivered through Stage 4. Stage 3 made the
  `state_invariants` packet contract-backed:
  contract-backed. `state-invariants` + `state-invariant-unit-ref` Malli mirrors
  (`contracts/schemas.clj`, registered `:example/state-invariants`), a standalone
  `contracts/schemas/state-invariants.schema.json`, and a validated
  `contracts/examples/state-invariants/impact-analysis-packet.json` (wired into
  the catalog + validator path mapping). `packet_version` uses an independent
  `^[0-9]+\.[0-9]+$` pattern to allow additive evolution. All packet lists,
  including `:triggered_by`, are now bounded to 12. MCP passthrough required no
  reshaping: `tool-impact-analysis` returns the whole hint map, so
  `:state_invariants` rides inside `:impact_hints` and usage-metric counters stay
  additive. Stage 4 additionally exposes the same conditional packet as a
  budget-accounted sibling on `expand_context` and the detail context packet;
  the Malli expansion/context mirrors and JSON context-packet schema accept it,
  and library/HTTP/gRPC parity tests cover the Java lifecycle fixture. When the
  packet cannot fit, staged retrieval emits `state_invariants_omitted` rather
  than exceeding its reserved budget.
- `plans/017` (ADR-045) is fully delivered: the deferred field-level
  state-invariant tranche, with entity fields modeled as relation target keys,
  never units. The Java lane (`languages/java.clj`, both regex and tree-sitter
  paths) emits two additive typed-relation kinds registered in
  `relation-types`: `structure/declares-field` for class-body fields of
  entity-like classes only (entity annotation / entity-or-model path /
  class-or-module suffix; method-body locals excluded), sourced at the synthetic
  `path::module` class node with `target_key` `pkg.Class#field` and
  annotation/nullability hints in `evidence_location`; and
  `dataflow/writes-field` for state-transition methods (writer-named methods or
  entity-class methods) from setter calls (`x.setStatus(..)`) and direct
  `this.field = ...` assignments, sourced at the writer method unit with a
  `field:<name>` sentinel target key. Both kinds have no target unit, normalize
  to `unresolved`, and are skipped by resolved-only traversal, so
  callers/callees, `impact_analysis`, and `relation_support` stay byte-identical.
  `runtime/state_invariants` consumes them: the packet gains an optional
  `entity_fields` section (declared fields + nullability/annotation evidence +
  `state_bearing` hint) and an optional `field_writes` section (fields each
  selected state writer touches), with a guardrail that names state-bearing
  fields and contrasts written vs. declared fields. `packet_version` is now
  dynamic: `1.2` with field writes, `1.1` with only declared fields, `1.0`
  (Slice-1) otherwise. The `state-invariants` Malli mirror, JSON Schema, and the
  example packet carry the additive sections (all lists bounded); both sections
  are present only when relations exist, so lanes/queries without field facts
  keep the exact `1.0` packet. `impact_analysis`, `expand_context`, and the
  detail packet all carry the upgraded packet through the existing
  budget-accounted seams. Non-Java lanes, migration/schema linkage, and richer
  column facts remain deferred to a future plan reusing these relation types.
- Antigravity first-contact MCP behavior is now partially verified in production-like use: it successfully stayed on `create_index -> repo_map -> resolve_context` without drifting into manual browsing, but staged continuation still needs one explicit follow-up check to prove that it will keep using `expand_context` and `fetch_context_detail` via `selection_id` / `snapshot_id` instead of switching back to filesystem reads or broad summarization.
- `plans/007` is now archived as a historical architecture bridge. It must not
  be used as an active execution queue; current continuation ownership lives in
  `plans/018`, `plans/019`, and `plans/020`. The feature inventory in
  root `FEATURES.md` plus
  `ideas/015_semidx_feature_inventory_for_prioritization.md` is the input for a
  future numbered plan after owner prioritization.
- `docs/development-strategy.md` is the active project development strategy:
  prove retrieval value first (`plans/020`), then raise provider authority
  (`plans/018`), then add one-shot delivery (`plans/019`), then choose workflow
  and documentation graph surfaces.

## Next Execution Priorities

1. `plans/020` is PAUSED by the owner (2026-08-28) and must not be resumed
   without them. The staged four-arm experiment is not the measurement approach
   the owner currently wants: the intent is passive telemetry of real working
   sessions — count tokens as the work happens, record whether semidx helped or
   not, aggregate by task and by process, analyse afterwards — instead of a
   synthetic A/B/C/D run with a purpose-built agent. What to build for that is
   an open question the owner is thinking through; the rewritten core idea is
   captured in `ideas/016_session_telemetry_value_measurement.md` (concept
   only - do not implement it without the owner). Its two unresolved gaps are
   who authors the worked/did-not-work verdict and how the host session's own
   token spend is joined to semidx events. Stages 1-3 and the live arm runner are delivered and committed
   but inert; no benchmark run exists and no live provider call was ever made.
   `plans/020`, `reports/021`, and `reports/023` are marked
   `status: blocked` / `agent_action: do_not_use_for_current_work`.
2. `plans/018` Stages 0 and 1 are complete (2026-08-28). The admission
   decisions are settled: Variant C `CanonicalFactKey`, TypeScript-first, and the
   identity fixtures approved. `semidx.runtime.fact-arbitration` now carries the
   pure kernel plus `FactBatch` normalization/validation and `arbitrate-batches`,
   the Stage 0 identity fixtures are executed as goldens by the tests (they used
   to be hand-mirrored, so a fixture correction could not fail a test), and
   identity is proven to survive EDN, JSON, and PostgreSQL `jsonb` round trips.
   Stage 2 is also complete (2026-08-28): `semidx.runtime.providers` (data-first
   catalog, status probes, content-digest anchoring, unit-to-fact roles),
   `provider-selection` (deterministic bounded ProviderPlan that records every
   exclusion), and `provider-execution` (bounded concurrency, timeouts, failure
   isolation, gap tracking, FactBatch emission, shadow entry point). Regex stays
   heuristic, tree-sitter structural — and strictly so: a tree-sitter provider
   whose parse silently fell back to regex is refused
   (`tree_sitter_fallback_refused`) instead of emitting lexical facts under the
   structural claim. Provider `source_identity` digests file bytes on the same
   basis as `workspace-state/sha256-file` and names its `digest_basis`, so
   provider evidence and workspace freshness are comparable; a lines-only digest
   is tagged and must never be compared to it. Execution runs one task per
   (operation, provider), and a provider with no observed status is excluded
   rather than assumed ready. Deliberate deviation: `index.clj` and
   `adapters.clj` were NOT wired to the seam — the shadow path is a standalone
   entry point so "default output unchanged" stays provable; call-site wiring
   belongs with the stage that consumes provider facts. Stage 3 (TypeScript SCIP
   slice) is COMPLETE (2026-09-01); the detail below is the build history. The
   toolchain preflight: `scip-typescript` is
   pinned at `@sourcegraph/scip-typescript@0.4.0` through the repo-managed
   `scripts/setup-scip-typescript.sh` (mirrors the tree-sitter ADR-047 pattern:
   `.scip-toolchain/` install, `SEMIDX_SCIP_TYPESCRIPT_CLI_PATH` override,
   gitignored). The Stage 0 TypeScript corpus gained a committed `tsconfig.json`
   so SCIP output is deterministic, and `scripts/scip-typescript-corpus-snapshot.sh`
   writes the decoded real output to
   `fixtures/provider-authority/scip/typescript-corpus.observed.json`. Verified
   fact that Stage 3 adapter design must honour: `scip-typescript@0.4.0` emits
   **no** alias symbol and **no** SCIP `Relationship` for a re-export
   (`export { normalize as canonicalize }`); both tokens are non-definition
   occurrences resolving to the origin symbol. The re-export edge is recoverable
   only from occurrence resolution on the export statement, so the SCIP tier
   contributes no re-export unit/relation fact — the heuristic/structural tier
   supplies the distinct exported-symbol unit and SCIP corroborates the origin
   resolution. The `typescript-re-export-canonical-key.json` identity fixture was
   corrected to this verified behaviour (the seeded "representative" SCIP form
   claimed an alias symbol + relationship that do not exist); the "alias
   canonicalizes to one origin key" claim is unchanged. Java SCIP and all LSP
   spellings remain representative pending Stages 4/5. The JVM SCIP reader is
   also delivered (2026-08-30): `proto/scip/scip.proto` is vendored verbatim
   from `sourcegraph/scip` v0.5.2 (last tag before the `scip-code/scip` rename;
   field-compatible with the schema bundled in `scip-typescript@0.4.0`, see
   `proto/scip/PROVENANCE.md`), `build.clj` generates its Java stubs alongside
   the gRPC ones through the same ADR-042 repo-managed protoc toolchain
   (`proto-specs` list; `src-generated/java/scip/Scip.java` committed; picked up
   by the existing `grpc-prep/ensure-grpc-classes!` javac step), and
   `semidx.runtime.scip/read-index` parses a `.scip` payload into plain Clojure
   data (metadata, documents with symbols + occurrences, external symbols,
   including `signature_documentation`; enums as lower-kebab keywords;
   `symbol_roles` exposed both raw and as a decoded set via
   `decode-symbol-roles`). `build.clj` strips protoc's trailing whitespace from
   every generated `.java` so the committed stubs pass `git diff --check`. It is a transport-level reader only: no Semantic
   IR / CanonicalFactKey normalization, no source-identity or freshness
   validation, and not wired into any provider yet. Tests read the committed
   `fixtures/provider-authority/scip/typescript-corpus.scrubbed.scip` (real
   `scip-typescript@0.4.0` output over the protected corpus with
   `metadata.project_root` cleared) plus a generated-builder round trip. The
   production reader is deliberately NOT coupled to
   `@sourcegraph/scip-typescript`'s bundled JS module; that module stays a
   preflight-only decoder/scrubber. The SCIP->CanonicalFactKey normalization
   slice is also delivered (2026-08-30): `semidx.runtime.providers.scip-normalize`
   has `parse-scip-symbol` (SCIP symbol grammar -> typed descriptors,
   backtick-escape aware), `scip-symbol->unit` (moniker -> semidx
   `{:owner :symbol :kind :path}` or `{:unmapped <reason>}`), and
   `normalize-index` (SCIP index -> `{:facts [...] :unmapped [...]}`). Identity
   comes entirely from the moniker: leading namespace descriptors up to the file
   extension rebuild the path, `ts-module-name` (now public) gives the owner,
   trailing descriptors give the symbol; a SCIP key matches the regex-tier
   `canonical-fact-key-id` for the same definition. Emits unit facts for
   `function`/method descriptors (`:kind "function"`) and every top-level
   `const`/`let` term (`:kind "term"` — scip-typescript spells arrow-function
   and value bindings identically, so arrow consts merge onto the regex-tier
   unit and a plain value const is an honestly-labelled exact-only term unit for
   the Stage 6 review); classes/fields/constructors/params/externals and
   unparseable symbols are `:unmapped` with a reason, and one bad occurrence
   never aborts the index. `source-identity` is injected by the caller
   (anchor required for the `exact` authority these facts carry); the real
   digest + stale-artifact gate + call-hierarchy facts are the still-unbuilt
   provider-adapter slice. Nothing wired into any provider yet.
   The SCIP provider adapter is also delivered (2026-09-01):
   `semidx.runtime.providers.scip-typescript`. `shadow-facts-for-project` is the
   production entry point — it resolves the repo-managed `scip-typescript` CLI
   through the ADR-047 chain (explicit `:scip_typescript_cli_path` ->
   `SEMIDX_SCIP_TYPESCRIPT_CLI_PATH` -> `.scip-toolchain/node_modules/.bin` ->
   ambient `PATH`), runs `scip-typescript index` once over the project, reads the
   `.scip` with `semidx.runtime.scip`, normalizes with `scip-normalize`, applies
   the per-document stale gate, and returns `fact-arbitration/arbitrate-batches`
   output. A missing CLI is `:result "unavailable"` + `reason_codes
   ["scip_cli_missing"]` with no facts and no throw — the caller degrades to
   tree-sitter/regex; a CLI that runs but errors is `:result "failed"` with a
   `:scip_index_failed` diagnostic. `facts-from-index` (takes an already-read
   SCIP index + `:project-root`) is the test/fixture seam only, not a production
   source mode. Stale gate (reports/024 F2, document-level for this slice):
   each SCIP `Document.relative_path` is resolved under the project root and
   digested (`workspace-state/sha256-file` basis); a missing workspace file or a
   digest that mismatches a caller-supplied `:expected-document-digests` entry
   drops that whole document's occurrences (`:scip_document_source_missing` /
   `:scip_document_stale` diagnostic, `coverage.complete false`), while a fresh
   document's own digest becomes the `content_digest` anchor on its exact
   evidence. Cross-file references from a fresh document to a symbol defined in a
   stale one survive (they are anchored to the fresh file). Deliberate deviation
   (mirrors Stage 2): SCIP is a project-level batch index, not a per-file parse,
   so the adapter is a standalone entry point and is NOT added to the
   `providers.clj` per-file catalog or the ProviderPlan orchestrator; its
   descriptor lives in the adapter ns tagged `:scope :project`.
   Stage 3 close-out (2026-09-01): `semidx.runtime.providers.scip-shadow-compare`
   is the SCIP-vs-Stage-2 comparison harness. `compare-fact-sets` diffs two
   arbitrated fact sets by `canonical_fact_key_id` (agreed / exact-only /
   legacy-only / authority-upgrade); `co-arbitrate` feeds raw SCIP + raw legacy
   facts through one `arbitrate-facts` and asserts shared symbols collapse to one
   canonical fact retaining both providers' evidence — the "no duplicate semantic
   identity" proof; `fact-set-size` / `measure` give the snapshot-size and
   latency numbers the plan's [Medium] risks ask for. Observed on the protected
   corpus: the 4 modelled symbols agree under one key and go heuristic->exact,
   the 2 `index.ts` re-export aliases are legacy-only (SCIP mints no re-export
   unit), co-arbitration yields 6 canonical facts / 0 diagnostics, SCIP carries
   ~2.25 evidence per fact vs 1 for regex, a 3-file SCIP run is ~0.5 s. Two
   additive passthroughs support it: `:raw_facts` on the SCIP result and
   `:raw_batches` on `provider-execution/shadow-facts-for-file`. Stage 3 is
   complete; next is Stage 4 (Java SCIP), which needs a scip-java toolchain pin
   and must factor the TypeScript-specific bridge out of `scip-normalize` without
   a TS regression (the identity fixtures + this harness are the guard).
   Verification: `clojure -M:test` 510 tests / 2928 assertions / 0 failures; the
   end-to-end tests run the real CLI and are skipped when it does not resolve.
   **Stage 4 preflight (2026-09-05) — two owner decisions that changed approved
   contracts.** Real `scip-java` (`semanticdb-javac` 0.12.3 + `scip-semanticdb`
   0.12.3) was run over the protected Java corpus; the Stage 0 SCIP spelling was
   false. Verified: the scheme is `semanticdb` (not `scip-java`); overloads carry
   a **source-order ordinal** (`handle().`, `handle(+1).`) counted over the method
   name across all arities, **not** a typed signature; neither parameter types nor
   arity are in the moniker; arity and a signature string come only from
   `SymbolInformation.signature_documentation.text`, whose types are simple and
   whose param names are included; FQ types exist only as separate occurrences
   inside the declaration range; a Java moniker carries the **package** path, not
   the file path (path must come from `Document.relative_path`, unlike
   TypeScript). **Decision 1: Variant C's typed-refinement claim is INVALIDATED
   FOR JAVA** — the Java exact tier commits
   `{:arity n :signature_precision "arity_only" :signature_key nil :ordinal nil}`;
   native symbol, `+N` disambiguator, and signature documentation are evidence
   only; reconstructing FQ types from occurrence layout was rejected as guessing.
   Variant C's two-layer model itself is unchanged. **Decision 2: same-arity
   overloads must not silently merge (reproduced defect).** With every Java exact
   fact at `arity_only`, a same-arity group has zero typed signatures, so
   `arbitrate-facts` takes its common-case branch and merges two genuinely
   distinct overloads into ONE canonical fact at exact authority with ZERO
   diagnostics — a false exact identity, reproduced in the REPL. The Java adapter
   must detect an all-`arity_only` group sharing
   `(language, path, owner, symbol, arity)`, never emit one exact fact for it,
   emit a diagnostic, and withhold the exact contribution so lower tiers supply
   the units. This is a Stage 4 exit criterion. **Toolchain decision: external
   process, repo-managed** — pinned jars in a gitignored dir + a committed minimal
   Java driver (`scip-semanticdb` has no CLI main), invoked as external
   `javac`/`java` processes; never on the semidx runtime classpath (it declares
   protobuf 3.15.6 against our 3.25.1 via gRPC). No build tool, `pom.xml`,
   coursier, or Scala CLI needed. `parse-scip-symbol` needs NO change: it already
   parses the Java grammar including `+N` and backtick-escaped `<init>`.
   **Stage 4 is COMPLETE (2026-09-05).** Toolchain:
   `scripts/setup-scip-java.sh` installs eight sha256-pinned jars
   (`scripts/scip-java-toolchain/dependencies.txt`) plus the committed driver
   `ScipJavaIndexer.java` into gitignored `.scip-java-toolchain/`; the adapter
   runs `javac` with the SemanticDB plugin then the driver, both as external
   processes. `scripts/scip-java-corpus-snapshot.sh` regenerates
   `fixtures/provider-authority/scip/java-corpus.scrubbed.scip` (byte-stable,
   host-path-free; the driver owns `--scrub-project-root` so Java fixtures do not
   depend on the TypeScript JS scrubber). Code:
   `semidx.runtime.providers.scip-normalize` now dispatches through **language
   bridges** (plain data in `bridges`) — `parse-scip-symbol` is language-neutral
   and needed no change; `normalize-index` requires `:language` because neither
   indexer populates `Document.language`. The Java bridge builds a
   `symbol -> {path, arity}` table from every document's `SymbolInformation`,
   which is what makes a cross-file reference key on the **defining** file;
   owner/symbol reproduce the heuristic lane exactly, so all four corpus units
   have byte-identical `canonical_fact_key_id`s across SCIP and regex.
   Constructors translate `<init>` -> `Class#Class`; nested types, undeclared
   symbols, and unreadable arity are `:unmapped` with a reason (degrade, never
   guess). `semidx.runtime.providers.scip-adapter` is the shared language-neutral
   boundary (stale gate, FactBatch assembly, result shapes, overload guard);
   `scip-typescript` was rewritten to delegate to it, and
   `semidx.runtime.providers.scip-java` is the Java adapter.
   **Same-arity guard (Stage 4 exit criterion):**
   `scip-adapter/withhold-ambiguous-arity-only-overloads` detects an
   all-`arity_only` group whose definitions carry different native symbols,
   withholds every fact in it (references included), emits
   `:same_arity_arity_only_overload_ambiguous`, and sets `coverage.complete`
   false. Contrast is executable as a test: guard on -> 0 facts / 2 withheld /
   diagnostic; guard off -> 1 fact at `exact` / 0 diagnostics (the defect).
   **Evidence contract change:** `fact-arbitration/normalize-fact-evidence` built
   a fixed map and silently dropped unknown keys; it gains `:native_details`, one
   opaque passthrough map (omitted when empty) so a language cannot leak field
   names into the kernel. Java native detail (`+N` disambiguator, signature
   documentation) rides there. Deferred and named: nested types, SCIP
   `Relationship`/implementations, `call/*` relations, catalog/planner
   integration of the project-scoped providers, Java latency/storage metrics, and
   the coverage the two-file corpus cannot exercise (inheritance, static imports,
   method references, entity fields — extending the corpus would change the
   Stage 0 baseline and needs its own decision). Next stage is an **owner
   decision** between Stage 5 (LSP overlay) and a consolidation slice; Stage 5
   must not assume the `java-lsp` typed-signature capability, which the fixture
   deliberately holds at the `arity_only` floor pending real jdtls output.
   **Stage 4 review repair (2026-09-05), two findings, both reproduced first and
   both worse than reported.** S3 (High): `signature-arity` used the FIRST paren
   in `signature_documentation`, but scip-java prefixes the declaration with its
   annotations, so `@Ann(x = 1)` was read as the parameter list — wrong in BOTH
   directions (`f(String,int)` -> 1, `g(String)` -> 2, annotated 3-arg
   constructor -> 1), which can land an exact fact on an arity bucket another
   overload owns. Fixed: the parameter list is located from the declaration name
   (identifier-boundary match, directly followed by `(`, LAST match wins because
   annotations and the return type precede the declaration); constructors are
   located by the class name the text repeats where the symbol says `<init>`;
   unlocatable -> nil -> `:arity-unavailable` degrade. S4 (Medium): the stale
   gate joined project-root with `Document.relative_path` unvalidated, so
   `"../typescript/src/orders.ts"` returned `:fresh` while digesting a file
   OUTSIDE the project, and `"/etc/hosts"` THREW instead of degrading. Fixed:
   `scip-adapter/document-path-problem` rejects blank, absolute, Windows-drive,
   and any `..` segment, with a canonical containment check as defence in depth
   against symlinks; such a document is `:invalid`, dropped without being read,
   reported as `:scip_document_path_invalid`, and tracked in a new
   `coverage.invalid_documents` field (an unsafe path is not a stale one);
   `workspace-digest` no longer throws. Suite after repair: 533 tests / 3077
   assertions / 0 failures.
3. Execute `plans/019` as an additive one-shot delivery track after its budget
   ledger and the `plans/020` run/strategy contracts are accepted. Its evaluation
   stage contributes adapters to `plans/020`; it does not own a second corpus,
   usage normalizer, or scorecard.
4. Keep relation-backed flows conservative: ambiguous facts remain excluded from
   resolved-only traversal, and confidence ceilings change only with replay and
   task-value evidence.
5. Keep roadmap, ADRs, examples, runtime surfaces, and active-plan lifecycle
   metadata aligned with the same canonical staged flow and current ownership.
6. On the next Antigravity touchpoint, explicitly test staged continuation after
   `resolve_context`: require `expand_context` and `fetch_context_detail`, verify
   reuse of `selection_id` / `snapshot_id`, and check that evidence quality
   improves without fallback to manual browsing.

## Update Rule

Update this file when any of the following changes:

- runtime behavior materially changes
- new invariants are introduced
- priorities or known gaps change
- integration assumptions change

The versioned pre-push hook runs a conservative freshness check for this file
when high-signal project files change. If a change is intentionally
memory-neutral, bypass with `SCI_SKIP_MEMORY_FRESHNESS=1` only after checking
the update rule above.
