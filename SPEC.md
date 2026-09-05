---
title: "semidx Direction Spec"
doc_type: "spec"
lifecycle: "active"
agent_action: "reference_for_context"
updated: "2026-08-27"
---

# semidx — Direction Spec

## What this document is

The single source of truth for the **current direction** of `semidx`: the axis
(one graph over code and documentation), the phase we are in, the current
authority state, and the falsifiable claims that must hold for the bet to be
worth it.

- **Covers**: the axis, the phased roadmap, non-goals, source authority, and the
  value hypotheses with their kill-criteria.
- **Does not cover**: feature status (see [`docs/roadmap-status.md`](docs/roadmap-status.md)),
  human onboarding (see [`README.md`](README.md)), decision rationale (see
  [`adr/`](adr/)), or agent operating rules (see [`RULES.md`](RULES.md)).

This document is **not** a fourth governance layer. It exists because the
direction is otherwise only reconstructable from a chain of superseding ADRs,
which is error-prone — and because keeping every effort aligned to one line is
itself Phase 3 payoff #4. Boundary between documents:

| Document | Answers |
| --- | --- |
| `SPEC.md` (this file) | What is the axis, which phase are we in, and how will we know we are right? |
| `adr/*.md` | Why did we decide each thing, and when? (decision log) |
| `README.md` | What is this, for a new human reader? |
| `docs/roadmap-status.md` | What is built vs not? (checklist) |

If this file and an ADR disagree on *current direction*, this file wins and the
stale ADR line must be corrected. ADRs remain authoritative for the historical
*why*.

## Status tags

Every claim below is tagged:

- **[committed]** — backed by an accepted decision or implemented behavior.
- **[in-progress]** — decided, implementation not yet the default.
- **[hypothesis-under-test]** — proposed direction, **not yet ratified or
  proven**. Do not treat as fact.

## 1. Axis (the one bet)

semidx builds **one deterministic, typed graph that unifies a repository's code
and its documentation**, and serves it to AI agents as staged, token-budgeted
context. The bet: *a query should return the relevant code together with the
docs and decisions bound to it — and the same graph that answers queries should
also keep the documentation itself small, consistent, and hierarchical.*

The graph is **deterministic first**. Any LLM use is for labels, summaries, and
narration — never as the source of truth for code or documentation
relationships. **[committed]** as a principle (see
[`ideas/011_agent_graph_intelligence_layer.md`](ideas/011_agent_graph_intelligence_layer.md)).

The bet is about **context quality per unit of cost** and **documentation
discipline**, not about being a universal semantic analyzer, a compiler, or a
general-purpose knowledge base.

## 2. Phased roadmap

The axis is delivered in three phases. Each phase must be *quality-proven*
before the next begins — a low-quality code graph makes a docs graph and a
linked graph worthless.

### Phase 1 — Quality graph over code  ·  **[in-progress]** (current phase)

A trustworthy typed-relation graph of the codebase, served through staged
retrieval.

- Typed-relation graph substrate:
  [`adr/039-separate-relation-identity-from-resolution-and-evidence.md`](adr/039-separate-relation-identity-from-resolution-and-evidence.md). **[committed]**
- Relations as a public query surface:
  [`adr/040-expose-bounded-relation-traversal-as-a-public-query-surface.md`](adr/040-expose-bounded-relation-traversal-as-a-public-query-surface.md). **[committed]**
- Staged retrieval as the canonical public contract:
  [`adr/024-make-compact-first-staged-retrieval-the-canonical-public-flow.md`](adr/024-make-compact-first-staged-retrieval-the-canonical-public-flow.md). **[committed]**
- Fact quality via the authority ladder (§4) — SCIP/LSP over tree-sitter over
  regex:
  [`adr/046-prefer-semantic-evidence-providers-over-structural-and-lexical-fallbacks.md`](adr/046-prefer-semantic-evidence-providers-over-structural-and-lexical-fallbacks.md),
  [`plans/018_semantic_provider_authority_migration_plan.md`](plans/018_semantic_provider_authority_migration_plan.md). **[planned]**
- One-shot context delivery on top of staging:
  [`plans/019_llm_one_shot_context_delivery_and_evaluation_plan.md`](plans/019_llm_one_shot_context_delivery_and_evaluation_plan.md). **[planned]**
- Persistent local runtime reuse for short-lived invocations:
  [`plans/021_persistent_jvm_runtime_reuse_plan.md`](plans/021_persistent_jvm_runtime_reuse_plan.md). **[in-progress]**
- Comparative real-repository value evidence:
  [`plans/020_retrieval_value_benchmark_harness_plan.md`](plans/020_retrieval_value_benchmark_harness_plan.md). **[in-progress]**

**Exit gate**: the code-graph value hypothesis (§5.1) passes on real repos.

### Phase 2 — Quality graph over documentation  ·  **[hypothesis-under-test]** (next)

Index the repository's own documentation (Markdown: README, `adr/`, `plans/`,
`reports/`, `docs/`, `ideas/`, `notes/`) as first-class graph nodes, with the
same determinism and provenance discipline as code.

- Documentation authority reuses the existing frontmatter lifecycle discipline
  (`lifecycle`, `status`, `agent_action`, supersession) so `active` beats
  `superseded` deterministically (see [`RULES.md`](RULES.md) Documentation
  Rules). **[hypothesis-under-test]**
- Motivation: documentation has proliferated (dozens of ADRs/plans/reports) and
  is expensive to keep consistent by hand.

**Exit gate**: the docs-graph is fresh, deterministic, and its authority model
(current vs superseded) is trustworthy.

### Phase 3 — Link code and documentation  ·  **[hypothesis-under-test]** (after)

Add edges between documentation nodes and the code they describe, enabling the
four payoffs:

1. **Reduce documentation volume** — detect redundant/overlapping docs.
2. **Eliminate contradictions** — surface docs that disagree with code or with
   each other (graph-lint over doc↔code edges).
3. **Build a documentation hierarchy** — derive structure from the graph instead
   of maintaining it by hand.
4. **Hold the general line** — flag work and docs that drift from the direction
   recorded here.

Concept and prior art: [`ideas/011_agent_graph_intelligence_layer.md`](ideas/011_agent_graph_intelligence_layer.md)
("docs/ADR-linked context", graph-lint checks).

**Exit gate**: the doc-discipline value hypothesis (§5.2) passes.

## 3. Non-Goals

Deliberately excluded possibilities (not negated goals):

- **Not a compiler.** No production-grade full interprocedural resolution across
  all languages. **[committed]** (README "What This Project Does Not Do (Yet)")
- **Not a universal 30+ language index** in the LSIF/SonarQube style. Language
  lanes are added by parser risk and ceiling, not for breadth. **[committed]**
  (see [`adr/028-prioritize-tree-sitter-adoption-by-language-risk-and-parser-ceiling.md`](adr/028-prioritize-tree-sitter-adoption-by-language-risk-and-parser-ceiling.md))
- **Not a SCIP-only / build-required index.** External semantic providers raise
  authority but are never *required*; the tool must still work on dirty and
  unbuildable trees. **[committed]** (decision driver in
  [`adr/046-...`](adr/046-prefer-semantic-evidence-providers-over-structural-and-lexical-fallbacks.md))
- **Not a general-purpose RAG / knowledge base over arbitrary prose.** Phase 2/3
  index the *repository's own* documentation (Markdown/ADRs), not external wikis,
  tickets, or the open web. **[committed]** (scope boundary)
- **Not an LLM-as-source-of-truth.** The graph is deterministic; LLMs only label
  and summarize. **[committed]** (principle per [`ideas/011`](ideas/011_agent_graph_intelligence_layer.md))
- **Not a REPL, formatter, or editor.** Retrieval only. **[committed]**
  ([`RULES.md`](RULES.md) Preferred Tool Boundaries)

## 4. Source authority (graph fact quality)

Per-operation, per-fact authority ladder. Higher tiers are preferred; lower
tiers fill gaps but must never overwrite or masquerade as a higher tier.
**[committed]** — accepted in [`adr/046-...`](adr/046-prefer-semantic-evidence-providers-over-structural-and-lexical-fallbacks.md).

1. `exact` — fresh SCIP / LSP / compiler evidence.
2. `structural` — tree-sitter (repo-managed toolchain,
   [`adr/047-...`](adr/047-retain-repo-managed-tree-sitter-toolchain-for-structural-providers.md)).
3. `heuristic` — regex / bounded lexical extraction (always confidence-limited;
   never presented as exact).
4. `fallback` — generic file-section coverage.

For documentation (Phase 2), authority derives from frontmatter lifecycle:
`active`/`accepted` outrank `superseded`/`archived`; supersession links define
replacement. **[hypothesis-under-test]**

Staged retrieval (compact selection → optional widening → detail fetch) is the
canonical delivery contract across code and, later, documentation nodes;
`selection_id` + `snapshot_id` are reused across stages. **[committed]**
([`RULES.md`](RULES.md) Contracts And Runtime Invariants)

## 5. Value hypotheses and kill-criteria

Each phase carries its own **Riskiest Assumption** — the belief that, if false,
means the phase is not worth its cost. Stated as falsifiable claims with success
and failure signals fixed in advance.

### 5.1 Phase 1 — code-graph value  ·  **[hypothesis-under-test]**

**Hypothesis (falsifiable).** *We believe that* an AI agent solving real tasks
on a real repository *will* reach correct results with fewer tokens and less
wall-clock using semidx retrieval *than* with `rg` + reading files (and `rg` +
LSP where available), *because* graph-backed, structure-ranked, staged, budgeted
context supplies the relevant code without whole-file dumping.

**North Star candidate.** *Agent task success per unit cost* (provider-priced
input/cache/output/reasoning/tool usage, normalized per agent) on a fixed
real-repo suite, relative to the preregistered competent `rg`+read baseline.
Inputs: retrieval precision/recall, packet compactness under budget,
`exact`-tier coverage vs `heuristic` fallback.

**Why not yet proven.** Current benchmarks build a synthetic repository and score
against self-authored expectations
([`src/semidx/runtime/benchmarks.clj`](src/semidx/runtime/benchmarks.clj)) —
behavior validation, not comparative product value. Any "N× token savings" figure
is an internal fixture result.

**Test.** Reproducible real-repo benchmark with four strategy arms: (A) semidx,
(B) competent `rg` + reading files, (C) `rg` + an LSP/SCIP navigation baseline
where available, and (D) the agent's versioned native no-index browsing policy.
Measured on task success, false negatives, wall-clock, tool calls, and cost
(cost-weighted tokens, normalized per agent — raw usage semantics differ by
provider). The four-arm measurement harness, per-agent usage normalization, and
success-per-cost aggregation are specified in
[`plans/020_retrieval_value_benchmark_harness_plan.md`](plans/020_retrieval_value_benchmark_harness_plan.md).

B is the preregistered primary comparator for the Phase 1 verdict. C and D are
reported controls; an unavailable C is explicit, and neither control may replace
B or redefine the pass/fail rule after scoring begins.

- **Success signal** (provisional, *moderate* posture — locked after the Stage 0
  pilot, never after scoring): arm A runs at **≥50% lower cost (≥2×;
  versioned provider/model price schedules)** than the competent `rg`+read
  baseline B, at task success **≥ B − 5 percentage points** (parity within the
  noise band), wall-clock **≤ 1.5× B**, over **≥30 tasks including ≥1 external
  repository**.
- **Failure signal**: A does not reach 2× lower cost against B at parity success,
  or loses more than 5 percentage points of task success vs B. C and D inform
  diagnosis but do not rescue the primary verdict.

**Pilot-then-lock.** The margins above are provisional. Stage 0 of
[`plans/020`](plans/020_retrieval_value_benchmark_harness_plan.md) runs a small
calibration pilot that measures only the competent-baseline cost and the
success-metric noise floor — not the verdict — then locks the final threshold
before the scoring run, preserving falsifiability.

**Kill-criterion.** On the failure signal, narrow scope to Clojure-first
retrieval (strongest lane) and stop presenting semidx as a general index.

### 5.2 Phase 3 — doc-discipline value  ·  **[hypothesis-under-test]**

**Hypothesis (falsifiable).** *We believe that* linking docs to code in the graph
*will* let maintainers cut documentation volume and catch code↔doc contradictions
that manual review misses, *because* redundancy and disagreement become explicit
graph queries rather than reading tasks.

**North Star candidate (future).** Doc-graph health: count of unresolved
code↔doc contradictions and redundant-doc clusters trending down while coverage
holds.

**Kill-criterion.** If graph-lint over doc↔code edges produces mostly
false positives, or finds nothing manual review would not, Phase 3 does not ship
as a product surface and stays an internal maintenance aid.

**Priority implication.** Until Phase 1's §5.1 evidence exists, the top priority
is that benchmark — not Phase 2/3, new transports, or governance surface.

## 6. Open questions

Hard questions surfaced deliberately (PR/FAQ style):

- What real-world task suite and repositories make the Phase 1 benchmark
  trustworthy rather than another synthetic fixture?
- What is the fair `rg`/LSP baseline harness, so a win is not an artifact of a
  weak baseline?
- Which MCP clients should use the still-open MCP HTTP launcher profile instead
  of stdio process-lifetime reuse?
- For Phase 2, what is a documentation *fact*? Node granularity: whole file,
  heading section, or claim-level?
- For Phase 3, how are doc↔code edges established without an LLM as source of
  truth — anchors, symbol mentions, path references, explicit frontmatter links?
- If Phase 1's kill-criterion fires, which lanes survive the Clojure-first
  narrowing?

## 7. Honesty contract (what to infer)

- **What semidx does today**: structure-ranked, staged, budgeted retrieval over a
  typed-relation code graph with explicit provenance/confidence per fact.
- **What it does not do yet**: index documentation, link docs to code, or
  guarantee compiler-grade correctness of every relation (it degrades explicitly
  to structural, then heuristic, then fallback). Runtime HTTP launcher reuse now
  exists for short-lived request paths, while MCP HTTP reuse guidance and
  launcher hardening remain in progress under `plans/021`.
- **What a caller may infer**: facts tagged `exact` reflect fresh
  compiler/LSP/SCIP evidence.
- **What a caller must not infer**: that `heuristic`/`fallback` facts, the
  internal token-savings figures, or any Phase 2/3 capability are validated
  product guarantees.

## 8. Maintenance

- Update this file when the axis, a phase, a non-goal, the authority line, or a
  value hypothesis changes — in the same commit as the change.
- When a `hypothesis-under-test` item is ratified or proven, retag it and link
  the ADR/plan/benchmark that backs it.
- Do not start a phase before the prior phase's exit gate passes.
- Keep this file short (a 1–3 page "mini design doc", not an implementation
  manual). Detail belongs in ADRs, reference docs, and `docs/roadmap-status.md`.
- Alternatives considered and their rationale live in [`adr/`](adr/); this file
  states the chosen line, not the full decision history.
- Revisit the North Star candidates and hypotheses at least every 6–12 months,
  or on any event that changes them.

## Structure basis

Section shape follows established practice, adapted to a small living spec:
Google design-doc *Context / Goals / Non-Goals / trade-offs* structure;
falsifiable-hypothesis and Riskiest-Assumption-Test framing; the North Star
Framework (metric + inputs); and Amazon working-backwards PR/FAQ open-questions
discipline.
