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
- `[~]` Phase 3 Elixir semantic-core: baseline alias/import handling exists, but deeper `import` / `use`, ownership, arity, and test linkage work remains
- `[~]` Phase 3 Java semantic-core: overload-sensitive identity exists, but import/class ownership and stronger overload disambiguation remain
- `[~]` Phase 3 Python semantic-core: baseline module/class/function normalization exists, but deeper ownership and import resolution remain
- `[~]` Phase 3 TypeScript: compatibility and regression coverage exist, but no strategic deepening work
- `[ ]` Capabilities extended enough to drive per-language confidence ceilings and guardrails
- `[~]` Phase 4 runtime and MCP hardening: snapshots, usage metrics, authz, and tenant hooks exist, but TTL, stale detection, provenance, snapshot pinning, rebuild reasons, unified error taxonomy, and full SLO metrics remain
- `[ ]` Phase 5 real self-improvement loop: automatic replay harvesting, query-to-outcome linking, difficult-case harvesting, and calibration reports against real feedback

## Canonical References

- GitHub Roadmap: <https://github.com/github/roadmap>
- Kubernetes Enhancements tracking repository: <https://github.com/kubernetes/enhancements>
- Go issue tracker milestones: <https://github.com/golang/go/milestones>
- Rust tracking issue guidance: <https://forge.rust-lang.org/feature-tracking.html>
