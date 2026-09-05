# Roadmap Status

Canonical in-repo checklist for the "Product-Ready Roadmap After Quality-Loop Foundations".

## Why This Lives Here

Canonical projects usually keep roadmap status in one of two places:

- a dedicated public tracking artifact outside the code tree, such as the official GitHub Roadmap repository
- a dedicated tracker inside the project governance flow, such as Kubernetes enhancement tracking issues/KEPs, Go issue tracker milestones, or Rust tracking issues

This repository does not use a dedicated public tracker as its canonical source of truth, so the versioned in-repo equivalent is a single docs page under `docs/` and a short pointer from `README.md` and `MEMORY.md`.

This placement is an inference from those official patterns, not a direct requirement from any one source.

## Checklist

Legend:

- `[x]` implemented
- `[~]` partial
- `[ ]` not implemented

- `[x]` Phase 2 governed quality loop: policy registry with `draft` / `shadow` / `active` / `retired`
- `[x]` Phase 2 replay governance: `score-policy`, `compare-policies`, `promote-policy`, `shadow-review`
- `[x]` Phase 2 protection flow: protected replay cases and promotion gates against protected regressions
- `[x]` Runtime policy selection on library, HTTP, gRPC, and MCP via registry-backed `policy_id` / `version`
- `[x]` Phase 3 Clojure semantic-core: namespace/var identity, test linkage, caller/callee resolution, multimethod dispatch, and macro-aware ownership now cover syntax-quoted, composed, local-helper-generated, top-level-helper-generated, and threaded-macro-generated forms, alias-heavy same-name var cases, one-hop helper-mediated `related_tests`, and conservative branch-sensitive generated ownership for the current roadmap scope
- `[x]` Phase 3 Elixir semantic-core: alias/import handling, `use`-aware unqualified-call expansion, arity-sensitive targeting, `defdelegate` linkage, ExUnit test linkage, and local-vs-import/use ownership precedence now exist, including arity-aware local shadowing for same-name functions, `__MODULE__`-qualified local ownership, and implicit imports propagated from `__using__/1`
- `[x]` Phase 3 Java semantic-core: overload-sensitive identity, arity-aware call linking for methods and constructors, and static-import/class ownership now exist, including same-name local-method vs static-import collision handling plus explicit `this.` local ownership
- `[x]` Phase 3 Python semantic-core: imported symbol resolution, relative-import normalization, module-alias handling, class-owned `self` / `cls` method linking, test-file linkage, and local-vs-imported ownership precedence now exist, including explicit module-alias preservation under same-name collisions plus parent-package relative-import targeting and local class-qualified ownership
- `[x]` Phase 3 TypeScript semantic-core: named, namespace, and default-import ownership now exist, local `this.` and class-qualified method targeting survive both regex and tree-sitter extraction, exported function-expression bindings get first-class unit identity, and advanced surfaces such as object methods, class field arrows, default-export aliases, and direct re-export aliases now stay aligned across parser modes while the public confidence ceiling intentionally remains conservative
- `[x]` Capabilities now drive per-language confidence ceilings and guardrails, governed replay reports `confidence_ceiling_distribution`, and MCP initialization exposes a versioned capability self-description contract
- `[x]` Phase 4 runtime and MCP hardening: snapshots, usage metrics, authz, tenant-aware correlation propagation, index lifecycle metadata, unified machine-readable error taxonomy, and SLO-facing metrics now exist on the main runtime surfaces
- `[x]` Phase 5 real self-improvement loop: replay harvesting from usage events and feedback, query-to-outcome linkage, difficult-case promotion into `protected_case`, calibration reports against real feedback, weekly review artifacts, conversion of those artifacts back into protected replay datasets, a batch `policy-review-pipeline` into `shadow-review`, retained `scheduled-policy-review` runs, retained `scheduled-governance-cycle` promotion decisions with deterministic best-candidate selection, history-aware selection, streak/cooldown gating, governance approval tiers / allow-block auto-promotion constraints, direct `promote-policy` governance-tier enforcement plus explicit manual approval, retained review/governance indexes, derived operator queue/status reports, and the top-level `scheduled-phase5-cycle` orchestration artifact now exist
- `[x]` Semantic snapshot productization tail: projection profiles are standardized across public outputs, semantic-quality reporting has an advisory CI/artifact lane, and the runtime/MCP docs now describe literal slices, snapshot diff, and semantic quality surfaces

## Canonical References

- GitHub Roadmap: <https://github.com/github/roadmap>
- Kubernetes Enhancements tracking repository: <https://github.com/kubernetes/enhancements>
- Go issue tracker milestones: <https://github.com/golang/go/milestones>
- Rust tracking issue guidance: <https://forge.rust-lang.org/feature-tracking.html>

## Current Focus

The compact-first staged retrieval refactor captured in [plans/002_compact_first_staged_retrieval_plan.md](/Users/ae/workspaces/semidx/plans/002_compact_first_staged_retrieval_plan.md) is now complete and should be treated as delivered.

The roadmap through Phase 5 is now effectively delivered for the current scope.

The post-roadmap semantic tranche in [plans/003_post_roadmap_semantic_deepening_plan.md](/Users/ae/workspaces/semidx/plans/003_post_roadmap_semantic_deepening_plan.md) is now also complete.

The semantic stabilization tranche captured in [plans/004_semantic_stabilization_plan.md](/Users/ae/workspaces/semidx/plans/004_semantic_stabilization_plan.md) is now delivered for the current scope:

- internal semantic IR now exists between extraction and resolver narrowing
- TypeScript now runs through its dedicated language module with advanced-surface regex/tree-sitter parity
- Java superclass ancestry and Python immediate-scope local suppression are tightened for graph correctness
- all supported languages now have dedicated entry namespaces under `runtime/languages/*`, while `runtime/adapters` remains the canonical facade

The semantic snapshot productization tail is now also delivered for the current scope:

- projection profiles are standardized across structural, summary, selection, widened API-shape, detail, literal-slice, and diff outputs
- semantic-quality reporting is available as an advisory CI artifact lane via `.github/workflows/mvp-runtime.yml`
- runtime and MCP docs now cover `literal-file-slice`, `snapshot-diff`, `semantic-quality-report`, and the projection taxonomy

The `plans/013` Stage 1 adapter split is now delivered for the current scope:

- `semidx.runtime.adapters` is a thin public parser facade over the language registry and per-language lane namespaces
- Clojure, Java, Python, Lua, Zig, TypeScript, and JavaScript parser ownership now lives under `semidx.runtime.languages.*`
- Zig definition and container-ownership facts now use one bounded ZLS/LSP session per indexing operation, with exact-current-source `documentSymbol` requests and regex fallback/supplementation
- shared line/signature/token and tree-sitter helper mechanics live in `semidx.runtime.languages.shared`
- the remaining legacy TypeScript adapter block and adapter-private compatibility wrappers have been removed rather than carried forward

The `plans/013` Stage 2 tree-sitter dependency cleanup is delivered. ADR-036 is
superseded: ADR-047 retains its repo-managed CLI/grammar resolution boundary,
while accepted ADR-046 and plans/018 replace its regex-default authority policy.
The current regex-first implementation is a legacy migration baseline, not the
target architecture: fresh SCIP/LSP evidence is primary per operation,
tree-sitter fills structural gaps, and regex is explicitly degraded fallback.

Stage 3 of `plans/013` is code-complete under ADR-034, ADR-037, ADR-038, and
ADR-039:

- Clojure and Python emit and resolve the v1 `dataflow/*` relation facts on the
  canonical typed-relation graph;
- relation identity is separated from mutable resolution/evidence and invalid
  facts produce explicit snapshot diagnostics;
- `semidx.runtime.relations/traverse-relations` provides the pure, deterministic,
  cycle-safe bounded traversal kernel;
- `build-impact-hints` consumes the kernel through conservative, reason-coded
  `relation_support`, with no confidence-ceiling increase.

Stage 4 of `plans/013` (semantic graph query surface, gap 7) is now fully
delivered across all four runtime surfaces under ADR-040:

- the bounded relation traversal is exposed as `semidx.core/relation-traversal`
  (usage-metrics-wrapped) and the MCP `traverse_relations` tool, returning the
  compact `relation-traversal` contract result plus a staged-retrieval
  `selection_id` that reuses `expand_context` / `fetch_context_detail`;
- the kernel runs through a batched frontier provider seam
  (`traverse-relations-with`) so execution backends batch neighbor lookups
  without owning traversal policy;
- a forward-only PostgreSQL `semantic_index_relations` projection plus
  `storage/pg-relation-neighbor-provider` execute the same traversal at proven
  parity with the pure in-memory kernel (with-redefs parity plus a
  `SEMIDX_TEST_POSTGRES_URL`-gated real-PostgreSQL round-trip test);
- the HTTP edge (`POST /v1/retrieval/traverse-relations`) and the gRPC edge
  (`TraverseRelations`, descriptor-built JSON-string messages with the unified
  error taxonomy) now expose the same contract and kernel, so all four surfaces
  (library/MCP/HTTP/gRPC) are aligned and the ADR-040 phased-exposure follow-up
  is complete.

Stage 5 is delivered under ADR-042: `runtime.proto` is the complete authoritative
contract for all 16 envelope messages and eight unary RPCs; the repo-managed
pinned toolchain deterministically generates and verifies 34 committed Java
sources; ordinary test/runtime starts perform offline idempotent javac; and the
runtime uses generated messages plus `RuntimeServiceGrpc` descriptors. The
temporary descriptor-built oracle was removed after parity was proven.

Stage 6 is delivered under ADR-043: the HTTP edge now exposes an online policy control-plane (`/v1/policies/registry`, `/v1/policies/promote`, `/v1/policies/retire`) that reuses offline governance gates and optionally persists state back to the registry file.

Stage 7 is delivered under ADR-044: HTTP and gRPC share an optional, default-off
fixed-window runtime limiter with bounded process-local state, tenant or
tenant+actor scoping, unified 429/`RESOURCE_EXHAUSTED` rejections, retry
metadata, and decision-based usage/SLO metrics. Ingress remains authoritative
for distributed quotas.

With operational Stages 5-7 complete, the next semantic priority is the provider
catalog/discovery foundation followed by the accepted ADR-046 / plans/018
semantic-provider authority migration. SCIP batch evidence and LSP live-overlay
evidence enrich the same canonical relation graph; they are not a future
parallel-graph spike. The Protobuf/OpenAPI vertical slice follows the provider
foundation, while broader parser-deepening remains evidence-driven.

The independent `plans/019` delivery track is now planned alongside that data
plane: an additive one-shot `get_context` facade composes the existing
snapshot-bound staged operations, an optional renderer projects the canonical
ContextPacket to bounded Markdown, and a comparative harness measures task
value, latency, round trips, and output cost against staged and lexical
baselines. ADR-024 remains current; one-shot does not become the documented
canonical default without comparative evidence and a new decision.

The independent `plans/021` runtime-reuse track is integration work, not
semantic-core work: existing MCP stdio, MCP HTTP, runtime HTTP, and runtime gRPC
modes are long-lived once started, while `clojure -M:runtime` is a one-shot CLI
that exits after a request. Runtime HTTP and MCP HTTP launcher reuse both
ship behind the `:launcher` alias, and `plans/021` is completed: hardening,
command latency reporting, and the cold-versus-warm benchmark landed in Stage 4.
