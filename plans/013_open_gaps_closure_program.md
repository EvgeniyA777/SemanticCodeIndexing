---
title: "Open Semantic and Ops Gaps Closure Program"
doc_type: "implementation_plan"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Implementation Plan: Open Semantic and Ops Gaps Closure Program

This plan sequences the seven currently-open post-roadmap gaps recorded in
`MEMORY.md` → *Known Gaps* and `docs/roadmap-status.md` → *Current Focus* into a
single staged program. Each stage is a self-contained delivery loop:

> **implement → verify → commit → review → fixes → docs update → push**

Uncommitted working-tree changes at program start (memory-freshness hook
tooling: `scripts/check-memory-freshness.sh`, `scripts/git-hooks/`,
`scripts/install-git-hooks.sh`, plus `MEMORY.md` / `RULES.md` edits) were
produced by a previous agent and are assumed already committed and pushed before
Stage 1 begins. If they are still dirty, checkpoint them in a dedicated
`chore:` commit first (do **not** fold them into Stage 1).

## Companion Progress Log

The companion log is `reports/014_open_gaps_closure_program_progress_log.md`.
Update it as each stage completes
(stage status, changed files / commit hash, verification commands + results,
review findings and their disposition, blockers, skipped checks).

## Ordering Rationale (dependency-first, not gap-number order)

1. **Stage 1 — Split `runtime/adapters.clj`** (gap 2) — delivered. The language
   lanes and shared helpers now own extraction; `adapters.clj` is the thin
   dispatch facade required by the deeper semantic stages.
2. **Stage 2 — Remove tree-sitter external-CLI runtime dependency** (gap 6) —
   delivered with the repository-managed toolchain now recorded by ADR-047. The
   historical regex-default authority clause in ADR-036 is superseded by
   ADR-046 and plans/018.
3. **Stage 3 — Interprocedural / dataflow-sensitive resolution v1** (gap 1). The
   major semantic tranche, built on the clean adapter base from Stage 1.
4. **Stage 4 — Semantic graph query surface** (gap 7). Builds on the stabilized
   semantic model and edge storage from Stage 3.
5. **Stage 5 — gRPC generated stubs** (gap 3). Independent ops hardening.
6. **Stage 6 — Online policy control-plane API** (gap 4). Independent ops.
7. **Stage 7 — Runtime-edge rate limiting** (gap 5). Independent ops.

Stages 5-7 are order-independent among themselves and may be reprioritized, but
should follow the semantic stages so runtime-edge churn does not collide with
resolver changes.

## Per-Stage Delivery Loop (applies to every stage)

Each stage MUST run this loop and record it in the progress log:

1. **Map before implementing.** Use semidx MCP first
   (`create_index` → `repo_map` → `resolve_context` → `fetch_context_detail`) to
   read the exact code shape before editing. Fall back to `Read`/`Grep` only if
   MCP fails (and say so).
2. **Implement** the stage scope with narrow, form-aware Clojure edits. Keep
   patches scoped to one top-level form where possible; run a syntax/compile
   probe after each structural edit.
3. **Verify — narrowest meaningful command first**, then widen:
   - `clojure -M:test` (auto-discovers every `*-test` ns)
   - `./scripts/validate-contracts.sh` when contract/schema surfaces change
   - `./scripts/run-mvp-gates.sh` before declaring a stage green
   - `./scripts/run-benchmarks.sh` and
     `./scripts/run-semantic-quality-report.sh` for any stage touching
     extraction, ranking, resolution, or confidence
   - `./scripts/validate-language-onboarding.sh <lang>` for lane-affecting work
4. **Commit** — group the stage's related changes into coherent commits, staging
   only explicit paths (`git add <file> ...`, never `git add .`/`-A`). Branch off
   first if on `main`. Commit only after verification is green.
5. **Review** — run the stage-gated external reviewer loop captured in
   `notes/2026-07-13-stage-gated-external-reviewer-loop.md` and/or
   `/code-review` on the stage diff. Record every finding.
6. **Fixes** — address accepted findings; re-verify; record fix summary +
   changed files/commit hash + verification results in the progress log. Mark
   rejected/deferred findings explicitly with rationale.
7. **Docs update** — in the same stage:
   - `MEMORY.md` (Current State / Known Gaps / Next Execution Priorities)
   - `docs/roadmap-status.md` (flip the relevant gap and Current Focus)
   - a new ADR under `adr/` (next number **033+**) when the stage sets a
     durable architectural decision
   - refresh CCC artifacts (`docs/code-context.md`, `.ccc/state.edn`) only when
     the stage materially changes architecture: `clojure -M:ccc check --root .`
     then regenerate if stale
   - relevant surface docs (`docs/runtime-api.md`, `docs/mcp-api.md`)
8. **Push** — sequential with commit, never parallel. The pre-push hook runs CCC
   freshness + `scripts/check-memory-freshness.sh`; because every stage updates
   `MEMORY.md`, the freshness gate should pass without bypass. Only use
   `SCI_SKIP_MEMORY_FRESHNESS=1 git push` for a genuinely memory-neutral commit,
   and only after re-checking the update rule.

Do not proceed to the next stage until the current stage's loop is fully closed
and its progress-log entry is written.

---

## Stage 1 — Split `runtime/adapters.clj` (gap 2)

**Goal:** Reduce the adapter hotspot to a thin facade over per-language lane
modules plus one shared-helper namespace, with zero behavior change.

**Current shape:**
- `src/semidx/runtime/adapters.clj` (3029 lines) still owns Clojure, Java,
  Python, and Lua regexes, scanning, and semantic-core logic.
- `src/semidx/runtime/languages/{clojure,java,python,lua,javascript}.clj` are
  5-6 line stubs; `typescript.clj` (668) is the reference for a completed lane
  extraction; `elixir.clj` (126), `css.clj` (173), `html.clj` (128) are real.
- `language_registry.clj` centralizes lane metadata (from `plans/011`).

**Sub-steps (each a commit inside the stage):**
1. Extract shared scanning/line/token utilities used by multiple lanes
   (`slurp-lines`, `unit-end-lines`, `trim-signature`, tail-token,
   call-stop-token handling, tree-sitter availability/config cache) into
   `src/semidx/runtime/languages/shared.clj`. Point existing lanes at it.
2. Move Clojure lane logic (`clj-*` regexes + semantic-core) out of `adapters`
   into `languages/clojure.clj`, mirroring the TypeScript module structure.
3. Repeat for Java, Python, Lua lanes into their modules.
4. Collapse `adapters.clj` into a thin dispatch facade over `language_registry`
   + lane modules; keep `runtime/adapters` as the canonical public facade
   (external callers unchanged).

**Verification focus:** `clojure -M:test` after every sub-step (mirrored suite
from `plans/012` guards behavior), then `./scripts/run-mvp-gates.sh`,
`./scripts/run-benchmarks.sh`, `./scripts/run-semantic-quality-report.sh`, and
`./scripts/validate-language-onboarding.sh` for each moved lane — outputs must be
byte-identical to pre-split baselines (capture baselines before Stage 1).

**Docs:** ADR-035 "Split language-lane extraction out of the adapter facade";
update `MEMORY.md` (facade now thin), `docs/roadmap-status.md` (drop
"split `runtime/adapters.clj`" from Current Focus); refresh CCC.

**Risk:** highest blast radius. Mitigation: pure move, no logic change per
sub-step; benchmark/quality parity is the gate.

---

## Stage 2 — Remove tree-sitter external-CLI runtime dependency (gap 6)

**Goal:** Make tree-sitter extraction work without a runtime dependency on an
externally-installed tree-sitter CLI; keep pinned-grammar reproducibility.

**Historical-policy note:** this delivered stage established the toolchain only.
Its original regex-default authority policy is no longer current; ADR-046 makes
tree-sitter a structural provider and regex degraded fallback, with migration
sequenced by plans/018.

**Current shape:** `scripts/setup-tree-sitter-grammars.sh` pins grammar refs
(Clojure/Java/TypeScript); `tree-sitter-available?` / `tree-sitter-cst` in the
lane layer shell out to the external CLI; `.tree-sitter-grammars/` holds
bootstrapped grammars.

**Sub-steps:**
1. Historical implementation used ADR-036 to resolve tree-sitter through
   explicit parser options, environment configuration, and a repository-managed
   toolchain rather than a required external CLI. ADR-047 retains that toolchain
   decision independently of parser authority.
2. Implement the chosen path in `languages/shared.clj` tree-sitter helpers.
3. Ensure clean degradation + a diagnostic when the accelerated path is
   unavailable (no silent fallback — surface it).

**Verification:** run the suite with tree-sitter path both available and forced
unavailable; parser-mode parity (regex vs tree-sitter) must hold for TS/Java/Clj.
`./scripts/run-semantic-quality-report.sh` for extraction parity.

**Docs:** ADR-036; `MEMORY.md` Known Gaps (tree-sitter dependency line);
`docs/roadmap-status.md`; update the grammar-bootstrap section of surface docs.

---

## Stage 3 — Interprocedural / dataflow-sensitive resolution v1 (gap 1)

**Goal:** First layer of interprocedural, dataflow-sensitive ownership beyond the
current single-hop, import/owner-aware resolution — built on the clean lane base.

**Current shape:** `runtime/semantic_ir.clj` remains the extraction IR, while
`runtime/relations.clj` owns the typed-relation substrate and snapshot indexes.
The Clojure and Python lanes emit the v1 `dataflow/*` facts under ADR-037;
legacy callers/callees remain unchanged. ADR-038 makes typed relations the
canonical graph for all new graph semantics.

**Sub-steps:**
1. Preserve the delivered relation-first scope from ADR-034 and ADR-037:
   `dataflow/local-binding-call-result`, `dataflow/returns-call-result`, and
   `dataflow/passes-argument` facts are emitted by Clojure and Python through
   snapshot-preserved typed relation indexes.
2. Treat ADR-038 as the canonical graph decision: do not add new graph fields
   to `semantic_ir.clj`, and do not create provider-specific graph stores.
3. Before adding consumers, stabilize relation identity independently from
   mutable resolution and evidence. A semantic relation ID must derive from
   relation type, source endpoint, semantic target key, and flow payload;
   resolution status, resolved target IDs, evidence quality, provenance, and
   evidence location must not create a second semantic edge. Record this
   durable identity/evidence split in the next ADR before changing v1 IDs.
   **Delivered under `ADR-039`:** `relation-id-input` now selects only
   `relation_type`, `source_unit_id`, `target_key`, `local_name`, `arg_index`,
   and `relation_schema_version`.
4. Replace permissive validation and silent invalid-fact filtering with an
   explicit internal schema plus structured diagnostics. Resolved relations
   require resolved targets; ambiguous/unresolved relations remain
   conservative; relation types and evidence shapes are validated.
   **Delivered under `ADR-039`:** `relation-errors` is the explicit schema,
   and `index-relations` surfaces invalid facts as snapshot
   `:relation_diagnostics` instead of dropping them.
5. Add a pure, storage-independent bounded traversal kernel under
   `runtime.relations`. Its request must specify start nodes, direction,
   relation-type allow-list, resolved-only behavior, cycle handling,
   deterministic ordering, and `max_depth` / `max_nodes` / `max_paths` budgets.
   Initial target bounds are depth at most 4, 200 nodes, and 50 paths, subject
   to benchmark-backed tightening before public exposure.
   **Delivered:** `relations/traverse-relations` is a breadth-first, cycle-safe,
   deterministic kernel with `:downstream`/`:upstream` direction, a
   `:relation_types` allow-list, `:resolved_only` (default true), and budgets
   clamped to `default-traversal-bounds`; no consumer is wired to it yet.
6. Add bounded retrieval/impact projections that consume the traversal kernel,
   keeping ambiguous flows conservative (no over-linking) and avoiding a public
   graph-query API in Stage 3. Start with reason-coded, low-weight support and
   preserve existing caller/callee outputs.
   **Delivered:** `retrieval/build-impact-hints` consumes `traverse-relations`
   and attaches an optional, reason-coded `:relation_support` field to
   `impact_hints` (downstream dataflow dependencies / upstream dataflow
   dependents under a conservative `:resolved_only` sub-ceiling of depth 2 / 24
   nodes / 12 paths). The field is omitted when there is no resolved
   relation-backed unit, so legacy caller/callee/dependent/test outputs stay
   byte-identical; ambiguous/unresolved relations are never surfaced. The
   optional `relation_support` object is mirrored in the JSON and malli
   `impact_hints` contracts. No public graph-query API was added.
7. Recalibrate confidence ceilings only if evidence supports it; otherwise keep
   ceilings unchanged and document the non-bump (as done previously).

**Verification:** relation identity remains stable as evidence is added and an
unresolved target becomes resolved; invalid relations emit deterministic
diagnostics; traversal tests cover cycles, direction, relation filtering,
budgets, deterministic ordering, and ambiguous targets. Add mirrored `*-test`
namespaces per lane plus replay/benchmark ambiguity fixtures under `fixtures/`;
`./scripts/run-benchmarks.sh` and `./scripts/run-semantic-quality-report.sh`
must show no regression and a measurable gain on the new interprocedural cases.
Consider protected replay-case promotion for the hardest new cases.

**Docs:** ADR-034, ADR-037, and ADR-038; `MEMORY.md` Current State +
Known Gaps (compiler-grade line); `docs/roadmap-status.md`; refresh CCC.

**Risk:** highest semantic risk. Mitigation: land per-lane behind conservative
defaults; benchmark/replay parity is the gate; split into multiple commits/PRs
per lane if the diff grows.

---

## Stage 4 — Semantic graph query surface (gap 7)

**Goal:** Productize the Stage 3 traversal semantics as a bounded public graph
query surface and add a PostgreSQL physical projection without moving graph
policy into storage.

**Current shape:** `storage.clj` exposes single-hop caller/callee/unit queries
over `semantic_index_call_edges` (in-memory + PostgreSQL). ADR-040 now fixes the
Stage 4 public and execution contract; its implementation is in progress.

**Sub-steps:**
1. Use accepted ADR-040 as the public query decision: expose bounded relation
   traversal through library + MCP first, report HTTP/gRPC as `not_exposed`, and
   reuse the Stage 3 traversal semantics and bounds instead of defining a second
   graph walk. **Delivered in Stage 4.1.**
2. Add JSON Schema under `contracts/schemas/` + `malli` mirror in
   `src/semidx/contracts/` for the new query request/response. **Delivered in
   Stage 4.1.**
3. Add a `semantic_index_relations` PostgreSQL projection scoped by repository
   and snapshot, with source, target, relation-type, and evidence indexes plus
   an explicit migration/backfill policy.
4. Implement a PostgreSQL execution adapter for the canonical traversal
   contract and prove parity with the pure in-memory kernel. Storage may
   optimize execution but must not own traversal semantics.
5. Expose `traverse_relations` through library + MCP and keep capability metadata
   explicit: library/MCP `supported`, HTTP/gRPC `not_exposed`. HTTP/gRPC parity
   is a follow-up and must reuse the same contract and traversal kernel.

**Verification:** `./scripts/validate-contracts.sh`; storage parity tests
(in-memory vs PostgreSQL, `SEMIDX_TEST_POSTGRES_URL`) — detect/stop/fresh-start the
PostgreSQL instance before running; `clojure -M:test`.

**Docs:** the new Stage 4 ADR; `contracts/` updates; `docs/runtime-api.md` +
`docs/mcp-api.md` if surfaced there; `MEMORY.md` Known Gaps (graph-query line);
`docs/roadmap-status.md`.

### Product sequence after Stage 4

The first product-facing graph slice follows the graph foundation rather than
the unrelated runtime-operations stages below:

1. Deliver `plans/007` Stage 2-3 provider catalog, arbitration, and discovery
   separation.
2. Add additive endpoint/entity references for contract operations, generated
   artifacts, code units, and document sections before external providers emit
   relations.
3. Ship one Protobuf/OpenAPI vertical slice that links a contract operation to
   a client call site, implementation, generated artifact, and documentation.
4. Execute the accepted ADR-046 / plans/018 semantic-provider migration:
   SCIP/LSP evidence must enrich existing semantic relations rather than create
   a parallel graph, tree-sitter fills structural gaps, and regex is degraded
   fallback.

This product sequence is independent of the ordering of operational Stages 5-7
and should receive its own implementation plan before provider work begins.

---

## Stage 5 — gRPC generated stubs (gap 3)

**Status:** Delivered under ADR-042.

**Goal:** Replace runtime descriptor-built gRPC messages with generated
Java/Kotlin stubs from `proto/semidx/runtime/grpc/v1/runtime.proto`.

**Delivered shape:** `runtime.proto` is the single editable wire-schema source;
committed generated Java lives under `src-generated/java`; the build contour
owns pinned code generation and drift verification; the test/runtime launchers
perform offline idempotent javac preparation; and `grpc_proto.clj` / `grpc.clj`
use generated messages and `RuntimeServiceGrpc` descriptors.

**Delivered sub-steps:**
1. Add a protobuf codegen path (build alias in `deps.edn`) producing stubs from
   the pinned `.proto`; record the toolchain decision in a new ADR numbered
   with the next available ADR at execution time.
2. Wire `grpc_proto.clj` / `grpc.clj` to the generated stubs while preserving the
   dedicated runtime envelope messages and error-trailer taxonomy
   (`x-sci-error-code` / `x-sci-error-category`).
3. Prove parity against the descriptor-built oracle and remove that oracle so
   no fallback or parallel schema remains.

**Verification:** `semidx.runtime-grpc-test` parity; `clojure -M:runtime-grpc`
smoke; error-taxonomy trailers unchanged.

**Docs:** the new Stage 5 ADR; `MEMORY.md` Known Gaps (gRPC stubs line);
`docs/roadmap-status.md`; `docs/runtime-api.md` gRPC section.

---

## Stage 6 — Online policy control-plane API (gap 4)

**Goal:** Add a bounded online policy-management/control-plane surface on top of
the existing server-configured registries + selector-based `resolve_context`
policy lookup, keeping offline governance authoritative.

**Current shape:** `authz.clj` (165 lines) is local file/callback authz;
registries are server-configured; broader online policy-management APIs are
intentionally absent; governance lifecycle (`draft/shadow/active/retired`) is
offline via `clojure -M:eval`.

**Sub-steps:**
1. In a new ADR numbered with the next available ADR at execution time, scope
   the control-plane (read/introspect registry + guarded promote/retire hooks)
   and its authz boundary; do not bypass promotion gates, protected-case
   checks, or approval tiers.
2. Contract-first: JSON Schema + `malli` mirror for control-plane requests.
3. Implement on HTTP (and mirror on gRPC/MCP as applicable), reusing existing
   governance-tier enforcement and error taxonomy.

**Verification:** `./scripts/validate-contracts.sh`; HTTP/gRPC conformance tests;
governance-gate regression tests (promotion still refuses blocked/regressing
candidates).

**Docs:** the new Stage 6 ADR; `contracts/`; `MEMORY.md` Known Gaps
(control-plane line); `docs/roadmap-status.md`; `docs/runtime-api.md` +
`docs/mcp-api.md`.

---

## Stage 7 — Runtime-edge rate limiting (gap 5)

**Status:** Delivered under ADR-044.

**Goal:** Add optional in-runtime rate limiting on HTTP/gRPC edges as defense in
depth, without displacing ingress/proxy responsibility.

**Delivered shape:** `runtime/rate_limit.clj` owns one bounded, monotonic,
fixed-window kernel shared by HTTP and gRPC. It is default-off, supports tenant
or tenant+actor scopes, emits unified 429/`RESOURCE_EXHAUSTED` errors with retry
metadata, and records allow/reject decisions for SLO rollups. Ingress remains
authoritative for distributed quotas.

**Sub-steps:**
1. ADR-044 scopes
   an optional, config-gated limiter (per-tenant / per-actor), default off,
   emitting the unified error taxonomy on rejection. **Delivered.**
2. Implement as edge middleware on HTTP and gRPC, fed by existing
   tenant/correlation context; surface limiter decisions in usage metrics.
   **Delivered.**
3. Keep it opt-in so the default in-memory/local path is unchanged.
   **Delivered.**

**Verification:** edge conformance tests with limiter on/off; usage-metrics
rollup includes limiter rejections; error taxonomy on 429-equivalent responses.

**Docs:** the new Stage 7 ADR; `MEMORY.md` Known Gaps (rate-limiting line);
`docs/roadmap-status.md`; `docs/runtime-api.md`.

---

## Program Exit Criteria

- All seven Known-Gaps lines in `MEMORY.md` are resolved or explicitly
  reclassified, and `docs/roadmap-status.md` Current Focus reflects the new
  frontier.
- `reports/014_open_gaps_closure_program_progress_log.md` records every stage's
  status, commits, verification, and review disposition.
- ADRs 033-039 (as applicable) capture the durable decisions.
- `clojure -M:test`, `./scripts/run-mvp-gates.sh`, `./scripts/run-benchmarks.sh`,
  and `./scripts/run-semantic-quality-report.sh` are green on the final state.

## Completion Note

All seven implementation stages are delivered. The final MVP gate and benchmark
suite are green. The standalone semantic-quality command exits successfully but
continues to report its pre-existing non-gating advisory baseline
(`gate_eligible=false`, 5/6 expected-change matches); Stage 7 changes only
runtime edges and does not alter extraction, ranking, resolution, or confidence.
