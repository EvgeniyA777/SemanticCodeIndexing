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
- `[~]` Phase 3 Clojure semantic-core: namespace/var identity, test linkage, caller/callee resolution, multimethod dispatch, and macro-aware ownership now cover syntax-quoted, composed, local-helper-generated forms, alias-heavy same-name var cases, one-hop helper-mediated `related_tests`, and conservative branch-sensitive generated ownership substantially better, but the tail is still not compiler-grade
- `[~]` Phase 3 Elixir semantic-core: alias/import handling, `use`-aware unqualified-call expansion, arity-sensitive targeting, `defdelegate` linkage, ExUnit test linkage, and local-vs-import/use ownership precedence now exist, including arity-aware local shadowing for same-name functions, but deeper ownership/disambiguation work still remains
- `[~]` Phase 3 Java semantic-core: overload-sensitive identity, arity-aware call linking, and static-import/class ownership now exist, including same-name local-method vs static-import collision handling plus explicit `this.` local ownership, but deeper Java ownership/disambiguation work still remains
- `[~]` Phase 3 Python semantic-core: imported symbol resolution, module-alias handling, class-owned `self` / `cls` method linking, test-file linkage, and local-vs-imported ownership precedence now exist, including explicit module-alias preservation under same-name collisions, but deeper Python ownership/disambiguation work still remains
- `[~]` Phase 3 TypeScript: compatibility and regression coverage exist, but no strategic deepening work
- `[x]` Capabilities now drive per-language confidence ceilings and guardrails, and governed replay reports `confidence_ceiling_distribution`
- `[x]` Phase 4 runtime and MCP hardening: snapshots, usage metrics, authz, tenant-aware correlation propagation, index lifecycle metadata, unified machine-readable error taxonomy, and SLO-facing metrics now exist on the main runtime surfaces
- `[~]` Phase 5 real self-improvement loop: replay harvesting from usage events and feedback, query-to-outcome linkage, difficult-case promotion into `protected_case`, calibration reports against real feedback, weekly review artifacts, conversion of those artifacts back into protected replay datasets, a batch `policy-review-pipeline` into `shadow-review`, retained `scheduled-policy-review` runs, retained `scheduled-governance-cycle` promotion decisions with deterministic best-candidate selection, history-aware selection, streak/cooldown gating, governance approval tiers / allow-block auto-promotion constraints, and direct `promote-policy` governance-tier enforcement plus explicit manual approval now exist; the remaining tail is fuller closed-loop orchestration

## Canonical References

- GitHub Roadmap: <https://github.com/github/roadmap>
- Kubernetes Enhancements tracking repository: <https://github.com/kubernetes/enhancements>
- Go issue tracker milestones: <https://github.com/golang/go/milestones>
- Rust tracking issue guidance: <https://forge.rust-lang.org/feature-tracking.html>
