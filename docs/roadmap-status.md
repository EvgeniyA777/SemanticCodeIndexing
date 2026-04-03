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
- `[x]` Capabilities now drive per-language confidence ceilings and guardrails, and governed replay reports `confidence_ceiling_distribution`
- `[x]` Phase 4 runtime and MCP hardening: snapshots, usage metrics, authz, tenant-aware correlation propagation, index lifecycle metadata, unified machine-readable error taxonomy, and SLO-facing metrics now exist on the main runtime surfaces
- `[x]` Phase 5 real self-improvement loop: replay harvesting from usage events and feedback, query-to-outcome linkage, difficult-case promotion into `protected_case`, calibration reports against real feedback, weekly review artifacts, conversion of those artifacts back into protected replay datasets, a batch `policy-review-pipeline` into `shadow-review`, retained `scheduled-policy-review` runs, retained `scheduled-governance-cycle` promotion decisions with deterministic best-candidate selection, history-aware selection, streak/cooldown gating, governance approval tiers / allow-block auto-promotion constraints, direct `promote-policy` governance-tier enforcement plus explicit manual approval, retained review/governance indexes, derived operator queue/status reports, and the top-level `scheduled-phase5-cycle` orchestration artifact now exist
- `[x]` Semantic snapshot productization tail: projection profiles are standardized across public outputs, semantic-quality reporting has an advisory CI/artifact lane, and the runtime/MCP docs now describe literal slices, snapshot diff, and semantic quality surfaces

## Canonical References

- GitHub Roadmap: <https://github.com/github/roadmap>
- Kubernetes Enhancements tracking repository: <https://github.com/kubernetes/enhancements>
- Go issue tracker milestones: <https://github.com/golang/go/milestones>
- Rust tracking issue guidance: <https://forge.rust-lang.org/feature-tracking.html>

## Current Focus

The compact-first staged retrieval refactor captured in [docs/compact-first-staged-retrieval-plan.md](/Users/ae/workspaces/SemanticCodeIndexing/docs/compact-first-staged-retrieval-plan.md) is now complete and should be treated as delivered.

The roadmap through Phase 5 is now effectively delivered for the current scope.

The post-roadmap semantic tranche in [docs/post-roadmap-semantic-deepening-plan.md](/Users/ae/workspaces/SemanticCodeIndexing/docs/post-roadmap-semantic-deepening-plan.md) is now also complete.

The semantic stabilization tranche captured in [docs/semantic-stabilization-plan.md](/Users/ae/workspaces/SemanticCodeIndexing/docs/semantic-stabilization-plan.md) is now delivered for the current scope:

- internal semantic IR now exists between extraction and resolver narrowing
- TypeScript now runs through its dedicated language module with advanced-surface regex/tree-sitter parity
- Java superclass ancestry and Python immediate-scope local suppression are tightened for graph correctness
- all supported languages now have dedicated entry namespaces under `runtime/languages/*`, while `runtime/adapters` remains the canonical facade

The semantic snapshot productization tail is now also delivered for the current scope:

- projection profiles are standardized across structural, summary, selection, widened API-shape, detail, literal-slice, and diff outputs
- semantic-quality reporting is available as an advisory CI artifact lane via `.github/workflows/mvp-runtime.yml`
- runtime and MCP docs now cover `literal-file-slice`, `snapshot-diff`, `semantic-quality-report`, and the projection taxonomy

The next near-term focus is now beyond this delivered stabilization slice:

- deeper shared-helper extraction out of the remaining adapter hotspot
- interprocedural/dataflow-sensitive semantic resolution
- continued benchmark/replay-driven validation as deeper semantic layers land
- Elixir tree-sitter readiness is now tracked by [notes/2026-03-26-1800-13931a71-c700-4f43-84d0-701ff08273b8.md](/Users/ae/workspaces/SemanticCodeIndexing/notes/2026-03-26-1800-13931a71-c700-4f43-84d0-701ff08273b8.md) and benchmark delta evidence by [notes/2026-03-26-1839-abc6454d-f08a-44ae-abe2-dba1557049d6.md](/Users/ae/workspaces/SemanticCodeIndexing/notes/2026-03-26-1839-abc6454d-f08a-44ae-abe2-dba1557049d6.md)
- Python parser-strategy frontier is now tracked in [notes/2026-03-26-1839-7815c5cd-3357-4776-a628-71dc7f695ee8.md](/Users/ae/workspaces/SemanticCodeIndexing/notes/2026-03-26-1839-7815c5cd-3357-4776-a628-71dc7f695ee8.md)
- Interprocedural/dataflow v1 frontier is now tracked in [notes/2026-03-26-1839-152b99e3-3b4b-4feb-93a9-957f43950934.md](/Users/ae/workspaces/SemanticCodeIndexing/notes/2026-03-26-1839-152b99e3-3b4b-4feb-93a9-957f43950934.md)
