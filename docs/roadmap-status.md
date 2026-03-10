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
- `[~]` Phase 3 Clojure semantic-core: namespace/var identity, test linkage, caller/callee resolution, multimethod dispatch, macro-aware ownership substantially improved, but not compiler-grade
- `[~]` Phase 3 Elixir semantic-core: alias/import handling, imported-call expansion, `defdelegate` linkage, and ExUnit test linkage now exist, but deeper `use` ownership and arity-sensitive targeting remain
- `[~]` Phase 3 Java semantic-core: overload-sensitive identity, arity-aware call linking, and static-import/class ownership now exist, but deeper Java ownership/disambiguation work still remains
- `[~]` Phase 3 Python semantic-core: imported symbol resolution, module-alias handling, class-owned `self` / `cls` method linking, and test-file linkage now exist, but deeper Python ownership/disambiguation work still remains
- `[~]` Phase 3 TypeScript: compatibility and regression coverage exist, but no strategic deepening work
- `[x]` Capabilities now drive per-language confidence ceilings and guardrails, and governed replay reports `confidence_ceiling_distribution`
- `[~]` Phase 4 runtime and MCP hardening: snapshots, usage metrics, authz, tenant hooks, index lifecycle metadata, unified machine-readable error taxonomy, and SLO-facing metrics now exist; remaining work is mainly deeper tenant/ops completeness rather than missing core governance primitives
- `[ ]` Phase 5 real self-improvement loop: automatic replay harvesting, query-to-outcome linking, difficult-case harvesting, and calibration reports against real feedback

## Canonical References

- GitHub Roadmap: <https://github.com/github/roadmap>
- Kubernetes Enhancements tracking repository: <https://github.com/kubernetes/enhancements>
- Go issue tracker milestones: <https://github.com/golang/go/milestones>
- Rust tracking issue guidance: <https://forge.rust-lang.org/feature-tracking.html>
