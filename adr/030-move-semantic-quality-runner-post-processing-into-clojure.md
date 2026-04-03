# ADR-030: Move Semantic-Quality Runner Post-Processing Into Clojure

**Status**: Proposed  
**Date**: 2026-04-02  
**Deciders**: project owner

---

## Context and Problem Statement

The semantic-quality advisory path currently spans multiple layers:

- a shell runner script
- inline Python used for report post-processing and summary rendering
- the existing Clojure evaluation command that builds the semantic-quality report

This split has already produced avoidable bugs:

- temp-path vs final-path confusion in stdout
- stale artifact masking on runner failure
- drift between shell behavior, Python summary generation, and workflow expectations

The current implementation is workable, but the change surface is fragmented. Each edit to runner behavior requires reasoning across:

- bash path handling
- bash exit semantics
- Python JSON parsing and markdown rendering
- Clojure report generation
- GitHub workflow artifact assumptions

This is not a runtime product-path problem, but it is a recurring CI and tooling maintenance problem. We expect to revisit the runner again, so keeping post-processing split across `bash+python` raises future change cost unnecessarily.

---

## Decision

### 1. Move runner post-processing into a single Clojure path

Post-processing responsibilities will move out of inline Python and into Clojure.

That includes:

- reading the semantic-quality dataset
- invoking report generation
- validating the resulting report shape
- rendering the markdown summary
- writing report and summary artifacts atomically
- printing the stdout contract
- deciding execution vs advisory exit semantics

### 2. Keep shell as a thin entrypoint only

`scripts/run-semantic-quality-report.sh` remains as a thin launcher that:

- provides default argument values
- invokes the Clojure command

It will not own:

- JSON parsing
- summary rendering
- temp-file orchestration
- stdout protocol details
- report validation logic

### 3. Keep report generation separate from runner orchestration

The existing semantic-quality report generation logic remains reusable as a library/eval capability.

The system distinguishes between:

- **report generation**
  - produce the semantic-quality JSON report
- **runner orchestration**
  - validate it
  - render summary
  - write artifacts
  - print stdout metadata
  - return correct exit semantics

This keeps CI/tooling concerns from leaking further into core evaluation logic.

### 4. Define a stable runner contract

The new Clojure runner path must provide:

#### Inputs

- `--dataset <path>`
- `--out <report.json>`
- `--summary-out <report.md>`

#### Side effects

- atomically write final JSON report
- atomically write final markdown summary

#### Stdout

- `semantic_quality_gate=eligible|advisory_failure`
- `semantic_quality_report=<final path>`
- `semantic_quality_summary=<final path>`

#### Exit semantics

- exit `0` when the report is successfully built, even if gate result is advisory failure
- exit nonzero on:
  - dataset read failure
  - report generation failure
  - invalid report structure
  - summary rendering failure
  - artifact write failure

### 5. Treat temp artifacts as internal implementation detail

If temp files are used, they are internal only.

They must never leak into the user-facing stdout contract.

The caller should only see final artifact paths.

---

## Architectural Consequences

### Positive

- future edits stay localized to one Clojure runner path
- stdout behavior, artifact writing, and exit semantics become easier to reason about
- workflow integration becomes simpler because it consumes one stable contract
- inline Python is removed from a Clojure-first codebase path

### Tradeoffs

- one more command path or helper namespace is introduced
- some lightweight scripting convenience moves into application code
- runner logic becomes more explicit and structured, which is slightly more code than a shell snippet

### What We Are Not Doing

- we are not changing the semantic-quality report schema itself
- we are not adding MCP/HTTP/gRPC surfaces for semantic-quality reporting
- we are not introducing protocol-based abstraction unless another runner implementation becomes real

---

## Boundary Design

### A. `semidx.runtime.evaluation`

**Responsibility**

- build semantic-quality reports from datasets

**Knows about**

- dataset structure
- thresholds
- metrics
- gate decision

**Does not know about**

- shell concerns
- markdown summary file writing
- stdout contract
- CI artifact UX

**Why this boundary exists**

- report-generation policy should remain reusable and testable independently of runner UX

### B. `semidx.runtime.semantic-quality-runner`

**Responsibility**

- orchestrate semantic-quality report execution for tooling and CI

**Knows about**

- dataset path
- output paths
- summary rendering
- report validation
- atomic writes
- stdout lines
- exit semantics

**Does not know about**

- workflow YAML details
- shell defaults

**Why this boundary exists**

- runner UX and artifact semantics are a separate axis of change from report generation

### C. `scripts/run-semantic-quality-report.sh`

**Responsibility**

- thin process launcher

**Knows about**

- default paths
- how to invoke the Clojure command

**Does not know about**

- JSON parsing
- summary rendering
- advisory vs execution semantics
- temp-file handling rules

**Why this boundary exists**

- shell remains cheap and replaceable instead of becoming the owner of business logic

---

## Contract Shape

### Runner Command

Suggested command:

- `clojure -M:eval semantic-quality-runner --dataset <...> --out <...> --summary-out <...>`

Why a separate command:

- keeps `semantic-quality-report` focused on report generation
- avoids overloading one command with both report semantics and CI UX semantics
- gives the shell wrapper a single stable target

### Helper Functions

Introduce small Clojure helpers:

- `render-semantic-quality-summary`
- `validate-semantic-quality-report`
- `write-json-atomically`
- `write-text-atomically`

These should be simple functions, not protocols, because there is no credible second implementation yet.

---

## Risks

### 1. Command Sprawl In `evaluation.clj`

**Why it matters**

- `evaluation.clj` already contains several command paths

**Mitigation**

- place runner helpers in a dedicated namespace and expose only a thin command adapter from `evaluation.clj`

### 2. Recreating Drift In Workflow Or Shell

**Why it matters**

- if shell or workflow starts rebuilding runner logic, the same class of bug returns

**Mitigation**

- keep shell and workflow consuming only the runner contract, never reproducing report semantics

### 3. Overengineering A Small Utility Path

**Why it matters**

- this is a tooling path, not the main product runtime

**Mitigation**

- keep the design to one thin shell wrapper plus one Clojure runner seam, no plugin architecture

---

## Implementation Sequence

1. Add pure Clojure helpers for summary rendering, report validation, and atomic file writing.
2. Add a dedicated runner command that calls report generation and owns stdout + exit semantics.
3. Reduce `scripts/run-semantic-quality-report.sh` to a thin launcher.
4. Keep workflow paths explicit and unchanged from the caller perspective.
5. Add regression tests for:
   - advisory success with final-path stdout
   - nonzero execution failure
   - invalid report rejection
   - atomic overwrite of stale output

---

## Follow-Ups

1. If runner behavior evolves again, prefer extending the Clojure runner rather than reintroducing shell-side parsing.
2. Consider moving the runner implementation into a dedicated namespace instead of growing `evaluation.clj` further.
3. Reassess whether the shell wrapper is still needed once the Clojure command contract is stable.
