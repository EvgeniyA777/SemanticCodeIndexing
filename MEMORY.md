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
- Public `unit_id` fields use a dedicated opaque handle bound (`unit-id`,
  max 2000) instead of the 240-character display string bound. Long language
  identities, especially Java test method handles that include path + package +
  class + method, must remain addressable through staged retrieval and relation
  traversal contracts without truncation or surrogate encoding.
- `targets_summary` has its own bound (80) rather than the generic 25-item
  `stringArray`. The query schema permits 20 each of paths, symbols, modules and
  tests and `summarize-query` echoes all four into one vector, so the generic
  bound let a legal query produce an illegal packet: 17 paths + 13 symbols
  resolved and expanded, then failed the detail stage with
  `internal_contract_error`. The echo is a diagnostic record of what was asked,
  so it is bounded to match the input rather than truncated.
- MVP runtime is implemented with public API in `semidx.core`.
- Agent attribution and promotional boilerplate are banned in git-facing and
  documentation artifacts. The policy is enforced by
  `scripts/check-agent-attribution.sh`, versioned `pre-commit`, `commit-msg`,
  and `pre-push` hooks, plus the `agent-attribution` GitHub Actions gate.
- Clojure parser path supports `clj-kondo` primary with regex fallback and optional tree-sitter extraction mode.
- **An absent `clj-kondo` degrades, it does not abort (fixed 2026-09-05).** `clojure.java.shell/sh` *throws* `IOException` when a binary is missing rather than returning a non-zero exit, and `parse-clojure-kondo` wrapped only `edn/read-string` in a `try`, so on a machine without clj-kondo the throw escaped `parse-file` and took the whole index build with it. The `sh` call is now guarded: the lane falls back to the regex parser and emits `kondo_unavailable` plus the underlying message. The unavailable branch is checked **before** `(seq units)`, because when clj-kondo yields nothing the fallback's units are still folded in as `supplemental-units` and stamped `"full"` — without that ordering a degraded parse reports `parser_mode "full"`, which ADR-046 forbids. Covered by `semidx.runtime.languages.clojure-test`. Note the distinction that made this hard to reproduce: a *present but failing* clj-kondo degrades gracefully (18 test failures), an *absent* one threw (166 failures / 11 errors locally, 155 / 3 in CI).
- **tree-sitter invocation inherits the environment (fixed 2026-09-05).** `shared/tree-sitter-cst` passed a three-key `:env` map to `clojure.java.shell/sh`, and `:env` **replaces** the child environment rather than adding to it, so `PATH` was dropped. A native tree-sitter binary survives that; the npm-installed CLI does not, because its `#!/usr/bin/env node` shebang then fails with `node: No such file or directory`. Homebrew ships a native Mach-O binary and CI installs the npm wrapper — which is why this only ever broke on CI. The `XDG_CACHE_HOME`/`TMPDIR` overrides are now merged onto the inherited environment. General lesson for any future `sh` call in this runtime: `:env` is a replacement, never an addition.
- **MCP conformance test teardown was racy (fixed 2026-09-05).** `semidx.mcp.server-test`'s stderr reader future runs `line-seq` over the process's error stream; `destroy-process!` kills the process and then derefs that future. The kill closes the stream, `line-seq` throws `IOException: Stream closed`, the future stores it, and the `deref` rethrows — so teardown failed because of its own kill, but only when the reader lost the race. Proof it was flaky, not a regression: on commit `04601b5` the same job **failed** in the push-triggered run and **passed** in the pull_request-triggered run. Fixed in the reader, which owns the stream lifecycle: an `IOException` during shutdown is logged through the existing step log and ends the future normally. Not fixed with a retry — the testing policy treats flakiness as a defect to fix.
- **Elixir tree-sitter named every ExUnit test `test-unnamed` (fixed 2026-09-05).** The lane read the test name off the first `string` descendant, but that node carries no value — the text sits on its `quoted_content` leaf. So the tree-sitter lane disagreed with the regex lane on unit identity for **every** test in the corpus (`.../test-unnamed` vs `.../test-process-order-uses-billing-adapter`). `quoted_content` is read first now, with `string` as a fallback. This was the last red CI check: `run-mvp-gates` ran the suite green and then failed `retrieval_elixir_exunit_module_scenario_001` on a rank-band mismatch, because the fixture names the unit id the regex lane produces. The fixture was right; no expectation was weakened. Invisible on both sides until now because CI never installed grammars and the maintainer's machine never had them configured, so the Elixir tree-sitter path had never actually run in either place. **General lesson: an engine that no environment exercises is an engine nobody is testing** — when two engines are meant to agree on identity, assert that directly.
- **CI needs ripgrep for the benchmark agent (fixed 2026-09-05).** `benchmark-agent/ripgrep-available?` gates lexical-arm competence, and the runner refuses an arm whose tool is missing *before* any provider call, so an absent `rg` surfaced as the live-runner contract test erroring with an empty `usage_matrix`, `pricing_status "unresolved"`, and a nil `cost_usd` that then NPE'd. `rg` is now installed in both CI test jobs. Reproduce this class of failure locally by masking the binary with a stub that exits non-zero.
- **Tests must not assert the absence of a toolchain (2026-09-05).** Three tests encoded the machine they were written on and failed once CI had a full toolchain: `provider-execution` asserted an exact batch list that depends on whether a Java tree-sitter grammar exists; `tree-sitter-cli-resolution-...` asserted that `:tree_sitter_grammars_dir` wins when ADR-047 puts `SEMIDX_TREE_SITTER_CLI_PATH` above it and CI exports one; and `elixir-tree-sitter-falls-back-when-grammar-is-missing` asserted the missing-grammar diagnostic unconditionally while CI installs that grammar. All three now assert whichever branch the environment actually exercises. When adding a toolchain-sensitive test, run it both with and without the toolchain.
- **CI installs the full toolchain (fixed 2026-09-05).** `clj-kondo` was never installed by `.github/workflows/mvp-runtime.yml`, which is why CI had been red on `dev` since April: every Clojure fixture degraded, the confidence ceiling dropped from `high` to `low`, and the retrieval, governance, and semantic-quality assertions failed wholesale. It is now pinned at `2026.01.19` (not `latest`) so CI parses the way a developer machine does. The `runtime-gates-postgres` job additionally lacked Node, the tree-sitter CLI, and the grammars, so it was exercising a degraded runtime for reasons unrelated to PostgreSQL; it now gets the same toolchain as `runtime-gates`.
- **JDK policy (2026-09-05): use JDK 21 for development/runtime parity, keep Java 17 bytecode compatibility.** CI already runs on Temurin 21, and the repo-managed Java LSP toolchain requires JDK 21+, so local development should prefer JDK 21. Do not raise the generated gRPC compilation target without a separate compatibility decision: `build.clj` and `semidx.runtime.grpc-prep` intentionally compile committed Java sources with `--release 17`, keeping clean-clone test/runtime paths usable on Java 17 while still running correctly on Java 21.
- **CI now runs the SCIP provider adapters (fixed 2026-09-05, issue #4).** Neither SCIP toolchain was installed in CI, so the four end-to-end tests of plans/018 Stages 3 and 4 skipped there and the suite still reported green: 3124 assertions in CI against 3128 locally. What ran was only the committed-`.scip` fixture path; the ADR-047 CLI resolution chain, the `javac` + `semanticdb-javac` + `scip-semanticdb` subprocess pipeline, and the freshness gate against a live artifact ran nowhere — the same shape as the Elixir lane defect above. `runtime-gates` now runs `scripts/setup-scip-typescript.sh` and `scripts/setup-scip-java.sh` (both already fail closed on version drift: `npm ci` against the committed lockfile, sha256-verified pinned jars) and sets `SEMIDX_REQUIRE_SCIP_TOOLCHAINS=1`, under which `semidx.test-support.scip-toolchain/unresolved!` fails the test instead of printing a skip — so the install steps are asserted, not trusted. `runtime-gates-postgres` deliberately does **not** install them: it runs the same suite for a PostgreSQL-specific reason, and a second `npm ci` plus eight jar downloads buys no extra signal. The Java toolchain was verified end-to-end under JDK 21 (CI's version), not only the maintainer's JDK 17. **The setup steps deliberately do not export their env files into `GITHUB_ENV`, unlike the tree-sitter step.** The first CI run of this change failed 7 assertions in `scip-typescript-test` exactly that way: ADR-047 ranks `SEMIDX_SCIP_TYPESCRIPT_CLI_PATH` **above** the repo-managed directory, so exporting it job-wide resolved a real CLI for the three tests that force the unavailable branch with `:scip_toolchain_dir "/semidx/does-not-exist"`, and `resolve-cli` stopped returning nil. This is the third instance of the class recorded above — a toolchain-sensitive test meeting an environment it was not run in. The general rule it adds: **installing a toolchain in CI and exporting its override are separate decisions**; export the override only when something actually needs it, because the override outranks the repo-managed path the end-to-end tests are named for and erases the absence branch.
- **CI stopped running the suite four times per push (fixed 2026-09-05, issue #5).** A push to `dev` with a PR open cost ~40 minutes: both workflows fire on `push` and on `pull_request`, so every job ran twice on the same content, and `validate-contracts.sh` ran twice more as stage 1 of `run-mvp-gates.sh`. Three changes. (1) A `concurrency` group on `mvp-runtime` and `contracts-validation` collapses the duplicate pair. The key is `${{ github.workflow }}-${{ github.head_ref || github.ref_name }}` and **not** `github.ref`: `ref` is `refs/heads/dev` on the push and `refs/pull/N/merge` on the pull_request, so keying on it puts the pair in different groups and collapses nothing. Which run survives is arrival order, not configuration — in practice `push` is delivered 2-4s before `pull_request`, so the PR run wins, and the repo has no branch protection or rulesets, so a cancelled run blocks nothing. Re-run a cancelled run from the Actions UI to get the pair back for a flakiness investigation. (2) `runtime-gates-postgres` now runs four namespaces instead of the whole suite. Measured, and the issue's estimate was one off: the full suite is 3128 assertions without `SEMIDX_TEST_POSTGRES_URL` and **3144** with it (not 3145), and `integration.runtime-test` + `runtime.storage-test` + `runtime.fact-arbitration-test` + `runtime.benchmark-report-test` alone go 821 -> 837, so those four hold the entire delta. Narrowed, not deleted: this job is what caught the snapshot round-trip defect from `reports/021`. (3) `run-mvp-gates.sh` no longer calls `validate-contracts.sh`. No coverage gap — `semidx.contracts.validator` requires only `semidx.contracts.schemas`, so nothing outside the `Contracts Validation` workflow's own path filter can break it.
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
- CI runtime gates now install tree-sitter CLI + grammars before running tests, plus both SCIP toolchains (`runtime-gates` only) with `SEMIDX_REQUIRE_SCIP_TOOLCHAINS=1` so an unresolved toolchain fails instead of skipping.
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
- **FIXED 2026-09-05 — the long-standing PostgreSQL roundtrip failure.** `semidx.integration.runtime-test/postgres-storage-roundtrip-test` reloaded 0 units against 163. Cause was not storage but enrichment: a snapshot read back through `clojure.data.json/read-str` with `:key-fn keyword` has its `:units` map keys **keywordised** (the reader keywordises every object key, and unit ids are used as data keys there), while `:unit_order` is a JSON array whose entries stay **strings**. `semantic-id/enrich-index` looked each `:unit_order` string up in that keyword-keyed map, matched nothing, and returned an empty `:units` — while `snapshot_id`, `files`, and `unit_order` all loaded fine, which is why it looked like storage losing units. Units are now keyed by the `:unit_id` inside each unit, so the lookup no longer depends on key serialisation; a unit absent from `:unit_order` is also retained (the vector is an ordering hint, not the membership list). Covered by `semidx.runtime.semantic-id-test` without needing a database. Verified against an ephemeral PostgreSQL 17 cluster: 541/3120/0 with postgres, 545/3145/0 with postgres + grammars.
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
- `plans/018` is in progress. Stages 0-3 are delivered and committed (see the numbered next-steps section below for the detail). Stage 3 (TypeScript SCIP) is complete: toolchain preflight, JVM SCIP reader, SCIP->CanonicalFactKey normalization, the shadow/default-off SCIP provider adapter (`semidx.runtime.providers.scip-typescript`) with a per-document stale gate, and the SCIP-vs-Stage-2 shadow comparison harness (`semidx.runtime.providers.scip-shadow-compare`) with latency/size metrics. Stage 4 (Java SCIP) is complete (2026-09-05): preflight (which invalidated Variant C's typed-refinement premise for Java and reproduced a same-arity false-exact-identity defect), a repo-managed external toolchain, language bridges in `scip-normalize`, the shared `scip-adapter` boundary, the `scip-java` adapter, and the same-arity overload guard. Stage 4.5 (project-scoped provider consolidation) is complete (2026-09-05): the two SCIP descriptors now live in `providers.clj` with an explicit `:scope`, `provider-selection` has `project-plan` plus an optional `:batch_coverage` input to per-file planning, and the new `semidx.runtime.provider-batch` runs each admitted project provider once and delivers its facts into per-file arbitration. Stage 5a (LSP overlay seam + TypeScript live provider) is complete (2026-09-05): `semidx.runtime.provider-overlay` is the language-neutral live-overlay boundary, `semidx.runtime.providers.lsp-typescript` is its first real backend, and `fact-arbitration` finally reports equal-authority contradictions. Stage 5b (Java LSP) is complete (2026-09-05) after the owner chose a repo-managed sha256-pinned jdtls tarball, so Stage 5 is closed; the default-off sub-stages 6a and 6b are complete (2026-09-06, own entries below), Stage 6 proper and Stage 7 are not started, and Stage 6 is gated on comparative evidence from the paused `plans/020`. All of it is still additive and default-off: `adapters/parse-file` owns default Java/TypeScript extraction, and a project provider is planned only when a caller supplies both an observed status and coverage from a completed run. Multi-provider evidence must normalize to a provider-neutral `CanonicalFactKey` before arbitration; provider ids, native symbols, source identity, and mutable evidence are not stable merge keys. Cross-provider Java overload and TypeScript re-export identity fixtures are Stage 0/1 admission gates (executed as goldens).
- `plans/022` is the passive session telemetry track, and **Stage 0 is complete (2026-09-05)**. It activates what already existed rather than building a layer: `UsageMetricsSink`, the PostgreSQL sink, `safe-record-*`, event identity, rollups and reports were all built and simply switched off. Enabling is one variable, `SEMIDX_USAGE_METRICS_JDBC_URL`, and the schema self-creates on first write. Owner decisions of record: a task boundary is **declared, never inferred** (`session_id` = session, `task_id` = one goal, `request_id` = one tool call; no id means ungrouped); `SPEC.md` §5.1 is **not** rewritten, because observability gives admission evidence and not a value verdict, so `plans/020` stays the path to a real verdict and its artefacts stay as fallback; collection starts with interactive MCP surfaces only. **Four Stage 0 findings, measured against a live database and real servers, not read off the source** (`reports/026`): (1) the MCP **HTTP** transport built no sink at all, so a host on that transport produced no telemetry *silently* — fixed and tested; (2) **`resolve_context` loses `session_id`**: the query `trace` *overwrites* the server session id instead of refining it, so a client sending no trace leaves it null on the one operation that matters while its neighbours carry the server id — events do not group today, and this must be fixed before `task_id` means anything; (3) **`selected_paths` / `selected_unit_ids` are not on the MCP event payload** (they exist on the library path), so the "was the file read afterwards inside the returned selection?" rule is not computable from the database — it stays computable from the host transcript, which retains the full MCP result per call; (4) **the raw intent text is recorded by default** in `normalized_query_summary.details`, while source code is never recorded anywhere — an owner decision (hash, truncate, or opt-in) before collection widens. semidx can never record host token cost, cache splits, or price: it sees only what it returned, so those enter through an offline transcript join and never as runtime event fields. **Stage 1a is complete (2026-09-05)**: the owner made findings (2) and (4) an input gate to task identity rather than parallel work, because a `task_id` on top of an inconsistent `session_id` groups events inside a session that does not group. Two rules now hold in `mcp/core.clj` `record-mcp-event!`: fields a request did not supply are dropped before merging, so a trace **refines** identity and never erases it; and the **server session id wins outright**, since it is the identity of the MCP session itself. Query text is redacted by default — telemetry stores `details_hash` + `details_chars` and keeps `purpose`/`target_keys`/`token_budget`, with raw text only under `SEMIDX_USAGE_METRICS_CAPTURE_QUERY_TEXT=1`. **Redaction is telemetry-only: the `normalized_query_summary` returned to the caller is unchanged**, and a test asserts that, because the tempting implementation redacts both. Verified on the live database, not only in unit tests: all five events of one session now share one server session id while the client's trace fields survive. Test-writing lesson repeated here: adding a `status` assertion revealed the identity tests had been exercising the *error* path, because a canonical query needs a UUID `trace_id` and at least one `targets` entry. Suite 623 / 3370 / 0. A client `session_id` that loses precedence is kept as `payload.client_session_id` (owner decision, 2026-09-06), recorded only when it differs from the server's, because that is what an offline transcript join keys on. **Stage 1b is complete (2026-09-06)**: task identity is a **session-scoped declaration through a new MCP tool `set_task_context`** — not `initialize` (a transport handshake, while one session runs several tasks) and not a per-call argument (`task_id` describes the working context, not one retrieval, and repeating it would widen every tool schema). Every later event inherits the declared task, so the staged flow groups into one attempt; clearing is explicit (`{task_id: null}`) because a task never expires on its own. `resolve-identity` is shared by `session_id` and `task_id`: session-scoped wins, a losing per-call value is kept as `payload.client_task_id` / `client_session_id` only when it differs, and with nothing declared the `query.trace` value still applies so the old contract keeps working. Verified live across declare → staged flow → switch → clear. Two existing tests pinned the exact tool list and failed as designed when the tool appeared — that is a public-surface assertion doing its job. Suite 630 / 3389 / 0. **Stage 2 is complete (2026-09-06)**: `semidx.runtime.session-telemetry` is the read-only offline join, split into a Claude Code transcript adapter and neutral rules. It **holds mechanically** — transcripts retain the full MCP result, so `focus[].path` recovers the selection the event does not carry, and the host token split (`cache_read`/`cache_creation`/`output`) that semidx can never see is there too. **What does not hold is the verdict rule**: `ideas/016` never bounds "what the agent did next", and measured across the four real sessions there are 0 to 429 tool calls between consecutive retrievals, so attributing them all to one retrieval describes the session. The distribution moves with the window — 241fd815 shows zero `out_of_selection_read` at window 3 and two at window 10 — so **a session reads as a miss or not depending on a number nobody has justified**, and choosing it by which value flatters the result is the exact failure `ideas/016` warned about. Volume was first reported as ten retrievals across four sessions with the gap blamed on rotated transcripts; **both were wrong (corrected 2026-09-06)** — nothing rotated, and the count had looked only at this repository. Across all projects: **144 semidx calls in 35 sessions** (UniPlan 46, ReaderLens 46 and still in use, this repo 22, JobApplicationTracker 22, Zig-aegis 8). One global MCP server serves every project, so telemetry now covers all of them and `root_path_hash` separates them. **The bias is global, not local** (corrected 2026-09-06 after the owner pushed back on a wrong claim): `~/.claude/CLAUDE.md` mandates semidx-first in *every* project and the project-level `CLAUDE.md` files never mention semidx, so all 144 calls are instruction-following and **no sample of unprompted choice exists anywhere**. Observational data can show how semidx performs when used and what the agent does after a retrieval; it cannot show whether an agent would choose it, nor whether it beats the alternative — those need withholding (`ideas/016`) or a comparative arm (`plans/020`), which is a stronger argument for keeping that track alive than the volume question was. `trace_verdict_policy_v1` therefore has two named prerequisites: an attribution window with a basis, and sessions to test it on. Re-query is classified but **never scored as a miss** (two of ten retrievals were followed immediately by another). The database half (`join-events`) is implemented and tested, pairing positionally within a session and reporting `:join_basis` because there is no shared identifier, but it is **not yet exercisable on real paired data**: the host that produced these transcripts ran without `SEMIDX_USAGE_METRICS_JDBC_URL`. Telemetry is now **live**: the host MCP server runs with `SEMIDX_USAGE_METRICS_JDBC_URL` (added to the `semidx` entry in `~/.claude.json`), and a real Claude Code session was verified end to end on 2026-09-06 — `set_task_context` -> `create_index` -> `resolve_context` all carried one server `session_id` and the declared `task_id`, `actor_id` was `claude-code`, and the query text was redacted (`details_chars` only). The Stage 2 join was then closed on that real pair: 1 transcript retrieval against 1 database event, confidence agreeing, 0 unpaired. **`plans/022` now lists five blockers before any `trace_verdict_policy_v1`**, three of them newly named: (1) what one task *is* — undefined, and finer slicing flatters every later metric; (2) **who may declare a task boundary** — today the agent declares its own, so the measured party defines its own denominator and re-declaring after each success would improve the statistic with no bad intent, which means an agent-authored boundary must be reported as a self-report rather than a measurement; (3) **who authors the verdict** — separate from the boundary, because behaviour is not agreement (a read from the selection can mean "found it" or "started double-checking"). Plus the two already known: the attribution window and volume. Nothing task-level is computed until all five are answered; raw events stay valid under any answer, a metric on a prematurely chosen boundary does not. **Three of the five are now answered (2026-09-06) by taking observable boundaries instead of inventing them.** The unit of attribution is the **user turn** — one real user prompt to the next — chosen because automatic segmentation of a query stream into tasks is an open research problem in IR at far larger scale, and because the turn is the only boundary that is both observable and **not drawn by the agent** (separable in practice: 37 real prompts against 481 tool-result and 6 meta records in one session). `task_id` stays as the declared semantic label on top, and divergence between observed turn and declared task is itself a signal. That also resolves blocker 2: the denominator no longer depends on what the agent declares. Blocker 4 split in two — **staged continuation needs no window at all** because `expand_context`/`fetch_context_detail` carry the retrieval's `selection_id` (a reference, like a span to its parent), and the old heuristic was wrong about it, reporting two staged continuations where there was one; **everything else is attributed to the end of the turn**. Measured: attribution range narrowed from 0–453 calls to 4–81. `boundary-comparison` keeps fixed-window figures as evidence for the choice, not as a knob. Still open: who authors the verdict, and volume and the fact that `selected_paths` is absent from MCP events, which by owner decision stays a Stage 2 offline-join concern rather than a runtime schema change.
- **`plans/018` Stage 6a is complete (2026-09-06): the provider pipeline now runs where real indexing happens, default-off.** `index/provider-pipeline-mode` is `:off` or `:shadow` (keyword or string, unknown falls back to `:off`); in shadow mode every provider-eligible path in a build goes through `provider-execution/shadow-facts-for-file` and reduces to an additive `:provider_summary` — counts, authority distribution, diagnostic codes, latency, never the facts themselves. The summary rides on the `create_index` usage event, which is the provider summary `plans/022` asked for. A throwing provider is counted as a failed observation and can never fail a real build. **The key is conditional, not nil-valued**: a snapshot is serialized, diffed and round-tripped, so an always-present key would change the shape of every default build — a test asserted the absence and caught exactly that mistake. Why this was worth doing before Stage 6: the pipeline had never executed during an actual index build (only fixtures and standalone shadow entry points), so there was nothing to compare against the path in use, and `plans/022` has since made real sessions observable. Authority, confidence, `parser_mode` and the default extraction path are untouched — Stage 6 still owns all four, and remains gated on comparative evidence from the paused `plans/020`.
- **`plans/018` Stage 6b is complete (2026-09-06): the shadow observation now says how the exact tier and the legacy tier relate, during an ordinary build.** The build runs `provider-batch/shadow-facts-for-project` instead of per-file `shadow-facts-for-file`, so project providers run once and their coverage reaches per-file planning — the Stage 4.5 seam that 6a did not use. This was the limit of 6a: file-scoped planning probes only `#{:tree-sitter :regex}`, so SCIP and LSP could never run in a real build and the observation had nothing to compare against. `provider_summary` gains `:providers` (per-provider `result` plus fresh / stale / invalid / uncovered document counts) and `:comparison` (`agreed`, `exact_only`, `legacy_only`, `authority_upgrades`, `multi_provider_symbols`). Counts only: `scip-shadow-compare/project-report` stays the single owner of what the comparison means, and the symbol lists it returns are facts that do not belong on an event. The SCIP adapters are resolved on first use and `target/classes` is on the `:mcp` / `:mcp-http` classpaths — before that the deployed server could not load the project seam at all, because the generated protobuf classes are a build output only the test aliases carried (`ClassNotFoundException scip.Scip$Diagnostic`); an unbuilt classpath now reports its own `scip_runtime_classes_unavailable`, since that and a broken probe are different operator problems. A related defect fixed just before it: the MCP surface passes `:suppress_usage_metrics true` and emits its own event from a literal map, so on the only surface that produces real sessions `provider_summary` was silently absent; now covered by a test that drives `handle-tools-call`. Measured on the protected Java corpus, where the exact tier is `ready`: 5 agreed symbols, 5 authority upgrades from heuristic to exact, 1 exact-only, 0 legacy-only, 5 symbols collapsing to one canonical fact carrying both providers. Measured on this repository, which is neither a Java nor a TypeScript project: both providers `failed` with `scip_index_failed` and the comparison reads `legacy_only 1404` — correct and readable, but the failed attempts cost about 5.3 s of the 6.1 s that shadow mode adds to a 14 s build. That retry cost was the gap this stage recorded and Stage 6c closed. What this closes: the first of the two questions Stage 6 bundles — does the switch change facts, and in which direction — answered locally, on one prepared corpus, without `plans/020`. What it does not close: whether the switch helps an agent finish a task. That needs `plans/020` or an equivalent comparative/withholding arm; accumulated telemetry alone cannot answer it, because `plans/022` blocker 5 records that every recorded semidx call is instruction-following under the global semidx-first mandate, so observational data shows how semidx performs when used and not whether it beats the alternative.
- **`plans/018` Stage 6c is complete (2026-09-06): a project provider that already failed on a workspace is no longer rerun on every build.** `semidx.runtime.provider-negative-cache` is a process-local, injectable cache consulted inside `run-one-project-provider` — not in `project-statuses`, because a status probe answers whether a provider *could* run while a remembered negative answers whether running it again *can produce anything*. Three guards, in this order of importance. **Default deny**: `cacheable-negative-result?` is false for anything it cannot classify — a throw, a contract violation, `scip_runtime_classes_unavailable`, an unnamed failure, or any result whose codes are not all on the allow-list. The generic `:scip_index_failed` is the only code either SCIP adapter emits today, for every failure mode including a caught exception, so it is admitted only with eligibility evidence behind it: the catalog descriptor declares `:project_manifests` (new field, `package.json` / `tsconfig.json` / `jsconfig.json`, `pom.xml` / `build.gradle*` / `settings.gradle*`) and none of them is present. A TypeScript project whose compile broke today is retried, not suppressed. **A five-minute TTL**, so even a correct negative expires and an `npm install` costs one stale build at most. **The provider stays visible**: a hit returns `:result "skipped"` — a new `result-states` value, produced by the boundary rather than by a provider — carrying `cached_negative_result` with the original failure code, fingerprint, and expiry, so `provider_summary` distinguishes a toolchain problem from a policy decision instead of losing a tier. Invalidated by: manifest presence/size/mtime, the observed status minus `:observed_at` (it carries the resolved toolchain identity, so installing a toolchain invalidates the entry without this namespace duplicating adapter resolution logic), the forwarded provider options minus `:expected_document_digests` (per-file content, which changes on every edit and says nothing about eligibility), and the provider version. `:provider_negative_cache false` disables it entirely — the rollback path. In memory only on purpose: a disk tier needs schema versioning, cleanup, corrupted-file handling and cross-branch behaviour, none of which the measured problem — repeated builds in one long-lived MCP process — calls for. Measured over three consecutive shadow builds in one process on this repository: provider time 11.1 s, then 3.9 s, then 4.1 s, with the file-scoped observation unchanged (the same 99 `equal_authority_value_conflict` diagnostics). Suite: 655 tests / 3496 assertions / 0 failures, and the classifier was verified by disabling it and watching the eligibility tests fail.
- **`plans/018` Stage 6 is under way (owner waived the `plans/020` gate on 2026-09-06); sub-stage 6.1 is complete (2026-09-07).** Stage 6 is decomposed 6.1-6.5 in the plan, with two owner decisions recorded there: degradation is labelled unconditionally (regex-only Java/TS projects `parser_mode: fallback`, heuristic evidence, reduced confidence — including the common no-toolchain case), and an `equal_authority_value_conflict` annotates rather than blocks (the same-arity arity-only overload guard stays the one exception). **The waiver is recorded, not hidden: the switch is admitted on evidence about facts — the Stage 6b Java-corpus comparison, protected replay, existing fixtures — and nothing in Stage 6 may be reported as evidence that the authority model helps an agent finish a task.** 6.1 delivered `semidx.runtime.provider-authority` plus `index/provider-pipeline-mode :authority`: the project tier runs once per build before parsing, and per file the parse stays the source of unit shape while arbitrated facts decide what it is worth — a matching fact raises `:authority` and records `:evidence_providers`, a fact with no parsed counterpart becomes a unit, a contradicted unit is annotated `:evidence_conflict` and kept. **A prerequisite defect was found and fixed (`7fde363`): `tree-sitter-fallback-diagnostic` read every `tree_sitter_*` code except the CLI probe as a degradation, and a successful structural parse emits `tree_sitter_active` — so the tree-sitter tier refused itself wherever a grammar actually worked, the pipeline could only ever observe heuristic evidence, and Stage 6 would have labelled every Java/TS file degraded regardless of toolchain.** Two implementation facts worth keeping: the file is never parsed twice (the tier that produced the parse answers the pipeline from those units through the injected `run-provider` role, and which tier that is comes from the parse result, never the request), and arbitration drops `:value`, so units built from facts recover kind and signature from the pre-arbitration batches. Measured on the committed TypeScript corpus, `:off` vs `:authority`: same six units, four now `exact` with evidence from both `scip-typescript` and `typescript-regex`, two still `heuristic`; 0.38 s vs 4.7 s, the difference being the SCIP index run. `:authority` is **not** the default yet — the workspace fingerprint does not separate the two authority models until 6.3, so a snapshot built under one could be reused under the other.
- **`plans/018` Stage 6.2 is complete (2026-09-07): the index now says what it knows and what it guessed.** In `:authority` mode a unit whose only evidence is heuristic is labelled `parser_mode "fallback"`, a file whose every unit is heuristic is labelled `fallback` and carries a `provider_authority_degraded` diagnostic naming the excluded providers, and the file's `:semantic_pipeline` record is kept in step. **This needed almost no new confidence machinery: `retrieval-policy/coverage-level` already counts fallback units and `confidence-ceiling` already caps a fallback-only selection at `low`, so honest labelling is the recalibration.** The other half goes upward: `evidence-strength` lets a *wholly* exact selection lift a language past its static strength to `high`, because TypeScript is rated `low` on account of a regex guessing rather than a SCIP index resolving; a partly exact selection keeps the static strength (mixed evidence is as good as its weakest member, the rule the ceiling already used across languages), and structural evidence lifts nothing. Measured on the committed TypeScript corpus, `:off` vs `:authority`: 6 units both ways; parser modes 6 full vs 4 full + 2 fallback; coverage `full` vs `mixed`; 0 vs 1 degraded file. **The pre-existing dishonesty this exposes: a regex-only index used to report coverage `full`.** Suite: 660+ tests, 0 failures.
- **`plans/018` Stage 6.3 is complete (2026-09-07): the authority model is part of workspace identity, so snapshots are never served across models.** **The hole was silent: `capture-workspace-state` knew nothing about the pipeline mode, so an `:authority` build and an `:off` build of the same files produced the same fingerprint and whichever ran second got the other's snapshot.** `provider-authority/authority-model` (mode + `authority-policy-version` + catalog version + provider versions + languages) now rides in the workspace manifest and the fingerprint, and `freshness/decide-freshness` compares it directly, forcing a **full** rebuild ahead of the delta rule — an incremental update would leave untouched files labelled under a model that no longer applies. Two invariants: the model is **nil for `:off`** and the key is omitted rather than nil-valued, so a pipeline-free build hashes exactly what it hashed before Stage 6 and every older snapshot stays reusable (`:shadow` and `:authority` each get their own model); and provider versions come from the catalog rather than the plan, because the question is whether two builds *could* have produced the same snapshot and a provider absent on one machine is what a plan would hide. Verified end to end on in-memory storage: repeated `:authority` reuses (same snapshot id — caching intact), `:off` after it rebuilds with units carrying no authority, switching back rebuilds again. **Defect found and only partly fixed: `index-lifecycle` resolves the rebuild reason through a whitelist that sends everything unrecognised to `initial_build`. `authority_model_changed` is carried through now; `no_prior_manifest`, `manifest_schema_incompatible`, `provider_or_pipeline_version_changed` and `delta_exceeds_threshold` are still mislabelled and are recorded in `bugs/002`.** The default flip is deliberately not here: the owner asked for it after 6.5, so it lands as a one-line change once surfaces and gates are in place.
- **`plans/018` Stage 6.4 is complete with one recorded gap (2026-09-07): the surfaces now carry what the runtime already knew.** **An authority build used to report nothing** — `provider_summary` was shadow-only, so switching the pipeline on cost the operator the observation. `provider-authority/build-summary` builds it from what the build produced rather than re-running the pipeline (which is what borrowing the shadow path would have cost), and it is not the shadow summary with a different `:mode`: `:comparison` is absent because shadow compares two tiers neither of which is the snapshot while here one of them *is* the snapshot, and `:units_supplied` / `:units_conflicted` / `:files_degraded` exist only in this mode. `:project_elapsed_ms` is the project tier alone — per-file provider work is interleaved with parsing and is not honestly separable. **Capabilities described a ceiling the server can now beat**: since 6.2 a wholly exact selection rises above the static per-language `confidence_ceiling`, so the payload gained `provider_authority` (policy version, languages, modes, `evidence_raises_confidence_ceiling`), with the JSON Schema, the malli mirror and the committed example updated together; it is static by design and never depends on the last index. Carried by library, MCP (usage event **and** tool response) and HTTP `POST /v1/index/create`, always as a conditional key so a pipeline-free build answers exactly what it answered before. **Known gap: gRPC `CreateIndexResponse` has no field for it and adding one needs `protoc`, which is not installed here; documented in `docs/runtime-api.md` next to the endpoint — gRPC clients read the summary from usage metrics or use HTTP.**
- **`plans/018` Stage 6.5 is complete (2026-09-07): gates run, two results that need reading rather than ticking.** Pass: `clojure -M:test`, `validate-contracts` (72 files), `run-mvp-gates`, TypeScript onboarding including gates. **The semantic-quality `advisory_failure` is pre-existing, and that was verified rather than assumed**: the same report run at `4fa9107` (the commit before the first Stage 6 change) in a scratch worktree is identical field for field — `expected_change_match_rate` 0.833, `implementation_vs_meaning_accuracy` 0.667, `gate_eligible` false. **The Java lane fails its own onboarding checklist with 10 errors and has always failed it**: four required artifacts (onboarding doc, two named retrieval fixtures, mirrored integration test) have never existed in git history, while the fixtures Java does have use other names; recorded as `bugs/003`. It does not block Stage 6 — it is a checklist gap, not an authority-path failure — but a gate that already fails cannot fail usefully at the flip, so it should be settled first. CCC artifacts were stale and were refreshed. New stage-owned gates live in `test/semidx/integration/provider_authority_gates_test.clj` (the standing gates all run the default path and say nothing about the new mode): the switch loses no unit the default path produced, retrieval still answers under it, and the confidence ceiling follows the evidence rather than the lane. ADR-046 gained an **Amendments** section recording the two owner decisions as policy of record; `docs/mcp-api.md` and `docs/runtime-api.md` document `provider_authority` and `provider_summary`. **Remaining before the flip: the owner's call on `bugs/003`, then the flip itself — one line in `index/provider-pipeline-mode`.**
- **`plans/023` is complete (2026-09-08): the language onboarding checklist now applies uniformly, and `bugs/003` is fixed.** Owner decision: the same requirements for every lane, with the repository brought up to the bar rather than the bar lowered. **The starting split was exact - the six lanes `new-language-adapter.sh` scaffolded (typescript, javascript, lua, zig, html, css) passed; the four that predate the checklist (clojure 9, python 9, java 10, elixir 11) failed.** Two checks were testing a spelling rather than a property and were corrected instead of satisfied: elixir's named parse-file wrapper and its deliberate lazy `requiring-resolve` wiring are now accepted shapes. Produced where genuinely missing: `docs/language-onboarding/java.md` (the only lane with none), real onboarding docs replacing the clojure/python/elixir placeholders, four `test/semidx/integration/*_onboarding_test.clj` regression tests, and eight retrieval fixtures registered in the corpus - every expectation measured on a real run before being written, never guessed. **The find that justifies the whole exercise: the Java class regex accepted only a bare `public` modifier, so `public final class Normalizer` was never recognised and every method of such a class was attributed to an invented `UnknownClass` - three of the four Java units in the benchmark corpus carried a fabricated owner.** That breaks retrieval by class name and, worse for `plans/018`, makes the heuristic tier's canonical fact key unable to ever agree with a SCIP/LSP key for the same method. Fixed with a regression test; recorded as `bugs/004`. It survived because the Java lane had no fixture asserting a symbol - which is precisely what uniform requirements would have caught. Verification: all ten lanes 0 errors, benchmarks 31/31, contracts ok (80 files, was 72), suite 681 tests / 3636 assertions / 0 failures. **Nothing from `bugs/003` blocks the flip any more; the flip itself remains one line.** `bugs/002` (rebuild-reason whitelist) is untouched and still open.
- **The `plans/018` authority default flip was attempted on 2026-09-08 and reverted the same day; `bugs/005` is what blocks it.** **Making `:authority` the default disables impact analysis and the entire state-invariant feature for Java on any machine without a semantic toolchain** — measured on a two-file entity fixture: same 3 units, same 5 relations, but the state-invariant packet goes from complete to empty. Chain: a unit whose only evidence is heuristic is labelled `parser_mode "fallback"` (Stage 6.2) -> `coverage-level` reports `fallback_only` -> confidence ceiling `low` -> `impact-seed-degradations` calls the selection degraded -> `impact-analysis` returns its stub and never assembles the packet. **The cause is a vocabulary collision, not the owner's labelling decision**: `parser_mode "fallback"` already meant "the parser could not extract structure", and Stage 6.2 gave it a second meaning, "the evidence is heuristic". A successful regex parse that produced methods, fields and relations is not a failed parse, and consumers keyed to the first meaning cannot tell them apart. Fix that unblocks the flip: keep `parser_mode` for extraction failure, keep the evidence tier on `:authority`, and have `coverage-level` / `selected-language-strengths` read `:authority` for the heuristic case — the way `evidence-strength` already reads it for the exact case. **Kept from the attempt**: provider-supplied units carried an empty `:signature`, which fails the context packet contract with `internal_contract_error` as soon as such a unit reaches retrieval; only an authority build can produce them, which is why nothing had hit it. Fixed in `unit-from-fact`. Default is `:off` again, suite green at 681 tests / 3636 assertions.
- **The flip landed on the second attempt (2026-09-08): `:authority` is now the default provider pipeline mode, and `bugs/005` is fixed.** The fix was to take `parser_mode` back: `provider-authority` no longer writes it, the evidence tier stays on `:authority`, and the file still announces degradation through its own diagnostic. The state-invariant packet came back complete and the twenty-odd cross-system failures vanished; the thirteen that remained were premise-only and now name `off` explicitly. `:off` is the rollback by name; an unrecognised mode resolves to the default rather than silently opting a caller out. Measured: this repository 14.8 s -> 16.7 s with all 5081 units kept (1631 labelled heuristic); the Java corpus with a toolchain 34 ms -> 482 ms and **5 units -> 6, all exact**. Gates: suite 681/3636/0, benchmarks 31/31, contracts ok. **Two consequences worth remembering.** A snapshot must not contain a clock: the authority summary's `project_elapsed_ms` made two identical builds differ, which `snapshot-diff` would report as a change and which contradicts ADR-046's determinism driver — removed (shadow's `total_elapsed_ms` has the same flaw and was left alone as opt-in). And **the confidence reduction from owner decision 1 is not in effect**: lowering the ceiling for a heuristic-only selection was the exact mechanism that disabled impact analysis, because `impact-seed-degradations` reads low confidence as "cannot reason about this". Degradation is explicit on `:authority`, in the file diagnostic and in `files_degraded`, but the ceiling stays at the language strength. Restoring a reduction needs that gate taught the difference between heuristic evidence and absent structure — open in `bugs/005` for the owner.
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
- MCP stdio startup is a host-integration gap, filed as
  `bugs/001_mcp_stdio_startup_exceeds_host_handshake_timeout.md`. Measured
  `initialize` latency is 16.68 s cold and 20.38 s warm because
  `scripts/start-mcp-server.sh` runs `clojure -M:mcp` from source on every host
  session. Codex CLI enforces a 10 s handshake timeout and aborts session
  bootstrap when the server is marked required. The runtime-reuse work in
  `reports/025` covered `runtime-http` only and explicitly deferred MCP reuse,
  so no warm path exists for this entry point. `bugs/` is the new home for
  defect reports and follows the standard `NNN_slug.md` and frontmatter rules.

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
   Stage 0 baseline and needs its own decision). Catalog/planner integration was
   the item the owner picked next, and it is now **Stage 4.5, COMPLETE
   (2026-09-05)** — see below. Stage 5 must not assume the `java-lsp`
   typed-signature capability, which the fixture deliberately holds at the
   `arity_only` floor pending real jdtls output.
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
   **Stage 4.5 is COMPLETE (2026-09-05): the SCIP providers are now ordinary
   participants of the catalog, planner, and execution boundary, still
   default-off.** Descriptors carry an explicit `:scope`; the two SCIP claims
   moved from the adapter namespaces into `providers/project-descriptors` and the
   adapters re-export them, so the dependency runs adapter -> catalog and the
   per-file planning path never loads the generated protobuf classes. Roles (a
   status probe and a run function per provider) live in the new
   `semidx.runtime.provider-batch`, the only namespace requiring both adapters.
   `provider-selection/project-plan` reuses `plan-operation`, so project
   admission has the same rules and the same recorded exclusions as per-file
   admission, and `provider-plan` takes optional `:batch_coverage` /
   `:batch_statuses`; with neither supplied the plan is provably the pre-stage
   plan (asserted by emptying `project-descriptors` under `with-redefs`, not by a
   hand-written expectation). Facts reach per-file arbitration through the
   already-injectable `run-provider` role keyed by `(path, operation)`, so
   `provider-execution` needed no behavioural change. **Two real defects were
   reproduced and fixed on the way in.** `providers/provider-status` returns
   `ready` for every non-tree-sitter engine, so a catalogued SCIP descriptor
   would have been declared ready with no toolchain probe at all — it now refuses
   a non-file scope (`provider_scope_not_file`). And `run-provider`'s
   `parse-with-engine` dispatches on **language, not engine**, so a SCIP id would
   have been parsed by the language lane and its regex units returned under the
   descriptor's `exact` claim — it now throws. **Admission signal: coverage, not
   selectors.** A project provider is a candidate for a file only when a
   completed run reported that path covered, so unavailable/failed runs, stale
   documents, and path-invalid documents all degrade with no separate branch.
   **Injection seam: `:project_roles`, never the runner** — the first version
   injected the runner itself, which bypassed the failure-isolation try/catch and
   made isolation untestable. `scip-shadow-compare` gained `project-report` /
   `project-shadow-report`: the two tiers are split back out of the merged
   per-file runs by descriptor scope, so the comparison is language-neutral.
   Measured on both protected corpora with real toolchains, every document fresh,
   zero arbitration diagnostics: TypeScript 4 agreed / 0 exact-only / 2
   legacy-only (the re-export aliases) / 4 authority upgrades, ~2.1 s; Java 5
   agreed / 1 exact-only (`Validator#Validator`, no regex constructor unit) / 0
   legacy-only / 5 upgrades, ~0.9 s. Suite: 574 tests / 3215 assertions / 0
   failures, plus contracts, MVP gates, and CCC. Deferred and unchanged: index
   and `adapters.clj` wiring (Stage 6 owns the default switch), SCIP
   relationships and `call/*`, nested Java types; newly named: batch providers
   run sequentially, which is right at two providers.
   **Stage 5a is COMPLETE (2026-09-05): the live-overlay seam plus a real
   TypeScript backend, opt-in and default-off.** The owner split Stage 5 into 5a
   (language-neutral seam + one complete real provider) and 5b (Java), because a
   seam proved only against mocks would have its lifecycle assumptions rewritten
   when a real server arrived, and jdtls is separate infrastructure.
   `semidx.runtime.provider-overlay` owns the roles, one session per operation,
   source identity, the failure taxonomy, coverage, and delivery into per-file
   arbitration through the same injected `run-provider` role the batch tier
   uses; `providers.lsp-typescript` is thin. **Four things the real server taught
   us, all reproduced**: (1) `typescript-language-server` locates TypeScript from
   the *workspace*, not from beside itself, so a workspace without
   `node_modules/typescript` fails `initialize` unless `tsserver.path` is passed
   in `initializationOptions` — which the LSP client could not send, hence the
   new passthrough; (2) the `typescript` pin is load-bearing — an unpinned
   install resolved 7.0.2, whose native compiler ships **no `lib/tsserver.js`**,
   so the toolchain pins 5.9.3 and the setup script fails closed on its absence;
   (3) `ProcessBuilder` resolves a relative program against the process's working
   directory, which is the *workspace*, so both resolvers must return absolute
   paths — the same trap Stage 3 recorded for `clojure.java.shell/sh`, in a
   different API; (4) `textDocument/references` returns nothing once the document
   is closed (so the document is opened once and held, not via
   `text-document-symbols!`), and `File.toURI` produces `file:/Users/...` while
   the server answers `file:///Users/...`, so URIs must be compared as decoded
   paths or every location is discarded silently. **Kernel defect found by
   writing the required merge test**: `merge-one-canonical-fact` discards
   `:value` entirely, so two `exact` tiers describing one definition differently
   produced one silent canonical fact — Stage 1's "equal-authority
   contradictions are observable" was not actually true. `arbitrate-facts` now
   emits `:equal_authority_value_conflict`; identity, authority, and the merge
   are unchanged, only fields present in both values are compared, and unequal
   authority is never a conflict. **Two planner generalisations**:
   `:batch_statuses` became `:observed_statuses`, merged only where
   `providers/locally-probed?` is false, so an external status can never override
   a real tree-sitter probe; and an unobserved external tier no longer widens the
   operation set — putting `typescript-lsp` in the catalog initially added a
   permanently-gapped `references` operation to every TypeScript plan, so
   `providers/statuses` now returns only what it can probe rather than
   present-and-unavailable entries. Source identity decides overlay scope by
   itself: disk text anchors on the file-bytes digest (the same anchor the other
   tiers use), a live buffer anchors on `overlay_text_sha256` plus the document
   version, so dirty evidence cannot pose as a claim about the file. Failure
   taxonomy is seven kinds — the plan's six plus `server_error` for a JSON-RPC
   error response — and all seven are reachable in a test. Suite: 602 tests /
   3303 assertions / 0 failures with both `SEMIDX_REQUIRE_*_TOOLCHAINS` set, so
   both end-to-end tests are asserted to run rather than skip. Deferred: Stage 5b
   reference lookup bounded at 32 definitions per document, and documentSymbol
   kinds outside function/method/term recorded as unmapped rather than modelled.
   **Stage 5b is COMPLETE (2026-09-05), so Stage 5 is closed.** Owner chose a
   repo-managed sha256-pinned jdtls tarball (`scripts/setup-jdtls.sh`, 1.54.0,
   gitignored `.jdtls-toolchain/`, digest verified before extraction); an ambient
   `PATH` jdtls stays unacceptable under ADR-047, so this chain has **no ambient
   step at all**. `providers.lsp-java` consumes the Stage 5a seam unchanged —
   the only edit to the boundary is one role entry, which is the proof the seam
   was built right. **Preflight against the real server again invalidated
   assumptions**: (1) jdtls needs **JDK 21+** and under 17 fails OSGi resolution
   and closes the stream (an unreadable `EOFException`), so the hosting JVM is
   resolved and version-checked separately from the JVM running semidx; (2)
   members arrive **late and silently** — right after `didOpen`,
   `documentSymbol` returns only package and class with no children and no
   error, so the adapter polls for readiness and reports exhaustion as a
   `timeout` rather than publishing an incomplete answer; (3) the `arity_only`
   floor is **confirmed, not lifted** — symbols are `handle(String)`,
   `handle(String, int)`, i.e. simple parameter type names, the exact form
   Stage 4 rejected as Variant B for scip-java; (4) `textDocument/references`
   returns empty even for a cross-file call, because without a build file jdtls
   runs an invisible project with no classpath, so the descriptor claims
   `definitions` only. **The defect only a real server would have shown**: jdtls
   returns the package and the type as **siblings** at depth 0, not nested, so
   the first implementation produced `OrderService#handle` instead of
   `example.OrderService#handle` and the LSP tier did not merge with the regex
   tier at all — two canonical facts for one method. The package is now read off
   the top level as a prefix; covered by a test named for the failure mode.
   Operationally: `-configuration` is copied per workspace because jdtls writes
   into it, and `-data` lives under `~/.cache/semidx/jdtls/<digest>` keyed by
   canonical root. Suite: 613 tests / 3342 assertions / 0 failures with every
   toolchain flag; without a JDK 21 configured the Java end-to-end test skips
   and the suite stays green, which is the developer path. Stage 6 is now the
   only remaining work and is **gated on comparative evidence from the paused
   `plans/020`** (its default-off sub-stages 6a and 6b have since been delivered
   as observation only, recorded above) — a hard gate, plus an open policy question Stage 5a left:
   whether an `equal_authority_value_conflict` may block a default-path fact or
   only annotate it.
   **Stage 5 review repair (2026-09-05), four findings, two of them real
   behaviour defects.** (1) `provider-plan` widened the operation set for an
   externally probed tier whenever a status entry merely *existed*, so an
   `unavailable` LSP still added `:references` and produced a permanent gap on
   every TypeScript file — and Stage 5a's own test had been *weakened* to accept
   that instead of the behaviour being fixed. Only a `ready` status widens
   operations now; an observed-but-unavailable tier is the same absence as an
   unobserved one. (2) Overlay coverage excluded failed documents, but the
   **provider-level** status was passed to every per-file plan, and an overlay
   provider is file-scoped (a candidate by selector alone, unlike the batch
   tier), so a timed-out document still planned `typescript-lsp`.
   `provider-overlay/statuses-for-path` now narrows status per document and
   downgrades outside coverage with the document's own failure reason
   (`overlay_timeout`, …). **Coverage is the per-document authority for both
   tiers.** (3) `resolve-home` also accepts `SEMIDX_JDTLS_TOOLCHAIN_DIR`, which
   the setup script honours but the provider ignored. (4) A Java test filtered
   `:signature_key` over whole fact maps and was vacuously true. Both Medium
   fixes were verified by *disabling* them and watching the new tests fail, not
   only by watching them pass. Suite: 614 tests / 3349 assertions / 0 failures.
   **General lesson, and the second time this class appears in this log: when an
   assertion fails, change the code or the claim — not the assertion.**
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
