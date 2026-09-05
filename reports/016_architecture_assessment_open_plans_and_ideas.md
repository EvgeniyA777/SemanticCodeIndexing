---
title: "Architecture Assessment: Open Plans And Idea Tracks"
doc_type: "architecture_assessment"
lifecycle: "completed"
status: "snapshot_complete"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Architecture Assessment: Open Plans And Idea Tracks

Historical baseline snapshot of what was open in this repository, produced by a
full read of all plan and idea documents cross-checked against the actual code,
progress logs, and live MCP responses on 2026-07-19, then refreshed for the
relation-first architecture review on 2026-08-01. Use this document as the
record of that planning pass. It was closed after Stage 3 became code-complete;
use `MEMORY.md`, `plans/013`, and `reports/014` for current execution state.

Inputs read in full: `plans/005`-`plans/009`, `plans/013`, `plans/014`,
`ideas/002`-`ideas/005`, `ideas/008`-`ideas/012`, `reports/010`,
`docs/roadmap-status.md`, plus code-level verification (namespace existence,
fixtures, live `create_index` / `resolve_context` payloads).

## 1. Genuinely Open Work

### 1.1 `plans/013_open_gaps_closure_program.md` — the only running program

Seven-stage program; current state:

- **Stage 1 is delivered.** Shared helpers and all planned language lanes are
  extracted, and `adapters.clj` is the thin dispatch facade (closure commit
  `2ec7c7c`).
- **Stage 2 is delivered; Stage 3 is partially delivered.**
  2. Remove the tree-sitter external-CLI runtime dependency (implemented as the
     repo-managed optional-toolchain strategy in ADR-036).
  3. Interprocedural / dataflow-sensitive resolution v1 has its relation
     substrate plus Clojure/Python producers; bounded retrieval and impact
     projections remain the next semantic step.
  4. Semantic graph query surface (bounded multi-hop traversal over
     `storage.clj`, contract-first).
  5. gRPC generated stubs (replace runtime descriptor-built messages).
  6. Online policy control-plane API (bounded, offline governance stays
     authoritative).
  7. Runtime-edge rate limiting (opt-in, default off).

### 1.2 `plans/007_semidx_extension_architecture_resolution_plan.md` — partially realized architecture

- **Delivered:** Stages 0-1 (contract baselines, workspace state, freshness
  policy, index lifecycle coordinator) — implemented via `plans/008` as
  `workspace_state.clj`, `freshness.clj`, `index_lifecycle.clj`; lifecycle
  fields are live in `create_index` responses.
- **Partially delivered:** typed relations now exist under
  `runtime/relations.clj` as the canonical graph for new semantics (ADR-038).
  The provider catalog, deterministic provider selection, discovery separation,
  Markdown/YAML slices, incremental relation resolution, and provider expansion
  remain open.
- **Binding constraint already decided:** Markdown/YAML indexing must arrive
  through the provider catalog (Stage 2), never as hardcoded dispatch
  exceptions. Any "index docs now" shortcut violates this architecture plan.

## 2. Plans That Were Marked Active But Are Delivered

Verified against code and fixed in commit `74e3d4e` (frontmatter flipped to
`completed` / `historical_reference_only`):

| Plan | Delivery evidence |
|---|---|
| `plans/005` multi-clone repo identity | `repo_identity.clj`; repo-aware helpers in `storage.clj`; ADR-031/032; `repo_identity` block in live `create_index` responses |
| `plans/006` resolve_context hardening | `query_anchors.clj`; `hint_suspected_symbol_*` / `source_path_prior` policy codes observed in live responses; `fixtures/retrieval/shorthand-*.json` present |
| `plans/008` Stage 0+1 workspace freshness | `workspace_state.clj` / `freshness.clj` / `index_lifecycle.clj`; lifecycle fields in responses |
| `plans/009` HTML/CSS language lanes | `languages/html.clj` / `css.clj`; onboarding tests; languages active in the index (optional tree-sitter follow-up folds into 013 Stage 2) |
| `plans/014` capability self-description | `capabilities.clj` + MCP `capabilities` tool + `capabilities.schema.json`; `reports/010` closes with "Stage 4 and 5 are now fully green and verified" |

Residual note on `plans/006`: `fixtures/retrieval/shorthand-ambiguous-low-evidence.json`
named in the plan was not found (2 of 3 shorthand fixtures exist), and
`reports/011` records a remaining intent-only test-retrieval recall gap. The
plan is delivered; the gap is a candidate input for a future retrieval-quality
pass, not a reason to reopen the plan.

## 3. Idea Inventory — Two Tracks

### Track A: "Semantic Core" (separate system, not semidx work)

Chain: `ideas/002` (raw brainstorm) → `ideas/003` (manifesto: S-Quant, semantic
ledger, projections, arbitration) → `ideas/004` (architecture plan) →
`ideas/005` (adversarial review of 004).

The review in `ideas/005` is the governing document of this track. Its key
findings: the S-Quant is a god object and must be decomposed; the logical hash
algorithm is load-bearing and must be designed and frozen first; the Authority
Arbiter and Contract Engine are boundaries around problems that do not exist
before slice 5; slice 1 is not thin enough. Its **stop condition** gates the
whole track:

> After hash + embedding validation, evaluate whether semantic search over a
> Clojure codebase is meaningfully better than what semidx already does
> structurally. If not, the premise needs revisiting before building the
> ledger, diff, and authority layers.

No implementation should start on this track without that validation slice.

### Track B: "Agent workflow substrate" (semidx evolution)

- **`ideas/012` state invariant context — the most mature idea.** Observed
  failure case (JobApplicationTracker OAuth Stage 7), concrete packet shape
  (entity fields, writers/transitions, assertion tests, fixture helpers,
  schema hints, guardrail recommendation), acceptance criteria, and a
  non-breaking delivery path: enrich `impact_analysis` / `expand_context`
  first, no dedicated tool. Best candidate for the next new implementation
  plan.
- **`ideas/011` agent graph intelligence layer** — a menu of 11 items. Already
  delivered by other work: content-hash freshness (item 5) and
  impact-analysis-as-gate basics (item 7). Open candidates: deterministic
  `architecture_graph` export, `change_map` (snapshot_diff + impact_analysis),
  graph lint, dead-code candidates, computed `handoff_summary`, docs/ADR
  indexing (gated by 007 Stage 2 — see section 1.2).
- **`ideas/008` agent-development improvements** — subsumed: item 4 (freshness
  signal) delivered; item 1 (index docs) gated by provider catalog; items 2-3
  overlap with `ideas/011` items 11 and 7.
- **`ideas/009` progress.txt vs semidx** and **`ideas/010` graph tools
  research** — context/source-intake for `ideas/011`; no standalone action.

## 4. Resolved Architectural Fork

ADR-034 and ADR-037 resolved the original fork by landing new dataflow facts as
typed relations while preserving existing call/import consumers. ADR-038 now
records the durable architecture: Semantic IR is an extraction intermediate and
typed relation facts plus snapshot indexes are the canonical graph for all new
graph semantics. Legacy calls/imports remain compatibility projections until a
separate parity-gated migration.

## 5. Recommended Order

1. ~~Hygiene commit: flip stale frontmatter on delivered plans~~ — **done**
   (`74e3d4e`).
2. Stabilize relation identity/evidence and validation before relation-backed
   consumers are introduced.
3. Complete the storage-independent bounded traversal kernel, then wire
   retrieval/impact projections over it while preserving conservative handling
   of ambiguity.
4. First new plan from the idea backlog: `ideas/012` state invariant context —
   it fits the existing staged-retrieval contract and does not wait for the
   provider catalog.
5. After the bounded public graph surface, schedule the provider catalog and
   Protobuf/OpenAPI product vertical slice before a SCIP evidence-provider
   spike.

## 6. Related Recent Work (context)

- Adaptive raw-fetch budget (ADR-033, `reports/015`): detail-stage raw fetch
  now degrades level and slices oversized chunks instead of returning an empty
  `raw_context`, and emits `suggested_token_budget` + `raise_token_budget`
  next-step guidance. Relevant to any future retrieval-quality work in
  Track B.
