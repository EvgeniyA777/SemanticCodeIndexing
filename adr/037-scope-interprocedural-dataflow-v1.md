---
file_type: adr
decision_id: ADR-037
title: Scope Interprocedural Dataflow V1
status: accepted
date: 2026-07-20
deciders:
  - project owner
tags:
  - architecture
  - semantic-core
  - dataflow
  - relations
summary: "Stage 3 starts with a narrow relation-first interprocedural/dataflow slice: a new typed-relation substrate, Clojure then Python producers, and bounded retrieval/impact projections without migrating existing calls/imports."
agent_summary: "Read this ADR before implementing plans/013 Stage 3. The decision of record is a minimal v1: create the relation schema/index boundary, emit only new dataflow facts as typed relations, ship Clojure first and Python second, expose gains through conservative retrieval/impact projections, and leave existing calls/imports plus public graph query surfaces unchanged."
supersedes: []
superseded_by: null
links:
  - plans/013_open_gaps_closure_program.md
  - adr/034-land-dataflow-facts-as-typed-relations-first.md
  - plans/007_semidx_extension_architecture_resolution_plan.md
  - reports/014_open_gaps_closure_program_progress_log.md
---

# ADR-037: Scope Interprocedural Dataflow V1

**Status**: Accepted  
**Date**: 2026-07-20  
**Deciders**: project owner

---

## Context

`plans/013` Stage 3 calls for the first interprocedural/dataflow-sensitive
semantic tranche. `ADR-034` already resolved the main fork: new dataflow facts
must land as typed relations, while existing `calls` and `imports` stay on the
current graph path until a later, parity-gated migration.

The current runtime has a load-bearing Semantic IR and index graph:

- `semidx.runtime.semantic-ir` normalizes parser output around files, units,
  imports, calls, call tokens, diagnostics, and semantic IDs.
- `semidx.runtime.index` builds `:callers_index`, `:callees_index`,
  `:module_dependents`, and `:test_target_index`.
- `semidx.runtime.retrieval` consumes those indexes directly for graph
  expansion and impact hints.
- `semidx.runtime.storage` stores full snapshot payloads and projects unit /
  call-edge tables for query helpers.

There is no existing `semidx.runtime.relations` namespace or relation index
yet. Stage 3 therefore starts by introducing that boundary rather than adding
new ad-hoc flow keys to `semantic-ir`.

## Decision Drivers

- Keep the first dataflow slice useful but small enough to verify rigorously.
- Prove the relation model with real semantic facts before migrating old graph
  edges.
- Preserve existing behavior for protected callers/callees, retrieval,
  `impact_analysis`, and `snapshot_diff`.
- Keep confidence ceilings honest; relation facts provide evidence, not an
  automatic confidence increase.
- Avoid a broad public graph query surface in Stage 3; that remains the job of
  `plans/013` Stage 4.

## Decision

Stage 3 v1 will ship a narrow, relation-first dataflow slice.

### Relation Boundary

Add `semidx.runtime.relations` as the owner of:

- relation schema versioning and normalization
- deterministic `relation_id` construction
- forward and reverse relation indexes
- conservative validation of relation fields

The v1 relation shape follows `ADR-034` / `plans/007` Decision 11:

```text
Relation {
  relation_id
  source_unit_id
  target_key
  target_unit_ids
  relation_type
  resolution_status
  evidence_quality
  provenance
  evidence_location
}
```

Optional additive metadata such as `language`, `parser_mode`,
`source_symbol`, `target_symbol`, `arg_index`, or `relation_schema_version` may
be included when needed for debugging or future migration, but consumers must
not depend on lane-specific producer internals.

Index state gains:

- `:relations` keyed by `relation_id`
- `:relation_forward_index` keyed by `source_unit_id`
- `:relation_reverse_index` keyed by `target_unit_id` and unresolved
  `target_key`

Snapshot payload storage must preserve these fields for both in-memory and
PostgreSQL-backed snapshots. A dedicated SQL relation table is deferred until
Stage 4 or another explicit graph-query stage needs storage-level relation
queries.

### V1 Fact Scope

Only these relation types are in scope:

1. `dataflow/local-binding-call-result`
   - A local binding receives the result of a resolved call.
   - Example shape: `x = make_client(...)`; relation from the enclosing unit
     to the `make_client` target.

2. `dataflow/returns-call-result`
   - A function or method returns the result of a resolved call directly or
     through a simple wrapper.
   - Example shape: `return normalize(order)` or the Clojure equivalent.

3. `dataflow/passes-argument`
   - A parameter or local binding is passed into a resolved call target at a
     known argument position.
   - Example shape: `validate(order)` inside a wrapper that received `order`.

All three relation types must use conservative resolution:

- resolved flows use `resolution_status "resolved"` only when the target units
  are unambiguous
- ambiguous flows use `resolution_status "ambiguous"` and must not create
  caller/callee edges
- unknown or unsupported flows are omitted or emitted as unresolved facts only
  when a consumer can ignore them safely

### Lane Order

Implementation order is:

1. Relation substrate and indexes with no retrieval behavior change.
2. Clojure producer as the reference high-confidence lane.
3. Python producer as the first non-Clojure lane.
4. Retrieval and impact projections over relation indexes after both producers
   have regression coverage.

TypeScript, Java, and Elixir dataflow producers are explicitly deferred. Their
existing import/owner-aware semantic-core behavior remains unchanged.

### Consumer Exposure

Stage 3 may consume relations only through explicit projections over the
relation indexes:

- retrieval may add low-weight, reason-coded support for relation-backed
  candidates when a query asks about wrappers, returned values, or argument
  forwarding
- `impact_analysis` may include relation-backed risky neighbors or dependents
  when the relation is resolved and bounded
- diagnostics or trace metadata may mention relation evidence when it affected
  selection

Stage 3 must not add:

- a broad public `query_relations` API
- new contract-required context-packet relation arrays
- replacement caller/callee projections
- dual-write of existing `calls` or `imports`

Those belong to later stages with separate compatibility gates.

## Architecture Plan

### Boundaries

1. `semidx.runtime.relations`
   Responsibility: relation schema, normalization, IDs, and indexes.  
   Knows about: unit IDs, target keys, relation metadata.  
   Does not know about: parser implementation, retrieval ranking policy,
   storage engine details.  
   Why this boundary exists: relation schema changes should stay local and
   consumers should read stable indexes, not producer internals.

2. Lane dataflow producers
   Responsibility: emit relation candidates from lane-specific semantic facts.  
   Knows about: lane syntax, local binding / return / argument shapes, existing
   call target evidence.  
   Does not know about: retrieval scoring, storage layout, public API shape.  
   Why this boundary exists: language-specific parsing changes should not
   modify relation consumers.

3. Index assembly
   Responsibility: collect produced relations and attach relation indexes to
   the snapshot.  
   Knows about: units, files, relation normalization entrypoint.  
   Does not know about: lane-specific extraction details.

4. Retrieval / impact projections
   Responsibility: convert relation indexes into bounded candidate support.  
   Knows about: retrieval policy, query intent, ranking and guardrail rules.  
   Does not know about: producer-specific syntax details.

### Dependency Direction

- Lane producers depend on the relation schema boundary.
- Index assembly depends on relation normalization/indexing.
- Retrieval and impact depend on relation indexes, not on lane producers.
- Storage preserves relation-bearing snapshots, but relation policy does not
  depend on storage implementation.

## Consequences

### Positive

- Stage 3 proves dataflow value without creating new legacy IR edge keys.
- Relation schema and indexes get exercised by real producers before the
  expensive calls/imports migration.
- Existing graph behavior stays stable unless a relation-backed improvement is
  explicitly tested and reason-coded.

### Negative

- Two graph models coexist: legacy calls/imports plus new dataflow relations.
- The first public benefit is intentionally narrow; a general relation query
  surface remains deferred.
- Python producer coverage may reveal parser limitations; unresolved or
  ambiguous flows must stay conservative instead of forcing confidence upward.

## Implementation Sequence

1. Add `semidx.runtime.relations`, relation normalization tests, index
   assembly fields, and snapshot preservation.
2. Add Clojure `dataflow/*` producer coverage for local binding call results,
   direct return call results, and argument forwarding.
3. Add Python producer coverage for the same three relation types where the
   current parser can identify the facts safely.
4. Add bounded retrieval / impact projections with low-weight relation reason
   codes and protected no-regression tests.
5. Add benchmark and semantic-quality fixtures that demonstrate measurable
   gains on wrapper/dataflow queries without confidence ceiling increases.

## Verification Requirements

- Unit tests for relation normalization, deterministic IDs, and forward/reverse
  indexes.
- Integration tests for Clojure and Python relation production.
- Retrieval tests showing relation-backed improvement for at least one wrapper
  or argument-forwarding scenario.
- Protected existing caller/callee and retrieval behavior remains unchanged.
- `clojure -M:test`, `./scripts/run-benchmarks.sh`,
  `./scripts/run-semantic-quality-report.sh`, `./scripts/run-mvp-gates.sh`,
  `clojure -M:ccc check --root .`, and `git diff --check` pass.
- Confidence ceilings remain unchanged unless a later evidence review records
  an explicit non-conservative recalibration.

## Follow-Up

- Update `plans/007` Stage 4 wording after Stage 3 implementation lands so it
  treats the relation substrate as existing infrastructure, while keeping the
  call/import dual-write migration gates intact.
- Revisit TypeScript, Java, and Elixir producers only after Clojure/Python v1
  results are measured.
- Keep Stage 4 responsible for any public semantic graph query surface.

## Status Changes

This ADR narrows `plans/013` Stage 3 and supersedes the old Stage 3 wording
that implied adding new flow keys directly to `semantic-ir`. It does not
supersede `ADR-034`; it executes the scoping follow-up required by `ADR-034`.

## References

- `adr/034-land-dataflow-facts-as-typed-relations-first.md`
- `plans/007_semidx_extension_architecture_resolution_plan.md` - Decisions 11
  and 12, Stage 4
- `plans/013_open_gaps_closure_program.md` - Stage 3
- `src/semidx/runtime/semantic_ir.clj`
- `src/semidx/runtime/index.clj`
- `src/semidx/runtime/retrieval.clj`
- `src/semidx/runtime/storage.clj`

## Definition Of Done

Stage 3 is done when Clojure and Python emit the v1 `dataflow/*` facts as typed
relations, relation indexes are snapshot-preserved, retrieval/impact can use
resolved relations through bounded projections, protected existing graph
behavior is unchanged, confidence ceilings do not inflate, and the required
test/benchmark/gate suite passes.
