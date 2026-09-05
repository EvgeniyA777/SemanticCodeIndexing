---
title: "semidx Extension Architecture Resolution Plan"
doc_type: "architecture_plan"
lifecycle: "archived"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-27"
---

# Semidx Extension Architecture Resolution Plan

Status: Archived historical architecture bridge

## Closure Note

This document is no longer an active execution queue. It resolved the extension
architecture boundaries that later work now owns through more specific plans
and ADRs.

Current continuation owners:

- `plans/018_semantic_provider_authority_migration_plan.md` owns provider
  authority, canonical fact identity, evidence, arbitration, and SCIP/LSP
  migration.
- `plans/019_llm_one_shot_context_delivery_and_evaluation_plan.md` owns
  additive one-shot context delivery.
- `plans/020_retrieval_value_benchmark_harness_plan.md` owns comparative
  value measurement and is the current highest-priority execution substrate.
- `ideas/015_semidx_feature_inventory_for_prioritization.md` records the
  feature inventory that will feed a new plan after owner prioritization.

Do not execute this plan directly. Use it only as historical context for why the
provider, lifecycle, typed-relation, and degradation boundaries exist.

## Goal

Resolve the structural gaps identified in:

- [Original `semidx` extension work plan](../notes/2026-06-09-1015-95e50b0e-5dfa-4033-bc2b-db6db47ffda4.md);
- [SOLID review of the `semidx` extension plan](../notes/2026-06-09-solid-architecture-review.md).

This document defines the architecture that later implementation plans must
follow. It answers the open questions around:

- index freshness and change detection;
- file-state persistence;
- provider registration and arbitration;
- capability ownership;
- typed-relation compatibility;
- degraded-mode reporting;
- corrected implementation sequencing.

The plan is intentionally narrower than the full product roadmap. It establishes
the boundaries required to implement the roadmap without creating temporary
hardcoded paths or parallel semantic models.

## Scope

This plan covers:

- the target module boundaries;
- client-owned contracts;
- dependency direction;
- authoritative freshness semantics;
- deterministic provider selection;
- additive typed-relation migration;
- public capability and degradation reporting;
- implementation order and acceptance gates.

This plan does not cover:

- detailed Markdown or YAML extraction rules;
- AutoParts-specific reference policies;
- Kotlin, Swift, or JetBrains implementation details; Java/TypeScript LSP and
  SCIP authority are defined by ADR-046 and plans/018.
- user-facing editing, rename, or refactoring operations;
- embedding model selection.

## Assumptions

- Existing public MCP, HTTP, gRPC, and library contracts remain backward
  compatible.
- Semantic IR remains the normalized extraction model; typed relation facts and
  their snapshot indexes are the canonical semantic graph for new graph
  semantics under ADR-038.
- Existing `IndexStorage` implementations remain usable without a breaking
  protocol migration.
- Existing `update-index` remains the first incremental update primitive.
- Correctness has priority over avoiding a filesystem scan.
- Optimization mechanisms may reduce work, but may not silently weaken
  freshness guarantees.
- The first provider-registry version is an in-process Clojure data registry,
  not a plugin framework or remote extension marketplace.
- ADR-046 amends the V1 one-primary-provider limitation for Java and TypeScript:
  they use bounded, capability-specific multi-provider plans. A single primary
  provider remains sufficient for unrelated simple document-provider cases.

## Change Model

The architecture must keep the following expected changes local:

| Expected change | Owning boundary |
|---|---|
| Git, filesystem, watcher, or CI change source | Workspace state source |
| Freshness and rebuild thresholds | Freshness policy |
| Index lifecycle orchestration | Index lifecycle coordinator |
| New file format or parser | Provider registry and file indexer |
| Provider-selection precedence | Provider selection policy |
| New semantic operation | Operation capability profile |
| New relation kind | Typed relation model and relation indexes |
| New retrieval behavior | Relation projections and retrieval |
| New transport field | MCP/HTTP/gRPC presentation adapters |

No transport handler should own any of these policies.

## Architecture Decisions

### Decision 1. Freshness Lives Below MCP

`mcp.core/tool-create-index` remains a transport adapter. It must not inspect
files, call Git, compare manifests, or decide whether an index is reusable.

Freshness belongs to an index lifecycle coordinator used by all entry surfaces:

```text
MCP / HTTP / gRPC / Library
             |
             v
    Index Lifecycle Coordinator
       |        |         |
       v        v         v
 Workspace   Freshness   Index Build /
 State       Policy      Update
 Source
```

This preserves dependency inversion: transports depend on lifecycle policy,
while Git and filesystem details plug into a policy-owned seam.

### Decision 2. Snapshot Workspace Manifest Is The Freshness Authority

Every newly built snapshot stores an additive `workspace_state` value in its
existing payload:

```text
WorkspaceState {
  schema_version
  root_path
  discovery_profile_hash
  provider_registry_version
  semantic_pipeline_version
  files: [
    {
      path
      content_digest
      size_bytes
      modified_at?
      provider_id
      provider_version
      classification
    }
  ]
  workspace_fingerprint
}
```

`workspace_fingerprint` is a digest of the canonical, path-sorted manifest and
all configuration or implementation versions that can change index output.

The authoritative freshness signal is content-addressed:

- file content digest proves file identity;
- provider and pipeline versions prove semantic-processing identity;
- discovery-profile hash proves inclusion-policy identity.

`modified_at` and `size_bytes` are acceleration hints only. They may identify
files that definitely need rehashing, but they are never sufficient evidence
for a correctness-sensitive cache hit.

This follows the content-addressed cache model used by build systems such as
Bazel: cache reuse is valid only when the inputs represented by the action key
are the same.

### Decision 3. Existing Snapshot Payload Carries Workspace State

The first implementation does not add a separate state database or widen the
`IndexStorage` protocol.

Reasons:

- storage already persists the full index payload;
- in-memory and Postgres storage automatically retain additive payload fields;
- the workspace manifest must be versioned atomically with the semantic index;
- a separate state store would create a consistency problem between two sources
  of truth.

Behavior by mode:

- in-memory mode can reuse state only while the process remains alive;
- after an in-memory process restart, a full build is expected and honest;
- persistent storage can load a prior manifest and validate it against the
  current workspace;
- a missing or incompatible manifest forces a full rebuild.

### Decision 4. Change Detection Has One Narrow Contract

The lifecycle coordinator owns a client-defined `WorkspaceStateSource` role:

```text
WorkspaceStateSource {
  capture(root_path, discovery_profile, provider_catalog)
    -> WorkspaceState
}
```

The contract deliberately does not include:

- index building;
- cache lookup;
- update thresholds;
- snapshot publication;
- provider execution.

Manifest comparison is a pure function:

```text
diff_workspace_state(previous, current)
  -> {
       added_paths
       changed_paths
       deleted_paths
       unchanged_paths
       compatibility_changes
     }
```

The initial implementation is a filesystem content-manifest source. Git-aware
and watcher-backed sources may later accelerate candidate discovery, but they
must produce the same `WorkspaceState` contract.

Git porcelain output, untracked-file listing, index stat checks, untracked
cache, and filesystem monitors are useful accelerators. They are not the
semantic-index source of truth.

### Decision 5. Freshness Policy Is Pure And Explicit

The lifecycle coordinator delegates the reuse decision to a pure policy:

```text
decide_freshness(previous_snapshot, current_workspace_state, options)
  -> {
       action: reuse | incremental_update | full_rebuild
       reason
       changed_paths
       deleted_paths
       diagnostics
     }
```

Rules:

1. Reuse only when workspace fingerprints and relevant request options match.
2. Incrementally update when the manifest schema is compatible and the delta is
   below the configured threshold.
3. Fully rebuild when provider versions, pipeline versions, discovery policy, or
   manifest schema are incompatible.
4. Fully rebuild when the change ratio exceeds the configured threshold.
5. Never convert an ambiguous or failed freshness check into a cache hit.

The first threshold is configuration, not a protocol abstraction. Introduce a
pluggable policy only if a second real policy appears.

### Decision 6. Snapshot Publication Is Atomic At The Lifecycle Boundary

For each project scope, the lifecycle coordinator:

1. captures current workspace state;
2. validates or updates the prior snapshot;
3. builds a candidate snapshot separately;
4. validates candidate integrity;
5. persists the candidate transactionally when storage is configured;
6. atomically replaces the active registry entry.

Readers continue using the last published snapshot until step 6.

The existing project-registry refresh lock is the natural starting point for
single-writer behavior. MCP must not introduce a second independent lock.

### Decision 7. Provider Registry Appears Before Markdown And YAML

The provider registry moves before all new document vertical slices.

Markdown and YAML must be the first consumers of the stable provider-selection
path, not temporary additions to the existing hardcoded dispatch.

To avoid a framework-sized first implementation, the initial registry contains:

- one `legacy-code` provider wrapping existing language dispatch;
- one `text-fallback` provider;
- later, one `markdown` provider;
- later, one `yaml` provider.

This proves the extension seam with real variation while avoiding migration of
every existing parser before the contract is validated.

### Decision 8. Provider Responsibilities Are Split By Client

There is no wide `SemanticProvider` interface containing detection, execution,
capabilities, health, and quality.

The registry stores separate role-specific values:

```text
ProviderDescriptor {
  provider_id
  provider_version
  selectors
  selection_priority
  support_level
}

FileIndexer {
  index(file_input, index_context) -> ParsedFile
}

OperationCapabilityProfile {
  operations: {
    document_symbols: exact | structural | heuristic | none
    definitions: exact | structural | heuristic | none
    references: exact | structural | heuristic | none
    implementations: exact | structural | heuristic | none
    call_hierarchy: exact | structural | heuristic | none
    diagnostics: exact | structural | heuristic | none
  }
}

ProviderRuntimeStatus {
  state: ready | degraded | unavailable
  reason_codes
  observed_at
}
```

Ownership:

- selection policy consumes `ProviderDescriptor`;
- indexing orchestration consumes `FileIndexer`;
- retrieval consumes `OperationCapabilityProfile`;
- lifecycle and presentation consume `ProviderRuntimeStatus`;
- semantic facts carry their own evidence quality and provenance.

This follows the interface-segregation pattern also visible in LSP, where
capabilities are advertised per operation instead of treating all servers as
equivalent.

### Decision 9. Provider Planning Uses A Dedicated Deterministic Policy

Provider selection is a pure policy owned outside the orchestrator:

```text
plan_providers(file_metadata, requested_operations, provider_descriptors,
               runtime_status, source_identity, project_overrides)
  -> ProviderPlan | no_provider | ambiguous_selection
```

For simple document providers, one primary provider per file remains the
default. For Java and TypeScript, ADR-046 requires a bounded plan per operation:
fresh SCIP/LSP semantic evidence, tree-sitter structural gap filling, and regex
only as degraded fallback. The planner, not a provider implementation, decides
which providers are eligible and when lower-tier gap filling is needed.

Selection precedence:

1. explicit project override;
2. exact path or filename selector;
3. exact extension or declared language selector;
4. content-signature selector;
5. generic text fallback.

Within the same selector class:

1. higher selector specificity wins;
2. higher configured priority wins;
3. a non-fallback provider always beats fallback;
4. an unresolved top-level tie produces `ambiguous_provider_selection`.

An unresolved equal-authority tie does not silently select by registration order
or provider identifier. The affected operation is reported as ambiguous; the
rest of the repository may still be indexed through eligible non-conflicting
providers.

This borrows the useful part of VS Code's document-selector model: providers
declare applicability, selectors receive specificity scores, and selection is
performed outside provider implementations.

### Decision 10. Provider Registry Is Data First

The initial provider registry is a versioned Clojure map containing descriptors,
index functions, capability profiles, and status functions.

Do not introduce:

- runtime class loading;
- remote provider installation;
- lifecycle hooks for arbitrary plugins;
- a general plugin SDK.

A protocol may be introduced later for an actual external-provider boundary,
but the in-process registry does not need one merely to dispatch functions.

### Decision 11. Typed Relations Are Canonical For New Graph Semantics And Shadow Legacy Migration

Typed relations are the canonical graph boundary for new graph semantics. They
do not immediately replace legacy `calls`, `imports`, callers indexes, or
callees indexes; those migrate through the shadow and parity gates below.

Migration stages:

1. Add the relation schema and relation indexes.
2. Dual-write relations derived from existing calls and imports.
3. Build relation-derived caller/callee projections in shadow mode.
4. Compare old and new projections on golden fixtures and real replay cases.
5. Switch individual consumers only after parity is proven.
6. Retain compatibility fields until all public consumers are migrated.

Initial relation shape:

```text
Relation {
  relation_id
  source_unit_id
  target_key
  target_unit_ids
  relation_type
  resolution_status
  evidence_quality
  provenance
  evidence_location
}
```

`evidence_quality` is fact-level quality. It must not be inferred solely from
the provider's global capability profile.

### Decision 12. Compatibility Is Defined By Observable Behavior

"Calls and imports remain compatible projections" means:

- existing callers and callees for protected fixtures are unchanged;
- existing `resolve_context` authority selections remain unchanged for
  protected queries;
- existing `impact_analysis` paths and related tests remain unchanged unless an
  approved improvement is recorded;
- snapshot diffs classify the same protected changes;
- confidence does not increase merely because facts moved into the relation
  model.

Compatibility is proven through golden contract tests, not only through unit
tests of relation conversion.

Required gates:

- old-index versus relation-projection graph comparison;
- protected retrieval replay comparison;
- snapshot-diff comparison;
- fallback and degraded-mode comparison;
- explicit approval for intentional semantic changes.

### Decision 13. Degraded Mode Is Additive In Public Responses

Existing fields such as `confidence_ceiling`, `coverage_level`, and
`fallback_unit_count` remain.

Additive public summaries:

```text
provider_summary {
  selected_provider_counts
  degraded_provider_counts
  unavailable_provider_counts
  ambiguous_selection_count
}

operation_capabilities {
  definitions
  references
  implementations
  call_hierarchy
  diagnostics
}

degradations [
  {
    code
    provider_id?
    operation?
    affected_paths?
    effect
    recommended_action?
  }
]
```

Placement:

- `create_index` and `repo_map` report repository-level provider summary and
  degradations;
- retrieval responses report operation capabilities and degradations for the
  selected evidence;
- transports serialize the same runtime-owned data and do not derive their own
  interpretations.

Unknown additive fields must remain safe for older clients. This follows the
capability-advertisement model used by LSP and the existing additive contract
style already used by `semidx`.

## Boundaries

### 1. Workspace State Source

Responsibility:

- capture an authoritative content-addressed workspace manifest.

Knows about:

- filesystem access;
- discovery profile;
- provider-selection inputs needed for manifest identity.

Does not know about:

- MCP or other transports;
- snapshot storage implementation;
- update thresholds;
- index construction.

Primary expected location:

- new `src/semidx/runtime/workspace_state.clj`.

### 2. Freshness Policy

Responsibility:

- compare prior and current state and decide reuse, incremental update, or full
  rebuild.

Knows about:

- workspace-state schemas;
- compatibility versions;
- configured rebuild threshold.

Does not know about:

- filesystem or Git commands;
- transport requests;
- storage implementation;
- parser details.

Primary expected location:

- new `src/semidx/runtime/freshness.clj`.

### 3. Index Lifecycle Coordinator

Responsibility:

- orchestrate capture, reuse validation, build/update, validation, persistence,
  and atomic publication.

Knows about:

- workspace-state source;
- freshness policy;
- index build/update functions;
- storage port;
- project registry publication.

Does not know about:

- Git command details;
- format-specific parsing;
- MCP response formatting.

Primary expected location:

- new `src/semidx/runtime/index_lifecycle.clj`, or a narrowly extracted
  lifecycle section from `runtime/index.clj`.

### 4. Provider Catalog

Responsibility:

- own the versioned collection of available provider descriptors and role
  implementations.

Knows about:

- provider identifiers and versions;
- selectors;
- index functions;
- static operation capability profiles;
- runtime-status probes.

Does not know about:

- repository traversal;
- provider-selection policy;
- snapshot storage;
- retrieval ranking.

Primary expected location:

- new `src/semidx/runtime/providers.clj`.

### 5. Provider Selection Policy

Responsibility:

- produce a bounded ProviderPlan or an explicit selection diagnostic.

Knows about:

- file metadata;
- provider descriptors;
- project overrides;
- selector scoring.

Does not know about:

- provider execution internals;
- Semantic IR details;
- retrieval;
- transports.

Primary expected location:

- new `src/semidx/runtime/provider_selection.clj`.

### 6. Semantic IR Relations

Responsibility:

- define normalized relation facts and compatibility projections.

Knows about:

- units;
- relation schema;
- provenance and evidence quality;
- relation indexes.

Does not know about:

- provider-selection rules;
- transport schemas;
- workspace freshness.

Primary expected locations:

- `src/semidx/runtime/semantic_ir.clj`;
- new `src/semidx/runtime/relations.clj`;
- `src/semidx/runtime/index.clj`.

### 7. Capability Projection

Responsibility:

- aggregate provider capabilities, runtime status, selected evidence, and
  degradations into runtime-owned summaries.

Knows about:

- operation capability profiles;
- selected facts and providers;
- provider runtime status;
- existing confidence policy.

Does not know about:

- MCP JSON schemas;
- provider execution details;
- filesystem change detection.

Primary expected location:

- new `src/semidx/runtime/capabilities.clj`;
- integration with `runtime/retrieval_policy.clj`.

### 8. Transport Presentation

Responsibility:

- validate requests and serialize runtime results.

Knows about:

- public request and response schemas;
- runtime lifecycle and retrieval entry points.

Does not know about:

- workspace state capture;
- provider arbitration;
- relation resolution;
- confidence derivation.

Primary expected locations:

- `src/semidx/mcp/core.clj`;
- `src/semidx/runtime/http.clj`;
- `src/semidx/runtime/grpc.clj`.

## Dependency Direction

```text
Transport Adapters
       |
       v
Index Lifecycle Coordinator --------> Retrieval
       |                                  |
       v                                  v
Freshness Policy                 Capability Projection
       |                                  |
       v                                  v
Workspace State Contract          Semantic IR + Relations
       ^                                  ^
       |                                  |
Filesystem / Git Adapters          Provider Indexers
                                          ^
                                          |
                              Provider Selection Policy
                                          ^
                                          |
                                   Provider Catalog
```

Rules:

- transports depend on lifecycle and retrieval entry points;
- lifecycle depends on client-owned state and storage contracts;
- provider details plug into catalog and file-indexer roles;
- retrieval depends on normalized facts, never provider implementations;
- storage persists snapshots but does not decide freshness;
- project registry publishes active snapshots but does not inspect files.

## Corrected Implementation Sequence

### Stage 0. Contract Baselines

Goal:

- establish observable compatibility before changing lifecycle or relations.

Deliverables:

- protected create/reuse/update lifecycle tests;
- protected caller/callee graph fixtures;
- protected retrieval replay queries;
- protected snapshot-diff cases;
- documented current MCP degradation fields.

Exit criteria:

- baseline artifacts can compare current and candidate behavior deterministically.

### Stage 1. Workspace State And Freshness

Goal:

- eliminate stale cache hits across library and transport surfaces.

Deliverables:

- `WorkspaceStateSource`;
- content-addressed workspace manifest;
- pure workspace-state diff;
- pure freshness decision;
- manifest embedded in snapshot payload;
- lifecycle coordinator used by `create_index`;
- additive lifecycle outcomes and diagnostics.

Exit criteria:

- unchanged repositories reuse the snapshot;
- changed, added, and deleted files cannot produce a stale cache hit;
- in-memory restart behavior is explicit;
- persisted snapshots are validated before reuse;
- incremental results match full rebuild results.

### Stage 2. Minimal Provider Catalog And Arbitration

Goal:

- establish the extension seam before adding document formats.

Deliverables:

- versioned provider catalog;
- provider descriptors;
- deterministic provider-selection policy;
- `legacy-code` provider wrapping current dispatch;
- `text-fallback` provider;
- ambiguity diagnostics;
- static operation capability profiles;
- provider runtime status.

Exit criteria:

- existing supported languages index through the catalog without observable
  behavior changes;
- fallback cannot outrank a specific provider;
- unresolved ties are reported explicitly;
- provider selection is independently testable.
- Java/TypeScript multi-provider plans preserve deterministic authority and
  explicit degradation under ADR-046.

### Stage 3. Discovery Separation

Goal:

- allow structured and fallback-only repositories.

Deliverables:

- repository discovery independent from supported-language detection;
- text/binary and safety classification;
- format/provider-selection inputs;
- provider coverage and exclusion summaries;
- structured-only and fallback-only index lifecycle.

Exit criteria:

- Markdown-only and safe unknown-text repositories reach provider selection;
- binary and oversized files are excluded with explicit reasons;
- language activation remains compatible for code providers.

### Stage 4. Legacy Graph Migration Into Canonical Typed Relations

Goal:

- migrate legacy call/import graph projections without changing observable
  graph behavior; the typed-relation substrate for new facts already exists.

Deliverables:

- existing relation schema and forward/reverse indexes as the migration target;
- call/import dual-write;
- relation-derived caller/callee shadow projections;
- compatibility comparison reports.

Exit criteria:

- protected graph and retrieval cases remain equivalent;
- intentional differences require explicit approval;
- confidence does not inflate.

### Stage 5. Markdown Vertical Slice

Goal:

- prove structured-document indexing through the stable provider and relation
  paths.

Required path:

```text
Discovery
→ Provider Selection
→ Markdown FileIndexer
→ Provider-Normalized Units And Typed Relations
→ Retrieval And Capability Projection
```

Markdown-specific rules must not enter lifecycle, selection, or transport code.

### Stage 6. YAML Vertical Slice

Goal:

- prove configurable business-contract relations through the same boundaries.

YAML-specific extraction stays in the YAML indexer. AutoParts-specific meaning
stays in a separate reference policy.

### Stage 7. Incremental Relation Resolution

Goal:

- use reverse relation indexes and semantic-key deltas to avoid global relation
  resolution.

This stage starts only after relation parity and document-provider behavior are
stable.

### Stage 8. Provider Expansion And Project Model

Goal:

- add Kotlin, Gradle Kotlin DSL, project structure, and external providers
  without changing the stable provider-selection and relation contracts.

Java/TypeScript SCIP and LSP providers are sequenced by plans/018. Future LSP
and JetBrains providers must adapt their operation capabilities and runtime
status into the same runtime-owned schemas without changing provider planning.

## Contract Tests

### Freshness Contract

- same content and configuration produces `reuse`;
- modified content produces `incremental_update` or `full_rebuild`, never
  `reuse`;
- added and deleted files are detected;
- provider or pipeline version changes force rebuild;
- incompatible manifest schema forces rebuild;
- failed state capture never produces a cache hit.

### Provider Selection Contract

- exact filename selector beats extension selector;
- extension selector beats generic fallback;
- explicit override beats automatic selection;
- fallback is selected only when no stronger provider is eligible;
- tied primary providers produce `ambiguous_provider_selection`;
- registration order does not affect selection.
- Java/TypeScript lower-authority providers cannot overwrite fresh exact facts;
  equal-authority conflicts remain observable.

### Provider Substitution Contract

Every file indexer must:

- return a valid normalized parsed-file shape;
- attach provider identity and version;
- report degraded or failed behavior explicitly;
- avoid throwing format-specific exceptions across the registry boundary;
- preserve stable line ranges and diagnostics semantics.

### Relation Compatibility Contract

- relation-derived callers equal protected current callers;
- relation-derived callees equal protected current callees;
- protected `resolve_context` selections remain stable;
- protected `impact_analysis` and snapshot diff remain stable;
- fallback relations do not become exact relations;
- unresolved and ambiguous relations remain observable.

### Public Degradation Contract

- repository-level responses expose provider summary;
- selected retrieval evidence exposes operation capabilities;
- degraded and unavailable providers produce reason-coded degradations;
- old response fields remain present and semantically unchanged;
- transports serialize identical runtime-owned degradation data.

## Rejected Alternatives

### Put Change Detection In MCP

Rejected because it duplicates policy across transports and couples MCP to
filesystem or Git details.

### Use Git Commit As The Sole Freshness Key

Rejected because commit identity does not include dirty-worktree or untracked
content, and non-Git repositories must remain supported.

### Trust `mtime + size` As Authoritative

Rejected because unchanged metadata does not prove unchanged content.

### Add A Separate Workspace-State Database

Rejected for the first implementation because it creates a second consistency
domain beside the snapshot payload.

### Let Providers Choose Themselves

Rejected because providers cannot fairly arbitrate conflicts with peers and
would leak selection policy into implementations.

### Resolve Provider Ties By Registration Order

Rejected because behavior would depend on incidental initialization order.

### Add Markdown And YAML Before The Registry

Rejected because both formats would become temporary hardcoded exceptions and
increase the later registry migration blast radius.

### Replace Calls And Imports Immediately

Rejected because it would break substitutability and make semantic regressions
hard to detect.

### Build A General Plugin Framework Now

Rejected because the first required variation is in-process provider selection,
not arbitrary external plugin lifecycle.

## Risks And Mitigations

### [High] Content Manifest Capture Is Expensive

Why it matters:

- hashing every included file adds work before reuse can be confirmed.

Mitigation:

- correctness-first content digests in Stage 1;
- retain size and modification metadata for measured optimization;
- later add Git or filesystem-monitor acceleration behind
  `WorkspaceStateSource`;
- require equivalence tests before allowing an accelerator to skip hashing.

### [High] Lifecycle Extraction Creates A New God Coordinator

Why it matters:

- freshness, building, storage, registry publication, and diagnostics could
  accumulate in one large namespace.

Mitigation:

- coordinator owns sequence only;
- freshness decision remains pure;
- workspace capture remains behind its narrow contract;
- index build/update remains in existing index code;
- publication remains in project registry.

### [High] Provider Catalog Becomes A Plugin Framework

Why it matters:

- speculative lifecycle and remote-loading features would slow document
  vertical slices.

Mitigation:

- data registry only;
- bounded provider plans with one primary only where no multi-provider semantic
  authority is required;
- no external loading before a concrete provider requires it.

### [Medium] Relation Dual-Write Diverges

Why it matters:

- old and new graphs may disagree silently.

Mitigation:

- shadow comparison reports;
- protected golden queries;
- no consumer switch before parity;
- explicit approval for intentional differences.

### [Medium] Capability Summaries Become A Second Confidence System

Why it matters:

- operation capabilities and existing confidence ceilings could contradict each
  other.

Mitigation:

- operation capability is provider ability;
- evidence quality is fact-level provenance;
- confidence remains retrieval's decision;
- capability projection may lower confidence but never raise it automatically.

## Industry Practice Basis

The decisions above adapt established patterns rather than copying one external
system wholesale:

- Git uses stat information, untracked caches, and filesystem monitors to
  accelerate working-tree checks, while retaining explicit tracked/untracked
  state and content identities:
  - <https://git-scm.com/docs/git-update-index>
  - <https://git-scm.com/docs/git-status>
  - <https://git-scm.com/docs/git-ls-files>
- Bazel separates an action cache from a content-addressable store and keys
  reusable results by hashes representing inputs:
  - <https://bazel.build/remote/caching>
- LSP advertises capabilities per operation and supports capability-aware
  clients rather than assuming equivalent language servers:
  - <https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/>
- VS Code uses document selectors and external matching policy to determine
  provider applicability; some operations select the best-scoring provider,
  while others explicitly merge provider results:
  - <https://code.visualstudio.com/api/references/vscode-api#DocumentSelector>

For simple document-provider cases, `semidx` intentionally retains one primary
indexing provider per file. ADR-046 and plans/018 introduce the first justified
exception: bounded Java/TypeScript semantic-provider reconciliation with stable
fact identity, source-identity checks, and explicit conflict diagnostics.

## Historical First Boundary And Current Continuations

The original first executable boundary covered only Stages 0 and 1: contract
baselines, workspace state, freshness policy, lifecycle coordination, snapshot
payload persistence, stale-cache prevention, atomic publication, tests, and
public lifecycle diagnostics. That boundary was executed by
[`plans/008`](./008_stage_0_1_workspace_freshness.md) and is historical; agents
must not treat it as the next work queue.

Current continuations are owned by narrower active plans:

- [`plans/018`](./018_semantic_provider_authority_migration_plan.md) owns the
  provider catalog/planning, fact evidence/arbitration, and Java/TypeScript
  semantic-authority migration. Its provider-neutral `CanonicalFactKey` must be
  established before multi-provider arbitration.
- [`plans/019`](./019_llm_one_shot_context_delivery_and_evaluation_plan.md) owns
  additive one-shot delivery and its strategy adapters.
- [`plans/020`](./020_retrieval_value_benchmark_harness_plan.md) owns the shared
  real-repository corpus, benchmark-run identity, cross-strategy harness, usage
  normalization, price schedules, and comparative aggregation.
- Markdown/YAML discovery and vertical slices remain later stages of this plan;
  they require dedicated executable plans after the provider seam is stable.

The typed-relation substrate for new graph semantics is delivered, while legacy
call/import compatibility migration remains governed by the Stage 4 parity gate.

## Closure Criteria And Lifecycle Transition

This document remains `active` only while it is the current architecture
reference for an unresolved scope. It is never an executable work queue. Mark it
`completed` only when **all** of the following closure gates have recorded
evidence:

1. **Continuation ownership is terminal or independently transferred.** Each
   current continuation (`plans/018`, `plans/019`, `plans/020`) is either
   `completed`, `cancelled`, or `superseded`, or an explicitly named active
   successor repeats the boundaries, dependency direction, and admission gates
   it needs without relying on this plan as its sole implementation source.
2. **Freshness and lifecycle foundation is closed.** The Stage 0/1 freshness
   boundary remains delivered with deterministic stale-cache prevention and
   atomic publication, and any remaining lifecycle compatibility exception is
   either resolved or accepted in an ADR with a named owner and review trigger.
3. **Provider-catalog scope is disposed.** Markdown and YAML are introduced by
   dedicated provider-catalog plans with verified degradation behavior, or an
   accepted ADR explicitly cancels or defers each scope and defines the condition
   that reopens it. Java/TypeScript semantic-provider authority is likewise
   closed by `plans/018` or an equivalent successor.
4. **Relation migration has a terminal parity decision.** Typed relation
   identities, storage projection, traversal, retrieval/impact projection, and
   legacy call/import compatibility have either met their parity gates before
   replacement, or an accepted ADR has retained the compatibility projection
   with an explicit reason and reassessment trigger.
5. **Public architecture boundaries are proven.** Current library, MCP, HTTP,
   and gRPC surfaces keep workspace state, provider selection, relation policy,
   capability reporting, and degradation policy in runtime-owned boundaries;
   applicable contract, replay, and transport-parity verification is recorded.
6. **Documentation handoff is complete.** No active plan or ADR treats this
   document as its only current source of implementation direction. The closure
   progress record links the terminal successor/ADR decisions, verification
   results, exceptions, and closing commit.

A mere link to a future plan does not satisfy a gate: the successor must be
active and self-contained for its remaining scope, or its terminal disposition
must be recorded. If any gate is open, retain `lifecycle: active`,
`status: in_progress`, and `agent_action: reference_for_context`.

When every gate closes, make this lifecycle transition in the same commit:

```yaml
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
```

Also relabel the "Current Continuations" section as historical, update
`MEMORY.md`, and create or update the companion progress log under `reports/`.
Future implementation must then rely on the named active successor plans and
accepted ADRs rather than reopening this umbrella plan.
