---
title: "LLM One-Shot Context Delivery And Comparative Evaluation Plan"
doc_type: "architecture_plan"
lifecycle: "active"
status: "planned"
agent_action: "reference_for_context"
updated: "2026-08-03"
---

# Architecture Plan: LLM One-Shot Context Delivery And Comparative Evaluation

This plan adds an LLM-oriented composite retrieval operation, an optional
Markdown projection, and evidence that the result is more useful than simpler
alternatives. It is intentionally separate from
[`plans/018`](./018_semantic_provider_authority_migration_plan.md): that plan
owns evidence-provider authority, while this plan owns one-shot delivery and the
strategy adapters needed to evaluate that delivery through `plans/020`.

## Decision Boundary

ADR-024 remains the accepted decision of record: compact-first staged retrieval
is the canonical public state machine. The proposed `get_context` operation is
an additive composite facade over that state machine. It must preserve the same
snapshot-bound selection artifact, budgets, diagnostics, and typed failures.

Shipping an additive one-shot operation does not require superseding ADR-024.
Making one-shot the documented canonical default, removing staged operations,
or weakening selection stability requires a new ADR supported by comparative
evidence from `plans/020` using the adapters supplied by this plan. Until then,
staged operations remain normative and one-shot remains a convenience surface
for clients that prefer one round trip.

[`plans/020`](./020_retrieval_value_benchmark_harness_plan.md) is the sole owner
of the real-repository task corpus, benchmark-run and task-attempt schemas,
cross-strategy harness, provider usage normalization, price schedules, and
success/cost aggregation. This plan owns only the one-shot delivery surface,
delivery-specific fixtures, and strategy adapters that plug into that substrate.
The latest independent review and resolved plan findings are recorded in
[`reports/022`](../reports/022_latest_active_plans_architecture_review.md).

## Goal

Let an LLM agent request useful code context in one network round trip against
an already available workspace index while preserving:

- deterministic server-side orchestration;
- explicit token and raw-code budgets;
- snapshot and selection stability;
- the canonical structured ContextPacket;
- optional compact Markdown optimized for direct model consumption;
- continuation into the existing staged operations;
- measurable comparison with staged semidx, lexical search, and available
  language-navigation baselines.

## Scope

In:

- Library orchestration for `get-context` using existing retrieval primitives.
- An additive MCP `get_context` vertical slice, followed by HTTP/gRPC parity only
  after value is demonstrated.
- A versioned deterministic one-shot policy.
- Structured, Markdown, and combined response projections.
- Delivery-specific protected fixtures plus staged/one-shot strategy adapters
  that consume the corpus and measurement contracts owned by `plans/020`.
- Compatibility with provider evidence introduced by `plans/018` without
  provider-specific delivery logic.

Out:

- Replacing or removing `resolve_context`, `expand_context`, or
  `fetch_context_detail`.
- Running an LLM inside the retrieval server to choose context.
- Making external SCIP/LSP providers mandatory for one-shot retrieval.
- Cold-start repository provisioning in the first slice; Stage 2 requires an
  `index_id` and reuses existing lifecycle behavior.
- A free-form Markdown-only contract with no structured source of truth.
- A generic workflow engine or agent framework.
- Using a nondeterministic LLM judge as a per-commit correctness gate.

## Assumptions

- The current `resolve-context-detail` helper proves that selection and detail
  can already be composed inside the library, but the new orchestration boundary
  must also support policy-controlled expansion and stage-level telemetry.
- A one-shot request reduces client round trips; latency and token savings are
  hypotheses until measured end to end.
- ContextPacket remains the authoritative result. Markdown is a pure projection
  and cannot introduce facts absent from the packet.
- Provider authority remains invisible to the orchestrator except through
  runtime-owned evidence, capability, confidence, and degradation summaries.
- The first delivery surface is MCP because it directly exercises the LLM-agent
  use case with the smallest transport blast radius.

## Change Model

| Expected change | Owning boundary |
|---|---|
| Change when one-shot expands or fetches detail | One-shot policy |
| Change stage orchestration | Context orchestrator |
| Change output formatting | Context renderer |
| Add or change a transport | Thin transport adapter |
| Change retrieval ranking or graph semantics | Existing retrieval/graph boundary |
| Add or change an evidence provider | `plans/018` provider pipeline |
| Add a one-shot evaluation strategy | One-shot strategy adapter in this plan |
| Change corpus, run identity, cost normalization, or thresholds | `plans/020` benchmark substrate |

## Target Architecture

```mermaid
flowchart LR
  agent["LLM agent or IDE host"] --> transport["Library, MCP, HTTP, or gRPC adapter"]

  transport --> oneShot["get_context"]
  transport --> staged["Staged operations"]

  oneShot --> orchestrator["Context orchestrator"]
  orchestrator --> policy["One-shot policy"]
  orchestrator --> resolve["resolve_context"]
  orchestrator --> expand["expand_context"]
  orchestrator --> detail["fetch_context_detail"]

  staged --> resolve
  staged --> expand
  staged --> detail

  resolve --> semanticGraph["Canonical semantic graph"]
  expand --> semanticGraph
  detail --> semanticGraph
  providers["plans/018 provider pipeline"] --> semanticGraph

  orchestrator --> packet["Canonical ContextPacket result"]
  packet --> structured["Structured projection"]
  packet --> markdown["Markdown renderer"]
  packet --> telemetry["Stage and request telemetry"]

  structured --> transport
  markdown --> transport
  telemetry --> evaluation["Comparative evaluation harness"]
```

The orchestrator coordinates existing roles; it does not absorb retrieval,
ranking, rendering, provider selection, storage, or evaluation policy.

## Boundaries

### 1. Context Orchestrator

Responsibility: execute a bounded one-shot workflow over existing staged library
operations and assemble one response.

Knows about:

- staged operation roles;
- one-shot policy decisions;
- selection and snapshot identifiers;
- stage results, budgets, and typed errors;
- response projection requests.

Does not know about:

- SCIP, LSP, tree-sitter, or regex implementations;
- graph storage details;
- ranking internals;
- Markdown layout rules;
- benchmark expectations.

Initial location: `src/semidx/runtime/context_orchestrator.clj` with a thin
library entry point in `src/semidx/core.clj`.

### 2. One-Shot Policy

Responsibility: decide whether expansion is needed, which selected units may
receive detail, and how the request budget is allocated.

Initial shape: versioned data plus pure decision functions, not a protocol or
plugin system.

```clojure
{:policy_id "agent_default_v1"
 :expand_when #{"change_impact" "ambiguous_selection"}
 :detail_level "enclosing_unit"
 :max_selected_files 8
 :budget_split {:selection 0.10
                :expansion 0.20
                :detail 0.70}}
```

The policy must be deterministic for the same index snapshot, query, options,
and policy version.

### 3. Context Renderer

Responsibility: project a ContextPacket into bounded model-oriented Markdown.

Rules:

- code bytes are copied verbatim into fenced blocks;
- explanations are outside code fences;
- every snippet carries path and line information;
- callers, relations, selection reasons, and degradation are concise and
  reason-coded;
- no fact absent from ContextPacket may be synthesized;
- Markdown has an independent output budget and deterministic ordering;
- escaping handles backticks and user-controlled source text safely.

Initial location: `src/semidx/runtime/context_renderer.clj`.

### 4. One-Shot Evaluation Adapters

Responsibility: expose staged, one-shot structured, and one-shot Markdown as
strategy adapters to the benchmark substrate owned by `plans/020`.

It owns delivery-specific adapters and assertions, not the corpus, run schema,
usage normalization, price schedule, aggregation, thresholds, or retrieval
implementation. Each adapter returns the common strategy result defined by
`plans/020`, including selected paths/symbols, latency, request count, output
size, degradation, and task evidence.

Initial location: keep the first delivery adapters beside the benchmark adapter
seam introduced by `plans/020`; do not create a second evaluation framework or
extract a generic module from this plan alone.

## One-Shot Contract

### Request

```clojure
{:api_version "1.0"
 :index_id "idx-..."
 :query {...}                         ;; existing retrieval query contract
 :budget {:max_output_tokens 4000
          :max_files 8
          :max_code_tokens 2800}
 :presentation {:format "structured" ;; structured | markdown | both
                :detail "auto"}       ;; auto | compact | code
 :policy_id "agent_default_v1"}
```

The first slice requires `index_id`. A future workspace reference or cold-start
mode needs separate lifecycle and authorization review because it combines
index creation cost with retrieval latency.

The top-level `budget` is the only caller-controlled hard response budget.
Caller-supplied staged budget fields inside `query.constraints` are rejected as
`conflicting_budget_sources`; the orchestrator derives internal staged budgets
from the top-level ledger and passes them to existing operations. The request
must satisfy `max_code_tokens <= max_output_tokens`, and the first slice also
enforces a hard serialized-byte ceiling derived from the same policy.

### Response

```clojure
{:api_version "1.0"
 :snapshot_id "snap-..."
 :selection_id "sel-..."
 :context_packet {...}
 :agent_markdown "..."               ;; only for markdown | both
 :guardrail_assessment {...}
 :diagnostics_trace {...}
 :stage_events [...]
 :degradations []
 :continuation {:can_expand true
                :can_fetch_more true
                :snapshot_id "snap-..."
                :selection_id "sel-..."}
 :budget_summary {...}}
```

Structured mode may omit `agent_markdown`. Markdown mode still returns the
minimal machine envelope, selection/snapshot identifiers, degradation, budget,
and continuation; it does not collapse the entire wire contract into prose.

`max_output_tokens` covers the combined variable-size response, not each
projection independently. The response carries a deterministic budget ledger:

```clojure
{:max_output_tokens 4000
 :allocations {:structured_packet 1800
               :markdown 1000
               :diagnostics 800
               :reserve 400}
 :returned {...}
 :truncation_flags []}
```

For `format: both`, structured and Markdown allocations are disjoint and their
sum plus diagnostics/reserve cannot exceed the hard cap. Markdown renders only
from the retained packet and only with the remaining allocation; it cannot
duplicate an uncapped packet. Fixed scalar envelope fields remain mandatory,
while variable diagnostics and stage events truncate deterministically.

### Orchestration Sequence

```mermaid
sequenceDiagram
  participant A as Agent
  participant T as Transport
  participant O as Context orchestrator
  participant R as Staged retrieval
  participant P as Context renderer

  A->>T: get_context request
  T->>O: validated request
  O->>R: resolve_context
  R-->>O: selection_id, snapshot_id, next_step

  alt policy requires expansion
    O->>R: expand_context with retained selection
    R-->>O: skeletons and impact hints
  end

  O->>R: fetch_context_detail with retained selection
  R-->>O: ContextPacket and diagnostics

  opt Markdown or both requested
    O->>P: render bounded ContextPacket
    P-->>O: agent_markdown
  end

  O-->>T: one response plus continuation
  T-->>A: structured, Markdown, or both
```

## Invariants

1. One-shot executes the same snapshot-bound staged semantics as direct staged
   callers.
2. The response always exposes the retained `selection_id` and `snapshot_id`.
3. A stage failure remains typed and observable; the orchestrator cannot
   silently re-resolve against a newer snapshot.
4. Combined variable-size structured, Markdown, diagnostic, and stage-event
   content cannot exceed the request hard cap.
5. Detail selection is derived only from the retained selection artifact.
6. Renderer output never feeds back into ranking or graph construction.
7. Provider availability may change evidence quality but cannot change the
   one-shot contract shape.
8. Repeated identical requests against the same retained snapshot and policy
   version produce equivalent semantic selections and deterministic rendering.
9. A request has one authoritative response-budget ledger; nested staged
   budgets cannot override it.
10. Persisted usage events distinguish `accounting_scope: aggregate` from
    `accounting_scope: stage`. Cost and rollup consumers select exactly one scope
    and never sum both for the same one-shot operation.

## Comparative Evaluation

```mermaid
flowchart TD
  corpus["plans/020 protected task corpus"] --> harness["plans/020 comparison harness"]

  harness --> lexical["rg plus bounded file read"]
  harness --> staged["semidx staged"]
  harness --> oneStructured["semidx one-shot structured"]
  harness --> oneMarkdown["semidx one-shot Markdown"]
  harness --> semantic["semidx one-shot with SCIP or LSP evidence"]

  lexical --> normalize["Normalized evaluation result"]
  staged --> normalize
  oneStructured --> normalize
  oneMarkdown --> normalize
  semantic --> normalize

  normalize --> deterministic["Deterministic correctness and cost gates"]
  normalize --> nightly["Optional nightly LLM task evaluation"]
  deterministic --> report["Comparative scorecard"]
  nightly --> report
  report --> decision["Promotion, revision, or rollback decision"]
```

Required delivery metrics (normalized and aggregated by `plans/020`):

| Metric | Purpose |
|---|---|
| Required-path and required-symbol recall | Did the strategy find necessary code? |
| Excluded-path precision | Did it avoid known noise? |
| Relation/caller evidence recall | Did graph context add useful structure? |
| End-to-end p50/p95 latency | Did one-shot improve interactive cost? |
| Client request count | Did the composite facade remove round trips? |
| Returned tokens and bytes | Did projection reduce model input cost? |
| Index preparation time | Is provider quality hiding startup cost? |
| Degraded-workspace success rate | Does retrieval remain available without providers? |
| Stale-evidence rejection rate | Are exact facts source-valid? |
| Task completion score | Does the agent solve the protected task? |

Per-commit gates remain deterministic. LLM task evaluation runs on a pinned
model/prompt configuration outside the mandatory correctness lane and reports
variance across repeated runs.

## Stage Execution Routing And Handoff

The table is the starting routing recommendation, not a permanent model lock.
High effort is explicitly allowed when justified by contract irreversibility,
budget/accounting ambiguity, comparative-verdict risk, a possible public-default
change, conflicting evidence, or repeated verification failures. It does not
require a separate exception when this table or the preceding stage's handoff
recommends it. Mechanical schema propagation, renderer snapshots, transport
parity, and routine test repair should normally use medium effort.

| Stage | Recommended executor | Recommended primary model | Effort | Why |
| --- | --- | --- | --- | --- |
| 0 — review and baseline | Antigravity independent review conversation | Claude Sonnet 4.6 (Thinking) | high | challenge additive scope independently and align fixtures with the locked `plans/020` contracts |
| 1 — library orchestration | Antigravity | Gemini 3.1 Pro | high | response budgets, snapshot stability, and usage accounting form one correctness boundary |
| 2 — MCP structured slice | Antigravity | Gemini 3.6 Flash | medium | the contract is fixed and the work is bounded schema/handler parity |
| 3 — Markdown projection | Antigravity | Gemini 3.6 Flash | medium | deterministic rendering and budget tests are implementation-heavy but locally bounded |
| 4 — comparative gate | Antigravity | Gemini 3.1 Pro | high | task-value interpretation may stop promotion or change the next plan stage |
| 5 — cross-surface parity | Antigravity | Gemini 3.6 Flash | medium | transport adapters must mirror an already-proven runtime contract |
| 6 — product default decision | Antigravity | Gemini 3.1 Pro | high | retaining or changing the documented default is an ADR-level decision |

Claude Sonnet 4.6 (Thinking) may also be used as a bounded independent reviewer
at Stages 1, 4, or 6. Claude Opus 4.6 (Thinking) is reserved for an unresolved
architecture conflict or contradictory scorecard evidence, not routine
execution. Gemini 3.5 Flash is the fallback for Gemini 3.6 Flash; GPT-OSS 120B
(Medium) may provide an adversarial review but does not own critical-path edits.

At the end of every stage, after verification and before the stage-closing
commit, the executing model must read:

1. the candidate next stage and its routing row in this plan, plus the
   `plans/020` strategy-result, scorecard, stop-rule, and ownership constraints;
2. the companion progress log, current `MEMORY.md`, and relevant `SPEC.md`
   decision or stop rules;
3. the completed stage diff, verification results, unresolved findings, and
   current file ownership across parallel worktrees;
4. current executor/model availability and quota constraints.

It must then add a `NextStageRoutingRecommendation` to the progress log with:

```text
completed_stage, recommended_next_stage,
recommended_executor, recommended_model, effort,
effort_justification, rationale, prerequisites_or_blockers,
file_ownership_and_conflict_risk,
fallback_executor_or_model,
model_availability_checked_at, confidence
```

The recommendation may retain or override the table default, but any override
must cite stage evidence. A `high` recommendation must contain a concrete
`effort_justification`. If no companion progress log exists, Stage 0 creates it
before recording the recommendation. If a stop/kill rule fires or prerequisites
are absent, the model must recommend `stop` or `defer` instead of auto-starting
work. The handoff is recorded in the same commit as the stage progress update
and never bypasses an owner approval or admission gate.

## Implementation Sequence

### Stage 0. Independent Review And Baseline

Deliverables:

- Independent review of this plan against ADR-024, plans/018, current contracts,
  and current convenience-helper behavior.
- A delivery-specific protected fixture overlay spanning code understanding,
  change impact, test targeting, and bug investigation, mapped onto the task
  schema owned by `plans/020`; do not fork its real-repository corpus.
- Current staged baseline for the delivery fixtures: required paths/symbols,
  request count, returned tokens, and end-to-end latency.
- Explicit decision that the first slice is additive and does not change the
  canonical public default.

Exit: findings are resolved or explicitly deferred and baseline inputs are
reproducible.

Commit boundary: documentation and evaluation fixtures only.

### Stage 1. Library Orchestration

Deliverables:

- Pure one-shot policy and budget allocation.
- Context orchestrator composing existing staged operations.
- Structured `get-context` library result.
- Parity tests proving selection, snapshot, error, and detail equivalence with
  direct staged execution.
- Stage-level and aggregate usage telemetry with parent correlation and explicit
  `accounting_scope`. The orchestrator may suppress wrapper-level persistence and
  emit correlated stage diagnostics itself, or retain wrapper events, but the
  chosen path must produce exactly one cost-accounting scope.

Exit: no transport exposure and no ranking behavior change.

Commit boundary: library orchestration and tests only.

### Stage 2. MCP Structured Vertical Slice

Deliverables:

- Additive `get_context` MCP tool requiring an existing `index_id`.
- JSON Schema, malli mirror, examples, tool catalog entry, and handler.
- Machine-readable `tools/list` and runtime parity tests.
- Explicit continuation into existing staged tools.

Exit: one MCP request returns the same bounded semantic result as the equivalent
staged calls, with no snapshot drift.

Commit boundary: MCP structured mode only.

### Stage 3. Markdown Projection

Deliverables:

- Pure bounded renderer.
- `structured`, `markdown`, and `both` response modes.
- Escaping, deterministic ordering, code-fidelity, truncation, and token-budget
  tests.
- A/B measurement against structured mode; no default-format change yet.

Exit: Markdown is never the sole machine contract and demonstrates measured
cost or usability value on protected tasks.

Commit boundary: renderer and additive projection only.

### Stage 4. Comparative Evaluation Gate (consumer of plans/020)

Deliverables:

- One-shot structured and one-shot Markdown adapters implementing the common
  strategy-result contract owned by `plans/020`.
- Reuse of the lexical, no-index, LSP/SCIP, and staged adapters from `plans/020`;
  this stage must not reimplement them.
- Optional provider-aware strategy populated as plans/018 slices become
  available.
- One-shot result rows incorporated into the `plans/020` scorecard and retained
  baselines.
- Optional one-shot participation in the pinned nightly LLM task evaluation
  owned by `plans/020`.

Exit: the `plans/020` scorecard can answer whether one-shot improves task
outcomes per unit of normalized cost, latency, and output budget.

Commit boundary: evaluation only; do not change runtime defaults in this stage.

### Stage 5. Cross-Surface Parity

Deliverables:

- HTTP and gRPC exposure of the same runtime-owned request/result contract.
- Shared authz, rate-limit, usage, and error semantics.
- Cross-surface examples and parity tests.

Exit: MCP, HTTP, gRPC, and library differ only in transport representation.

Commit boundary: transport adapters only.

### Stage 6. Product Default Decision

Use the comparative scorecard to choose one outcome:

1. retain staged retrieval as the documented default and keep one-shot additive;
2. recommend one-shot for selected LLM clients while staged remains canonical;
3. propose a new ADR that changes the canonical public default.

No option removes staged operations. Any new default requires an accepted ADR,
updated README/runtime docs/examples, and an explicit rollback path.

## Verification Gates

- One-shot versus staged semantic parity.
- Snapshot mismatch, selection eviction, unsupported version, and truncation.
- Hard output and raw-code budget enforcement.
- Deterministic renderer snapshot tests and code-byte fidelity.
- Contract schema, malli, MCP tool-list, and runtime handler parity.
- Usage metrics count one aggregate request while retaining stage diagnostics.
- Delivery fixture overlay plus retained `plans/020` corpus/run baseline.
- Complete Clojure tests and applicable contract/MVP gates before transport
  promotion.
- CCC and MEMORY freshness when runtime behavior or priorities change.

## Rollback

- Stages 1-4 are additive; disable `get_context` without changing staged tools.
- Renderer modes can be disabled independently from structured one-shot output.
- Stage 5 transport exposure is feature-gated until parity and rate-limit checks
  pass.
- Evaluation baselines are immutable inputs; a rollback records the failed
  candidate rather than rewriting prior results.
- Provider rollbacks are owned by plans/018 and remain invisible to delivery
  orchestration.

## Risks

### [High] One-shot becomes a god orchestrator

Mitigation: keep ranking, provider selection, rendering, storage, and evaluation
outside the orchestrator; orchestration owns only stage composition and budget
coordination.

### [High] Composite execution hides snapshot or stage failures

Mitigation: preserve selection/snapshot ids, typed stage errors, diagnostics,
stage events, and continuation in the one-shot response.

### [High] Markdown silently drops correctness metadata

Mitigation: keep ContextPacket authoritative and retain a minimal machine
envelope even in Markdown mode.

### [Medium] One-shot moves latency rather than reducing it

Mitigation: measure end-to-end p50/p95, server stage durations, request count,
and output size before recommending it as a client default.

### [Medium] Evaluation favors semidx-specific metadata

Mitigation: score all strategies first on task-level required paths, symbols,
and completion outcomes; treat semidx diagnostics as explanatory secondary
metrics.

### [Medium] LLM evaluation becomes a flaky CI gate

Mitigation: deterministic per-commit gates; pinned and repeated LLM runs only in
the advisory nightly/release lane until variance is understood.

## Independent Review Brief

The reviewer should challenge implementation readiness without reopening
ADR-024 or ADR-046 by default.

Required questions:

1. Is an additive external composite operation compatible with ADR-024 when the
   staged state machine remains canonical and visible?
2. Can the existing helper be reused without making `semidx.core` own rendering
   or transport policy?
3. Are the one-shot policy inputs sufficient to remain deterministic and
   snapshot-bound?
4. Does Markdown preserve code fidelity and enough machine metadata for safe
   continuation?
5. Do evaluation metrics compare task value rather than rewarding semidx-only
   diagnostics?
6. Can the MCP slice remain useful without cold-start index creation?
7. Is any proposed module premature before the second implementation variant?
8. What evidence threshold should trigger a new ADR about the canonical public
   default?

Expected output: severity-ordered findings, an implementation-readiness
recommendation, unresolved owner decisions, and the smallest required revision
set before Stage 1.

## Execution Admission

Stage 1 source implementation may begin only when:

- independent review findings are resolved or explicitly deferred;
- the delivery fixture overlay is accepted and the `plans/020` run identity,
  usage normalization, and strategy-result contracts are accepted (the complete
  real-repository harness may land later);
- the first slice remains additive under ADR-024;
- one-shot policy and budget ownership are explicit;
- no provider-specific condition exists in orchestration or rendering.
