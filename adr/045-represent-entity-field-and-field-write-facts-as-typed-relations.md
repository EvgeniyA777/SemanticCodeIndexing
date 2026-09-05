---
file_type: adr
decision_id: ADR-045
title: Represent Entity-Field And Field-Write Facts As Typed Relations
status: accepted
date: 2026-08-02
deciders:
  - project owner
tags:
  - architecture
  - semantic-core
  - relations
  - state-invariants
summary: The deferred field-level half of the state-invariant packet (entity-field listing and setter-to-field writes) lands as new typed-relation kinds with fields modeled as semantic target keys, not as new unit kinds, keeping every existing graph consumer byte-identical.
agent_summary: Read this ADR before implementing plans/017 (field-level state-invariant enrichment). The decision of record is that entity fields are represented as relation target keys (never as units), carried by two additive relation types - structure/declares-field and dataflow/writes-field - emitted conservatively per lane (Java first). The state-invariant assembler consumes them; legacy callers/callees, resolve_context, impact_analysis, and resolved-only relation_support stay unchanged.
supersedes: []
superseded_by: null
links:
  - plans/017_state_invariant_field_facts_plan.md
  - plans/016_state_invariant_context_plan.md
  - adr/034-land-dataflow-facts-as-typed-relations-first.md
  - adr/038-make-typed-relations-the-canonical-semantic-graph.md
  - adr/039-separate-relation-identity-from-resolution-and-evidence.md
---

# ADR-045: Represent Entity-Field And Field-Write Facts As Typed Relations

**Status**: Accepted  
**Date**: 2026-08-02  
**Deciders**: project owner

---

## Context

`plans/016` shipped the Slice-1 state-invariant packet: for stateful/lifecycle
queries it surfaces entity/model files, assertion tests, and fixture helpers,
plus a guardrail telling the agent to read those whole files before editing.
It deliberately deferred the richer half of `ideas/012`: naming the affected
fields, their nullability / annotation / column facts, and which selected
method writes them. `plans/016` recorded this as a "separate plan, gated by
ADR-034 / plans/013 Stage 3".

That gate is now open. `plans/013` Stage 3 delivered the typed-relation
substrate (`src/semidx/runtime/relations.clj`): a registered `relation-types`
set, schema-versioned relations, `relation-id` identity separated from
resolution and evidence (ADR-039), explicit `relation-errors` validation with
snapshot `:relation_diagnostics`, and a bounded `traverse-relations` kernel.
ADR-034 requires new dataflow facts to land in that model rather than as ad-hoc
IR keys; ADR-038 makes typed relations the canonical graph for **all** new
graph semantics.

Two facts are still missing and must be represented before the assembler can be
upgraded:

- **entity-field membership**: which fields an entity/model declares, with the
  nullability / annotation / column hints that mark state-bearing fields.
- **field-write**: which selected method writes which entity field (the signal
  that lets the packet say "this method writes X but leaves `connectedAt`
  untouched - verify that is intentional").

The open question this ADR resolves: how are entity fields represented in the
semantic model, given that today no lane extracts fields (the Java lane emits
only methods and constructors as units), and how do the new facts avoid
disturbing the load-bearing calls/imports graph and its consumers.

## Decision Drivers

- Relation-first canon: ADR-034 and ADR-038 require new graph semantics to land
  as typed relations, not as new IR keys or provider-specific stores.
- Protect existing consumers: callers/callees indexes, `resolve_context`
  authority selection, `impact_analysis`, `snapshot_diff`, and the Stage 3
  resolved-only `relation_support` projection must not change behavior without
  parity evidence.
- Honesty and conservatism: ambiguous field ownership stays ambiguous; new
  facts must not inflate confidence merely by existing (ADR-034 / plans/007
  Decision 12 discipline).
- Retrieval value: the observed `plans/016` case needs the field names and their
  writers, not a new browsable field-node graph.
- Minimal blast radius: adding fields must not force a change to the unit model,
  unit identity, or every retrieval path that assumes units are methods /
  classes / functions.

## Considered Options

### Option 1. Promote entity fields to first-class units

Extract each field as a new unit kind (`field`), give it a `unit_id`, and let it
participate in ranking, selection, and the callers/callees graph.

### Option 2. Attach an ad-hoc entity-field map to entity units

Store declared fields as a bespoke attribute on the entity class unit and a
bespoke "writes" map on method units, outside the relation model.

### Option 3. Model fields as relation target keys inside typed relations

Introduce two additive relation types - `structure/declares-field` and
`dataflow/writes-field` - where the field is a semantic **target key**
(`SimpleClass#field`), never a unit. Source endpoints are existing units (the
declaring class unit; the writer method unit). Nullability / annotation / column
hints ride in evidence, not identity.

## Decision

We accept **Option 3: model fields as relation target keys inside typed
relations**.

- **Two additive relation types**, registered in `relations/relation-types`
  before any producer emits them:
  - `structure/declares-field` - an entity/model unit declares a field.
  - `dataflow/writes-field` - a method writes an entity field (direct field
    assignment or setter call).
  The relation schema version stays additive; unknown kinds remain rejected with
  a structured diagnostic (unchanged `relation-errors` discipline).
- **Fields are semantic target keys, not units.** A field's identity is the
  canonical `target_key` (`SimpleClass#field`) plus the ADR-039 identity inputs.
  `target_unit_ids` carries the **declaring entity class unit** when it is
  identified in the same snapshot; then `resolution_status` is `resolved`.
  When the declaring entity cannot be confidently identified, the relation is
  `ambiguous` / `unresolved` and is never surfaced through resolved-only
  projections. Fields never receive a `unit_id`.
- **Evidence, not identity, carries the descriptive facts.** Nullability, JPA /
  annotation tokens, and column names live in `evidence_location` /
  evidence payload so that enriching them does not mint a second edge
  (ADR-039).
- **Conservative, per-lane emission.** Producers emit field relations only
  inside entity-like classes and only for confidently recognized field
  declarations / writes. Java is the reference lane (the observed case is
  Java/JPA); other lanes are deferred. Lanes that do not emit field relations
  degrade to today's Slice-1 packet.
- **The consumer is the state-invariant assembler**, which reads these relations
  by entity target key through the relation indexes to add `entity_fields` and
  `field_writes` to the packet and to upgrade the guardrail. Because the new
  field relations are resolved only to the entity class unit (or stay
  ambiguous), the Stage 3 resolved-only `relation_support` projection and every
  legacy caller/callee/dependent/test output remain byte-identical.

Option 1 loses because promoting fields to units changes unit identity, ranking,
and the calls/imports graph for every consumer, spending the highest-risk budget
for a signal that only the state-invariant packet needs today. Option 2 loses
because it creates a second, ad-hoc graph vocabulary that contradicts ADR-038
and would have to migrate into relations later - exactly the "born legacy"
outcome ADR-034 forbids.

## Consequences

### Positive

- The field-level packet is built on the already-hardened relation substrate;
  no new storage or graph vocabulary is introduced.
- Existing consumers are provably unaffected: field relations are additive and
  either resolve only to the entity class unit or stay ambiguous, so
  resolved-only traversal ignores them.
- The relation schema gains a second, low-risk producer family (structural +
  field-write) that further validates the model before the deferred
  calls/imports migration.

### Negative

- Fields as target-key-only endpoints stretch the relation model: a field
  relation's `target_unit_ids` points at the *declaring class*, not at the field
  itself, so consumers must read the field from `target_key`. This is documented
  here as the intended representation, not an accident.
- Two edge representations for fields do not exist, but two *relation families*
  (dataflow flow-facts vs. structural declares/writes) now coexist under the
  same schema; producers and consumers must be explicit about relation type.
- Cross-lane parity is intentionally deferred; anyone reading `plans/017` for a
  non-Java lane will find field facts absent until that lane is onboarded.

### Follow-Up

- `plans/017` fixes the per-stage sequencing (declares-field first, then
  consumer wiring, then writes-field) within this decision.
- Additional lanes (Python, Clojure, TypeScript), migration/schema-file linkage,
  and richer column/relationship facts remain deferred and, when scheduled, must
  reuse these relation types rather than introduce new vocabulary.
- Add the corresponding `MEMORY.md` line with the `plans/017` Stage 1 commit.

## Status Changes

None. This ADR extends the relation model established by ADR-034 and ADR-038 and
respects the identity split from ADR-039; it supersedes nothing.

## References

- `plans/016_state_invariant_context_plan.md` - Deferred Work
- `plans/017_state_invariant_field_facts_plan.md` - execution plan
- `adr/034-land-dataflow-facts-as-typed-relations-first.md`
- `adr/038-make-typed-relations-the-canonical-semantic-graph.md`
- `adr/039-separate-relation-identity-from-resolution-and-evidence.md`
- `ideas/012_state_invariant_context_for_retrieval.md`

## Definition Of Done

`structure/declares-field` and `dataflow/writes-field` are registered relation
types emitted only by lane producers as typed relations with fields as target
keys; no field ever becomes a unit; existing callers/callees, `resolve_context`,
`impact_analysis`, `snapshot_diff`, and resolved-only `relation_support` behavior
is unchanged; and the state-invariant assembler consumes the new relations to
emit `entity_fields` / `field_writes` with an upgraded guardrail.
