---
title: "SOLID Architecture Review: Stage 0+1 Workspace Freshness Plan"
doc_type: "review"
lifecycle: "completed"
status: "final"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# SOLID Architecture Review: Stage 0+1 Workspace Freshness Plan

Review of: [plans/008_stage_0_1_workspace_freshness.md](../plans/008_stage_0_1_workspace_freshness.md)  
Reviewer: Antigravity AI  
Date: 2026-07-13  

---

## Executive Summary

A review of the **Stage 0+1 Workspace Freshness Plan** was conducted under SOLID principles and Clojure encapsulation best practices. While the plan establishes a strong, pure boundary for freshness decisions, several critical issues were identified regarding incremental indexing completeness, caching in standard stdio mode, performance bottlenecks in file hashing, and namespace visibility boundaries.

---

## Findings

### 1. [High] `added_paths` Omitted from Incremental Updates

* **Issue:** In the proposed `decide-freshness` rules (step 8) and `coordinate-index-lifecycle` dispatcher (step 4), only `changed_paths` and `deleted_paths` are handled during incremental updates. The `added_paths` returned by `diff-workspace-state` are completely omitted.
* **Impact:** Newly created files in the repository will never be parsed or included in the index during incremental runs, violating Acceptance Gate 3 ("Added file included in new snapshot") and 10 ("Incremental update result semantically equivalent to full rebuild").
* **Evidence:** [plans/008_stage_0_1_workspace_freshness.md#L120-L122](../plans/008_stage_0_1_workspace_freshness.md#L120-L122) and [plans/008_stage_0_1_workspace_freshness.md#L147-L148](../plans/008_stage_0_1_workspace_freshness.md#L147-L148)
* **Suggested Fix:** Merge `added_paths` into the collection of paths processed by `update-index` since the parser path is identical for both added and modified files:
  ```clojure
  (let [paths-to-update (vec (concat changed_paths added_paths))]
    (runtime.index/update-index index paths-to-update))
  ```

### 2. [Medium/High] In-Memory Cache Loss in Stdio MCP Mode

* **Issue:** In standard stdio execution (e.g., when no external Postgres or filesystem persistence is active), `storage-adapter` is `nil` and the index cache is stored in the MCP session state atom. The plan coordinates loading the prior snapshot exclusively via `storage/load-latest-index`.
* **Impact:** Without a storage adapter, the coordinator will always find `nil` for the prior snapshot. This will trigger a `:full_rebuild "initial_build"` on every `create_index` call, completely breaking caching and incremental indexing in standard stdio environments.
* **Evidence:** [plans/008_stage_0_1_workspace_freshness.md#L142-L143](../plans/008_stage_0_1_workspace_freshness.md#L142-L143) and [plans/008_stage_0_1_workspace_freshness.md#L263-L265](../plans/008_stage_0_1_workspace_freshness.md#L263-L265)
* **Suggested Fix:** Add an optional `:prior_snapshot` argument to `coordinate-index-lifecycle`. If provided, use it directly; otherwise, load from `storage`. Let `tool-create-index` in `mcp.core` extract the cached index from the session atom and pass it down.

### 3. [Medium/High] Hashing Performance Bottleneck in `capture-workspace-state`

* **Issue:** The plan states that `modified_at` + `size_bytes` will be used as mtime/size acceleration hints to skip hashing unmodified files. However, the signature for `capture-workspace-state` is defined as `[root-path discovery-profile provider-catalog-version]` and does not receive the previous state manifest.
* **Impact:** Without access to the prior `WorkspaceState` file mapping, the function cannot compare metadata to detect unmodified files. It will be forced to compute SHA-256 hashes of all indexable files in the workspace on every invocation, causing significant performance overhead in large projects.
* **Evidence:** [plans/008_stage_0_1_workspace_freshness.md#L55-L57](../plans/008_stage_0_1_workspace_freshness.md#L55-L57) and [plans/008_stage_0_1_workspace_freshness.md#L84-L86](../plans/008_stage_0_1_workspace_freshness.md#L84-L86)
* **Suggested Fix:** Accept an optional prior workspace state:
  ```clojure
  (defn capture-workspace-state
    [root-path discovery-profile provider-catalog-version & [prior-workspace-state]]
    ...)
  ```
  Check file metadata against the prior state and copy the `content_digest` directly for unmodified files.

### 4. [Medium] Encapsulation Breakage of Private Helper Functions

* **Issue:** The plan proposes placing the lifecycle coordinator in a new file `src/semidx/runtime/index_lifecycle.clj` and calling `remove-paths-from-index`, `parse-files`, `build-index-state`, `maybe-load-index`, and `maybe-save-index!`.
* **Impact:** These five functions are currently private (`defn-`) in [src/semidx/runtime/index.clj](../src/semidx/runtime/index.clj). Exposing them as public to support a separate lifecycle namespace pollutes the namespace's interface and weakens module encapsulation.
* **Evidence:** [plans/008_stage_0_1_workspace_freshness.md#L147-L149](../plans/008_stage_0_1_workspace_freshness.md#L147-L149), [plans/008_stage_0_1_workspace_freshness.md#L163-L164](../plans/008_stage_0_1_workspace_freshness.md#L163-L164), and [src/semidx/runtime/index.clj](../src/semidx/runtime/index.clj)
* **Suggested Fix:** Implement the lifecycle coordinator (`coordinate-index-lifecycle`) directly inside [src/semidx/runtime/index.clj](../src/semidx/runtime/index.clj). This consolidates all index-building orchestration and maintains helper encapsulation.

---

## Open Questions

1. **Fingerprint Serialization:** How is `workspace_fingerprint` serialized before hashing? We assume it should hash a canonical representation (e.g., sorted lines of `path,content_digest`) to avoid platform-specific variation.

---

## Verification Summary

A baseline test run was conducted on the current codebase:
* **Command:** `clojure -M:test`
* **Result:** Passed (189 tests, 1273 assertions, 0 failures, 0 errors).
