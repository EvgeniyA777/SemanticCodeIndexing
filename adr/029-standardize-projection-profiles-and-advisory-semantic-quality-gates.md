# ADR-029: Standardize Projection Profiles and Add Advisory Semantic-Quality Gates

**Status**: Proposed  
**Date**: 2026-04-02  
**Deciders**: project owner

---

## Context and Problem Statement

The runtime now has several distinct output shapes that are all valid, but they have not been described consistently:

- compact staged selection
- widened structural/API context
- rich detail packets
- bounded project compression summaries
- exact literal file slices
- semantic snapshot diffs

At the same time, semantic quality evaluation exists as a library/eval capability, but it is not yet part of the normal developer feedback loop in CI.

Without a shared projection vocabulary:

- clients must infer payload shape from endpoint names alone
- downstream orchestration has no explicit way to reason about what kind of context a payload represents
- later additions risk introducing ad hoc output labels

Without an operational semantic-quality lane:

- semantic identity and snapshot diff can regress silently
- developers do not see advisory failures until they run offline evaluation manually
- the project cannot gather artifact history before deciding whether a future gate should become blocking

This ADR defines a small shared projection taxonomy and a first operational CI posture for semantic-quality reporting.

---

## Decision

### 1. Projection metadata becomes a first-class additive contract

Public outputs now carry:

- `projection_profile`
- optionally `recommended_projection_profile`

The v1 projection taxonomy is fixed to:

- `structural`
- `summary`
- `selection`
- `api_shape`
- `detail`
- `literal_slice`
- `diff`

### 2. `recommended_projection_profile` is emitted only on progressive outputs

`recommended_projection_profile` is used only when the current output is part of a refinement path.

Examples:

- `repo_map` => recommends `selection`
- `compress-project` => recommends `selection`
- `resolve-context` => recommends `api_shape`
- `expand-context` => recommends `detail`
- `skeletons` => recommends `detail`

Terminal outputs do not emit a recommended projection:

- `fetch-context-detail`
- `resolve-context-detail`
- `literal-file-slice`
- `snapshot-diff`

### 3. `skeletons` remains backward-compatible in the library API

The library `skeletons` function keeps its vector return shape.

Its projection metadata is attached as collection metadata in the library surface and materialized as normal response fields on MCP/transport wrappers.

### 4. Semantic-quality CI is advisory first

Semantic-quality reporting is wired into CI as an artifact-producing advisory lane.

The v1 posture is:

- generate a deterministic semantic-quality report in CI
- publish JSON and markdown artifacts
- surface the gate result in the workflow summary
- do not block merge purely because the semantic-quality gate is not eligible

Execution failures still fail the job. Gate failure remains advisory.

### 5. `semantic-quality-report` stays library/eval-only in this phase

No new MCP, HTTP, or gRPC surface is added for semantic-quality reporting in this tranche.

The operational path is:

- library
- offline eval CLI
- advisory CI job

---

## Consequences

### Positive

- clients can reason about output shape explicitly instead of inferring it from tool names
- staged retrieval and adjacent outputs now share one vocabulary
- semantic quality becomes visible in CI before it becomes merge-blocking
- the project gains deterministic artifacts for future threshold and gate tuning

### Tradeoffs

- one more additive field appears on several public payloads
- library/vector-returning `skeletons` needs metadata handling instead of a plain map conversion
- semantic-quality gate status is visible but not yet enforceable in CI

### Follow-Ups

1. Reassess whether semantic-quality CI should become blocking once the fixture corpus is broad enough and false positives are rare.
2. Keep future output shapes inside the fixed projection taxonomy unless a new ADR justifies an expansion.
3. Extend docs/examples when additional transport surfaces or refinement outputs adopt new projection semantics.
