---
title: "State Invariant Context Plan"
doc_type: "architecture_plan"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Architecture Plan: State Invariant Context

Companion progress log:
`reports/018_state_invariant_context_progress_log.md`.

## Context

`ideas/012_state_invariant_context_for_retrieval.md` records a real retrieval
gap: for stateful changes (disconnect/reconnect, status enums, credential and
timestamp handling), the correct blast radius is not only the service methods
that rank highly. The invariants live in the entity/model file, the test tail
that asserts field preservation, and fixture helpers that build state - all of
which plain `impact_analysis` under-ranks. In the observed case
(JobApplicationTracker Google Sheets OAuth), the agent still had to manually
read the entity and the test tail to avoid clearing `connectedAt` /
`lastValidatedAt` or erasing a spreadsheet target that must survive reconnect.

The intended outcome: when a query smells like state/lifecycle work, retrieval
surfaces the entity file, the field-asserting tests, and the fixture helpers,
and - when it cannot confidently assemble the packet - emits an explicit
guardrail telling the agent to read those whole files before editing. This
turns today's implicit best practice into an agent-facing signal.

## Scope

This plan covers a first, heuristic slice that ships value using only facts the
index already has, plus the contract and surface wiring to expose it.

In:
- A pure intent classifier: does this query concern state/lifecycle/persistence?
- A state-invariant assembler that reuses existing blast-radius machinery to
  gather entity-like units, their field-asserting tests, and fixture helpers.
- A guardrail recommendation when the packet cannot be confidently completed.
- Additive output on `impact_analysis` first, then optionally `expand_context`.

Out (deferred, see "Deferred Work"):
- Setter-to-field write extraction ("which fields does this method write").
- Listing entity fields with nullability / JPA annotations / column names.
- Migration/schema-file linkage.
- A dedicated `state_invariant_context` MCP tool.

## Assumptions

- The canonical staged flow stays `resolve_context -> expand_context ->
  fetch_context_detail`; this is an additive enrichment, not a new stage.
- Every output change is additive and contract-valid; no existing field or
  shape is removed.
- The first slice is heuristic and must not fabricate field-level facts the
  index does not have. When evidence is thin, it emits a guardrail instead of a
  confident-looking but invented packet (honesty over completeness).
- Java is the reference lane (the observed case is Java/JPA), but the assembler
  must degrade cleanly for lanes without entity conventions.

## Data-Availability Boundary (why this plan is split)

The Java lane extracts only methods and constructors as units. Entity **fields**
are not units, and there is no "method writes field X" relation in the index
today. Therefore the rich half of `ideas/012` - naming affected fields, their
nullability/annotations, and which selected method writes them - cannot be built
without new dataflow-style extraction.

Per `ADR-034`, interprocedural/dataflow facts (including setter-to-field writes)
must land in the typed-relation model, not as ad-hoc keys. So this plan does
**not** add field-write extraction. Slice 1 delivers what existing facts allow -
surfacing the right files and an honest guardrail - which is exactly what the
observed case needed. Field-level enrichment is deferred to the relation tranche.

## Change Model

| Expected change | Owning boundary |
|---|---|
| New trigger terms / better intent detection | Intent classifier |
| New language's entity conventions | Assembler (per-lane heuristic) |
| Packet shape evolves as we learn | State-invariant packet contract (versioned, additive) |
| Field-level facts become available | Relation model (ADR-034), consumed later by the assembler |
| New surface exposure | Transport passthrough only |

No transport handler owns intent detection, assembly, or the guardrail rule.

## Boundaries

### 1. State Intent Classifier

Responsibility: decide whether a query concerns state/lifecycle/persistence,
from intent text and any explicit targets.
Knows about: the query's intent string and target symbols; a bounded trigger-term
set (`disconnect`, `reconnect`, `status`, `state`, `lifecycle`, `credential`,
`secret`, `token`, `timestamp`, `persist`, `entity`, and close variants).
Does not know about: the index, ranking, retrieval internals, or transports.
Shape: pure function `state-intent? :: query -> boolean` (plus a reason code for
diagnostics). Independently testable with plain maps.
Primary location: `src/semidx/runtime/query_anchors.clj` (co-located with the
existing intent-inference helpers) or a small dedicated helper it requires.

### 2. State-Invariant Assembler

Responsibility: given the selected units and the bound index, collect the
state-invariant packet from existing facts.
Knows about: index unit facts (`:kind`, `:symbol`, `:path`, `:module`,
`:signature`, `:class_name`), the existing blast-radius helpers, and
entity-like / fixture-like heuristics.
Does not know about: MCP/HTTP/gRPC shapes, budget accounting, confidence policy.
Reuses (do not re-implement): the `callers_index`, `test_target_index`,
`units-for-path`, and module-dependent traversal already inside
`build-impact-hints` (`src/semidx/runtime/retrieval.clj:555`).
Primary location: a new private helper section in
`src/semidx/runtime/retrieval.clj` (or an extracted `state_invariants.clj` if it
grows past a few helpers), called from `impact-analysis` /
`build-impact-hints`.

Slice-1 packet content (existing facts only):
- **entity_candidates**: units in the blast radius whose path/class look like a
  persistent entity/model (heuristic: entity-suffixed class names, `/entity/` or
  `/model/` path segments, and - Java - `@Entity`-bearing signature lines when
  present in the unit signature). Reported as file + unit references, not
  invented field lists.
- **state_writers**: already-selected methods whose names match writer/transition
  patterns (`save*`, `update*`, `disconnect*`, `clear*`, `reset*`, `set*`).
- **assertion_tests**: test units/paths (via existing `test_target_index` and
  test-kind detection) that reference the entity module, including test-tail
  helpers that plain related-tests ranking misses.
- **fixture_helpers**: helper units in those test files that construct the
  entity/summary type (heuristic: builder-named methods returning the entity
  type in signature).
- **guardrail**: when entity_candidates is non-empty but field-level confidence
  is unavailable (always true in Slice 1), an explicit recommendation to read
  the full entity file, primary service test, and fixture helpers before editing.

### 3. Guardrail Recommender

Responsibility: turn assembler completeness into an agent-facing recommendation
string + reason code.
Knows about: the assembled packet's completeness signals.
Does not know about: storage, extraction, transports.
Shape: pure function. Reuses the existing `coded` diagnostic pattern.

### 4. Transport Passthrough

Responsibility: serialize the additive `state_invariants` section unchanged.
Primary locations: `src/semidx/mcp/core.clj` (`tool-impact-analysis`, and
`tool-expand-context` if the section is added there), then `http.clj` / `grpc.clj`
for parity. No transport derives or re-interprets the packet.

## Contracts

### Contract: state-invariant packet

Client: `impact_analysis` consumers first; `expand_context` consumers second.
Shape (additive, bounded, versioned):

```clojure
{:state_invariants
 {:packet_version "1.0"
  :triggered_by ["status" "timestamp"]        ;; matched trigger terms
  :entity_candidates [ {:unit_id .. :path .. :symbol ..} ]
  :state_writers     [ {:unit_id .. :symbol ..} ]
  :assertion_tests   ["path/to/FooServiceTest.java" ..]
  :fixture_helpers   [ {:unit_id .. :symbol ..} ]
  :guardrail {:code "state_invariants_require_whole_file_read"
              :recommendation "..."}}}
```

Rules:
- The whole `:state_invariants` key is present only when the intent classifier
  fires; absent otherwise (zero cost / zero noise for non-stateful queries).
- All lists are bounded (reuse the existing take-12 discipline).
- Slice 1 never emits field-level claims; `:guardrail` is always present when
  `:entity_candidates` is non-empty.
- `:packet_version` allows additive evolution when relation-backed facts land.

Placement decision: land on `impact_analysis` first. Its library return is a
bare hint map (`retrieval.clj:1630`), so adding a sibling key is the smallest
contract surface. `expand_context`'s `:impact_hints` is context-packet-bound;
adding a sibling section there requires a `context-packet.schema.json` + malli
change and is a separate, later step in this plan.

## Dependency Direction

- Intent classifier is pure and owned by retrieval; nothing depends on transports.
- Assembler depends on index facts and existing blast-radius helpers, never on
  MCP request handling.
- Transports depend on the assembled packet; they do not compute it.
- When field-level facts arrive, the assembler consumes them through the relation
  projection (ADR-034), not by reaching into lane extraction internals.

## SOLID Check (honest tensions, not a rubber stamp)

- **SRP**: intent detection, assembly, and guardrail wording are separate. Risk:
  the assembler could accrete per-lane heuristics; mitigate by keeping each
  heuristic a small named predicate and adding lanes only when a real case exists.
- **OCP**: new trigger terms and new lanes attach without touching the output
  contract; `:packet_version` absorbs shape growth.
- **ISP**: `impact_analysis` consumers that ignore `:state_invariants` are
  unaffected (additive, conditionally present).
- **DIP**: the assembler depends on index-fact and (future) relation
  abstractions, not on Java-lane internals.
- **Tension**: the most valuable facts (field writes) are deliberately absent in
  Slice 1. This is honest scoping, not a hidden limitation - the guardrail states
  it to the agent.

## Risks

1. **[High] Heuristic entity detection is noisy or misses non-conventional
   layouts.** Why: not every project suffixes entities or uses `/entity/`.
   Mitigation: require at least one corroborating signal; when detection is
   weak, still emit the guardrail rather than a confident empty packet; assert
   behavior on fixtures, not on exact rank order.
2. **[Medium] Trigger over-firing adds noise to unrelated queries.** Why: terms
   like `state` are common. Mitigation: require the term in intent text (not
   incidental code), keep the section absent when no entity candidate is found,
   and cap all lists.
3. **[Medium] Scope creep toward field-level facts.** Why: the idea's richest
   content tempts ad-hoc extraction. Mitigation: ADR-034 forbids it here; this
   plan's Deferred Work section is the boundary.
4. **[Low] Contract churn if the packet shape changes.** Mitigation:
   `:packet_version` + additive-only rule.

## Implementation Sequence

### Stage 1 - Intent classifier + fixtures (thin, provable)
Status: completed in commit `5f65c1a`.

Pure `state-intent?` in `query_anchors.clj` + mirrored test. A fixture repo
(Java entity + service + service-test-with-tail + fixture helper) that
reproduces the observed case. Verify the classifier fires on lifecycle intent
and stays quiet otherwise. `clojure -M:test`.

### Stage 2 - Assembler from existing facts
Status: completed on 2026-08-02; see
`reports/018_state_invariant_context_progress_log.md`.

The assembler is isolated in `runtime/state_invariants.clj` and wired from
`retrieval.clj`, reusing blast-radius results and indexes without adding policy
to transports.
Wire an additive `:state_invariants` section into the `impact-analysis` return,
gated by the classifier. Guardrail always present when entity candidates exist.
Verify against the Stage 1 fixture: entity file surfaced, test tail + fixture
helper included, guardrail present. `clojure -M:test`.

### Stage 3 - Contract + MCP surface
Status: completed on 2026-08-02; see
`reports/018_state_invariant_context_progress_log.md`.

The packet contract now has a `malli` mirror (`state-invariants` +
`state-invariant-unit-ref` in `src/semidx/contracts/schemas.clj`), a standalone
`contracts/schemas/state-invariants.schema.json` JSON Schema, and a validated
`contracts/examples/state-invariants/impact-analysis-packet.json` sample wired
into the example catalog and path-based validator mapping. The MCP
`tool-impact-analysis` handler already returns the whole hint map, so the
additive `:state_invariants` section passes through inside `:impact_hints`
unchanged; usage-metric counters stay additive (unchanged). `triggered_by` is now
bounded to the take-12 discipline so the `codeArray` contract stays honest.
Verified with `./scripts/validate-contracts.sh`, `clojure -M:test`,
`./scripts/run-mvp-gates.sh`, and a REPL MCP smoke over the Java state fixture.

### Stage 4 - Parity + optional expand_context
Status: completed on 2026-08-02; see
`reports/018_state_invariant_context_progress_log.md`.

The staged-retrieval ergonomics justify exposing the packet after selection:
agents otherwise need a separate `impact_analysis` call to receive the
whole-file guardrail. `expand_context` and the detail-stage context packet now
conditionally carry the same budget-accounted `state_invariants` sibling.
The Malli expansion/context mirrors and the JSON context-packet schema accept
the additive section. HTTP and gRPC continue to delegate to the library result
without transport-owned policy; cross-surface tests prove unchanged passthrough.

### Deferred (separate plan, gated by ADR-034 / plans/013 Stage 3)
Setter-to-field write relations, entity-field listing with nullability/annotation
/ column facts, and migration linkage. These are dataflow facts that must land in
the typed-relation model. When they exist, the assembler consumes them to upgrade
the packet from "read these files" to "these specific fields and invariants",
and `:packet_version` bumps.

## Verification

- `clojure -M:test` (auto-discovers new mirrored `*-test` namespaces).
- `./scripts/validate-contracts.sh` for Stage 3+.
- MCP smoke: `impact_analysis` with a lifecycle intent over the fixture repo
  returns the `state_invariants` section with the entity file, test tail, fixture
  helper, and guardrail; a non-lifecycle intent returns no such section.
- Acceptance criteria from `ideas/012`: entity/model file surfaced even when the
  initial selection focuses on service methods; assertion tests and fixture
  helpers included or explicitly recommended for whole-file reading; a guardrail
  present when confidence is low instead of implying the selected spans suffice.

## Non-Goals

- No replacement of tests or human review.
- No interprocedural dataflow precision in this plan.
- No whole-file dumping by default; whole-file reads are an explicit
  recommendation, keeping staged retrieval compact.
