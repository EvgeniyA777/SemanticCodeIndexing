---
title: "semidx Feature Inventory For Prioritization"
doc_type: "feature_inventory"
lifecycle: "concept"
status: "draft"
agent_action: "use_as_input_for_future_plan_only"
updated: "2026-08-27"
---

# semidx Feature Inventory For Prioritization

## Purpose

This document captures the current feature direction before a new execution plan
is written. It is intentionally detailed enough to prioritize and later split
into plans, but it is not itself an implementation queue.

The shared product line is:

> semidx is a deterministic context layer for AI coding agents: one typed graph
> over repository code, then documentation, served through bounded retrieval
> surfaces with provenance, confidence, budgets, diagnostics, and guardrails.

## Prioritization Rules

Use these rules when deciding what becomes the next plan:

1. Finish evidence before expanding scope. The code-graph value benchmark is the
   gate for the current phase.
2. Preserve the canonical staged flow unless a new ADR changes it.
3. Keep facts deterministic. LLMs may label or summarize, but must not create
   authoritative graph edges.
4. Prefer additive surfaces over replacing public contracts.
5. Provider-native facts must normalize into the existing semantic graph rather
   than forming a parallel graph.
6. Do not introduce document indexing as generic text chunking. Documentation
   needs a provider with lifecycle-aware authority.

## Feature Areas

### 1. Retrieval Value Benchmark Harness

Current owner: `plans/020_retrieval_value_benchmark_harness_plan.md`.

Goal: prove whether semidx improves real agent work on real repositories.

Core capabilities:

- Run the same task suite through A/B/C/D strategy arms:
  - A: semidx retrieval.
  - B: competent `rg` plus targeted bounded file reads.
  - C: `rg` plus LSP/SCIP navigation where available.
  - D: the agent's native no-index repository browsing policy.
- Record immutable `BenchmarkRun` and `TaskAttempt` identities.
- Normalize provider usage into comparable cost fields instead of comparing raw
  token names across providers.
- Aggregate success per normalized cost and wall-clock.
- Require at least one external repository to avoid self-repo bias.
- Record pass/fail back into `SPEC.md` without moving the threshold after
  scoring starts.

Implementation notes:

- Stage 0 still needs calibration and final threshold lock.
- Stage 2 owns the task suite and isolated four-arm harness.
- Stage 3 owns attempt-first aggregation.
- Stage 4 owns the first evidence run and write-back.

Decision point:

- If the success signal passes, continue broad code-graph productization.
- If it fails, narrow semidx to the strongest lane rather than presenting it as
  a general-purpose index.

### 2. Semantic Provider Authority Migration

Current owner: `plans/018_semantic_provider_authority_migration_plan.md`.

Goal: move Java and TypeScript from regex-first extraction to a provider
authority ladder.

Core capabilities:

- Add provider descriptors, runtime status, provider plans, fact batches, and
  evidence records.
- Normalize all providers to `CanonicalFactKey` before arbitration.
- Treat SCIP and LSP as exact evidence only when source identity is fresh.
- Use tree-sitter as structural evidence.
- Keep regex as bounded heuristic fallback.
- Merge agreeing same-key facts while retaining all evidence.
- Mark equal-authority conflicts as ambiguous instead of guessing.
- Project capability and degradation summaries consistently through library,
  MCP, HTTP, and gRPC.

Implementation notes:

- Provider ids and native symbols are evidence, not stable merge keys.
- Retrieval must depend on normalized facts and runtime-owned capability
  summaries, never provider implementations.
- TypeScript remains the first likely vertical slice unless toolchain evidence
  justifies starting with Java.

Decision point:

- Switch public authority only after deterministic fixtures, shadow comparison,
  and benchmark evidence support the change.

### 3. One-Shot Context Delivery

Current owner: `plans/019_llm_one_shot_context_delivery_and_evaluation_plan.md`.

Goal: give LLM agents a one-round-trip `get_context` facade without weakening
staged retrieval.

Core capabilities:

- Compose `resolve_context`, `expand_context`, and `fetch_context_detail` inside
  a deterministic orchestrator.
- Require an existing `index_id` in the first slice.
- Return the canonical `ContextPacket` plus continuation identifiers.
- Support structured, Markdown, and combined presentation modes.
- Enforce one top-level response-budget ledger.
- Track aggregate and stage usage separately so cost accounting is not double
  counted.
- Add MCP first; HTTP/gRPC parity only after value is demonstrated.

Implementation notes:

- Markdown is a projection of the structured packet, not a second source of
  truth.
- The orchestrator must not branch on provider ids.
- One-shot may become recommended for selected clients only after comparative
  evidence; staged retrieval remains canonical until an ADR changes it.

Decision point:

- Keep one-shot additive unless the benchmark scorecard justifies changing the
  documented default.

### 4. Agent Workflow Context Surfaces

Source ideas: `ideas/011_agent_graph_intelligence_layer.md`,
`ideas/008_agent_development_improvements.md`, and
`ideas/009_progress_txt_vs_semidx.md`.

Goal: turn semidx from code search into an agent workflow substrate.

Candidate capabilities:

- `architecture_graph`: deterministic module and symbol views from indexed
  facts, exportable as JSON, DOT, Mermaid, or static HTML.
- `change_map` or `review_context`: combine `snapshot_diff` and
  `impact_analysis` for PR review and pre-change planning.
- `architecture_lint`: detect cycles, forbidden dependency crossings, orphan
  modules, central hubs, generated-code leakage, and test-to-production
  inversions.
- `dead_code_candidates`: review-only candidate detection from configured roots
  and reachable graph edges.
- `handoff_summary`: computed state from snapshot diffs, git history, dirty
  files, failed checks, active blockers, and unresolved notes.

Implementation notes:

- Full graph dumps should not be the default. Start with staged, focused views.
- Results must distinguish facts from inferences.
- Deletions and lints must be review candidates, not automatic edits.
- Handoff summaries must explicitly name gaps semidx cannot infer, such as
  rejected designs or uncommitted intent.

Decision point:

- Prioritize the workflow surface that removes the most repeated agent friction:
  pre-change impact, PR review, or continuation handoff.

### 5. Documentation Graph

Source: `ideas/013_markdown_document_intelligence.md` and `SPEC.md` Phase 2.

Goal: model repository documentation as first-class graph nodes with explicit
authority and freshness.

Core capabilities:

- Add a Markdown document provider through the provider catalog.
- Split Markdown by heading sections while preserving heading paths and line
  spans.
- Parse YAML frontmatter fields such as `lifecycle`, `status`,
  `agent_action`, and `updated`.
- Rank current documents above completed, archived, or superseded documents by
  default.
- Expose document sections as retrieval units.
- Detect broken links, stale status, and lifecycle conflicts.
- Prepare edges from docs to specs, ADRs, plans, reports, and later code units.

Implementation notes:

- Do not add Markdown as a hardcoded language-adapter exception.
- Do not indiscriminately index all prose as equal text chunks.
- Documentation authority starts from repo-owned metadata, not LLM judgment.

Decision point:

- Start Phase 2 only after the Phase 1 code-graph value gate has useful
  evidence, unless document-graph work is explicitly scoped as internal
  maintenance.

### 6. Code And Documentation Linkage

Source: `SPEC.md` Phase 3 and `ideas/011_agent_graph_intelligence_layer.md`.

Goal: connect documentation claims to code facts so semidx can detect drift and
reduce documentation volume.

Candidate capabilities:

- Link path and symbol mentions in ADRs, plans, specs, and reports to graph
  units.
- Detect current docs that reference removed or moved code.
- Detect overlapping or contradictory active documents.
- Surface governing docs in `resolve_context`, `impact_analysis`, and
  one-shot packets.
- Produce doc-health reports for stale decisions, redundant plans, and
  unresolved contradictions.

Implementation notes:

- LLM-generated labels can help humans read clusters, but cannot create
  authoritative doc-code edges.
- Edges should begin with deterministic anchors: paths, symbols, frontmatter
  links, supersession links, and explicit references.

Decision point:

- Ship as a product surface only if graph-lint over doc-code edges finds
  actionable issues with an acceptable false-positive rate.

### 7. State-Invariant Context

Current status: delivered for the planned Java field-fact tranche through
`plans/016` and `plans/017`; remaining work is future deepening.

Goal: make lifecycle, credential, timestamp, status, and persistence invariants
first-class retrieval context.

Possible next capabilities:

- Extend non-Java lanes with entity/model and state-writer facts.
- Add richer schema, migration, and column facts.
- Improve fixture-helper and assertion-test selection.
- Make state-invariant guardrails more precise when packets are incomplete.
- Link state packets to documentation once the documentation graph exists.

Implementation notes:

- Keep packets bounded and additive.
- Avoid pretending to have full dataflow when only field-level evidence exists.
- Preserve the whole-file-read guardrail for low-confidence stateful changes.

Decision point:

- Reopen this area only when a real task shows repeated missed invariants after
  the delivered Java packet behavior.

### 8. Performance And Native Runtime Experiments

Source: `ideas/014_zig_rewrite_performance_assessment.md`.

Goal: improve startup, memory, indexing latency, and packaging without accepting
a full rewrite on intuition alone.

Candidate capabilities:

- Add controlled startup, indexing, retrieval, memory, and snapshot-loading
  benchmarks.
- Batch `clj-kondo` analysis instead of launching it once per Clojure file.
- Evaluate safe `clj-kondo` cache reuse.
- Add bounded parallel parsing for independent files.
- Improve incremental indexing and snapshot reuse.
- Split lightweight server surfaces from optional heavy dependencies.
- Prototype a native Zig launcher, indexing worker, or retrieval vertical slice.

Implementation notes:

- Fresh indexing, cold process startup, and warm retrieval are separate
  performance domains.
- A native rewrite must pass semantic parity fixtures, not only return valid
  JSON faster.
- A full Zig rewrite is the last option after optimized Clojure and hybrid
  experiments have been measured.

Decision point:

- Continue to a full rewrite only if the remaining measured gap maps to an
  explicit product requirement such as sub-100-ms startup, strict memory limits,
  or high-throughput large-repo service mode.

### 9. Semantic Core Research Track

Source: `ideas/003_semantic_code_manifesto.md`,
`ideas/004_semantic_core_architecture.md`, and
`ideas/005_semantic_core_architecture_review.md`.

Goal: explore a separate semantic-ledger system based on logical identity,
contracts, embeddings, and semantic diff.

Candidate capabilities:

- Validate a versioned logical hash over normalized Clojure forms.
- Build a golden pair suite for same-meaning and different-meaning changes.
- Add embeddings only after hash behavior is defined.
- Compare meaning-based search against semidx structural retrieval.
- Decompose the original S-Quant idea into identity, source, contract,
  embedding, authority, and storage records before implementation.

Implementation notes:

- This is not the next semidx implementation track.
- The original S-Quant schema is too broad to implement directly.
- Authority arbitration and contract engines are premature before real
  bidirectional flows exist.

Decision point:

- Continue only if a small hash-plus-embedding validation slice beats existing
  semidx structural retrieval on a concrete Clojure corpus.

## Suggested Prioritization Questions

Before creating the next plan, answer these:

1. Is the next goal to prove value, improve fact quality, improve agent
   ergonomics, or expand the graph to documentation?
2. Which user pain is highest: missed context, too many round trips, stale
   docs, weak provider authority, slow startup, or difficult handoff?
3. Which feature has a falsifiable success condition within one or two stages?
4. Which feature can ship additively without changing current public defaults?
5. Which feature is blocked by `plans/020` evidence?

## Non-Plan Status

This file should become input to a new numbered plan only after the owner
prioritizes feature areas and chooses a first execution target. Until then it is
a feature inventory, not an active work queue.
