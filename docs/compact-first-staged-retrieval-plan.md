# Compact-First Staged Retrieval Plan

Execution plan for finishing the clean-break retrieval line centered on compact selection, staged expansion/detail fetches, and snapshot-bound selection artifacts.

Last updated: 2026-03-10

Status: Completed on 2026-03-11

## Goal

Bring the repository fully in line with the intended role of a large-repo context narrowing layer:

- compact `resolve_context` as the default hot path
- explicit staged expansion and detail fetches
- `selection_id` artifacts bound to an exact `snapshot_id`
- no silent re-resolve or silent drift across snapshots
- `api_version` as the only client-facing version switch

## Current Status

- The staged API shape already exists on library, HTTP, gRPC, and MCP surfaces.
- Compact selection, structured `next_step`, and separate `expand_context` / `fetch_context_detail` flows are already implemented.
- Iteration 4 alignment is now landed: `ADR-024` exists and `README`, runtime docs, MCP docs, and canonical example metadata all describe staged retrieval as the public default flow.
- Full plan outcome is now delivered and this document should be treated as a completed execution record rather than the active near-term backlog.

## Prioritized Work

### 1. Snapshot-Bound Selection Correctness

Highest priority because it protects correctness and explainability.

- Make `expand_context` and `fetch_context_detail` operate only against the exact snapshot bound to the original `selection_id`.
- Eliminate cross-stage drift caused by rebuilding or reloading a newer index between `resolve_context` and later stages.
- Store enough selection artifact metadata to validate and reproduce later stages safely.
- Add explicit errors for:
  - missing selection
  - evicted selection
  - snapshot mismatch

### 2. Selection Retention and Detail Idempotency

Required to make snapshot-bound selection operational rather than nominal.

- Introduce bounded selection-artifact retention with explicit cache/eviction rules.
- Make repeated `fetch_context_detail(selection_id, snapshot_id, same opts)` return an identical result while the artifact is retained.
- Remove unstable behavior from the canonical idempotent contract or cache the stable detail artifact.

### 3. Clean-Break Versioning and Contract Cleanup

Required to finish the intended API break.

- Make `api_version` the only client-facing version switch.
- Default missing `api_version` to `"1.0"`.
- Introduce a dedicated `unsupported_api_version` error path.
- Keep `schema_version` as an internal/schema artifact marker, not a client routing field.
- Remove stale public knobs from the canonical retrieval API, especially:
  - `favor_compact_packet`
  - implicit use of `favor_higher_recall` on the compact path

### 4. Real Per-Stage Budget Enforcement

Needed to turn bounded retrieval from a soft convention into a hard runtime guarantee.

- Keep selection-stage reserved budget behavior.
- Add real shaping and hard caps to:
  - `expand_context`
  - `fetch_context_detail`
- Return explicit stage statuses when a stage is truncated or budget-exhausted.
- Ensure selection cannot consume budget reserved for later stages.

### 5. Stage-Aware Usage Metrics

Needed for governance, regression review, and tuning.

- Track token footprints separately for:
  - selection
  - expand
  - detail
- Track per-stage latency and budget outcomes.
- Surface stage-aware metrics in operational rollups and reports.

### 6. Tests, ADR, and Documentation Alignment

This finalizes the refactor as the canonical line rather than a partial implementation.

- Add coverage for:
  - weak-query `insufficient_evidence`
  - `budget_exhausted_at_selection`
  - snapshot mismatch
  - evicted selection
  - unsupported API version
  - detail idempotency
  - drift after repo mutation between resolve and detail fetch
- Add an ADR for compact-first staged retrieval and selection stability.
- Update README, runtime API docs, MCP docs, examples, and catalogs so they describe the staged flow as canonical.

## Execution Order

### Iteration 1

- Snapshot-bound selection correctness
- Selection retention
- Detail idempotency

### Iteration 2

- Clean-break versioning and contract cleanup

### Iteration 3

- Real per-stage budget enforcement
- Stage-aware usage metrics

### Iteration 4

- ADR, examples, and full documentation alignment
- Delivered on 2026-03-10

## Definition of Done

- `clojure -M:test` remains green.
- Selection artifacts are explicitly snapshot-bound across all public surfaces.
- A repeated detail fetch is idempotent while the selection artifact exists.
- Canonical docs and examples describe `resolve_context -> expand_context -> fetch_context_detail` as the default retrieval flow.

All definition-of-done items are now satisfied.
