---
title: "semidx Direction Vector Note"
doc_type: "direction_note"
lifecycle: "concept"
status: "draft"
agent_action: "use_as_input_for_future_plan_only"
updated: "2026-08-27"
---

# semidx Direction Vector Note

This note preserves the direction summary produced during the 2026-08-27
conversation, rewritten in English to satisfy the repository documentation
language rule.

## Summary

The main direction is that semidx is not evolving as a better grep and not as a
general RAG system. It is becoming a deterministic context layer for AI coding
agents: one typed repository graph that can provide the relevant context with
provenance, confidence, budget accounting, diagnostics, and guardrails while
also proving its own usefulness through measurement.

The current axis is recorded in `SPEC.md`: one deterministic typed graph, first
over code, then over documentation, then over code-documentation links. LLMs are
not the source of truth. They may label, summarize, and explain, but facts must
come from deterministic sources such as the indexer, SCIP, LSP, tree-sitter,
regex fallbacks, frontmatter, git, tests, and usage feedback.

## 1. Prove Value Before Expanding Scope

The most important active track is `plans/020`. It asks whether semidx actually
saves money, tokens, or time while preserving task quality, or whether it is
only a well-structured index.

The plan defines a real benchmark with four arms:

- A: semidx.
- B: competent `rg` plus targeted file reads.
- C: `rg` plus LSP/SCIP where available.
- D: the agent's native no-index workflow.

The key criterion from `SPEC.md` is roughly a two-times cost reduction at parity
task success on real repositories. This is the current gate. If it fails, the
product line narrows to the strongest lane rather than claiming a general
index.

## 2. Raise Fact Authority

`plans/018` moves Java and TypeScript from regex/tree-sitter-first extraction to
a provider authority ladder:

- exact: fresh SCIP, LSP, or compiler-like evidence;
- structural: tree-sitter;
- heuristic: regex;
- fallback: generic structure.

The key idea is not merely adding LSP. It is adding `CanonicalFactKey`,
evidence, freshness, and arbitration. semidx should be able to report whether a
fact is exact, structural, heuristic, stale, degraded, or in conflict with
another provider.

This makes semidx more honest. It does not need to be compiler-grade in every
case, but it must be explicit about what it knows and how strongly it knows it.

## 3. Add LLM-Facing Delivery

`plans/019` adds `get_context`: a one-round-trip facade for agents, built on top
of the existing staged retrieval flow.

This does not replace `resolve_context -> expand_context ->
fetch_context_detail`. It composes those operations while preserving
`selection_id`, `snapshot_id`, budget ledgers, diagnostics, guardrails, and
typed failures.

Markdown output is also only a projection from the canonical ContextPacket, not
a separate prose contract. The intended shape combines strict staged semantics
with a more convenient delivery surface for LLM agents.

## 4. Become An Agent Workflow Substrate

`ideas/011` points beyond retrieval toward semidx as an agent workflow
substrate:

- architecture graphs;
- impact maps;
- PR-oriented change maps;
- graph lint;
- dead-code candidates;
- docs and ADR-linked context;
- computed handoff summaries instead of fragile manual `progress.txt` files.

The product direction is that an agent should not only find files. It should
understand the blast radius, applicable decisions, stale documentation, relevant
tests, risky neighbors, and the current state of work.

## 5. Make Documentation First-Class

`ideas/013` and `SPEC.md` Phase 2 say that Markdown should not be indexed as
plain text chunks. It needs a document provider:

- heading sections as units;
- YAML frontmatter as authority and lifecycle metadata;
- active or accepted documents ranked above completed, archived, or superseded
  documents;
- links between docs, ADRs, plans, reports, and code symbols.

This is likely the next distinctive layer after Phase 1 value evidence. The
question becomes not just "find this ADR text" but "which current decision
governs this code, and which documents are stale?"

## 6. Treat Performance And Zig As Experiments

`ideas/014` is intentionally conservative. A full Zig rewrite would likely
improve cold startup, memory footprint, and packaging, but it would not
automatically improve fresh indexing or warm retrieval enough to justify the
risk.

The right order is:

1. Measure the current runtime.
2. Remove obvious bottlenecks such as per-file `clj-kondo`, sequential parsing,
   and disabled parser caching.
3. Try bounded parallel parsing and better incremental reuse.
4. Prototype a Zig launcher, worker, or narrow vertical slice.
5. Accept a full rewrite only if the measured remaining gap maps to an explicit
   product requirement.

## Compressed Conclusion

semidx is aiming to become the operating context layer for AI development. It is
not an IDE, compiler, RAG system, or visualization toy. Its value is that an
agent receives compact, fresh, checked, provenance-aware context and can see
what is safe to change, what is risky, which docs and ADRs apply, and how much
trust to place in each fact.

The practical order is:

1. `plans/020`: prove value on real tasks.
2. `plans/018`: raise fact authority through SCIP, LSP, and provider evidence.
3. `plans/019`: add one-shot `get_context` without breaking staged retrieval.
4. Phase 2: add the Markdown/document provider.
5. Phase 3: add code-documentation graph lint, hierarchy, and contradiction
   detection.
6. Performance and Zig experiments only after benchmark evidence.

The separate Semantic Core, S-Quant, and semantic-ledger line is conceptually
interesting, but it should remain a separate research track until a small
hash-plus-embedding validation slice proves better than the structural graph
retrieval semidx already provides.
