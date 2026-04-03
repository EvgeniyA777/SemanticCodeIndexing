---
file_type: adr
decision_id: ADR-031
title: Distinguish Logical Repository From Workspace Checkout
status: proposed
date: 2026-04-03
deciders:
  - project owner
tags:
  - architecture
  - storage
  - identity
summary: Model repository identity with a logical repo key plus workspace-specific checkout metadata instead of treating root_path as the only identity dimension.
agent_summary: Read this ADR before changing multi-clone repository identity. The decision of record is to distinguish logical repository identity from workspace checkout identity while keeping root_path as a compatibility field.
supersedes: []
superseded_by: null
links:
  - plans/2026-04-03-multi-clone-repo-identity-rollout-plan.md
  - src/semidx/runtime/index.clj
  - src/semidx/runtime/storage.clj
  - src/semidx/runtime/project_context.clj
---

# ADR-031: Distinguish Logical Repository From Workspace Checkout

**Status**: Proposed  
**Date**: 2026-04-03  
**Deciders**: project owner

---

## Context

`semidx` currently uses `root_path` as the main practical identity key for both in-memory runtime coordination and persistent snapshot storage.

This works well for a single checkout of a repository on one machine.

It becomes insufficient when the same git repository exists in multiple clones or worktrees because:

- the same logical project appears as several unrelated roots
- snapshot reuse cannot distinguish project identity from checkout identity
- branch and commit metadata become disconnected from project-level history

The new decision is not about transport APIs or retrieval behavior. It is about the identity model that snapshot metadata should carry from this point forward.

## Decision Drivers

- preserve current behavior for existing callers
- support several clones or worktrees of the same repository
- keep branch and commit metadata observable but secondary
- avoid making branch names or root paths the only durable identity key
- keep implementation small enough for staged rollout

## Considered Options

### Option 1. Keep `root_path` as the only identity key

Continue treating each checkout path as a separate repository identity.

### Option 2. Add a logical repository key alongside workspace checkout identity

Model one logical repository plus one or more workspace checkouts and keep `root_path` as compatibility metadata.

### Option 3. Use branch name as the primary repository identity

Treat each branch as a top-level repository identity boundary.

## Decision

We accept Option 2: add a logical repository key alongside workspace checkout identity.

After this decision:

- a snapshot may describe one logical repository and one concrete checkout at the same time
- `repo_key` identifies the logical repository
- `workspace_path` and `workspace_key` identify the concrete checkout
- `git_branch`, `git_commit`, and `git_dirty` are snapshot metadata, not identity roots
- `root_path` remains present for backward compatibility and current callers

Option 1 loses because it keeps multi-clone support fundamentally impossible without higher-level heuristics.

Option 3 loses because branch names are unstable and are not reliable repository identity keys.

## Consequences

### Positive

- snapshots from several clones of the same repository can be related safely
- branch and commit metadata can be used for filtering and reuse without becoming the primary key
- current `root_path`-based code can continue to work during rollout

### Negative

- snapshot metadata becomes richer and more complex than the current single-key model
- identity derivation must handle no-remote and detached-head cases
- storage and tests must be extended to cover the new dimensions

### Follow-Up

- add a repo identity derivation module
- attach repo identity metadata to snapshots
- persist additive identity metadata in storage
- keep public APIs backward-compatible during the first rollout

## Status Changes

No status change yet.

## References

- [2026-04-03-multi-clone-repo-identity-rollout-plan.md](/Users/ae/workspaces/semidx/plans/2026-04-03-multi-clone-repo-identity-rollout-plan.md)
- [index.clj](/Users/ae/workspaces/semidx/src/semidx/runtime/index.clj)
- [storage.clj](/Users/ae/workspaces/semidx/src/semidx/runtime/storage.clj)
- [project_context.clj](/Users/ae/workspaces/semidx/src/semidx/runtime/project_context.clj)

## Definition Of Done

This decision is fully implemented when:

1. New snapshots contain `repo_key`, `workspace_path`, and `workspace_key`.
2. Git metadata is attached as additive snapshot metadata.
3. Existing `root_path`-based callers still work without request changes.
