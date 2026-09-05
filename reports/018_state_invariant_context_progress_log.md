---
title: "State Invariant Context Progress Log"
doc_type: "progress_log"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# State Invariant Context Progress Log

Tracks execution of `plans/016_state_invariant_context_plan.md`.

## Stage 1 - Intent Classifier And Tests

- Status: completed.
- Historical note: this entry was backfilled during the 2026-08-02
  documentation-status cleanup; it was not maintained live while Stage 1 was
  implemented.
- Summary: Added the pure `matched-state-terms` and `state-intent?` query
  classifier with bounded trigger terms, camelCase/snake_case tokenization,
  target-symbol support, distinct diagnostic terms, and nil-safe behavior.
- Commit: `5f65c1a feat: add state/lifecycle intent classifier (plan 016 Stage 1)`.
- Changed files:
  - `src/semidx/runtime/query_anchors.clj`
  - `test/semidx/runtime/query_anchors_test.clj`
- Verification recorded by the implementation: mirrored tests cover lifecycle
  intent, camelCase and snake_case tokenization, target-symbol triggers,
  deduplication, unrelated intent, substring false positives, and nil safety.
- Verification during this backfill: not rerun; this entry changes documentation
  only.
- Known blockers: none.

## Stage 2 - State-Invariant Assembler

- Status: completed.
- Summary:
  - Added `semidx.runtime.state-invariants`, a focused Slice-1 policy module that
    consumes the normalized query, selected units, existing caller/callee and
    test indexes, and the existing `related_tests` blast-radius result.
  - `impact-analysis` now conditionally adds a versioned `state_invariants`
    packet only for stateful queries with a corroborated entity/model candidate.
  - The packet contains bounded, deterministic entity candidates, state writer
    references, prioritized assertion-test paths, fixture helpers, and an
    explicit whole-file-read guardrail. It never emits field-level claims.
  - `expand_context`, detail fetch, contracts, MCP, HTTP, and gRPC are unchanged;
    those surfaces remain Stage 3/4 work.
- Changed files:
  - `src/semidx/runtime/state_invariants.clj`
  - `src/semidx/runtime/retrieval.clj`
  - `test/semidx/runtime/state_invariants_test.clj`
  - `test/semidx/integration/runtime_test.clj`
  - `plans/016_state_invariant_context_plan.md`
  - `reports/018_state_invariant_context_progress_log.md`
  - `MEMORY.md`
  - `docs/code-context.md`
- Review findings:
  - **[Medium, accepted and fixed]** The first implementation placed roughly
    140 lines of independently changing state-invariant policy inside the
    already broad retrieval namespace. The policy was extracted into the
    dedicated `state_invariants` module with one narrow `assemble` seam.
  - **[Medium, accepted and fixed]** A global lexical sort before the 12-item
    assertion-test cap could let weak `related_tests` entries displace direct
    caller/import/signature evidence. Budget priority is now deterministic:
    corroborated paths, then `test_target_index` paths, then general related
    tests; a saturated-budget regression test proves the order.
  - No high- or medium-severity findings remain after the fixes. The full-index
    test-unit scan is an accepted Slice-1 heuristic cost: `impact_analysis`
    already performs an all-unit blast-radius scan, and this path runs only when
    state intent plus an entity candidate are present.
- Verification:
  - REPL compile/load probes for `semidx.runtime.state-invariants`,
    `semidx.runtime.retrieval`, and the new focused tests: passed.
  - `clojure -M:test -n semidx.runtime.state-invariants-test -n
    semidx.integration.runtime-test`: passed, 116 tests / 530 assertions.
  - `./scripts/run-mvp-gates.sh`: passed; contracts 68/68, 284 tests / 1907
    assertions, benchmarks 21/21, four query smokes, `mvp_gates=ok`.
  - `./scripts/run-semantic-quality-report.sh`: exited 0 with the existing
    non-gating advisory baseline (`gate_eligible=false`, 5/6 expected-change
    matches, identity and move/rename rates 1.0).
- Commit: the enclosing Stage 2 commit.
- Known blockers: none.

## Stage 3 - Contract And MCP Surface

- Status: completed.
- Summary:
  - Added the `state-invariants` and `state-invariant-unit-ref` Malli mirrors to
    `src/semidx/contracts/schemas.clj` and registered
    `:example/state-invariants` in the `contracts` map. `packet_version` uses an
    independent `^[0-9]+\.[0-9]+$` pattern (not the strict contract
    `schema_version`) so the packet can evolve additively.
  - Added the standalone `contracts/schemas/state-invariants.schema.json` JSON
    Schema (with a local `unitRef`/`unitRefArray` `$defs` block, all lists capped
    at 12) and a validated `contracts/examples/state-invariants/impact-analysis-packet.json`
    example, wired into `contracts/examples/catalog.json` and the
    path-based mapping in `src/semidx/contracts/validator.clj`.
  - Bounded `:triggered_by` in `state-invariants/assemble` to the take-12
    discipline so the `codeArray` (maxItems 12) contract stays truthful; this was
    the only runtime-facing change and it does not affect gating or existing
    assertions.
  - Confirmed MCP passthrough: `tool-impact-analysis` already returns the whole
    hint map, so the additive `:state_invariants` section rides inside
    `:impact_hints` unchanged, and the usage-metric payload counters are
    untouched (still additive).
- Changed files:
  - `src/semidx/contracts/schemas.clj`
  - `src/semidx/contracts/validator.clj`
  - `src/semidx/runtime/state_invariants.clj`
  - `contracts/schemas/state-invariants.schema.json`
  - `contracts/examples/state-invariants/impact-analysis-packet.json`
  - `contracts/examples/catalog.json`
  - `test/semidx/integration/runtime_test.clj`
  - `plans/016_state_invariant_context_plan.md`
  - `reports/018_state_invariant_context_progress_log.md`
- Review findings: none outstanding; the one runtime change (`triggered_by`
  cap) aligns the assembler with the plan's stated take-12 rule and the new
  contract.
- Verification:
  - REPL: example JSON validates against the Malli mirror; the real packet
    assembled from the Java state fixture conforms to `state-invariants`; a live
    MCP smoke through `tool-impact-analysis` returns `:state_invariants` inside
    `:impact_hints` for a stateful query and omits it for a non-stateful query.
  - `./scripts/validate-contracts.sh`: `checked_json_files=70`,
    `contracts_validation=ok`.
  - `clojure -M:test -n semidx.runtime.state-invariants-test -n
    semidx.integration.runtime-test`: passed, 116 tests / 531 assertions.
  - `./scripts/run-mvp-gates.sh`: `mvp_gates=ok`; contracts 70/70, benchmarks
    21/21, four query smokes.
- Known blockers: none.

## Stage 4 - Cross-Surface Parity And Staged Retrieval

- Status: completed.
- Summary:
  - Added the conditional `state_invariants` sibling to `expand_context` and
    the detail-stage context packet. Both paths reuse the Stage 2 assembler and
    the existing impact evidence; transports remain policy-free passthroughs.
  - Accounted for the packet in expansion/detail token budgets. When an
    assembled packet does not fit, the result records
    `state_invariants_omitted` instead of silently exceeding the reserved
    stage budget.
  - Extended the Malli expansion-result/context-packet mirrors and the JSON
    context-packet schema with the optional packet reference.
  - Added library, HTTP, and gRPC parity coverage over the Java lifecycle
    fixture, including entity evidence, the guardrail, non-state intent
    silence, contract conformance, and budget bounds.
- Changed files:
  - `src/semidx/runtime/retrieval.clj`
  - `src/semidx/contracts/schemas.clj`
  - `contracts/schemas/context-packet.schema.json`
  - `test/semidx/integration/runtime_test.clj`
  - `test/semidx/runtime/http_test.clj`
  - `test/semidx/runtime/grpc_test.clj`
  - `plans/016_state_invariant_context_plan.md`
  - `reports/018_state_invariant_context_progress_log.md`
  - `MEMORY.md`
- Verification:
  - REPL compile/load probes for the retrieval/contracts namespaces and the
    focused library + HTTP tests: passed.
  - `clojure -M:test -n semidx.integration.runtime-test -n
    semidx.runtime.http-test -n semidx.runtime.grpc-test`: passed, 143 tests /
    825 assertions (the final added expansion-contract assertion also passed in
    the full gate).
  - `./scripts/validate-contracts.sh`: passed, 70 JSON files checked.
  - `./scripts/run-mvp-gates.sh`: passed; contracts 70/70, 287 tests / 1927
    assertions, benchmarks 21/21, four query smokes, `mvp_gates=ok`.
- Commit: the enclosing Stage 4 commit.
- Known blockers: none.
