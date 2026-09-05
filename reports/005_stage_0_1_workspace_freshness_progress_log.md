---
title: "Stage 0+1 Workspace Freshness Progress Log"
doc_type: "progress_log"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Progress Log: Stage 0+1 — Contract Baselines and Workspace Freshness

This execution log is closed. Unresolved review follow-ups were moved to
`reports/019_legacy_workspace_and_html_css_followups.md` so this completed plan
is not presented as an active work queue.

Companion progress log for
[008_stage_0_1_workspace_freshness.md](../plans/008_stage_0_1_workspace_freshness.md).

## Status Summary

- [x] **Commit 1 (Baseline tests)**: Completed
- [x] **Commit 2 (Workspace state)**: Completed
- [x] **Commit 3 (Freshness logic)**: Completed
- [x] **Commit 4 (Index lifecycle & wiring)**: Completed
- [x] **Commit 5 (Storage payload)**: Completed
- [x] **Commit 6 (MCP integration)**: Completed
- [x] **Commit 7 (HTTP/gRPC integration)**: Completed

## Details of Changes

### Commit 1 — Contract baseline tests (Stage 0)
- **Status**: Completed
- **Changes**: Created `test/semidx/freshness_baseline_test.clj` asserting expected baseline freshness behaviors.
- **Verification**: Run `clojure -M:test :only semidx.freshness-baseline-test` (all pass).

### Commit 2 — `workspace_state.clj` + unit tests
- **Status**: Completed
- **Changes**: Created `src/semidx/runtime/workspace_state.clj` and `test/semidx/workspace_state_test.clj` (all pass).

### Commit 3 — `freshness.clj` + unit tests
- **Status**: Completed
- **Changes**: Created `src/semidx/runtime/freshness.clj` and `test/semidx/freshness_test.clj` (all pass).

### Commit 4 — `index_lifecycle.clj` wired into `runtime.index`
- **Status**: Completed
- **Changes**: Created `src/semidx/runtime/index_lifecycle.clj`, modified `src/semidx/runtime/index.clj`. Updated rebuild reason mappings and fixed TOCTOU verification compare-and-set loops.

### Commit 5 — Storage additive field
- **Status**: Completed (no source change — corrected per review M2)
- **Changes**: No modification to `src/semidx/runtime/storage.clj`. `:workspace_state` is an
  additive key on the snapshot map; `InMemoryStorage` persists and reloads the raw map so it
  round-trips automatically. Verified by `in-memory-storage-workspace-state-round-trip-test`.
  Postgres JSONB round-trip is untested (see Coverage gaps).

### Commit 6 — MCP transport additive fields + cache-key fix
- **Status**: Completed
- **Changes**: Modified `src/semidx/mcp/core.clj` to delegate lifecycle/freshness decisions to the coordinator, and return detailed lifecycle action reasons/diagnostics. Bypassed storage saving and TOCTOU retry for paths-subset indexing to ensure they do not pollute the repository-level latest snapshot.

### Commit 7 — HTTP and gRPC additive fields
- **Status**: Completed (no source change — corrected per review M2)
- **Changes**: No modification to `src/semidx/runtime/http.clj` or `src/semidx/runtime/grpc.clj`.
  Lifecycle outcome fields ride through the existing index map's `:index_lifecycle`, so no edits
  were required. No HTTP/gRPC test asserts they reach the wire (see Coverage gaps).

## Review Findings (2026-07-13)

Consolidated from two review passes on 2026-07-13 against
`plans/008_stage_0_1_workspace_freshness.md` and the staged workspace-freshness changes.
Pass A (H1–H3) was contributed by an earlier reviewer and confirmed with one-off reproduction
checks; Pass B (M1–L2) is the follow-up review. Both are preserved below, ordered high → low.
Verification at review time: `clojure -M:test` → 194 tests, 1335 assertions, 0 failures, 0
errors. Baseline gate (`freshness-baseline-test`) green: reuse on unchanged repo, new
snapshot on modify/add/delete, `force_rebuild`. Core P0 fix confirmed working.

Disposition ∈ {accepted, rejected, deferred, fixed}.

### H1 — [High] Pinned snapshot flow can rebuild or fail instead of returning the exact stored snapshot
- **Status**: Fixed (Pass A finding, Pass B fix)
- **Evidence**: `src/semidx/runtime/index_lifecycle.clj` loads the pinned prior snapshot, then still runs freshness and rebuild/update decisions. The post-publish verification reloads through the same pinned options, so changed or missing pinned snapshots can loop until `:concurrency_failure`.
- **Why it matters**: The runtime API contract says `:pinned_snapshot_id` reuses the exact stored snapshot even if stale and marks it pinned.
- **Suggested fix**: When `:pinned_snapshot_id` is present, load that exact snapshot, throw `:invalid_request` if missing, and return it with pinned lifecycle metadata without freshness rebuild/update.
- **Fix**: `coordinate-index-lifecycle` now short-circuits pinned requests to `:reuse` (skipping capture, freshness, and the rebuild/update+TOCTOU paths) and throws `:invalid_request` when the pinned snapshot is absent.
- **Verification**: `pinned-snapshot-returns-exact-snapshot-after-change-test` in `test/semidx/freshness_regression_test.clj` (changed workspace returns the exact pinned snapshot; missing pinned → `:invalid_request`). `clojure -M:test` → 197 tests, 1344 assertions, 0 failures.

### H2 — [High] Incremental freshness can violate `:language_policy`
- **Status**: Fixed (Pass A finding, Pass B fix)
- **Evidence**: `src/semidx/runtime/workspace_state.clj` captures all `activation/source-files`, while `src/semidx/runtime/index.clj` parses incremental `added_paths` and `changed_paths` without filtering by the active language set.
- **Why it matters**: A repository indexed with `:language_policy {:allow_languages ["clojure"]}` can later add an excluded Python file during incremental update.
- **Suggested fix**: Build workspace state from the same active source paths used by indexing, or filter freshness deltas through the resolved active language set before calling `update-index`.
- **Fix**: The coordinator resolves activation up front and passes the active source-path set into `capture-workspace-state`, which now restricts the manifest (new optional `allowed-paths` arg) to those paths. Excluded-language files never enter the manifest, so they cannot appear in a freshness delta.
- **Verification**: `incremental-update-respects-language-policy-test` (adding `src/intruder.py` under a Clojure-only policy leaves it out of `:files`). `clojure -M:test` → 197 tests, 0 failures.

### H3 — [High] Incremental update drops language activation metadata
- **Status**: Fixed (Pass A finding, Pass B fix)
- **Evidence**: `coordinate-index-lifecycle` calls `update-index` without activation metadata, and `update-index` rebuilds index state without passing `:activation_metadata`, leaving fields such as `:active_languages` unset.
- **Why it matters**: MCP/project context and retrieval guardrails depend on activation metadata remaining present after incremental updates.
- **Suggested fix**: Preserve activation metadata from the prior snapshot or recompute and pass current activation metadata through the incremental `update-index` path.
- **Fix**: The coordinator threads the freshly resolved `:activation_metadata` into the incremental `update-index` call; `update-index` passes it to `build-index-state` (falling back to the prior snapshot's activation fields when not supplied).
- **Verification**: `incremental-update-preserves-activation-metadata-test` (a single-file change yields `lifecycle_action "incremental_update"` with `:active_languages` preserved). `clojure -M:test` → 197 tests, 0 failures.

### M1 — [Medium] Compare-and-set publication is not atomic (publish precedes the check)
- **Evidence**: `src/semidx/runtime/index_lifecycle.clj:219-239` (incremental) and `:265-287`
  (rebuild). `create-index`/`update-index` call `maybe-save-index!` internally, so the snapshot
  is published *before* the coordinator loads latest and compares `workspace_fingerprint`. The
  `recur` cannot prevent overwriting a newer snapshot because the overwrite already happened.
  The lock is a process-local `root-locks` atom (`index_lifecycle.clj:10-20`), not the "existing
  project-registry single-writer lock" named in the plan (plan lines 188-194). Within one process
  under the lock `latest-fp` always equals `expected-fp`, so the retry loop is dead code;
  cross-process concurrency (Acceptance Gate #13) is unprotected.
- **Disposition**: deferred — needs a decision: implement CAS before publish, or restate Gate #13
  as single-process-only and drop the "no corrupted/partial snapshot" claim for multi-process.

### M2 — [Medium] Progress log claimed changes that do not exist (Commits 5 & 7) — FIXED
- **Status**: Fixed (Pass B)
- **Evidence**: `git diff --stat HEAD` shows `storage.clj`, `http.clj`, `grpc.clj` unchanged, yet
  Commit 5/7 entries above stated they were "Modified". The `:workspace_state` additive field
  rides through the snapshot payload / `:index_lifecycle` map without source edits (in-memory
  storage persists the raw map; HTTP/gRPC serialize the existing index map).
- **Disposition**: fixed — see Corrections below.

### M3 — [Medium] provider-catalog alignment test does not verify against real adapters
- **Evidence**: `test/semidx/workspace_state_test.clj:75-81` only asserts each catalog entry has
  `classification = "source"` and non-empty ids. Plan (lines 112-119) requires the test to assert
  the table matches adapters wired in `default-parser-opts`, so a new/changed adapter fails loudly.
  As written, a new language adapter yields `provider = nil` in `workspace_state.clj:60-78`
  (`merge … nil` is a no-op, no error) → a silently incomplete fingerprint.
- **Disposition**: accepted (open) — tighten the test to enumerate actual adapters and assert
  presence in `provider-catalog`.

### L1 — [Low] Staleness rule added to `decide-freshness` outside the plan's 8 rules
- **Evidence**: `src/semidx/runtime/freshness.clj:79-88` inserts `snapshot_stale → :full_rebuild`
  before the fingerprint-match reuse rule. Unchanged workspace past `max_snapshot_age_seconds`
  rebuilds instead of reusing, diverging from Acceptance Gate #1. Deliberate back-compat choice
  (`test/semidx/runtime_test.clj:1294` updated to `max_age_stale`), but undocumented in the plan.
- **Disposition**: accepted (open) — record the deviation in the plan or scope the rule so it does
  not break Gate #1.

### L2 — [Low] Minor cleanliness
- Dead parameter `provider-catalog-version` in `capture-workspace-state` (`workspace_state.clj:55`).
- `parse-instant`/`age-seconds`/`stale?` duplicated in `freshness.clj:7-20` and
  `index_lifecycle.clj:107-120`.
- `compute-workspace-fingerprint` relies on `pr-str` map key ordering for determinism
  (`workspace_state.clj:44-52`).
- **Disposition**: deferred — low-risk cleanup.

### Coverage gaps
- No Postgres round-trip test for `:workspace_state` JSONB (plan lines 234-240); only in-memory verified.
- No HTTP/gRPC test asserting lifecycle fields reach response serialization (Commit 7).
- Gates #12 (failed update leaves prior snapshot) and #13 (concurrent `create_index`) have no tests;
  given F1, treat Gate #13 as uncovered.

## Corrections (2026-07-13)

M2 fixed in place: the Commit 5 and Commit 7 entries above were rewritten to state that no source
files (`storage.clj`, `http.clj`, `grpc.clj`) were modified — the additive `:workspace_state` and
lifecycle fields propagate through the existing snapshot payload and `:index_lifecycle` map.

## Review Verification

- Pre-fix `clojure -M:test`: Passed (194 tests, 1335 assertions). Pass A one-off checks reproduced
  the H1 (`:concurrency_failure` on pinned+changed workspace) and H2/H3 (excluded `.py` indexed,
  `:active_languages nil`) defects.
- Post-fix `clojure -M:test`: Passed. Ran 197 tests containing 1344 assertions, 0 failures, 0 errors.
  The three new regression tests in `test/semidx/freshness_regression_test.clj` cover H1–H3 and now pass.

## Fix Summary (2026-07-13)

High-severity findings H1–H3 fixed in Pass B; regression tests added. Remaining open items:
M1 (deferred — needs a decision on CAS-before-publish vs. restating Gate #13 as single-process),
M3 (catalog/adapter alignment test), L1 (staleness-rule deviation), L2 (cleanliness). M2 already fixed.

- `src/semidx/runtime/index_lifecycle.clj`: activation resolved up front; pinned short-circuit
  (H1); active-path-restricted capture (H2); `:activation_metadata` threaded into incremental
  update (H3); removed duplicate discovery in the rebuild branch.
- `src/semidx/runtime/workspace_state.clj`: `capture-workspace-state` gains an optional
  `allowed-paths` arg restricting the manifest to the active language set (H2).
- `src/semidx/runtime/index.clj`: `update-index` accepts/forwards `:activation_metadata`,
  falling back to the prior snapshot's activation fields (H3).
- `test/semidx/freshness_regression_test.clj` (new) + `src/semidx/test_runner.clj` registration.
