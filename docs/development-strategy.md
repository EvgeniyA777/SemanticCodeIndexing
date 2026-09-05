---
title: "Project Development Strategy"
doc_type: "strategy"
lifecycle: "active"
status: "accepted"
agent_action: "reference_for_context"
updated: "2026-08-27"
---

# Project Development Strategy

## Purpose

This document defines the recommended development order for semidx. It sits
between the direction spec and execution plans:

- `SPEC.md` defines the long-term axis and phase gates.
- `FEATURES.md` tracks feature status and next gates.
- Numbered files in `plans/` define implementation sequences.
- This file explains which direction should move first and why.

The strategy is value-first: prove the current phase, improve fact quality,
improve agent delivery, then expand into workflow and documentation graph
surfaces.

## Strategic Principle

Do not optimize for the most interesting feature. Optimize for the feature that
earns the right to build the next layer.

The dependency chain is:

```text
Prove retrieval value
  -> raise fact authority
  -> improve agent delivery
  -> remove avoidable runtime startup friction
  -> add workflow surfaces
  -> add documentation graph
  -> link code and documentation
```

Persistent JVM reuse is a product-integration track, not a rewrite track:
server modes are already long-lived once started, but short-lived launcher or
CLI paths must not pay cold JVM startup for every request. Broader performance
and native-runtime work remains evidence-driven. Semantic Core / S-Quant
remains a separate research track until validated.

## Priority Model

| Priority | Direction | Value | Stage |
| --- | --- | --- | --- |
| P0 | Retrieval value benchmark harness | Proves whether semidx is worth using against a competent baseline, including explicit negative-utility cases. | Now |
| P1 | Semantic provider authority | Raises trust in graph facts through exact, structural, heuristic, and fallback evidence. | Next |
| P1 | One-shot context delivery | Reduces agent round trips without weakening staged retrieval. | Next |
| P1 | Persistent JVM runtime reuse | Removes avoidable cold-start tax when clients use short-lived invocations or restart the runtime per request/session. | Integration quick win |
| P2 | Agent workflow context surfaces | Turns retrieval into planning, review, and handoff support. | After core proof |
| P2 | Documentation graph | Makes repository docs, ADRs, plans, and reports first-class context. | After Phase 1 evidence |
| P3 | Code-documentation linkage | Detects drift and contradictions between code and governing documents. | After docs graph |
| P3 | State-invariant deepening | Expands already delivered state-invariant support only from repeated real misses. | Opportunistic |
| P4 | Performance and native-runtime experiments | Improves startup, memory, packaging, or throughput only against measured targets. | Evidence-driven |
| R | Semantic Core / S-Quant | Explores logical hashes, embeddings, semantic diff, and ledger concepts separately. | Research only |

## Stage 0: Prove The Code-Graph Value Claim

Primary direction: `plans/020_retrieval_value_benchmark_harness_plan.md`.

Why first:

- It is the exit gate for `SPEC.md` Phase 1.
- It tests whether semidx beats a competent `rg` plus targeted-read baseline on
  real work.
- It protects the project from building more surfaces around an unproven value
  claim.

Deliver next:

- Finish Stage 0 calibration and final threshold lock.
- Build the four-arm task harness.
- Add the negative-utility calibration slice before the verdict run: Zig
  signature/API extraction, Zig struct/config fields, Zig blast-radius seed
  correctness, and stale-snapshot-after-edit behavior.
- Add attempt-first aggregation over normalized provider cost.
- Run the first real-repository evidence suite.
- Write the verdict back into `SPEC.md`.

Exit decision:

- If the success signal passes, continue broad code-graph productization.
- If it fails, narrow the product line to the strongest lane and stop presenting
  semidx as a general-purpose index.

## Stage 1: Raise Fact Authority

Primary direction: `plans/018_semantic_provider_authority_migration_plan.md`.

Why before delivery polish:

- Better packaging cannot compensate for weak facts.
- `get_context` is more valuable when it can expose exact, structural,
  heuristic, fallback, stale, and degraded evidence honestly.
- Provider authority is the path from "useful structural retrieval" toward
  trustworthy graph context.

Deliver next:

- Approve and implement provider-neutral `CanonicalFactKey`.
- Add FactEvidence and FactBatch normalization.
- Implement deterministic arbitration.
- Add provider planning and shadow execution.
- Land the first TypeScript or Java SCIP slice based on toolchain evidence.
- Add LSP overlay only after source identity and freshness behavior are proven.
- Keep Zig value-recovery as evidence-triggered follow-up work: do not raise the
  Zig lane beyond structural/skeleton usefulness until the negative-utility
  cases isolate missing facts from ranking and freshness defects.

Exit decision:

- Switch public authority only after fixture parity, shadow comparison, and
  benchmark evidence support the change.

## Stage 2: Improve Agent Delivery

Primary direction: `plans/019_llm_one_shot_context_delivery_and_evaluation_plan.md`.

Why after provider authority starts:

- One-shot delivery reduces round trips, but it should preserve the same
  snapshot-bound staged semantics.
- The first useful version can ship additively over the current graph, but the
  stronger product story comes when the packet also carries truthful provider
  evidence and degradation.

Deliver next:

- Add library `get_context` orchestration over existing staged operations.
- Enforce one top-level response-budget ledger.
- Preserve `selection_id`, `snapshot_id`, diagnostics, and guardrails.
- Add the MCP structured slice.
- Add bounded Markdown projection only as a packet renderer.
- Feed one-shot strategy adapters into the `plans/020` scorecard.

Exit decision:

- Keep one-shot additive unless comparative evidence justifies a new ADR that
  changes the documented default.

## Stage 2.5: Remove Runtime Startup Friction

Primary source:
[`plans/021_persistent_jvm_runtime_reuse_plan.md`](../plans/021_persistent_jvm_runtime_reuse_plan.md).

Why here:

- The core server paths are already long-lived, so this is not a semantic
  rewrite.
- The one-shot CLI remains intentionally short-lived and exits after a request.
- If an MCP host or wrapper launches semidx per query or per short command, the
  user sees repeated JVM startup cost even though the runtime can serve many
  requests in-process.

Current evidence:

- MCP stdio creates one session state and processes JSON-RPC messages in a
  loop until stdin closes.
- MCP HTTP, runtime HTTP, and runtime gRPC start server processes and block.
- The runtime CLI calls the indexer and then exits with `System/exit`.
- Runtime HTTP launcher reuse exists behind the `:launcher` alias. It provides
  `status`, `start`, `stop`, and `request`, uses a cache-directory state slot
  and start lock, adopts healthy local runtimes, and forwards requests to the
  existing runtime HTTP endpoints.
- The `mcp-http` profile is managed by the same commands: the launcher owns the
  endpoint's process lifetime while the MCP client owns the protocol, health is
  matched by reported service so profiles cannot adopt each other, and `request`
  is refused for it. MCP stdio stays host-lifetime scoped by construction.
- Hardening landed in Stage 4: every command reports timings, stale-state
  recovery is tested against a real killed process and a real occupied port, and
  `./scripts/run-launcher-benchmark.sh` measures the reuse win (cold CLI 11.6s
  versus 1.7s warm through the CLI client and 47ms over direct HTTP, all three
  paths doing the same resolve + fetch-detail work).

Deliver next:

- Nothing planned. Optional follow-ups are a gRPC profile, supervision/restart
  policy, and readiness that waits for the first index build instead of the HTTP
  port.

Exit decision:

- Keep the launcher path only if it measurably removes cold-start latency
  without adding ambiguous runtime ownership or stale-server failure modes.

## Stage 3: Add Agent Workflow Surfaces

Primary source: `ideas/011_agent_graph_intelligence_layer.md`.

Why here:

- Workflow surfaces are where semidx becomes more than retrieval.
- They should consume the proven graph, not create a parallel planning system.
- The first surface should be narrow and high-friction-reducing.

Recommended first feature:

- `change_map` or `review_context`.

Reason:

- It can compose existing `snapshot_diff`, `impact_analysis`, related tests,
  risky neighbors, and relation support.
- It directly helps PR review, pre-change planning, and agent handoff.
- It has a clearer correctness boundary than a broad architecture visualizer.

Later candidates:

- `architecture_graph`.
- `architecture_lint`.
- `dead_code_candidates`.
- `handoff_summary`.

Exit decision:

- Promote only surfaces that produce actionable planning or review signal with
  bounded false positives.

## Stage 4: Add The Documentation Graph

Primary sources: `SPEC.md` Phase 2 and
`ideas/013_markdown_document_intelligence.md`.

Why after code-graph proof:

- Documentation graph value depends on the code graph being trustworthy.
- Markdown indexing must be lifecycle-aware, not generic text chunking.
- The provider catalog and authority model should exist before adding document
  providers.

Deliver next:

- Add a Markdown provider through the provider catalog.
- Split documents into heading-section units with line spans.
- Parse frontmatter fields such as `lifecycle`, `status`, `agent_action`, and
  `updated`.
- Rank active and accepted documents above completed, archived, or superseded
  material.
- Emit document facts and diagnostics for broken links and lifecycle conflicts.

Exit decision:

- Continue only if the docs graph is fresh, deterministic, and trustworthy
  enough to guide agents away from stale planning material.

## Stage 5: Link Code And Documentation

Primary sources: `SPEC.md` Phase 3 and
`ideas/011_agent_graph_intelligence_layer.md`.

Why after the documentation graph:

- Code-doc linkage needs both sides to have stable identities.
- This is the strongest long-term differentiator: semidx can answer which
  current decisions govern a code change and which docs are stale or
  contradictory.

Deliver next:

- Link deterministic path and symbol mentions from docs to code units.
- Respect frontmatter, supersession links, explicit path links, and current
  document lifecycle.
- Surface governing docs in retrieval, impact, and one-shot packets.
- Add doc-health reports for stale references, redundant active documents, and
  contradictions.

Exit decision:

- Ship as a product surface only if graph-lint over doc-code edges finds
  actionable issues that manual review would plausibly miss.

## Opportunistic Track: State-Invariant Deepening

Primary sources: `plans/016`, `plans/017`, and ADR-045.

Current posture:

- The Java field-fact tranche is already implemented.
- This area should not displace the main Phase 1 proof or provider authority
  work.

Reopen when:

- Real tasks repeatedly miss state, lifecycle, credential, timestamp, schema, or
  fixture invariants even after current Java packets are available.

Likely next slices:

- Extend state-invariant facts beyond Java.
- Add schema, migration, enum, and column facts.
- Improve assertion-test and fixture-helper selection.
- Link state packets to governing docs after the documentation graph exists.

## Opportunistic Track: Performance And Native Runtime

Primary source: `ideas/014_zig_rewrite_performance_assessment.md`.

Current posture:

- Do not start with a full Zig rewrite.
- Treat performance work as product-target-driven, not language-preference
  driven.

Recommended order:

1. Build controlled startup, indexing, retrieval, memory, and snapshot-loading
   benchmarks.
2. Batch `clj-kondo` analysis instead of launching it once per Clojure file.
3. Evaluate safe parser cache reuse.
4. Add bounded parallel parsing.
5. Improve incremental indexing and snapshot reuse.
6. Try a Zig launcher, indexing worker, or retrieval vertical slice.
7. Consider a full rewrite only after semantic parity and product-level speed or
   packaging targets justify it.

## Research Track: Semantic Core / S-Quant

Primary sources: `ideas/003`, `ideas/004`, and `ideas/005`.

Current posture:

- Keep this separate from the main semidx implementation path.
- The original S-Quant object is too broad to implement directly.
- Logical hash behavior must be validated before storage, embeddings, semantic
  diff, or authority arbitration.

Recommended validation:

1. Build a versioned logical hash over normalized Clojure forms.
2. Create a golden suite of same-meaning and different-meaning pairs.
3. Add embeddings only after the hash boundary is coherent.
4. Compare against existing semidx structural retrieval.

Exit decision:

- Continue only if the small hash-plus-embedding slice produces meaningfully
  better search or diff behavior than semidx already provides structurally.

## Operating Rules

- Do not start Phase 2 or Phase 3 before Phase 1 value evidence exists, unless
  the work is explicitly scoped as internal maintenance.
- Do not change the canonical staged default without an ADR and comparative
  evidence.
- Do not let provider-native output leak into public retrieval contracts.
- Do not use LLMs as authoritative graph-edge producers.
- Do not build broad graph visualizations before focused workflow surfaces have
  proven value.
- Do not treat `FEATURES.md` as an implementation queue. Convert prioritized
  work into a numbered plan first.
