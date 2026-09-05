---
title: "Semantic Provider Authority Migration Plan"
doc_type: "architecture_plan"
lifecycle: "active"
status: "in_progress"
agent_action: "reference_for_context"
updated: "2026-09-05"
---

# Architecture Plan: Semantic Provider Authority Migration

Decision dependency: accepted
[`ADR-046`](../adr/046-prefer-semantic-evidence-providers-over-structural-and-lexical-fallbacks.md).

This is the approved architecture plan for the migration. Independent review is
the required first delivery stage and must complete before source implementation
begins. The repo-managed tree-sitter operational boundary is ADR-047.
The latest independent review and resolved plan findings are recorded in
[`reports/022`](../reports/022_latest_active_plans_architecture_review.md).
The Stage 0 execution, independent review findings, and baseline are recorded in
the companion progress log
[`reports/024`](../reports/024_semantic_provider_authority_stage0_progress_log.md).

## Goal

Migrate Java and TypeScript from regex-first single-parser dispatch to a
capability-specific provider pipeline in which:

- fresh SCIP and LSP facts are the semantic-authority tier;
- tree-sitter supplies structural facts and fills semantic-provider gaps;
- regex is a bounded, explicitly degraded last resort;
- all facts normalize into the existing Semantic IR and canonical typed-relation
  graph;
- external provider absence never removes the basic local indexing path.

## Scope

In:

- Provider-plan, fact-batch, evidence, status, and arbitration contracts.
- Multi-provider orchestration for Java and TypeScript.
- SCIP artifact ingestion for TypeScript, then Java.
- LSP live-overlay enrichment for TypeScript, then Java.
- Tree-sitter and regex adaptation behind the same provider boundary.
- Source-identity validation, explicit degradation, bounded provider execution,
  and deterministic evidence merging.
- Shadow comparison, confidence recalibration, compatibility migration, and
  public additive provider summaries.

Out:

- Implementing an LSP server or SCIP indexer inside semidx.
- Making an external provider mandatory for basic indexing.
- Editing, rename, refactoring, completion, or diagnostic UI features.
- Migrating every language lane in the first tranche.
- Replacing typed relations with provider-native graph formats.
- A general plugin marketplace, runtime classloader, or remote extension SDK.
- Promoting tree-sitter or regex evidence to compiler-grade authority.
- One-shot LLM delivery, Markdown rendering, or cross-strategy task evaluation;
  those belong to
  [`plans/019`](./019_llm_one_shot_context_delivery_and_evaluation_plan.md).

## Assumptions

- ADR-039 relation identity remains stable and excludes mutable evidence.
- Existing Semantic IR and typed relations remain the only normalized graph
  model; SCIP and LSP do not create parallel indexes exposed to retrieval.
- Provider integration uses client-owned, narrow function roles and data-first
  registration. Introduce a protocol only when an external runtime boundary
  requires substitution that plain functions cannot express cleanly.
- Provider-native symbols and ids are never merge keys. After source-identity
  validation, every adapter must derive the same provider-neutral
  `CanonicalFactKey` from language, path/module owner, fact kind, canonical
  symbol, and overload/dispatch identity before evidence reaches arbitration.
  Relation facts use the ADR-039 identity fields anchored to canonical
  source/target keys. Mutable source identity and evidence are excluded from
  either stable key.
- Existing public parser options remain compatibility controls during migration.
- TypeScript is the first vertical slice because its current confidence ceiling
  and regex brittleness make the improvement easiest to observe; review may
  reverse Java and TypeScript only with concrete toolchain or fixture evidence.
- LSP is primarily a live overlay. SCIP is primarily a reproducible batch
  source. Neither assumption grants authority without matching source identity.
- Provider authority is a data-plane concern. Retrieval orchestration, one-shot
  delivery, and rendering consume the canonical graph and must not inspect
  provider implementations.

## Change Model

| Expected change | Owning boundary |
|---|---|
| Add or replace a semantic provider | Provider catalog and provider adapter |
| Change authority or fallback order | Provider planning policy |
| Change freshness requirements | Provider freshness policy |
| Add an evidence field | Fact evidence contract and storage projection |
| Change conflict behavior | Fact arbitrator |
| Add a language-specific normalization rule | Provider adapter for that language |
| Change repository rebuild identity | Workspace-state provider-plan fingerprint |
| Change public degradation reporting | Capability projection |

Index lifecycle, retrieval, transports, and individual providers must not own
provider precedence.

## Target Flow

```mermaid
flowchart TD
  discovery["Source discovery"] --> eligibility["Provider eligibility"]
  eligibility --> validation["Runtime status and source-identity validation"]
  validation --> plan["Capability-specific ProviderPlan"]

  plan --> semantic["Semantic tier: SCIP and LSP"]
  plan --> structural["Structural tier: tree-sitter"]
  plan --> heuristic["Heuristic tier: regex"]

  semantic --> batches["Normalized FactBatch values"]
  structural --> batches
  heuristic --> batches
  batches --> arbitration["Authority and freshness arbitration"]
  arbitration --> semanticGraph["Semantic IR and typed relations"]
  semanticGraph --> index["Index and storage"]
  index --> retrieval["Capability projection and retrieval"]
```

Execution order is not a whole-file short circuit. A provider that returns
definitions does not suppress a lower-tier provider needed for missing structure
or another operation. Lower tiers execute only for planned gaps or explicit
shadow comparison. Physical scheduling may precompute tree-sitter structure for
anchoring or latency reasons; the invariant is the evidence-authority and merge
order, not process start order.

## Boundaries

### 1. Provider Catalog

Responsibility: own versioned provider descriptors and role functions.

Knows about:

- provider id and version;
- language/file selectors;
- operation capability profile;
- static authority claims;
- status, freshness, and execution functions.

Does not know about:

- provider precedence;
- repository traversal;
- retrieval ranking;
- storage implementation;
- transport schemas.

Initial location: `src/semidx/runtime/providers.clj`, evolving the metadata-only
catalog currently derived from `runtime/language_registry.clj`.

### 2. Provider Planning Policy

Responsibility: produce a deterministic per-file, per-operation execution plan.

Inputs:

- file metadata and content identity;
- requested indexing operations;
- eligible descriptors;
- provider runtime status and freshness;
- project overrides;
- shadow/default mode.

Output:

```clojure
{:path "src/example.ts"
 :source_identity {:content_digest "sha256:..."}
 :operations
 {:definitions [{:provider_id "scip-typescript" :authority "exact"}
                {:provider_id "typescript-tree-sitter" :authority "structural"}
                {:provider_id "typescript-regex" :authority "heuristic"}]
  :references  [{:provider_id "scip-typescript" :authority "exact"}
                {:provider_id "typescript-lsp" :authority "exact"}
                {:provider_id "typescript-regex" :authority "heuristic"}]}
 :mode "shadow"}
```

Does not know provider execution internals or Semantic IR normalization rules.

Initial location: `src/semidx/runtime/provider_selection.clj`; extend the
plans/007 selection seam from one winner to a bounded provider plan.

### 3. Provider Execution Orchestrator

Responsibility: execute a ProviderPlan with bounded concurrency, timeouts,
failure isolation, gap tracking, and diagnostics.

Knows about provider role functions and FactBatch values. Does not decide
authority, merge semantics, retrieval confidence, or transport formatting.

Initial location: `src/semidx/runtime/provider_execution.clj`; invoked from the
file-indexing path that currently calls `adapters/parse-file` directly.

### 4. Fact Evidence And Normalization

Responsibility: convert provider-native output into normalized units, relations,
diagnostics, and evidence records.

Conceptual contract:

```clojure
{:provider_id "scip-typescript"
 :provider_version "..."
 :path "src/example.ts"
 :source_identity {:content_digest "sha256:..."
                   :revision "..."}
 :runtime_status "ready"
 :facts {:units [...]
         :relations [...]}
 :coverage {:definitions "exact"
            :references "exact"
            :call_hierarchy "none"}
 :diagnostics []}
```

Each fact carries or references one or more evidence records. The current
`parser_mode` remains a compatibility projection; new authority and provenance
fields are authoritative internally.

Primary locations:

- `src/semidx/runtime/semantic_ir.clj`;
- `src/semidx/runtime/relations.clj`;
- provider-specific adapters under `src/semidx/runtime/providers/`.

### 5. Fact Arbitrator

Responsibility: merge facts with the same canonical fact key and surface
conflicts.

Rules:

1. Reject evidence that fails source-identity, canonical-key, or schema
   validation.
2. Merge agreeing same-key facts and retain all evidence sources.
3. Let lower authority fill missing facts and missing non-conflicting details.
4. Never let lower authority replace a higher-authority value.
5. Mark equal-authority contradictions ambiguous and emit a diagnostic.
6. Produce stable ordering independent of registration or completion order.
7. Keep fact identity independent from evidence accumulation.

Initial location: `src/semidx/runtime/fact_arbitration.clj`.

### 6. Provider Freshness Policy

Responsibility: decide whether provider output matches the intended source.

SCIP requirements:

- per-document content digest; or
- artifact revision plus verification that the covered source content matches
  the current workspace.

LSP requirements:

- intended workspace root;
- matching open-document version or content digest for live overlays;
- source identity attached to every accepted batch.

Unknown or stale identity cannot produce exact facts. The provider is skipped or
degraded for the affected file/operation.

Initial location: pure functions in
`src/semidx/runtime/provider_freshness.clj`.

### 7. Capability And Degradation Projection

Responsibility: aggregate selected provider evidence into runtime-owned
capability, coverage, and degradation summaries.

Required outcomes:

- no confidence increase from provider branding alone;
- fallback-only evidence remains low and review-required;
- mixed exact/structural/heuristic coverage is visible per operation;
- `create_index`, `repo_map`, retrieval, MCP, HTTP, and gRPC serialize the same
  runtime-owned interpretation.

Primary locations:

- `src/semidx/runtime/capabilities.clj`;
- `src/semidx/runtime/retrieval_policy.clj`;
- transport passthrough and additive contract mirrors.

## Contracts

### ProviderDescriptor

```clojure
{:provider_id "typescript-lsp"
 :provider_version "1"
 :languages ["typescript"]
 :classification "semantic"
 :selectors {:extensions [".ts" ".tsx"]}
 :operation_capabilities
 {:definitions "exact"
  :references "exact"
  :implementations "exact"
  :call_hierarchy "structural"
  :document_symbols "exact"}}
```

Capability values are claims bounded by successful freshness and runtime-status
checks. They are not unconditional confidence grants.

### ProviderRuntimeStatus

```clojure
{:provider_id "typescript-lsp"
 :state "ready"                 ;; ready | degraded | unavailable
 :reason_codes []
 :observed_at "..."}
```

### CanonicalFactKey

```clojure
{:fact_schema_version "1"
 :language "java"
 :path "src/example/OrderService.java"
 :fact_kind "unit"
 :owner "example.OrderService"
 :symbol "example.OrderService#handle"
 :overload_identity {:arity 2
                     :signature_key "java.lang.String,int"}
 :dispatch_identity nil}
```

The `signature_key` above is illustrative of the two-layer model, not of what
any current Java provider emits. Verified 2026-09-05: `scip-java` supplies no
parameter types in its symbols — it disambiguates overloads with a source-order
ordinal — so the Java exact tier commits `signature_precision: "arity_only"`
with a `nil` `signature_key`, exactly like the heuristic tier. The two-layer
model is unchanged; only the assumption that the Java exact tier can supply the
typed refinement is withdrawn. See Stage 4 and
`fixtures/provider-authority/identity/java-overload-canonical-key.json`.

Relation facts use the corresponding provider-neutral form:

```clojure
{:fact_schema_version "1"
 :fact_kind "relation"
 :relation_type "dataflow/passes-argument"
 :source_unit_key {...canonical unit fact key...}
 :target_key {:language "java"
              :symbol "example.Validator#validate"
              :overload_identity {:arity 1
                                  :signature_key "java.lang.String"}}
 :flow_identity {:arg_index 0}}
```

The key is computed from provider-neutral normalized fields and is the sole
input to same-fact arbitration. Provider ids, provider-native symbol ids,
runtime status, freshness, content digests, locations, and evidence quality are
not part of the stable key. Adapters may retain native ids in evidence for
diagnostics. A fact batch is rejected or degraded when it cannot produce a
canonical key without guessing. Relation projection resolves
`source_unit_key` to ADR-039's `source_unit_id`; the normalized `target_key` and
flow identity map directly to ADR-039's remaining stable identity fields.

### FactEvidence

```clojure
{:provider_id "typescript-lsp"
 :provider_version "1"
 :canonical_fact_key {...}
 :authority "exact"
 :operation "definitions"
 :freshness "exact"
 :source_identity {:content_digest "sha256:..."
                   :document_version 42}
 :evidence_location {:path "src/example.ts"
                     :start_line 7
                     :end_line 10}}
```

### ProviderPlan

- Bounded provider list per operation.
- Deterministic stable order.
- Explicit default, forced-test, and shadow modes.
- Exact source identity used to build the plan.
- Explicit maximum execution count and timeout policy.

### Compatibility Projection

- Existing `units`, `relations`, `calls`, `imports`, and diagnostics remain
  available while consumers migrate.
- Existing `parser_mode` remains present during shadow migration.
- On the final default switch, regex-only Java/TypeScript units project
  `parser_mode: fallback`; this intentional confidence change requires approved
  replay evidence.
- Existing `java_engine`, `typescript_engine`, and `tree_sitter_enabled` options
  remain temporary explicit overrides and test controls before deprecation.

## Dependency Direction

```mermaid
flowchart TD
  lifecycle["Index lifecycle and builder"] --> execution["Provider execution orchestrator"]
  execution --> plan["ProviderPlan"]
  execution --> arbitrator["Fact arbitrator"]

  policy["Provider planning policy"] --> plan
  catalog["Provider catalog"] --> policy
  adapters["Provider adapters"] --> execution
  tools["SCIP, LSP, tree-sitter, regex"] --> adapters

  plan --> arbitrator
  arbitrator --> semanticGraph["Semantic IR and typed relations"]
  semanticGraph --> retrieval["Retrieval and capability projection"]
  retrieval --> delivery["Staged or one-shot delivery from plans/019"]
```

- Policy depends on descriptors and evidence contracts, never SDKs or CLIs.
- SCIP, LSP, tree-sitter, and regex details plug into provider-owned adapters.
- Retrieval depends on normalized facts and evidence summaries, never provider
  implementations.
- Transports depend on runtime outputs and do not derive authority themselves.

## Coordination With plans/019 And plans/020

This plan owns the evidence data plane. `plans/019` owns LLM-facing one-shot
delivery and its strategy adapters. `plans/020` exclusively owns the protected
real-repository corpus, benchmark-run identity, cross-strategy harness, usage
normalization, price schedules, and comparative aggregation. Their shared
boundary is the canonical snapshot, ContextPacket, and plans/020 strategy-result
contract, not provider-native payloads.

```mermaid
flowchart LR
  providers["plans/018 provider pipeline"] --> canonicalGraph["Canonical snapshot graph"]
  canonicalGraph --> retrieval["Existing staged retrieval primitives"]
  retrieval --> oneShot["plans/019 one-shot orchestrator"]
  retrieval --> staged["Staged public operations"]
  oneShot --> packet["Canonical ContextPacket"]
  staged --> packet
  packet --> renderer["Optional Markdown renderer"]
  packet --> adapters["plans/019 delivery strategy adapters"]
  adapters --> evaluation["plans/020 comparative evaluation"]
  staged --> evaluation
```

Coordination rules:

- `plans/019` may ship its first one-shot slice over the current graph before
  the provider authority switch.
- `plans/018` Stage 6 consumes comparative task-value evidence from the
  `plans/020` scorecard. The scorecard may include one-shot adapters from
  `plans/019`, but provider correctness remains gated by deterministic provider
  and relation tests in this plan.
- The one-shot orchestrator and renderer must not branch on provider ids.
- Provider-native details stay outside the public retrieval contract except for
  bounded runtime-owned evidence and degradation summaries.

## Stage Execution Routing And Handoff

The table is the starting routing recommendation, not a permanent model lock.
High effort is explicitly allowed when the correctness risk justifies its cost;
it does not require a separate exception when this table or the preceding
stage's handoff recommends it. Typical justifications are canonical identity,
arbitration, freshness/concurrency, public authority changes, conflicting
evidence, or repeated verification failures. Routine fixture maintenance,
mechanical parity edits, and already-understood test repairs should remain at
medium effort unless new evidence raises the risk.

| Stage | Recommended executor | Recommended primary model | Effort | Why |
| --- | --- | --- | --- | --- |
| 0 — review and baseline | Claude Code 2.1.212+ independent reviewer teammate; lead synthesizes | Claude Opus 4.6 | high | challenge the migration independently and lock provider-neutral identity/toolchain assumptions before source work |
| 1 — evidence/arbitration | Claude Code team lead | Claude Opus 4.6 | high | `CanonicalFactKey`, ambiguity, and storage compatibility are correctness-critical contracts |
| 2 — provider shadow path | Claude Code team lead | Claude Sonnet 4.6 | medium | bounded implementation behind a default-off seam |
| 3 — TypeScript SCIP | Claude Code team lead | Claude Sonnet 4.6 | high | source identity and cross-provider normalization enter the weakest current lane |
| 4 — Java SCIP | Claude Code team lead | Claude Sonnet 4.6 | high | overload, constructor, import, and relation identities need careful parity |
| 5 — LSP overlay | Claude Code team lead | Claude Opus 4.6 | high | live freshness, cancellation, and deterministic batch behavior interact |
| 6 — default switch | Claude Code team lead | Claude Opus 4.6 | high | this is the public authority and truthful-degradation decision gate |
| 7 — cleanup | Claude Code team lead | Claude Sonnet 4.6 | medium | removal follows proven compatibility and retention gates |

Claude Code may use at most two concurrent teammates for independent fixture,
toolchain, or read-only regression work. The lead remains the sole owner of
production edits and stage commits. Teammates must have disjoint file ownership;
Fable and the repository-prohibited Explore agent are excluded.

At the end of every stage, after verification and before the stage-closing
commit, the executing model must read:

1. the candidate next stage and its routing row in this plan, plus any applicable
   coordination or execution-admission gates;
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

### Stage 0. Independent Review And Compatibility Baseline

Goal: independently challenge the accepted authority model before source
implementation.

Deliverables:

- Independent review of ADR-046 and this plan.
- Explicit resolution of review findings.
- Protected Java and TypeScript fixtures for definitions, references, calls,
  overloads, re-exports, dirty-file behavior, and provider unavailability.
- Cross-provider identity fixtures proving that regex, tree-sitter, SCIP, and
  LSP spellings of the same Java overload or TypeScript re-export normalize to
  one `CanonicalFactKey`, while distinct overloads remain distinct.
- Baselines for retrieval selections, callers/callees, impact, snapshot diff,
  confidence, latency, and snapshot size.
- A reusable provider-quality baseline that `plans/020` can include in its
  cross-strategy scorecard, optionally through the delivery adapters supplied by
  `plans/019`, without duplicating fixtures.

Exit criteria:

- Independent review findings are recorded and resolved or explicitly deferred.
- Intentional future semantic differences are named before code changes.
- Baseline comparisons are deterministic.

Commit boundary: documentation and baseline fixtures only.

### Stage 1. Evidence Model And Arbitration Kernel

Goal: establish stable contracts without changing default extraction.

Deliverables:

- Additive FactEvidence and FactBatch normalization.
- Provider-neutral `CanonicalFactKey` normalization before arbitration,
  including explicit unkeyable/ambiguous diagnostics.
- Deterministic same-key merge.
- Authority, freshness, and conflict rules.
- Additive multi-source relation evidence compatible with ADR-039.
- In-memory and PostgreSQL round-trip coverage.
- Property-style tests proving registration and completion order do not change
  merged output.

Exit criteria:

- Same semantic fact from multiple providers produces one canonical fact key
  and keeps one unit/relation identity; provider-native ids remain evidence only.
- Java overloads, TypeScript re-exports, and dispatch-sensitive identities have
  cross-provider golden parity before any external provider slice begins.
- Lower authority cannot overwrite higher authority.
- Equal-authority contradictions are observable.
- Existing snapshots remain readable.

Commit boundary: evidence contracts and pure arbitration only.

### Stage 2. Provider Plan And Legacy Adapter Shadow Path

Goal: prove orchestration using providers already present in the repository.

Deliverables:

- Data-first provider catalog.
- Capability-specific ProviderPlan.
- Provider execution orchestrator with bounded concurrency and timeouts.
- Java/TypeScript tree-sitter provider adapters.
- Java/TypeScript regex provider adapters classified as heuristic.
- Compatibility adapter preserving the current `adapters/parse-file` facade.
- Shadow output that does not affect the active snapshot.

Exit criteria:

- Existing default output remains unchanged.
- Shadow provider output is deterministic.
- Tree-sitter unavailability routes to regex with an explicit degradation.
- Regex shadow facts carry heuristic authority and never exact authority.

Commit boundary: provider seam and shadow execution, no default switch.

### Stage 3. TypeScript SCIP Vertical Slice

Goal: prove reproducible compiler-grade batch evidence in the lowest-confidence
current lane.

Deliverables:

- SCIP artifact reader behind a provider adapter.
- Source-identity and artifact-version validation.
- TypeScript definitions, references, implementations, and available call facts
  normalized into units and typed relations.
- Shadow comparison against current TypeScript extraction.
- Provider coverage, conflict, stale-artifact, latency, and storage metrics.

Exit criteria:

- Stale or mismatched SCIP artifacts never produce exact facts.
- SCIP facts merge with tree-sitter structure without duplicate semantic
  identities.
- Protected TypeScript retrieval cases do not regress; approved improvements are
  recorded explicitly.
- Absence of a SCIP artifact degrades to tree-sitter/regex without index failure.

Commit boundary: TypeScript SCIP provider remains shadow/default-off.

### Stage 4. Java SCIP Vertical Slice

Goal: reuse the proven SCIP seam for Java without widening the core contracts.

Deliverables and gates mirror Stage 3, with Java-specific coverage for overloads,
constructors, inheritance, static imports, method references, entity fields, and
field-write relations.

#### Amendment (owner decision, 2026-09-05): Java overload identity is arity-only

Stage 4 preflight indexed the protected Java corpus with real `scip-java`
(`semanticdb-javac` 0.12.3 + `scip-semanticdb` 0.12.3) and found the Stage 0
fixture's seeded SCIP spelling to be false. Verified behaviour:

- the symbol scheme is `semanticdb`, not `scip-java`;
- overloads carry a **source-order ordinal** (`handle().`, `handle(+1).`), not a
  typed signature; the ordinal counts over the method name across all arities;
- neither parameter types nor arity are recoverable from the moniker;
- arity and a human-readable signature come only from
  `SymbolInformation.signature_documentation.text`, whose type names are simple
  and whose parameter names are included — the shape F1 rejected as Variant B;
- fully-qualified parameter types exist only as separate occurrences inside the
  method's declaration range.

Therefore the Java exact tier commits:

```clojure
{:arity n :signature_precision "arity_only" :signature_key nil :ordinal nil}
```

The native symbol, the `+N` disambiguator, and the raw signature documentation
are **evidence only** and must never enter the canonical key. Reconstructing a
typed signature from occurrence layout is inference, and this plan forbids
guessing a canonical key; a wrong key is worse than an honest arity-only one.

Additional deliverables:

- A repo-managed **external** Java SCIP toolchain: pinned jars installed by a
  setup script into a gitignored directory, a committed minimal Java driver
  (`scip-semanticdb` ships no CLI entry point), and an adapter that invokes
  `javac` and `java` as external processes. The toolchain is never added to the
  semidx runtime classpath, mirroring ADR-047 and the Stage 3 TypeScript
  pattern, so no provider dependency can conflict with the runtime protobuf and
  gRPC stack.
- A same-arity ambiguity guard (below).

Exit criteria:

- No TypeScript-specific rule enters the shared SCIP adapter boundary.
- Java state-invariant facts preserve stable relation identity while gaining
  richer evidence.
- Provider absence and stale artifacts degrade cleanly.
- **Same-arity overloads never produce a false exact identity.** With every Java
  exact fact at `arity_only`, two genuinely distinct same-arity overloads share a
  core key and carry no typed signature to split on, so the arbitrator's
  common-case branch would merge them into one exact canonical fact with no
  diagnostic. This was reproduced on 2026-09-05 and is a hard requirement, not an
  observation. The Java adapter must:
  1. detect a group of two or more definition facts sharing
     `(language, path, owner, symbol, arity)` where every fact is `arity_only`;
  2. never emit a single exact canonical fact for that group;
  3. emit a diagnostic naming the ambiguous group; and
  4. withhold the exact contribution for that group so the lower tiers supply the
     units, rather than assert an exact identity it cannot justify.

  Non-ambiguous groups are unaffected and still merge with the heuristic tier on
  the core key.

Commit boundary: Java SCIP provider remains shadow/default-off.

### Stage 5. LSP Live Overlay

Goal: add exact evidence for live or dirty workspace content not represented by
the batch SCIP snapshot.

Deliverables:

- Narrow host-integrated LSP fact-source role; semidx does not implement an LSP
  server.
- Workspace-root, document-version, and content-digest validation.
- TypeScript live overlay first, then Java.
- Bounded requests, cancellation, timeout, and server-unavailable behavior.
- Merge tests for clean agreement, dirty LSP override, stale SCIP exclusion, and
  equal-authority conflict.

Exit criteria:

- LSP facts are accepted only for matching source content.
- Dirty-file LSP evidence affects only the intended overlay/snapshot scope.
- LSP timeout or crash cannot fail unrelated files or the whole index.
- Batch snapshots remain reproducible when live overlay mode is disabled.

Commit boundary: LSP remains opt-in and shadowed.

### Stage 6. Default Authority Switch And Truthful Degradation

Goal: make the reviewed provider plan authoritative for Java and TypeScript.

Deliverables:

- Provider planner becomes the default Java/TypeScript indexing path.
- Fresh SCIP/LSP facts receive exact authority per supported operation.
- Tree-sitter becomes the structural gap-filling tier.
- Regex-only output projects `parser_mode: fallback`, heuristic evidence, and
  explicit degradation.
- Workspace fingerprint includes provider plan, provider versions, relevant
  source identities, and authority-policy version.
- Capability/confidence recalibration based on selected fact evidence.
- Additive provider summary and degradation parity across public surfaces.

Exit criteria:

- Protected contract, retrieval, relation, impact, snapshot-diff, and storage
  gates pass.
- Intentional confidence reductions for fallback-only repositories are approved.
- New semantic improvements are supported by fixture/replay evidence.
- Comparative task-value evidence from the `plans/020` scorecard, optionally
  including `plans/019` one-shot adapters, shows that the authority switch does
  not regress required-file recall, normalized cost, output budget, or
  degraded-workspace availability.
- Forced provider overrides remain available for diagnosis and rollback.

Commit boundary: default switch and public additive contract changes.

### Stage 7. Compatibility Cleanup And Expansion Decision

Goal: remove temporary duplication only after the new path is stable.

Deliverables:

- Deprecation schedule for engine-specific parser options.
- Removal of shadow-only legacy branches after the agreed retention window.
- Decision whether another language justifies provider migration.
- Updated ADR-036 historical marker, ADR-046/ADR-047 cross-links, plans/007,
  runtime docs, MEMORY, onboarding docs, and capability documentation reflecting
  the accepted authority model.

Exit criteria:

- No public consumer depends on removed compatibility behavior.
- Rollback remains possible through a documented provider-policy override during
  the retention window.
- Further language expansion requires evidence, not symmetry.

Commit boundary: cleanup only; do not combine with a new language migration.

## Verification Gates

Every implementation stage must run the narrowest relevant tests first and then
the applicable repository gates. The complete migration gate includes:

- provider selection and arbitration unit tests;
- Java and TypeScript onboarding validation;
- contract validation;
- complete Clojure test suite;
- semantic-quality report;
- protected retrieval replay comparison;
- cross-strategy task-value comparison from `plans/020`, with `plans/019`
  one-shot adapters when available;
- relation projection and traversal parity;
- snapshot-diff parity;
- in-memory and PostgreSQL evidence round trips;
- forced provider unavailable, stale, timeout, and conflict cases;
- CCC freshness check and MEMORY update when runtime behavior changes.

No default switch is justified by tests that cover only provider happy paths.

## Rollback Strategy

- Stage 1-5 changes are additive and default-off or shadow-only.
- Each provider can be disabled independently by project/runtime policy.
- Stage 6 retains explicit tree-sitter and regex overrides for diagnosis and
  emergency rollback.
- Snapshot provider-policy versions prevent silent reuse across authority-policy
  changes.
- A rollback never relabels heuristic evidence as exact; it explicitly reports
  reduced coverage and confidence.

## Risks

### [High] LSP is treated as a reliable batch indexer

Why it matters: LSP servers are stateful, capability-variable, and optimized for
interactive operations rather than deterministic repository export.

Mitigation: use LSP primarily as a source-validated live overlay; require bounded
operation support and retain SCIP as the reproducible semantic batch source.

### [High] Multi-provider merging creates silent semantic conflicts

Why it matters: accepting whichever provider finishes first makes output
nondeterministic and can conceal ownership disagreements.

Mitigation: pure authority/freshness policy, stable merge ordering, explicit
ambiguity, and tests randomized by registration/completion order.

### [High] Stale semantic artifacts receive exact authority

Why it matters: precise but stale definitions or references are more dangerous
than an explicit structural fallback.

Mitigation: content/revision identity is a prerequisite for exact authority;
unknown identity degrades or excludes the evidence.

### [High] The provider seam becomes a plugin framework

Why it matters: lifecycle hooks, remote loading, and a broad SDK would delay the
first semantic slice and widen the attack/maintenance surface.

Mitigation: versioned data registry plus narrow role functions only; add a
protocol when the SCIP/LSP boundary proves a concrete substitution need.

### [Medium] Fact-level evidence expands snapshots significantly

Why it matters: duplicated provider locations and metadata increase memory,
serialization, and PostgreSQL costs.

Mitigation: measure Stage 3/4 snapshots, deduplicate source identities, bound
evidence records, and retain detailed provider-native payloads outside the
canonical snapshot.

### [Medium] Truthful regex degradation lowers apparent product capability

Why it matters: current users may observe lower confidence even when raw
retrieval selections remain similar.

Mitigation: expose the reason and recommended provider setup; approve the
confidence change through replay and contract review instead of masking it.

## Independent Review Brief

The reviewing agent should treat ADR-046 as the accepted decision of record and
this plan as the approved migration direction. Challenge implementation
readiness, hidden assumptions, and unsafe sequencing rather than reopening the
authority decision by default. Review against current code, accepted ADRs, and
active plans, and report findings by severity.

Required review questions:

1. Is per-operation authority necessary, or can a simpler whole-file provider
   model meet the same correctness requirements?
2. Are SCIP and LSP correctly treated as peers whose precedence depends on
   freshness and workspace mode?
3. Is the source-identity rule sufficient to prevent stale exact facts?
4. Does the proposed `CanonicalFactKey` normalize current unit and ADR-039
   relation identities across provider spellings without collapsing distinct
   Java overloads, TypeScript re-exports, or dispatch-sensitive facts?
5. Does `FactEvidence` need a public contract immediately, or should it remain
   internal through the shadow stages?
6. Does the LSP live-overlay scope avoid turning semidx into an LSP lifecycle
   manager or nondeterministic batch indexer?
7. Are the Stage 0-6 gates sufficient to catch retrieval, confidence, storage,
   and incremental-index regressions?
8. Should TypeScript remain the first SCIP slice, or does concrete Java toolchain
   evidence justify reversing the order?
9. Verify that ADR-036 supersession, ADR-047 toolchain retention, and the
   plans/007 amendment leave no active conflicting parser-authority rule.
10. Identify any simpler design that preserves exact semantic authority,
    deterministic fallback, and explicit degradation with less machinery.
11. Verify that provider ids or provider-native payloads do not leak into the
    one-shot orchestration and rendering boundaries defined by `plans/019`.

Expected review output:

- findings ordered by severity with file/line evidence;
- implementation-readiness recommendation: proceed, proceed after named
  revisions, or block pending a named decision;
- unresolved decisions that require the project owner;
- smallest revision set needed before implementation;
- verification performed and limitations.

## Execution Admission

Stage 1 source implementation may begin only when:

- independent review findings are recorded and resolved or explicitly deferred;
- ADR-046 and ADR-047 are accepted;
- ADR-036 supersession and the plans/007 amendment are documented;
- Stage 0 baseline scope and ownership are approved;
- the provider-neutral `CanonicalFactKey` contract and cross-provider identity
  fixtures are approved;
- the project owner confirms whether TypeScript remains the first vertical slice.
