---
title: "Review — Stage 0+1 Workspace Freshness Plan"
doc_type: "review_report"
reviewed_document: "plans/008_stage_0_1_workspace_freshness.md"
reviewer: "claude"
lifecycle: "completed"
status: "final"
agent_action: "historical_reference_only"
created: "2026-07-13"
updated: "2026-08-02"
---

# Review Report: Stage 0+1 — Contract Baselines and Workspace Freshness

Reviewed document: `plans/008_stage_0_1_workspace_freshness.md`.
Claims were cross-checked against the current codebase where possible (grounded
findings are marked as such), not assessed on the prose alone.

## Overall assessment

The design is strong. The decomposition is correct: a pure
`freshness/decide-freshness` (independently testable), a content-addressed
`workspace_state` capture, and a single `index_lifecycle` coordinator shared by
all entry surfaces (MCP/HTTP/gRPC/library). SHA-256 content digest as the
authoritative identity, mtime/size as acceleration hints, a TDD approach with
failing baseline tests as the acceptance gate (Commit 1), and additive,
backward-compatible fields are all sound choices. The overall direction is
approved; the items below should be resolved before implementation.

**Positive, confirmed in code:** `update-index` (`src/semidx/runtime/index.clj:671`)
reconstructs the full `callers_index` / `callees_index` / `module_dependents`
graph via `build-index-state` over the merged unit set, so an incremental update
does not leave stale cross-file edges. Acceptance gate #10 (incremental ≡ full
rebuild) is architecturally supported.

## HIGH — contradictions / design gaps

### 1. Absolute freshness guarantee vs. the hash-skip optimization
The Goal states `create_index` must **never** return a stale snapshot, but the
rule "`modified_at` + `size_bytes` are used only to skip hashing unmodified
files" opens a hole: a content change that preserves **both** mtime and size
(in-place same-size edit with mtime restored, `touch`, some git operations) is
skipped and wrongly reused. Resolve one way or the other: either always hash
(and soften "never" with a performance note), or explicitly accept a
Make-level trade-off and rewrite the guarantee. As written, the plan
contradicts itself.

### 2. Concurrency (gate #13) is asserted but not designed
"Publish under the existing project-registry refresh lock" does not define the
critical-section granularity. If only publication is locked, two concurrent
`create_index` calls can (a) both full-rebuild redundantly, or (b) worse, a slow
build over an older state publishes **over** a newer snapshot (lost update /
TOCTOU). Specify what runs inside the lock and add a compare-and-set on
`workspace_fingerprint` at publish time. No test is planned for gate #13.

### 3. Deleted paths are not routable as designed
Step 4 says "handle `deleted_paths` via `remove-paths-from-index`", but that
function is private (`defn-`, `src/semidx/runtime/index.clj:659`) and
`update-index` only accepts `changed_paths`. A coordinator in a different
namespace cannot call the private helper. Either export it, or fold deletions
into `changed_paths` (then `parse-files` over a missing file yields nothing →
effective deletion).

### 4. Double persistence / overlapping ownership
`update-index` already calls `maybe-save-index!` (`src/semidx/runtime/index.clj:694`),
while coordinator step 6 also persists and step 7 publishes under the lock. This
is overlapping responsibility (double write / lock-ordering ambiguity). Define a
single owner of the save/publish step.

## MEDIUM

### 5. Manually bumped version constants are a footgun
`provider_registry_version` / `semantic_pipeline_version` are constants that a
developer must remember to bump on any breaking parser/schema change, with no CI
guard. At minimum add a test that fails loudly on a mismatch, or derive the value
from a hash of the relevant code.

### 6. The hardest gates have no tests
Gates #12 (a failure during update leaves the previous snapshot active) and #13
(concurrency) appear in no per-commit test matrix. These are the riskiest gates;
add failure-injection and concurrency tests (in Commit 4 or a dedicated commit).

### 7. `cache_hit` semantics change
`cache_hit` becomes `true` only for `:reuse`, excluding `:incremental_update`.
Existing consumers may have read it as "no full work happened". Document the
behavioral change.

### 8. Delta threshold (50%) is arbitrary
Rule 7 has no rationale and is not configurable. Provide a justification and make
it a parameter.

## LOW / nits

- **9.** Capture runs on every `create_index` (a stat-walk of all files); note
  the cost model for large repositories.
- **10.** Frontmatter `agent_action: reference_for_context` conflicts with
  `doc_type: implementation_plan`; align the metadata.
- **11.** `discovery_profile_hash` inputs are vague (".gitignore, paths, etc.");
  pin down the exact input list.
- **12.** Rules 5 and 6 are partially redundant (a version change already alters
  the fingerprint), but the ordering is correct — leave as is.

## Recommended order of remediation

1. Items **#1** and **#2** — they concern the stated P0 correctness guarantee.
2. Items **#3** and **#4** — concrete implementation gaps.
3. Item **#6** — tests for the two hardest gates.
4. The remaining items are polish.
