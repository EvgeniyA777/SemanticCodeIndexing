---
title: "Stage 0+1 Workspace Freshness Plan"
doc_type: "implementation_plan"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Implementation Plan: Stage 0+1 — Contract Baselines and Workspace Freshness

Status: Delivered (historical)
Architecture reference:
  plans/007_semidx_extension_architecture_resolution_plan.md

> Revision (2026-07-13): incorporates reviewer remediation priority 1–2 from
> `reports/004_stage_0_1_workspace_freshness_consensus_claude.md` — `added_paths`
> routing, `capture-workspace-state` prior-state arg + explicit STRICT/FAST
> hashing stance, provider/pipeline version source, widened `update-index`
> selector with preserved encapsulation, and single-writer atomic publication
> with a fingerprint compare-and-set.

## Goal

Fix the P0 correctness defect: `create_index` must never return a stale snapshot
when repository files have changed. All entry surfaces (MCP, HTTP, gRPC, library)
must share a single freshness decision — not each implement their own.

**Root problem:** `tool-create-index` in `mcp.core` computes a cache key from
`(root-path, paths, parser-opts, language-policy)` — no file content is
included. A file can change between two identical `create_index` calls and the
second returns a stale snapshot. Similarly, `runtime.index/create-index` checks
storage reuse only by snapshot age (`stale?`), not content. Both are correctness
defects.

## Scope

In:
- Contract baseline tests (Stage 0)
- WorkspaceState capture and diff (Stage 1)
- FreshnessPolicy (Stage 1)
- IndexLifecycleCoordinator (Stage 1)
- Workspace manifest embedded in snapshot payload (Stage 1)
- Atomic snapshot publication via existing project-registry lock (Stage 1)
- Additive lifecycle outcomes and diagnostics in MCP/HTTP/gRPC responses (Stage 1)

Out (next stages):
- Provider catalog and registry
- Discovery separation from language activation
- Typed relations / Reference model
- Markdown or YAML indexing

## New Files

### `src/semidx/runtime/workspace_state.clj`

Responsibility: capture a content-addressed manifest of all indexable files.

Key functions:

```clojure
(defn capture-workspace-state
  [root-path discovery-profile provider-catalog-version & [prior-workspace-state]]
  ;; Returns WorkspaceState map.
  ;; `prior-workspace-state` is optional; it is consulted ONLY under the FAST
  ;; hashing mode (see Rules) to reuse a previously computed content_digest for a
  ;; file whose mtime AND size are unchanged.)

(defn diff-workspace-state
  [previous current]
  ;; Returns {:added_paths [...] :changed_paths [...] :deleted_paths [...]
  ;;          :unchanged_paths [...] :compatibility_changes [...]})
```

WorkspaceState shape:

```clojure
{:schema_version            "1"
 :root_path                 "..."
 :discovery_profile_hash    "..."    ; hash of discovery inputs (.gitignore, paths, etc.)
 :provider_registry_version "1"     ; bumped when parser output changes
 :semantic_pipeline_version "1"     ; bumped when index schema changes
 :files
   [{:path            "src/foo.clj"
     :content_digest  "sha256:..."  ; SHA-256 hex — primary correctness signal
     :size_bytes      1234
     :modified_at     "2026-06-09T10:00:00Z"  ; acceleration hint only
     :provider_id     "clojure-native"
     :provider_version "1"
     :classification  "source"}]
 :workspace_fingerprint "sha256:..."}  ; digest of sorted file list + all versions
```

Rules:
- `content_digest` is the authoritative identity and is always computed from the
  file bytes.
- **Hashing mode (resolves the correctness vs. performance fork):**
  - **STRICT (default, Stage 1 correctness path):** every indexable file is read
    and hashed on every capture. This is what makes the "never stale" guarantee
    literal — a content change is always detected regardless of mtime/size.
  - **FAST (opt-in, off by default):** when `prior-workspace-state` is supplied,
    reuse its `content_digest` for a file whose mtime AND size are both unchanged.
    This is an explicitly accepted Make-level trade-off: a content change that
    preserves both mtime and size is missed. FAST must never be the default and
    must be documented at the call site; `force_rebuild` always bypasses it.
- `modified_at` + `size_bytes` are acceleration hints used only by FAST mode.
- A cache hit (`:reuse`) is valid only when `workspace_fingerprint` matches.
- Sort file list by path before computing `workspace_fingerprint` for determinism.
- Use `java.security.MessageDigest` (SHA-256) for all hashing.
- Walk `root-path` via existing `adapters/source-files` until discovery is
  separated in Stage 3.
- **Provider/pipeline version source (Stage 1, catalog out of scope):**
  `provider_registry_version`, `semantic_pipeline_version`, and per-file
  `provider_id` / `provider_version` come from a single temporary constant table
  in this namespace (the only source of truth until the provider registry lands).
  A unit test asserts this table matches the adapters actually wired in
  `default-parser-opts`, so a new/changed adapter fails loudly instead of
  producing a fake or unstable fingerprint. Bump the versions in the same commit
  as any breaking change to parser output or index schema.

### `src/semidx/runtime/freshness.clj`

Responsibility: pure function — decide reuse, incremental update, or rebuild.

```clojure
(defn decide-freshness
  [previous-snapshot current-workspace-state opts]
  ;; Returns:
  ;; {:action         :reuse | :incremental_update | :full_rebuild
  ;;  :reason         "..."
  ;;  :added_paths    [...]
  ;;  :changed_paths  [...]
  ;;  :deleted_paths  [...]
  ;;  :diagnostics    [...]})
```

Decision rules (in priority order):

1. `force_rebuild` in opts → `:full_rebuild "force_rebuild_requested"`
2. No previous snapshot → `:full_rebuild "initial_build"`
3. `:workspace_state` missing from previous snapshot → `:full_rebuild "no_prior_manifest"`
4. Manifest schema version incompatible → `:full_rebuild "manifest_schema_incompatible"`
5. `provider_registry_version` or `semantic_pipeline_version` changed →
   `:full_rebuild "provider_or_pipeline_version_changed"`
6. `workspace_fingerprint` matches → `:reuse "workspace_unchanged"`
7. Delta file count > threshold (`freshness-delta-rebuild-ratio`, default 0.5 of
   total; a named, configurable constant, not a magic number) →
   `:full_rebuild "delta_exceeds_threshold"`
8. Otherwise → `:incremental_update` with `added_paths`, `changed_paths`, and
   `deleted_paths` (all three must be carried through; `added_paths` and
   `changed_paths` take the same parse path, `deleted_paths` are removed).

Zero side effects. Independently testable with plain maps.

### `src/semidx/runtime/index_lifecycle.clj`

Responsibility: orchestrate capture → freshness → build/update → persist →
publish. Used by all entry surfaces.

```clojure
(defn coordinate-index-lifecycle
  [{:keys [root-path parser-opts storage load-latest force-rebuild
           pinned-snapshot-id language-policy max-snapshot-age-seconds
           rebuild-reason repo-identity-reuse-mode]}]
  ;; Returns same index map as current create-index, plus
  ;; additive :index_lifecycle outcome fields.
```

Sequence:

1. Capture `current-workspace-state` via `workspace-state/capture-workspace-state`.
2. Load prior snapshot from storage (same logic as current `maybe-load-index`).
3. Call `freshness/decide-freshness`.
4. Dispatch on action:
   - `:reuse` → return prior snapshot with lifecycle outcome attached.
   - `:incremental_update` → call `runtime.index/update-index` with the widened
     `{:added_paths :changed_paths :deleted_paths}` selector (see Modified Files).
     Added and changed paths are re-parsed; deleted paths are removed. The
     coordinator uses only the public `update-index` entry point and never
     reaches into `runtime.index` private helpers.
   - `:full_rebuild` → call the public `runtime.index` full build path.
5. Embed `current-workspace-state` into snapshot payload as additive
   `:workspace_state` field.
6. **Single owner of persist + publish.** `update-index` / full build already
   call `maybe-save-index!`; the coordinator must NOT persist a second time.
   Persistence and publication happen exactly once, inside the critical section.
7. **Atomic publication (concurrency).** The whole capture → decide → build →
   persist → publish sequence runs under the existing project-registry
   single-writer lock, for every entry surface (MCP, HTTP, gRPC, and direct
   library calls — the lock lives in the coordinator, not in a transport). At
   publish time apply a compare-and-set on `workspace_fingerprint`: if the
   currently published snapshot's fingerprint no longer matches the state this
   build started from, discard this build and re-run rather than overwrite a
   newer snapshot (prevents the lost-update / TOCTOU case in gate #13).

The coordinator does NOT touch MCP JSON, HTTP status codes, or transport fields.

## Modified Files

### `src/semidx/runtime/index.clj`

- `create-index` delegates to `index-lifecycle/coordinate-index-lifecycle` for
  the full reuse/update/build decision.
- Internal helpers (`parse-files`, `build-index-state`, `maybe-load-index`,
  `maybe-save-index!`, `remove-paths-from-index`) stay **private**. The
  coordinator lives close to this namespace and interacts only through the
  public `create-index` / `update-index` entry points, so encapsulation is
  preserved (no `defn-` is promoted to public just to serve the coordinator).
- `update-index` signature is widened from `{:changed_paths ...}` to
  `{:added_paths :changed_paths :deleted_paths :parser_opts :storage}`. Added and
  changed paths flow through the existing parse path; deleted paths go through the
  private `remove-paths-from-index`. This keeps deletion/addition behavior behind
  the runtime-index boundary and rebuilds the full caller/callee/module graph via
  `build-index-state` over the merged unit set (preserving gate #10 equivalence,
  which holds only when added + changed + deleted are all carried through).
- `build-index-state` accepts an optional `:workspace_state` arg and stores it
  in the snapshot map (additive, no schema break for existing callers).

### `src/semidx/mcp/core.clj`

- `tool-create-index`: in-memory cache-key atom is retained for `index_id`
  lookup, but freshness correctness is delegated to the lifecycle coordinator.
  `find-cached-entry` is no longer the source of truth for reuse.
- `index-summary` gains three additive fields:

  ```clojure
  :lifecycle_action      "reuse" | "incremental_update" | "full_rebuild"
  :lifecycle_reason      "..."
  :lifecycle_diagnostics [...]
  ```

  Existing `cache_hit` field is preserved and set `true` only for `:reuse`.

### `src/semidx/runtime/storage.clj`

- `save-index!` (both `InMemoryStorage` and `PostgresStorage`) persists
  `:workspace_state` as part of the snapshot payload. For Postgres, stored in
  the existing `payload` JSONB column — additive, backward-compatible.
- `load-latest-index` and `load-index-by-snapshot` return `:workspace_state`
  when present; `nil` for legacy snapshots (triggers full rebuild).

### `src/semidx/core.clj`

No logic changes. `create-index` already delegates to `idx/create-index` and
transparently receives the richer lifecycle result.

### `src/semidx/runtime/http.clj`, `src/semidx/runtime/grpc.clj`

Additive lifecycle outcome fields passed through to response serialization.

## Implementation Sequence

Seven small commits, each independently testable.

### Commit 1 — Contract baseline tests (Stage 0)

New: `test/semidx/freshness_baseline_test.clj`

Protected cases that must stay green through all subsequent commits:
- `create-index` on unchanged repo returns same `snapshot_id` (reuse).
- `create-index` after modifying file content returns new `snapshot_id`.
- `create-index` after adding a file includes the new file in the index.
- `create-index` after deleting a file excludes it from the index.
- `update-index` result is semantically equivalent to `create-index` full
  rebuild on the same repo state.
- `force_rebuild: true` always rebuilds regardless of workspace state.

These tests initially FAIL — they are the acceptance gate for Commits 4–6.

### Commit 2 — `workspace_state.clj` + unit tests

New: `src/semidx/runtime/workspace_state.clj`
New: `test/semidx/workspace_state_test.clj`

Test matrix:
- Identical filesystem state → identical `workspace_fingerprint`.
- Modified content → file in `:changed_paths`, different fingerprint.
- Added file → file in `:added_paths`.
- Deleted file → file in `:deleted_paths`.
- `modified_at` change, content unchanged → NOT in `:changed_paths`.
- `size_bytes` change without content change → hash decides (not changed).
- `provider_registry_version` bump → appears in `:compatibility_changes`.

### Commit 3 — `freshness.clj` + unit tests

New: `src/semidx/runtime/freshness.clj`
New: `test/semidx/freshness_test.clj`

Tests cover all eight decision rules with plain Clojure maps — no I/O.

### Commit 4 — `index_lifecycle.clj` wired into `runtime.index`

New: `src/semidx/runtime/index_lifecycle.clj`
Modified: `src/semidx/runtime/index.clj`

- `create-index` calls `coordinate-index-lifecycle`.
- Snapshot payload includes `:workspace_state` on fresh build.
- In-memory mode after restart → no prior manifest → honest full rebuild.
- All existing `runtime_test.clj` tests must remain green.
- Commit 1 baseline tests that were failing should now pass.

### Commit 5 — Storage additive field

Modified: `src/semidx/runtime/storage.clj`

- `save-index!` persists `:workspace_state`.
- `load-latest-index` returns `:workspace_state` when present.
- Existing `storage_test.clj` tests must remain green.
- New tests: `InMemoryStorage` workspace-state round-trip.

### Commit 6 — MCP transport additive fields + cache-key fix

Modified: `src/semidx/mcp/core.clj`

- `tool-create-index` delegates freshness to lifecycle coordinator.
- `index-summary` returns `lifecycle_action`, `lifecycle_reason`,
  `lifecycle_diagnostics`.
- Existing `mcp_server_test.clj` and `mcp_http_server_test.clj` must pass.
- New MCP test: stale cache does not survive file content change.

### Commit 7 — HTTP and gRPC additive fields

Modified: `src/semidx/runtime/http.clj`, `src/semidx/runtime/grpc.clj`

Same additive lifecycle outcome fields. Existing transport tests must pass.

## Acceptance Gates

All must pass before Stage 1 is considered complete:

1. Unchanged repository → `:reuse`, `files_reindexed: 0`.
2. Modified file content → `:incremental_update` or `:full_rebuild`, never `:reuse`.
3. Added file included in new snapshot.
4. Deleted file excluded from new snapshot.
5. `provider_registry_version` bump → `:full_rebuild`.
6. Incompatible manifest schema → `:full_rebuild`.
7. Failed state capture → never a cache hit (throws or returns error, not reuse).
8. In-memory mode after process restart → honest `:full_rebuild`.
9. Persisted snapshot validated before reuse (missing `workspace_state` → rebuild).
10. Incremental update result semantically equivalent to full rebuild on same state.
11. `force_rebuild: true` → always `:full_rebuild`.
12. Artificial failure during update leaves previous snapshot active.
13. Two concurrent `create_index` on same root → no corrupted or partial snapshot.

## Verification

```bash
# Run full test suite
clojure -M:test

# Run only new tests
clojure -M:test :only semidx.workspace-state-test
clojure -M:test :only semidx.freshness-test
clojure -M:test :only semidx.freshness-baseline-test
```

MCP smoke test sequence:

```
1. Start MCP server
2. create_index on this repo                   → lifecycle_action: "full_rebuild"
3. create_index again, no changes              → lifecycle_action: "reuse"
4. Touch src/semidx/core.clj (append newline)
5. create_index again                          → lifecycle_action: "incremental_update"
6. Revert the touch
7. create_index again                          → lifecycle_action: "reuse"
```
