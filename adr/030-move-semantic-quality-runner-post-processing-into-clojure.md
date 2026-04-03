---
file_type: adr
decision_id: ADR-030
title: Move Semantic-Quality Runner Post-Processing Into Clojure
status: proposed
date: 2026-04-02
deciders:
  - project owner
tags:
  - architecture
  - tooling
  - ci
summary: Consolidate semantic-quality runner post-processing into a Clojure-owned runner path and keep shell as a thin launcher.
agent_summary: Read this ADR before changing the semantic-quality runner path. The decision of record is to move post-processing, summary rendering, artifact writing, and exit semantics into a Clojure runner while keeping shell as a thin entrypoint.
supersedes: []
superseded_by: null
links:
  - notes/2026-04-02-semantic-quality-runner-post-processing-plan.md
  - scripts/run-semantic-quality-report.sh
  - src/semidx/runtime/evaluation.clj
---

# ADR-030: Move Semantic-Quality Runner Post-Processing Into Clojure

**Status**: Proposed  
**Date**: 2026-04-02  
**Deciders**: project owner

---

## Context

The semantic-quality advisory runner currently spans multiple implementation layers:

- a shell script that chooses paths and drives process flow
- inline Python that parses report JSON and renders markdown summary output
- Clojure code that generates the semantic-quality report itself

This split has already produced avoidable defects, including:

- temp-path vs final-path confusion in stdout
- stale artifact masking after runner failure
- drift between shell behavior, Python post-processing, and workflow expectations

The current flow works, but the change cost is too high for a path that is likely to be revisited. Small adjustments require reasoning across bash, Python, Clojure, and workflow artifact conventions at the same time.

The decision that now needs to be made is not about the semantic-quality report schema itself. It is about where runner post-processing and artifact semantics should live.

## Decision Drivers

- clear ownership of runner behavior
- lower change cost for CI and tooling paths
- stable stdout and artifact contract
- explicit distinction between advisory failure and execution failure
- fewer cross-language bugs in a Clojure-first codebase
- ability to test runner behavior without re-deriving shell and Python semantics

## Considered Options

### Option 1. Keep the current split across shell, inline Python, and Clojure

Retain the current mixed implementation and continue fixing issues at the layer where they appear.

### Option 2. Move post-processing into a dedicated Clojure-owned runner path

Keep report generation reusable, but move summary rendering, validation, artifact writing, stdout emission, and exit semantics into one Clojure runner path.

### Option 3. Move all semantic-quality behavior into the shell layer

Treat shell as the primary orchestration and contract layer and keep Clojure limited to raw report generation only.

## Decision

We accept Option 2: move semantic-quality runner post-processing into a dedicated Clojure-owned runner path.

After this decision:

- semantic-quality report generation remains reusable as a Clojure capability
- runner post-processing moves out of inline Python
- shell remains only a thin launcher with defaults and command invocation
- stdout, artifact writing, validation, and exit semantics are owned by one Clojure path

The chosen design separates two concerns that were previously entangled:

- report generation
- runner orchestration

`report generation` means computing the semantic-quality JSON report from the dataset.

`runner orchestration` means:

- validating the report shape
- rendering the markdown summary
- writing final artifacts atomically
- printing stable stdout metadata
- deciding whether the outcome is advisory success or execution failure

Option 1 loses because it preserves the exact multi-language seam that already created repeated defects.

Option 3 loses because it would push more semantic responsibility into shell, which is the least suitable place for structured validation, artifact contracts, and long-term maintainability.

## Consequences

### Positive

- runner behavior has one primary owner
- stdout and artifact semantics become easier to reason about
- summary rendering and validation can be tested in Clojure directly
- the shell wrapper becomes cheaper, thinner, and less error-prone
- future CI and tooling changes stay localized to one implementation seam

### Negative

- the codebase gains one more explicit runner-oriented Clojure path
- some behavior that was previously quick to sketch in shell or Python now needs proper Clojure implementation
- there is a short-term migration cost to move summary rendering, validation, and artifact writing into the new path
- if the runner grows carelessly, it can still over-centralize too much tooling logic in one namespace

### Follow-Up

- add a dedicated runner path or namespace for semantic-quality orchestration
- keep `scripts/run-semantic-quality-report.sh` as a thin launcher only
- move summary rendering and report validation into Clojure helpers
- ensure artifact writes are atomic and stdout emits final paths only
- add regression coverage for advisory success, execution failure, invalid reports, and stale-output replacement

## Status Changes

No status change yet.

If accepted, this ADR becomes the decision of record for the semantic-quality runner architecture.

## References

- [notes/2026-04-02-semantic-quality-runner-post-processing-plan.md](/Users/ae/workspaces/semidx/notes/2026-04-02-semantic-quality-runner-post-processing-plan.md)
- [run-semantic-quality-report.sh](/Users/ae/workspaces/semidx/scripts/run-semantic-quality-report.sh)
- [evaluation.clj](/Users/ae/workspaces/semidx/src/semidx/runtime/evaluation.clj)

## Definition Of Done

This decision is fully implemented when all of the following are true:

1. Semantic-quality runner post-processing no longer depends on inline Python.
2. Shell acts only as a thin launcher and does not own report validation, summary rendering, or exit semantics.
3. The runner emits final artifact paths on stdout rather than temp paths.
4. Advisory failure is represented as a successful run with a negative gate result, while execution failure exits nonzero.
5. Regression tests cover the runner contract at the Clojure-owned seam.
