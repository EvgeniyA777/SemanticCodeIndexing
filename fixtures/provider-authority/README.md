# Provider-Authority Fixtures (plans/018 Stage 0)

Protected fixtures and baselines for the Semantic Provider Authority Migration
([`plans/018`](../../plans/018_semantic_provider_authority_migration_plan.md),
[`ADR-046`](../../adr/046-prefer-semantic-evidence-providers-over-structural-and-lexical-fallbacks.md),
[`ADR-047`](../../adr/047-retain-repo-managed-tree-sitter-toolchain-for-structural-providers.md)).

These fixtures exist so later stages can prove additive, non-regressing
behavior and correct cross-provider fact identity **before** any SCIP/LSP
provider work begins. Stage 0 does not change production extraction.

## Layout

- `corpus/` — a small protected Java + TypeScript mini-repo exercising
  definitions, references, calls, method overloads, and re-exports. The
  TypeScript corpus carries a committed `tsconfig.json` so real `scip-typescript`
  output over it is deterministic.
- `scip/` — decoded real SCIP output over `corpus/`, replacing the seeded
  representative spellings. Regenerate with
  `scripts/scip-typescript-corpus-snapshot.sh`.
- `identity/` — cross-provider `CanonicalFactKey` fixtures. Each proves that
  regex / tree-sitter / SCIP / LSP spellings of the SAME fact must normalize to
  one provider-neutral key, while distinct overloads / aliases stay distinct.
- `behavior/` — protected extraction baseline (ground truth) plus degradation
  and freshness expectations (dirty files, provider unavailability).
- `baseline/` — deterministic retrieval, callers/callees, impact, and snapshot
  identity baseline over the corpus; reusable by `plans/020`.

## Ground truth vs specification

- Fields marked `ground_truth: true` were captured from the current extractor
  (`semidx.runtime.languages.{java,typescript}/parse-file`), from the semidx
  MCP retrieval/impact surfaces, or (Stage 3+) from real external provider
  output. They must stay stable or change only via an approved, recorded
  semantic improvement.
- The **TypeScript** SCIP spelling is verified against
  `@sourcegraph/scip-typescript@0.4.0` (Stage 3, 2026-08-29); see
  `scip/typescript-corpus.observed.json` and each spelling's `verified_with`.
- **Java** SCIP and all **LSP** provider spellings remain **representative**,
  not verified tool output. Stage 4 (Java SCIP) and Stage 5 (LSP) must re-verify
  them against real provider payloads.
- The canonical overload form is **decided**: Variant C (owner decision
  2026-08-03). `overload_identity` is precision-aware — the heuristic tier
  commits arity only, the exact tier adds the typed signature, and unit identity
  anchors on the core key. See `reports/024`, section "F1 Resolution — Variant
  C". The same-arity arbitration rule (F1a) remains an open Stage 1 decision.
