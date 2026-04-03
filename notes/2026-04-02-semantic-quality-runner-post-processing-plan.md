---
file_type: working-note
topic: semantic-quality-runner-post-processing-consolidation
created_at: 2026-04-02T13:05:00-0700
author: codex
language: en
status: active
reason: Created because the semantic-quality advisory runner has already required multiple fixes on the bash plus inline-python boundary, and we expect to revisit this runner again. This note preserves the full architecture plan so the next pass does not have to reconstruct the rationale.
---

# Working Note: Move Semantic-Quality Runner Post-Processing Into One Clojure Path

## Why This Note Exists

This note exists because the semantic-quality advisory runner has already shown a repeated failure pattern at the boundary between:

- bash orchestration
- inline Python post-processing
- Clojure report generation
- workflow artifact expectations

We already had to fix multiple issues in this area:

- stale artifact masking
- temp-path vs final-path confusion in stdout
- path drift between runner behavior and workflow expectations

The runner is likely to be touched again, so this note captures the full architecture plan for consolidating post-processing into one Clojure-owned path rather than re-deriving it later.

## Problem Summary

The current runner flow is split across multiple layers:

- a shell script chooses paths and controls process flow
- inline Python parses JSON and renders markdown summary output
- Clojure builds the semantic-quality report
- GitHub Actions consumes artifacts and summary files

This creates a maintenance problem:

- one behavior is spread across several technologies
- ownership is unclear
- fixes tend to patch symptoms at the boundary instead of shrinking the boundary

The path works, but the change cost is higher than it needs to be.

## Architecture Plan

### Goal

Move semantic-quality runner post-processing out of `bash+python` and into one Clojure path while keeping the shell layer as a thin launcher.

### Scope

Only the advisory semantic-quality runner path:

- report invocation
- summary rendering
- artifact writing
- stdout contract
- exit semantics

Not in scope:

- changing the semantic-quality report schema
- new network surfaces for semantic-quality reporting
- larger refactors of unrelated evaluation commands

### Assumptions

- the shell wrapper may still be useful as a stable script entrypoint
- future change is more likely in runner UX and artifact semantics than in the report schema itself
- there is no near-term need for multiple runner implementations, so protocols would be overkill

## Boundaries

### 1. `semidx.runtime.evaluation`

**Responsibility**

- build semantic-quality reports from datasets

**Knows about**

- dataset structure
- thresholds
- metrics
- gate decision

**Does not know about**

- shell behavior
- markdown summary file UX
- stdout protocol
- workflow artifact conventions

**Why this boundary exists**

- report-generation policy should stay reusable independently of tooling ergonomics

### 2. `semidx.runtime.semantic-quality-runner`

**Responsibility**

- orchestrate runner behavior for CI and local tooling

**Knows about**

- dataset path
- final artifact paths
- summary rendering
- report validation
- atomic file replacement
- stdout contract
- exit semantics

**Does not know about**

- workflow YAML details
- shell default path selection

**Why this boundary exists**

- artifact-writing and CLI UX are a separate axis of change from report generation

### 3. `scripts/run-semantic-quality-report.sh`

**Responsibility**

- thin launcher only

**Knows about**

- default argument values
- how to invoke the Clojure runner command

**Does not know about**

- JSON parsing
- markdown generation
- temp-file logic
- advisory vs hard-failure rules

**Why this boundary exists**

- the shell should stay cheap and dumb, not become a second source of truth

## Contracts

### Runner Command Contract

Suggested command:

- `clojure -M:eval semantic-quality-runner --dataset <path> --out <report.json> --summary-out <summary.md>`

### Inputs

- `--dataset <path>`
- `--out <report.json>`
- `--summary-out <summary.md>`

### Outputs

- writes validated final JSON report
- writes markdown summary

### Stdout

- `semantic_quality_gate=eligible|advisory_failure`
- `semantic_quality_report=<final path>`
- `semantic_quality_summary=<final path>`

### Exit Semantics

- exit `0` if the report was successfully built and written, even if the gate result is advisory failure
- exit nonzero for:
  - dataset read failure
  - report generation failure
  - invalid report shape
  - summary rendering failure
  - write/move failure

### Important Distinction

- advisory failure means the report is valid and the gate result is negative
- execution failure means the runner did not successfully produce a trustworthy report

Those must never be conflated.

## Dependency Direction

- `semantic-quality-runner` depends on `evaluation/semantic-quality-report-from-dataset`
- shell script depends on the runner command only
- workflow depends on shell or runner stdout contract only
- report-generation policy does not depend on shell, Python, or workflow logic

## Why One Clojure Path Is Better

### What improves

- changes stay localized
- stdout behavior is easier to reason about
- temp/final artifact semantics are controlled in one place
- validation and summary rendering live next to report semantics
- no mixed-language bug at the bash/Python seam

### What we avoid

- drift between shell and Python logic
- printing internal temp paths to users
- stale artifact reuse logic leaking through shell checks
- duplicated path semantics in workflow and runner

## Recommended Internal Design

### Pure helpers

Add small functions such as:

- `render-semantic-quality-summary`
- `validate-semantic-quality-report`
- `write-json-atomically`
- `write-text-atomically`

These should stay small and direct. No protocol is needed unless a second output backend becomes real.

### Runner orchestration function

One orchestrator should:

1. read dataset
2. generate report
3. validate report
4. render summary
5. write temp artifacts
6. atomically move them into final locations
7. print stdout contract
8. choose the final exit code

## Risks

### Risk 1: `evaluation.clj` gets too crowded

**Why it matters**

- too many command paths in one file become harder to reason about

**Mitigation**

- place new runner helpers in a dedicated namespace and keep only a thin command adapter in `evaluation.clj`

### Risk 2: shell grows back into a second source of truth

**Why it matters**

- the same class of bugs returns if logic drifts back into bash

**Mitigation**

- limit the shell script to defaults plus a single Clojure invocation

### Risk 3: overengineering a small utility path

**Why it matters**

- this is tooling, not the main runtime

**Mitigation**

- keep design minimal: one runner seam, pure helpers, no speculative interface layer

## Implementation Sequence

### Step 1. Add pure Clojure helpers

Add:

- summary rendering helper
- report validation helper
- atomic JSON write helper
- atomic text write helper

This proves the internal shape before touching the shell entrypoint.

### Step 2. Add a dedicated runner command

Implement:

- `semantic-quality-runner`

The command should own:

- final stdout lines
- exit semantics
- artifact writing

### Step 3. Shrink the shell wrapper

Reduce `scripts/run-semantic-quality-report.sh` to:

- default path handling
- invocation of the runner command

Remove all inline Python.

### Step 4. Keep workflow stable

Continue passing explicit final paths from workflow.

Workflow should consume:

- final summary file
- final JSON report
- stdout contract

Nothing else.

### Step 5. Add regression coverage

Tests should cover:

- advisory result with exit `0`
- final-path stdout
- execution failure with nonzero exit
- atomic replacement of stale outputs
- invalid report rejection

## Decision Summary

Short version:

- keep shell thin
- move post-processing into Clojure
- separate report generation from runner orchestration
- treat temp files as internal only
- keep advisory failure distinct from execution failure

## Follow-Up Guidance

When this area is touched again:

- do not add new parsing/rendering logic back into bash
- do not reintroduce inline Python
- prefer extending the Clojure runner contract instead
