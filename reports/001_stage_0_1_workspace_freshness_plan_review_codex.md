---
title: "Stage 0+1 Workspace Freshness Plan Review"
doc_type: "review_report"
lifecycle: "completed"
status: "final"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Stage 0+1 Workspace Freshness Plan Review

Reviewed document:
[plans/008_stage_0_1_workspace_freshness.md](../plans/008_stage_0_1_workspace_freshness.md)

Architecture reference:
[plans/007_semidx_extension_architecture_resolution_plan.md](../plans/007_semidx_extension_architecture_resolution_plan.md)

## Verdict

The plan is directionally strong. It identifies a real P0 correctness defect,
sets good responsibility boundaries, and defines acceptance gates that would
catch stale-index regressions. Before implementation, it should be tightened in
the contract details between lifecycle, cache, transports, storage, and subset
indexing.

The main weakness is not decomposition. The weak points are underspecified
runtime semantics: cache validation, request identity, pinned snapshots, atomic
publication, and wire-contract shape.

## Strengths

- The problem statement is clear: `create_index` must not return a stale
  snapshot after repository files change, and all entry surfaces must share one
  freshness decision.
- The root cause is correctly named: MCP cache keys do not include file content,
  and runtime storage reuse currently checks snapshot age rather than content.
- The scope is appropriately constrained. Workspace freshness is in scope, while
  provider catalog, typed relations, and Markdown/YAML indexing are deferred.
- The proposed boundaries are sound:
  - `workspace_state` captures a content-addressed manifest.
  - `freshness` makes a pure reuse/update/rebuild decision.
  - `index_lifecycle` orchestrates capture, decision, build/update, persistence,
    and publication.
- The correctness model is strong: `content_digest` is authoritative, while
  `modified_at` and `size_bytes` are acceleration hints only.
- `freshness/decide-freshness` as a pure function is easy to test and keeps
  policy separate from filesystem, storage, and transport details.
- The lifecycle coordinator is kept below transport formatting, preserving
  dependency direction.
- Storing `workspace_state` in the existing snapshot payload is a pragmatic
  backward-compatible first step.
- The implementation sequence is mostly good: baseline tests, manifest capture,
  pure policy, lifecycle wiring, storage, MCP, then HTTP/gRPC.
- Acceptance gates cover more than happy paths, including failed capture,
  missing manifests, update failure, and concurrent `create_index` calls.

## Weaknesses And Risks

### High Severity

1. Provider versions are underspecified.

   The plan includes `provider_registry_version`, `provider_id`, and
   `provider_version` in the workspace manifest, but provider catalog and
   registry work is explicitly out of scope. Stage 1 needs a temporary,
   deterministic source of provider and pipeline versions for existing
   adapters, or fingerprints will be either fake or unstable.

2. Freshness identity is too narrow in the Stage 1 rules.

   The plan says matching `workspace_fingerprint` implies reuse. The
   architecture reference is stricter: reuse must consider the fingerprint plus
   relevant request options. The plan should explicitly include `paths`,
   `parser_opts`, `language_policy`, discovery profile, activation policy, and
   subset-indexing scope in the identity.

3. MCP cache behavior is not precise enough.

   The plan says `find-cached-entry` is no longer the source of truth, but it
   does not define the exact cache algorithm. It must specify when an existing
   `index_id` can be returned, when a cached entry is replaced, and how
   `cache-key->index-id` is updated after rebuild or incremental update.

4. Deleted-path handling crosses a private boundary.

   The plan says incremental update should handle `deleted_paths` via
   `remove-paths-from-index`, but that helper is currently internal to
   `runtime.index`. The plan should either extend `update-index` to accept
   `deleted_paths` or define a small internal lifecycle API for removal.

5. Atomic publication is asserted but not fully designed.

   The plan references the existing project-registry refresh lock, but does not
   explain how library-only and storage-only paths get the same single-writer
   boundary. That matters for concurrent `create_index` behavior and avoiding
   partial publication.

### Medium Severity

1. Pinned snapshot semantics are missing.

   The freshness rules should explicitly say whether pinned snapshots are
   historical exact reads, whether they bypass current workspace validation, and
   what lifecycle fields they report.

2. Lifecycle output schema is incomplete.

   The acceptance gates mention `files_reindexed: 0`, but the lifecycle output
   schema does not define that field. The plan should define the Clojure map and
   JSON response shape before implementation.

3. Existing `:index_lifecycle` vs flat lifecycle fields is unresolved.

   The plan adds `lifecycle_action`, `lifecycle_reason`, and
   `lifecycle_diagnostics`, while the current code already has nested
   `:index_lifecycle`. The plan should choose the canonical representation and
   explain any transport-level projection.

4. Digest acceleration needs an exact algorithm.

   The plan correctly treats `modified_at` and `size_bytes` as hints, but does
   not define when old digests may be reused, when files must be re-read, or how
   timestamp resolution and clock skew are handled.

5. Manifest payload size has no performance guard.

   Persisting full file manifests in snapshot payload JSONB is acceptable for
   Stage 1, but the plan should include at least a basic large-repo or manifest
   size check.

6. HTTP/gRPC contract changes are too vague.

   "Same additive lifecycle outcome fields" is not enough. The plan should add
   concrete request/response examples or schema updates for transport tests.

### Low Severity

1. Failing baseline tests need a mainline strategy.

   Commit 1 intentionally adds failing tests. That is useful in a local TDD
   sequence, but awkward for a mainline commit sequence. Either keep the failing
   tests local until Commit 4, mark them pending, or group the red-green slice
   in one PR stage.

2. Verification should include contract checks.

   `clojure -M:test` is necessary, but the plan should also include contract
   validation and targeted MCP/HTTP/gRPC smoke checks after wire changes.

## Recommended Plan Edits Before Implementation

1. Define the canonical lifecycle schema.

   Include both internal Clojure shape and transport JSON shape. Decide whether
   nested `:index_lifecycle` remains canonical and flat fields are projections,
   or whether flat fields become the new public contract.

2. Define freshness identity precisely.

   Freshness should be a function of workspace content plus request/discovery
   identity: paths, parser options, language policy, active language set,
   discovery profile, provider versions, and semantic pipeline version.

3. Specify the MCP cache algorithm.

   Cached entries may speed index-id lookup, but every `create_index` call that
   can observe filesystem changes must pass through lifecycle validation before
   returning a cache hit.

4. Add pinned snapshot semantics.

   Pinned snapshots should have explicit lifecycle posture, such as
   `snapshot_pinned: true`, `action: "pinned_read"`, or another clearly
   documented value.

5. Resolve deleted-path API ownership.

   Prefer extending `update-index` with `:deleted_paths` if lifecycle needs to
   call it. That keeps removal behavior behind the runtime index boundary.

6. Clarify atomic publication for every entry surface.

   Define where the single-writer lock lives and how it applies to MCP, HTTP,
   gRPC, and direct library calls.

7. Add a small performance gate.

   Include a test or smoke check that manifest capture and payload persistence
   remain acceptable on a repository larger than the tiny fixtures.

## Suggested Acceptance Gate Additions

- Same file contents with only mtime changes must reuse the snapshot.
- Same workspace content with different `parser_opts` must not reuse an
  incompatible snapshot.
- Same workspace content with different `language_policy` or `paths` scope must
  not reuse an incompatible snapshot.
- Pinned snapshot reads must return the requested snapshot with explicit
  lifecycle metadata.
- MCP `cache_hit` must be false when lifecycle action is not reuse.
- Failed update must leave the previously published snapshot addressable by
  `snapshot_id`.

## Bottom Line

Plan 008 is a good implementation plan after one tightening pass. It has the
right boundaries and the right correctness target. The main work before coding
is to make the contracts exact enough that lifecycle, cache, storage, and
transport behavior cannot diverge during implementation.
