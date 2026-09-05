---
title: "State Invariant Field Facts Plan"
doc_type: "architecture_plan"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Architecture Plan: State Invariant Field Facts

Companion progress log:
`reports/020_state_invariant_field_facts_progress_log.md`.

## Context

`plans/016` shipped the Slice-1 state-invariant packet (entity files, assertion
tests, fixture helpers, whole-file-read guardrail) and explicitly deferred the
field-level half of `ideas/012`: which fields an entity declares, their
state-bearing hints, and which selected method writes them. The deferral was
"gated by ADR-034 / plans/013 Stage 3", both now delivered: the typed-relation
substrate (`runtime/relations.clj`) exists with registered relation types,
ADR-039 identity, `relation-errors` validation, snapshot relation indexes, and a
bounded traversal kernel.

`ADR-045` fixes the representation decision: entity fields are modeled as
relation **target keys** (never units), carried by two additive relation types
- `structure/declares-field` and `dataflow/writes-field` - emitted conservatively
per lane, with Java as the reference lane. This plan sequences the delivery
within that decision.

The intended outcome: for a stateful query, the packet upgrades from "read these
whole files" to "entity `X` declares fields `{...}` (state-bearing:
`connectedAt`, `lastValidatedAt`, ...); method `disconnect()` writes `{status,
token}` - verify the unwritten state-bearing fields are intentionally
preserved."

## Scope

In:
- Two additive relation types (`structure/declares-field`,
  `dataflow/writes-field`) registered in `runtime/relations.clj`.
- A Java-lane producer for field declarations and field writes, emitted only for
  entity-like classes, conservatively resolved.
- Consumer upgrades in `runtime/state_invariants.clj`: `entity_fields` and
  `field_writes` packet sections and an upgraded guardrail; additive
  `packet_version` bumps.
- Additive contract mirrors (malli + JSON Schema) and cross-surface parity.

Out (deferred, see "Deferred Work"):
- Field extraction for lanes other than Java (Python, Clojure, TypeScript, ...).
- Migration / schema-file linkage.
- Richer column-name / JPA-relationship / cross-entity facts.
- Promoting fields to first-class units (explicitly rejected by ADR-045).

## Assumptions

- The canonical staged flow is unchanged; this is additive enrichment of an
  already-additive packet.
- Every output change is additive and contract-valid; no existing field or shape
  is removed; `packet_version` absorbs growth (1.0 -> 1.1 -> 1.2).
- New facts never inflate confidence and never surface ambiguous field ownership
  (ADR-034 / ADR-045 conservatism).
- Java is the reference lane; other lanes degrade cleanly to the Slice-1 packet.

## Data-Availability Boundary

Before this plan, no lane extracted fields, so the assembler could only name
files, not fields. This plan adds field facts **only** as typed relations
(ADR-045), so the unit model, unit identity, and the calls/imports graph are
untouched. Fields are target keys; a field relation resolves at most to the
declaring entity class unit. Consequently the Stage 3 resolved-only
`relation_support` projection and all legacy caller/callee/dependent/test outputs
stay byte-identical, verified per stage.

## Change Model

| Expected change | Owning boundary |
|---|---|
| New field-fact kind | `runtime/relations.clj` `relation-types` (registered once) |
| New language's field conventions | Per-lane producer (Java first) |
| Packet shape evolves | State-invariant packet contract (versioned, additive) |
| Guardrail wording sharpens | Guardrail recommender (pure) |
| New surface exposure | Transport passthrough only |

No transport handler owns field extraction, relation emission, assembly, or the
guardrail rule.

## Boundaries

### 1. Field-Relation Vocabulary

Responsibility: define and validate the two new relation types.
Knows about: the existing `relation-types`, `relation-errors`, and ADR-039
identity inputs.
Does not know about: lanes, retrieval, transports.
Shape: additive entries in `relations/relation-types`; `relation-errors` already
validates unknown kinds, resolution status, and evidence shape - the new types
must pass unchanged validation. Field target keys use the canonical
`SimpleClass#field` form.
Primary location: `src/semidx/runtime/relations.clj`.

### 2. Java Field Producer

Responsibility: emit `structure/declares-field` (Stage 1) and
`dataflow/writes-field` (Stage 3) relations for entity-like Java classes.
Knows about: Java field-declaration and field-write syntax, entity-class
heuristics, and the shared relation-emission helper pattern already used by the
Clojure/Python dataflow producers.
Does not know about: the assembler, contracts, transports, or the relation
indexes' internal shape.
Reuses (do not re-implement): the per-lane producer -> normalizer pattern
(`py-passes-argument-relations` feeding the relation normalizer) and the Java
lane's existing class/annotation scanning (`java-class-spots`,
`java-resolve-class-name`, `parse-file`).
Shape: pure functions returning relation maps (`:relation-type`, `:source-unit-id`,
`:target-key`, `:evidence-location`, resolution inputs). The Java lane emits **no
relations today**; this establishes the first Java relation output.
Primary location: `src/semidx/runtime/languages/java.clj`.

### 3. Field-Aware Assembler

Responsibility: consume field relations for the packet's entity candidates and
add `entity_fields` / `field_writes`; keep Slice-1 behavior when field facts are
absent.
Knows about: the relation indexes (by entity target key), the existing Slice-1
inputs (`index`, `query`, `selected`, `related-test-paths`), and the bounded
take-12 discipline.
Does not know about: lane internals, relation producer syntax, MCP/HTTP/gRPC
shapes, budget accounting.
Reuses: the existing `assemble` seam and `unit-reference` / bounding helpers.
Primary location: `src/semidx/runtime/state_invariants.clj`.

### 4. Guardrail Recommender

Responsibility: turn field availability into a sharper agent-facing
recommendation + reason code, degrading to the Slice-1 whole-file guardrail when
fields are unavailable.
Shape: pure function; reuses the existing `coded` guardrail pattern.
Primary location: `src/semidx/runtime/state_invariants.clj`.

### 5. Transport Passthrough

Responsibility: serialize the additive `entity_fields` / `field_writes` sections
unchanged across `impact_analysis`, `expand_context`, and the detail packet;
HTTP/gRPC delegate to the library result.
Primary locations: `src/semidx/mcp/core.clj`, `src/semidx/runtime/retrieval.clj`
budget accounting, then `http.clj` / `grpc.clj` parity. No transport derives the
packet.

## Contracts

### Contract: field relation

Producer: lane field producers. Consumer: relation indexes + assembler.
Shape (typed relation, ADR-045):

```clojure
{:relation_type "structure/declares-field"   ;; or "dataflow/writes-field"
 :source_unit_id "src/.../Connection.java::...#Connection"
 :target_key "Connection#connectedAt"
 :target_unit_ids ["...Connection"]           ;; declaring entity class unit when resolved
 :resolution_status "resolved"                 ;; else "ambiguous" / "unresolved"
 :evidence_quality "medium"
 :evidence_location {:start_line 12
                     :annotations ["@Column(nullable = false)"]
                     :nullable false}}
```

Rules:
- Emitted only inside entity-like classes; ambiguous ownership stays
  `ambiguous`/`unresolved` and never surfaces through resolved-only projections.
- Descriptive facts (nullability, annotations) live in evidence, not identity
  (ADR-039).
- Fields never become units.

### Contract: state-invariant packet (evolved, additive)

```clojure
{:state_invariants
 {:packet_version "1.2"
  :triggered_by [...]
  :entity_candidates [...]
  :entity_fields                                    ;; added Stage 2 (packet 1.1)
  [ {:entity "Connection"
     :path "..."
     :fields [ {:name "connectedAt" :nullable false
                :annotations ["@Column(nullable = false)"]
                :state_bearing true} ] } ]
  :state_writers [...]
  :field_writes                                     ;; added Stage 3 (packet 1.2)
  [ {:unit_id ".../disconnect" :symbol "disconnect"
     :writes ["status" "token"] } ]
  :assertion_tests [...]
  :fixture_helpers [...]
  :guardrail {:code "..." :recommendation "..."}}}
```

Rules:
- All lists bounded (existing take-12 discipline).
- `entity_fields` / `field_writes` are present only when field relations exist;
  otherwise the packet is exactly the Slice-1 (1.0) shape and guardrail.
- The guardrail names state-bearing and unwritten fields only when field facts
  are available; otherwise it keeps the Slice-1 whole-file recommendation.
- `packet_version` bumps additively: 1.1 adds `entity_fields`, 1.2 adds
  `field_writes`.

Placement: same as `plans/016` - `impact_analysis` first, then `expand_context`
and the detail packet, with budget accounting; HTTP/gRPC passthrough only.

## Dependency Direction

- The relation vocabulary is pure and owned by `runtime/relations.clj`.
- Lane producers depend on the relation vocabulary and lane syntax, never on the
  assembler or transports.
- The assembler depends on the relation indexes (abstraction), never on lane
  producer internals (ADR-045 / plans/007 dependency direction).
- Transports depend on the assembled packet; they never compute it.

## SOLID Check (honest tensions)

- **SRP**: vocabulary, lane extraction, assembly, and guardrail wording stay
  separate. Risk: the Java producer could accrete unrelated field heuristics;
  mitigate with small named predicates and Java-only scope this plan.
- **OCP**: new relation types and new lanes attach without changing the packet
  contract; `packet_version` absorbs growth.
- **ISP**: consumers ignoring `entity_fields`/`field_writes` are unaffected
  (additive, conditionally present).
- **DIP**: the assembler depends on the relation index abstraction, not on Java
  extraction internals.
- **Tension**: fields as target-key-only endpoints (ADR-045) are a deliberate
  stretch of the relation model, documented rather than hidden; field writes for
  non-Java lanes are absent by design and the guardrail degrades honestly.

## Risks

1. **[High] Field-write inference is noisy or wrong.** Direct-assignment /
   setter detection can misattribute writes. Mitigation: keep writes-field in
   its own Stage 3 behind conservative resolution; assert behavior on fixtures;
   ambiguous writes stay ambiguous; benchmark/quality parity gates the stage.
2. **[Medium] Entity-field extraction over- or under-fires on non-conventional
   layouts.** Mitigation: require entity-like class context; when detection is
   weak, emit no field relations and keep the Slice-1 guardrail.
3. **[Medium] Relation-model stretch (fields as target keys) confuses future
   consumers.** Mitigation: ADR-045 documents the representation; the assembler
   reads fields from `target_key`, and resolved-only projections ignore field
   relations.
4. **[Medium] Behavior drift in existing graph consumers.** Mitigation:
   per-stage byte-identical parity checks on callers/callees, `impact_analysis`,
   `snapshot_diff`, and resolved-only `relation_support`; benchmarks + semantic
   quality as gates.
5. **[Low] Contract churn.** Mitigation: additive-only rule + `packet_version`.

## Implementation Sequence

### Stage 1 - `structure/declares-field` relations for the Java lane
Status: completed.

Register `structure/declares-field` in `relation-types`. Add a Java-lane field
producer that emits declares-field relations for entity-like classes, capturing
name + nullability/annotation evidence, resolved to the declaring entity class
unit when identified. Wire the relations into the Java lane's parse output (the
lane emits none today) and confirm `index-relations` ingests them. Extend the
Java state fixture with entity fields (including `connectedAt`,
`lastValidatedAt`, a `@Column(nullable = false)` field). Add mirrored `*-test`
namespaces: extraction, ADR-039 identity stability, conservative non-emission for
non-entity classes, and invalid-fact diagnostics. No consumer wiring - the packet
stays 1.0 and every legacy output (callers/callees, `impact_analysis`,
resolved-only `relation_support`) stays byte-identical. Verify: `clojure -M:test`,
`./scripts/run-mvp-gates.sh`, `./scripts/run-benchmarks.sh`,
`./scripts/run-semantic-quality-report.sh`,
`./scripts/validate-language-onboarding.sh java`.

### Stage 2 - Field-aware assembler + `entity_fields` (packet 1.1)
Status: completed.

Thread the relation indexes into `state-invariants/assemble`; add the
`entity_fields` section for entity candidates from `structure/declares-field`
relations; upgrade the guardrail to name state-bearing fields when available,
degrading to the Slice-1 guardrail otherwise; bump `packet_version` to 1.1. Add
additive malli + JSON Schema for `entity_fields`; refresh the example. Wire the
section through `impact_analysis`, `expand_context`, and the detail packet with
budget accounting; prove HTTP/gRPC passthrough parity. Verify:
`./scripts/validate-contracts.sh`, `clojure -M:test`,
`./scripts/run-mvp-gates.sh`, MCP smoke over the Java fixture.

### Stage 3 - `dataflow/writes-field` relations + `field_writes` (packet 1.2)
Status: completed.

Register `dataflow/writes-field`. Add a Java-lane field-write producer
(direct `this.field = ...` / `field = ...` assignment and `setField(...)` calls),
conservatively resolved. Consume in the assembler as `field_writes` per selected
state writer; sharpen the guardrail to contrast written vs. declared
state-bearing fields; bump `packet_version` to 1.2. Additive contract + parity +
fixtures with a disconnect/reconnect writer. Verify: full gates plus
`./scripts/run-benchmarks.sh` and `./scripts/run-semantic-quality-report.sh`
showing no regression and a measurable gain on the state-invariant case.

### Deferred Work (separate future plan)
Field extraction for Python, Clojure, and TypeScript lanes; migration /
schema-file linkage; richer column-name / JPA-relationship facts. These reuse the
ADR-045 relation types; they do not introduce new vocabulary.

## Verification

- `clojure -M:test` (auto-discovers new mirrored `*-test` namespaces).
- `./scripts/validate-contracts.sh` for Stage 2+.
- `./scripts/run-mvp-gates.sh` before declaring any stage green.
- `./scripts/run-benchmarks.sh` + `./scripts/run-semantic-quality-report.sh` for
  extraction/relation/packet changes; no regression, measurable gain on the
  state case by Stage 3.
- `./scripts/validate-language-onboarding.sh java` for Java lane changes.
- Byte-identical parity for callers/callees, `impact_analysis`, `snapshot_diff`,
  and resolved-only `relation_support` at every stage.
- Acceptance from `ideas/012`: the packet names the entity's declared fields and
  (Stage 3) which writer touches which field, with an honest guardrail when field
  facts are unavailable.

## Non-Goals

- No promotion of fields to units (rejected by ADR-045).
- No non-Java lane field facts in this plan.
- No migration/schema-file linkage in this plan.
- No confidence inflation from the mere existence of new relations.
