---
file_type: adr
decision_id: ADR-032
title: Roll Out Repo-Aware Snapshot Storage In Shadow Mode
status: proposed
date: 2026-04-03
deciders:
  - project owner
tags:
  - architecture
  - storage
  - rollout
summary: Introduce repo-aware snapshot persistence and lookup as additive behavior in shadow mode before any default runtime reuse changes.
agent_summary: Read this ADR before changing snapshot lookup behavior for multi-clone repositories. The decision of record is to add repo-aware storage and shadow lookup first while keeping root_path-first behavior as the default.
supersedes: []
superseded_by: null
links:
  - plans/2026-04-03-multi-clone-repo-identity-rollout-plan.md
  - src/semidx/runtime/storage.clj
  - src/semidx/runtime/index.clj
  - src/semidx/runtime/project_context.clj
---

# ADR-032: Roll Out Repo-Aware Snapshot Storage In Shadow Mode

**Status**: Proposed  
**Date**: 2026-04-03  
**Deciders**: project owner

---

## Context

Once snapshot metadata carries logical repository identity, `semidx` still needs a safe rollout path for persistence and lookup.

The current runtime behavior is stable because:

- storage lookups are `root_path`-based
- runtime registry ownership is `root_path`-based
- snapshot reuse expectations are simple and local

A direct cutover to repo-aware reuse would raise change risk in several layers at once:

- storage reads
- runtime registry coordination
- branch and commit reuse rules
- debugging and operator expectations

The decision to make now is not whether repo-aware reuse is valuable. It is how to introduce it without destabilizing the existing flows.

## Decision Drivers

- preserve existing runtime behavior during rollout
- enable additive persistence of richer snapshot metadata
- make repo-aware lookup testable before it affects default behavior
- reduce migration risk across storage, registry, and transport edges
- keep rollback trivial

## Considered Options

### Option 1. Cut over immediately to repo-aware lookup

Replace `root_path`-first behavior as soon as repo metadata exists.

### Option 2. Introduce repo-aware persistence and lookup in shadow mode first

Keep default behavior unchanged while adding new persistence fields and additive helper lookups for testing and diagnostics.

### Option 3. Avoid repo-aware lookup entirely and keep metadata informational only

Persist identity metadata but never use it for reuse or lookup.

## Decision

We accept Option 2: introduce repo-aware persistence and lookup in shadow mode first.

After this decision:

- storage will dual-write repo-aware snapshot metadata
- new lookup helpers may query by `repo_key`, `repo_key + git_branch`, and `repo_key + git_commit`
- default runtime behavior remains `root_path`-first
- repo-aware reuse may be observed, tested, and diagnosed before it changes default behavior

Option 1 loses because it couples schema migration, identity rollout, and default selection behavior into one risky step.

Option 3 loses because it collects metadata without creating a path to useful multi-clone reuse.

## Consequences

### Positive

- rollout risk stays localized
- old callers and existing runtime flows continue to behave the same
- repo-aware lookup logic can be validated with targeted tests before enablement
- rollback remains simple because default behavior is not replaced

### Negative

- there is temporary duplication between legacy and repo-aware lookup paths
- storage code becomes more complex during the transition period
- operators may see metadata and shadow results before the behavior is fully enabled

### Follow-Up

- add nullable storage columns for repo-aware metadata
- dual-write metadata on snapshot save
- add additive repo-aware lookup helpers
- keep runtime registry semantics unchanged for the first rollout
- decide later whether to enable repo-aware reuse in guarded modes

## Status Changes

No status change yet.

## References

- [2026-04-03-multi-clone-repo-identity-rollout-plan.md](/Users/ae/workspaces/semidx/plans/2026-04-03-multi-clone-repo-identity-rollout-plan.md)
- [storage.clj](/Users/ae/workspaces/semidx/src/semidx/runtime/storage.clj)
- [index.clj](/Users/ae/workspaces/semidx/src/semidx/runtime/index.clj)
- [project_context.clj](/Users/ae/workspaces/semidx/src/semidx/runtime/project_context.clj)

## Definition Of Done

This decision is fully implemented when:

1. Postgres storage persists additive repo-aware metadata without breaking older reads.
2. Repo-aware lookup helpers exist and are covered by tests.
3. Default runtime behavior still resolves snapshots through the current compatibility path unless explicitly opted into a later mode.
