# Multi-Clone Repo Identity Rollout Plan

## Goal

Add clone-aware and branch-aware repository identity to `semidx` without breaking the current `root_path`-based behavior.

## Scope

This plan covers:

- repository identity derivation for multiple clones of the same git repository
- additive snapshot metadata on newly built indexes
- additive storage schema changes for persistent snapshots
- shadow-mode repo-aware lookup helpers
- staged rollout with backward-compatible defaults

This plan does not cover:

- switching the runtime registry from workspace ownership to repo ownership
- making repo-aware reuse the default behavior
- changing the current MCP, HTTP, or gRPC request shapes

## Assumptions

- `root_path` remains the current compatibility key for active runtime behavior.
- Existing in-memory and Postgres storage users must continue to work without request changes.
- Repo-local compression artifacts in `docs/code-context.md` and `.ccc/*` remain local to each checkout.
- A branch name is useful metadata, but not a stable identity key.

## Rollout Model

### Stage 0. Planning Artifacts

Create one implementation plan and separate ADRs for:

- repository identity model
- backward-compatible staged rollout and shadow-mode reuse

Deliverables:

- this plan
- new ADRs under `adr/`

### Stage 1. Additive Repo Identity Metadata

Goal:

- compute and attach repo/workspace/git metadata to newly created snapshots without changing lookup behavior

Primary files:

- `src/semidx/runtime/repo_identity.clj` (new)
- `src/semidx/runtime/index.clj`
- `src/semidx/core.clj`
- tests for identity derivation and metadata attachment

Success criteria:

- new indexes include repo identity metadata
- existing create/update flows behave the same for callers
- no storage migration is required yet

### Stage 2. Additive Storage Persistence

Goal:

- persist repo identity metadata in storage while preserving all current read paths

Primary files:

- `src/semidx/runtime/storage.clj`
- storage tests

Success criteria:

- Postgres storage dual-writes new metadata fields
- old rows remain readable
- existing `load-latest-index` and `load-index-by-snapshot` continue to work

### Stage 3. Shadow Repo-Aware Lookup

Goal:

- add repo-aware lookup helpers without changing default selection behavior

Primary files:

- `src/semidx/runtime/storage.clj`
- `src/semidx/runtime/index.clj`
- tests for shadow lookup helpers

Success criteria:

- repo-aware lookup can find latest snapshots by repo and branch
- default runtime behavior remains `root_path`-first
- repo-aware reuse is available only behind explicit opt-in or shadow flows

## Boundaries

### 1. Repo Identity Derivation

Responsibility:

- derive `repo_key`, `workspace_path`, `workspace_key`, `git_branch`, `git_commit`, and `git_dirty`

Does not know about:

- SQL schema
- runtime registry semantics
- transport layers

### 2. Snapshot Metadata Attachment

Responsibility:

- add identity metadata to index payloads and lifecycle summaries

Does not know about:

- how storage persists rows
- how runtime chooses reuse candidates

### 3. Storage Compatibility

Responsibility:

- persist and read new metadata safely

Does not know about:

- git command strategy
- runtime session ownership

### 4. Repo-Aware Reuse

Responsibility:

- provide additive lookup helpers for future multi-clone reuse

Does not know about:

- registry locking
- request transport details

## Compatibility Rules

- Do not remove or repurpose `root_path`.
- Do not require new request fields in public APIs.
- Do not change the default runtime registry key in this rollout.
- Do not make branch name an identity key.
- Do not enable cross-clone reuse by default in the first implementation pass.

## Test Strategy

1. Add identity derivation tests for:
   - shared remote across two clones
   - no-remote fallback
   - detached HEAD
2. Add index metadata tests for:
   - `create-index`
   - `update-index`
3. Add storage migration tests for:
   - missing nullable columns on older persisted rows
   - dual-write correctness
4. Add repo-aware lookup tests for:
   - latest by repo
   - latest by repo plus branch
   - exact by repo plus commit
5. Keep all existing storage and runtime tests green.

## Commit Plan

1. `Stage 0`: plan and ADR artifacts
2. `Stage 1`: repo identity metadata only
3. `Stage 2`: storage schema and dual-write
4. `Stage 3`: shadow repo-aware lookup helpers and tests

Each stage ends with a dedicated commit and push on branch `dev`.
