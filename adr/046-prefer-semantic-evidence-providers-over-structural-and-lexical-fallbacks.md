---
file_type: adr
decision_id: ADR-046
title: Prefer Semantic Evidence Providers Over Structural And Lexical Fallbacks
status: accepted
date: 2026-08-02
deciders:
  - project owner
tags:
  - architecture
  - providers
  - scip
  - lsp
  - tree-sitter
  - parsing
summary: Prefer fresh compiler-grade semantic evidence from SCIP or LSP, use tree-sitter for structural gap filling, and restrict regex extraction to an explicitly degraded last-resort tier.
agent_summary: This ADR is the decision of record for Java and TypeScript evidence authority. Fresh SCIP/LSP evidence is primary per operation, tree-sitter fills structural gaps, and regex is an explicitly degraded fallback. Execute the staged migration in plans/018; use ADR-047 for managed tree-sitter toolchain resolution.
supersedes:
  - ADR-036
superseded_by: null
links:
  - ADR-004
  - ADR-028
  - ADR-036
  - ADR-039
  - ADR-047
  - plans/007
  - plans/018
---

# ADR-046: Prefer Semantic Evidence Providers Over Structural And Lexical Fallbacks

**Status**: Accepted
**Date**: 2026-08-02
**Deciders**: project owner

---

## Context

The accepted parser-adapter contract in ADR-004 requires a primary extractor,
optional enrichers, explicit provenance, and a lexical fallback that does not
pretend to be full semantic extraction. The current Java and TypeScript lanes
invert that intent: regex is the guaranteed default, tree-sitter is opt-in, and
regex-produced units are generally marked `parser_mode: full`.

ADR-028 already records that Java and TypeScript have declaration and call
surfaces that are brittle under regex-only parsing. ADR-036 later made regex the
guaranteed default for operational portability and treated tree-sitter as
optional acceleration. That solved toolchain availability but did not solve the
semantic-authority problem.

The canonical typed-relation graph now separates stable relation identity from
mutable resolution and evidence under ADR-039. The graph can therefore accept
additional SCIP, LSP, compiler, or structural evidence without minting parallel
semantic edges. The active provider architecture in plans/007 also defines
provider descriptors, per-operation capabilities, runtime status, and explicit
degradation, but its first version selects exactly one provider per file and
postpones multi-provider enrichment.

The repository needs a durable rule for source authority before SCIP or LSP
integration begins. A global parser switch is insufficient because SCIP, LSP,
tree-sitter, and regex expose different operations, freshness properties, and
failure modes.

## Decision Drivers

- Semantic facts must come from the strongest fresh evidence available for the
  requested operation.
- Dirty live workspaces and reproducible batch snapshots need different semantic
  sources without creating separate graphs.
- External provider absence, staleness, or failure must degrade explicitly and
  must not make basic indexing unavailable.
- Lower-authority evidence must be able to fill gaps without overwriting stronger
  facts.
- Every accepted fact must retain provider identity, version, source identity,
  authority, and evidence location where available.
- Snapshot construction must remain deterministic for the same source content,
  provider versions, configuration, and provider inputs.
- Existing library, MCP, HTTP, gRPC, storage, and retrieval contracts must evolve
  additively until an explicit compatibility migration is approved.

## Considered Options

### Option 1. Keep regex as the guaranteed default

Preserve ADR-036: regex remains the normal Java and TypeScript extraction path,
while tree-sitter, SCIP, and LSP remain optional accelerators or enrichers.

### Option 2. Use one global provider chain per file

Short-circuit the entire file through `SCIP -> LSP -> tree-sitter -> regex` and
accept the first provider that returns a non-empty result.

### Option 3. Use per-operation semantic authority with lower-tier gap filling

Treat fresh SCIP and LSP facts as the semantic-authority tier, tree-sitter facts
as the structural tier, and regex facts as the heuristic fallback tier. Plan and
merge provider execution per operation and fact identity rather than selecting
one winner for the entire file.

## Decision

We accept Option 3: per-operation semantic authority with lower-tier gap
filling.

### Authority ladder

Provider authority is determined per operation and per fact:

1. `exact`: fresh SCIP, LSP, compiler, or equivalent language-semantic evidence;
2. `structural`: tree-sitter or another syntax-tree provider;
3. `heuristic`: regex or bounded lexical/state-machine extraction;
4. `fallback`: generic file-section coverage when no language extractor succeeds.

The labels describe evidence strength, not implementation branding. A provider
may advertise different authority for different operations. For example, an LSP
provider may be exact for definitions and references but expose no complete
batch call hierarchy; a SCIP artifact may be exact for references while omitting
an operation required by a live editor overlay.

### SCIP and LSP arbitration

SCIP and LSP belong to the same semantic-authority tier. They do not have one
fixed global ordering.

- A clean file covered by a source-matching SCIP artifact prefers SCIP for batch
  snapshot facts.
- A live or dirty file whose LSP document version or content digest matches the
  indexed source prefers LSP for the affected operations.
- If both sources are fresh and agree, their evidence is merged into one fact.
- If both are equally authoritative, fresh, and contradictory, the result is
  explicit ambiguity; registration order must not choose a winner.
- A provider whose source identity cannot be tied to the current content is
  stale and is excluded from exact authority.

Acceptable SCIP freshness evidence is a per-document content digest or a
revision-bound artifact whose source content has been verified against the
workspace. Acceptable LSP freshness evidence is a matching document version or
content digest under the intended workspace root. Provider health alone is not
freshness evidence.

### Gap filling and conflict rules

- Provider execution is planned by operation capability and observed gaps.
- A lower tier may add a missing fact or missing evidence detail.
- A lower tier must not replace, weaken, or silently contradict a higher-tier
  fact.
- Facts with the same stable semantic identity merge evidence deterministically.
- Equal-tier contradictions produce structured ambiguity or conflict
  diagnostics.
- Regex-produced semantic facts are always heuristic or fallback evidence. They
  must never be presented as exact or full semantic extraction.
- Failure of one provider is isolated to its affected files and operations; the
  planner continues through eligible lower tiers and records the degradation.

### Evidence contract

Every normalized unit or relation fact must be attributable to one or more
evidence records. The additive internal shape is conceptually:

```clojure
{:provider_id "scip-typescript"
 :provider_version "..."
 :authority "exact"             ;; exact | structural | heuristic | fallback
 :operation "references"
 :source_identity {:content_digest "sha256:..."
                   :revision "..."}
 :freshness "exact"             ;; exact | stale | unknown
 :evidence_location {:path "src/example.ts"
                     :start_line 12
                     :end_line 12}}
```

Relation identity remains defined by ADR-039 and excludes this mutable evidence.
The current singular `provenance` field remains a compatibility projection until
the additive multi-source evidence representation is proven across in-memory and
PostgreSQL storage.

### Provider planning boundary

Provider selection becomes a capability-specific execution plan:

```text
File metadata + requested operations + provider descriptors/status/freshness
  -> ProviderPlan
  -> execute eligible semantic providers
  -> fill missing structural facts
  -> fill remaining gaps with heuristic fallback
  -> normalize and arbitrate facts
```

The provider planner owns precedence policy. Provider implementations do not
select themselves or compare themselves with peers. Index construction consumes
only normalized fact batches and does not depend on SCIP, LSP, tree-sitter, or
regex implementation details.

### Compatibility policy

Existing parser options remain compatibility inputs during migration. They may
force a provider in tests or explicit diagnostic runs, but they must not remain
the long-term authority policy. New provider policy is expressed in terms of
operation capability, freshness, authority tier, and project overrides.

The migration runs in shadow mode before changing default selection, confidence
ceilings, or observable retrieval behavior.

## Consequences

### Positive

- Java and TypeScript semantic facts can reach compiler-grade authority without
  making external providers mandatory for basic indexing.
- SCIP batch snapshots and LSP live overlays can coexist without parallel graph
  models.
- Tree-sitter remains valuable as a deterministic structural safety net instead
  of being mislabeled as optional acceleration only.
- Regex behavior becomes honest, observable, and confidence-limited.
- New compiler-grade providers can attach through the same evidence and
  arbitration contracts.
- ADR-039 relation identity directly supports evidence enrichment across
  providers.

### Negative

- Multi-provider execution and arbitration are materially more complex than one
  parser choice per file.
- SCIP artifact production and LSP lifecycle management add external operational
  dependencies, caching, timeouts, and version coordination.
- Fact-level provenance increases snapshot and PostgreSQL storage volume.
- Live LSP results can reduce reproducibility unless source identity and overlay
  boundaries are strict.
- Regex reclassification will lower reported capability and confidence for
  repositories without a stronger provider; this is an intentional truthfulness
  correction but an observable behavioral change.
- Existing provider selection and workspace fingerprints must evolve from one
  provider per language/file to a versioned provider plan.

### Follow-Up

- Review and, if accepted, execute
  `plans/018_semantic_provider_authority_migration_plan.md`.
- Add provider-plan and fact-evidence contracts before implementing SCIP or LSP
  adapters.
- Revise plans/007 Decision 9 from single-provider selection to bounded
  multi-provider enrichment.
- Supersede the regex-default clauses of ADR-036 while retaining its
  repo-managed tree-sitter toolchain decision.
- Recalibrate language confidence only after shadow evidence and protected replay
  gates pass.

## Status Changes

ADR-046 supersedes ADR-036's mixed parser-authority decision. The
repo-managed tree-sitter executable and grammar-resolution boundary is retained
and restated without regex-default policy in ADR-047. Plans/007 Decision 9 is
amended by this ADR and plans/018: Java and TypeScript use a bounded
multi-provider plan, while a single primary provider remains sufficient for
unrelated simple document-provider cases.

## References

- [ADR-004](./004-define-parser-adapters-as-capability-based-fact-extractors.md)
- [ADR-028](./028-prioritize-tree-sitter-adoption-by-language-risk-and-parser-ceiling.md)
- [ADR-036](./036-use-a-repo-managed-tree-sitter-toolchain.md)
- [ADR-039](./039-separate-relation-identity-from-resolution-and-evidence.md)
- [plans/007](../plans/007_semidx_extension_architecture_resolution_plan.md)
- [plans/018](../plans/018_semantic_provider_authority_migration_plan.md)

## Definition Of Done

This decision is fully implemented only when:

1. Java and TypeScript use provider plans rather than a single parser-engine
   default.
2. Fresh SCIP and LSP evidence can contribute exact facts with source identity.
3. Tree-sitter fills structural gaps without replacing exact facts.
4. Regex facts are explicitly heuristic/fallback and confidence-limited.
5. Same-identity multi-provider facts merge evidence deterministically.
6. Equal-authority conflicts remain observable as ambiguity.
7. Provider unavailability and staleness produce additive degradations.
8. Workspace fingerprints include every provider and configuration input that
   can change snapshot output.
9. Protected retrieval, relation, impact, snapshot-diff, and storage gates pass
   before the provider pipeline becomes the default.
