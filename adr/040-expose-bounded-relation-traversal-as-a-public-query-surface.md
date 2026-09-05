---
file_type: adr
decision_id: ADR-040
title: Expose Bounded Relation Traversal As A Public Query Surface
status: accepted
date: 2026-08-02
deciders:
  - project owner
tags:
  - architecture
  - semantic-core
  - relations
  - graph-query
  - storage
summary: The Stage 3 pure relation-traversal kernel is productized as a bounded public "relation traversal" query surface that reuses the kernel semantics and bounds verbatim, is exposed on the library and MCP surfaces first (HTTP/gRPC deferred), keeps all traversal policy in the pure kernel via a batched frontier provider, and gains a forward-only PostgreSQL `semantic_index_relations` projection that only optimizes neighbor fetching.
agent_summary: Read this ADR before adding or changing the public relation-traversal surface, the traversal kernel, or the relation storage projection. There is exactly one graph walk - the Stage 3 kernel in semidx.runtime.relations. Do not add a second graph-query engine or move eligibility/fan-out/ordering/budget semantics into SQL. New execution backends plug in through a batched per-level frontier provider (traverse-relations-with); storage returns the relations touching a frontier and the kernel owns filtering, fan-out, ordering, cycle handling, and budgets. Stage 4 exposes the surface on library + MCP only; HTTP/gRPC are not_exposed and are a documented follow-up. The PostgreSQL projection is forward-only (migration + new saves); do not auto-backfill historical snapshots inside init-storage!.
supersedes: []
superseded_by: null
links:
  - adr/034-land-dataflow-facts-as-typed-relations-first.md
  - adr/037-scope-interprocedural-dataflow-v1.md
  - adr/038-make-typed-relations-the-canonical-semantic-graph.md
  - adr/039-separate-relation-identity-from-resolution-and-evidence.md
  - plans/013_open_gaps_closure_program.md
  - reports/014_open_gaps_closure_program_progress_log.md
---

# ADR-040: Expose Bounded Relation Traversal As A Public Query Surface

**Status**: Accepted
**Date**: 2026-08-02
**Deciders**: project owner

---

## Context

Stage 3 of `plans/013` (ADR-034, ADR-037, ADR-038, ADR-039) landed typed
relations as the canonical semantic graph and a pure, storage-independent,
bounded traversal kernel `semidx.runtime.relations/traverse-relations`. That
kernel is breadth-first, cycle-safe, deterministic, resolved-only by default,
and clamped to `default-traversal-bounds` (depth 4 / 200 nodes / 50 paths).
Stage 3 deliberately added no public graph-query API: the only consumer is the
internal, low-weight `retrieval/build-impact-hints` projection.

Stage 4 (gap 7) productizes those semantics as a bounded public query surface
and adds a physical PostgreSQL projection. `storage.clj` currently persists
snapshots, units, and single-hop `semantic_index_call_edges`, but has no typed
relation projection and no multi-hop query.

The decision now required: what shape the public surface takes, which runtime
edges expose it in this stage, and how a PostgreSQL execution backend can serve
the same traversal without owning its semantics.

## Decision Drivers

- **One graph walk.** Stage 3 semantics are the source of truth; a second graph
  engine would drift from the kernel and from ADR-038.
- **Bounded, staged retrieval.** The public contract must stay compact and
  snapshot-bound and fit the existing selection -> expand -> detail flow rather
  than inventing a parallel code-delivery mechanism.
- **Storage optimizes execution, never semantics.** PostgreSQL may make neighbor
  lookup cheaper but must not own eligibility filtering, fan-out, ordering,
  cycle handling, or budgets.
- **Primary consumer is the agent.** MCP exposure delivers value immediately;
  HTTP/gRPC transport work should not gate the semantic contract.
- **Migration cost and safety.** A new projection must not force a heavy,
  implicit rewrite of historical snapshots.

## Considered Options

### Option 1. A generic graph-query language

Expose an open query surface (arbitrary node/edge predicates, path expressions).

### Option 2. A bounded relation-traversal surface reusing the Stage 3 kernel

Expose exactly the kernel's bounded traversal (direction, start nodes,
relation-type allow-list, resolved-only, depth/nodes/paths budgets) as the
public contract, on library + MCP first, with a batched frontier provider so a
PostgreSQL backend can execute the same walk.

### Option 3. Push traversal into storage / SQL (recursive CTE)

Implement the multi-hop walk as PostgreSQL recursive queries and treat the
in-memory kernel as a fallback.

## Decision

We accept **Option 2: a bounded relation-traversal surface reusing the Stage 3
kernel**.

Option 1 loses because an open graph language cannot be kept bounded, cannot be
kept parity-provable across backends, and would immediately outgrow the v1
typed-relation vocabulary. Option 3 loses because it moves traversal semantics
into storage, violating ADR-038 and the storage-optimizes-execution boundary,
and would make two implementations drift.

### Public surface

The surface is named **relation traversal** (not a generic `graph_query`),
because it is exactly a bounded walk over typed relations, not an arbitrary
graph-query language. Its contract mirrors the kernel:

- Request: `start_nodes`, `direction` (`downstream` | `upstream`),
  optional `relation_types` allow-list, optional `resolved_only` (default true),
  optional `budgets` (`max_depth` / `max_nodes` / `max_paths`, clamped to
  `default-traversal-bounds`), optional `snapshot_id`, and a `trace` ref.
- Response: `snapshot_id`, `direction`, `start_nodes`, `relation_types`,
  `budgets` (the applied bounds including `resolved_only`), `nodes`
  (`unit_id` + `depth`), `edges` (`relation_id`, `from`, `to`, `relation_type`,
  `resolution_status`, `depth`), `paths` (sequences of `relation_id`),
  `truncated` (`max_depth` / `max_nodes` / `max_paths`), and an optional
  `selection_id`.

The JSON Schema under `contracts/schemas/` is the external source of truth and a
`malli` mirror in `semidx.contracts.schemas` validates it at runtime.

> **Amendment (2026-08-02, plan 013 post-delivery review):** the traversal path
> fields above were renamed before broad reliance on them — `max_paths` ->
> `max_discovery_paths` and `paths` -> `discovery_paths` — across the kernel,
> the `malli`/JSON Schema contracts, the MCP tool, the HTTP/gRPC edges, docs,
> and tests. The rename is behaviour-preserving: the field carries at most one
> deterministic shortest first-discovery path per reached node, not an
> enumeration of alternative routes (multipath enumeration was deliberately not
> implemented), and the old name misleadingly implied path enumeration and
> merely duplicated `max_nodes`. Read every `max_paths` / `paths` in the prose
> above as `max_discovery_paths` / `discovery_paths`.

### Phased exposure

Stage 4 exposes the surface on library and MCP only. Capability metadata reports
the availability honestly:

| Surface | Stage 4      |
| ------- | ------------ |
| library | supported    |
| MCP     | supported    |
| HTTP    | not_exposed  |
| gRPC    | not_exposed  |

The MCP tool is `traverse_relations`. Its result is compact and snapshot-bound
and carries a `selection_id` derived from the discovered code units so that the
existing `expand_context` / `fetch_context_detail` staged-retrieval flow delivers
code, rather than a parallel code-delivery mechanism. HTTP and gRPC exposure is a
documented follow-up that must not change traversal semantics.

The table above records the initial phased decision. That follow-up has since
been delivered: HTTP (`POST /v1/retrieval/traverse-relations`) and gRPC
(`TraverseRelations`) now expose the same contract and kernel, so all four
surfaces are `supported` while the semantics remain single-sourced in the Stage 3
kernel.

### Execution backends and the frontier provider

`traverse-relations` stays a pure in-memory function. The BFS is refactored to
run level by level and to obtain neighbors through a **batched frontier
provider** (`traverse-relations-with`): given the current frontier, the provider
returns the relations touching those nodes for the requested direction, in one
batch per depth level. The kernel keeps all traversal policy - eligibility
(`relation_types`, `resolved_only`), direction fan-out, deterministic ordering,
cycle handling, and budget enforcement. Because a whole depth level is contiguous
in the original FIFO queue, level batching preserves the exact node/edge/path
ordering and truncation semantics of the Stage 3 kernel.

The in-memory provider reads the snapshot relation indexes. The PostgreSQL
provider issues one query per level (`... where source in (frontier)` for
downstream, target-indexed for upstream), avoiding N+1 SQL. Parity between the
in-memory and PostgreSQL backends is proven by test.

### PostgreSQL projection

A `semantic_index_relations` projection scoped by `root_path` + `snapshot_id`
stores source, target, relation-type, resolution status, and evidence indexes.
It is **forward-only**: the migration creates the table and indexes, and new
`save-index!` transactions write the projection. `init-storage!` does **not**
auto-backfill historical snapshots. Older snapshots are served from their JSON
payload or reprojected by an explicit, separate command if needed later.

## Consequences

### Positive

- One graph walk: the public surface, the internal impact projection, and both
  execution backends share the Stage 3 kernel semantics and bounds.
- The contract stays bounded, snapshot-bound, and inside the existing staged
  retrieval flow; agents get multi-hop relation traversal over MCP immediately.
- Storage can optimize neighbor fetching without owning semantics, and parity is
  provable because the kernel is the single decision point.
- No heavy implicit migration: the projection grows forward with new snapshots.

### Negative

- The kernel gains a provider seam (`traverse-relations-with`); the refactor must
  preserve byte-identical output for the existing in-memory path.
- Two neighbor providers exist (in-memory, PostgreSQL) and must be kept parity
  tested.
- Historical snapshots written before the projection have no
  `semantic_index_relations` rows until reprojected, so the PostgreSQL backend
  must fall back or be scoped to snapshots it has projected.

### Follow-Up

- Expose relation traversal on HTTP and gRPC without changing traversal
  semantics (phased-exposure follow-up). **Delivered:** the HTTP edge
  (`POST /v1/retrieval/traverse-relations`) and the gRPC edge
  (`TraverseRelations`) now reuse the same contract and kernel, so all four
  surfaces (library/MCP/HTTP/gRPC) are aligned.
- Provide an explicit reprojection command for historical snapshots if the
  PostgreSQL backend needs multi-hop over pre-projection snapshots.
- Revisit `default-traversal-bounds` only with benchmark evidence; this ADR does
  not change them.

## References

- ADR-034: typed relations first for new dataflow facts
- ADR-037: bounded interprocedural dataflow v1 scope
- ADR-038: typed relations as the canonical semantic graph
- ADR-039: separate relation identity from resolution and evidence
- `plans/013_open_gaps_closure_program.md` (Stage 4)
- `reports/014_open_gaps_closure_program_progress_log.md`

## Definition Of Done

- A `relation-traversal-query` / `relation-traversal-result` JSON Schema pair
  exists under `contracts/schemas/` with a `malli` mirror and validated examples.
- `traverse-relations` runs through a batched frontier provider, with the
  in-memory path preserving Stage 3 output exactly.
- The library API and the MCP `traverse_relations` tool expose the surface;
  capability metadata reports library + MCP as supported and HTTP/gRPC as
  not_exposed.
- A forward-only `semantic_index_relations` PostgreSQL projection exists with a
  PostgreSQL frontier provider proven at parity with the in-memory kernel.
