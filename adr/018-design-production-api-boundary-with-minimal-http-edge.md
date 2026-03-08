# ADR-018: Design Production API Boundary with a Minimal HTTP Edge over the Library Runtime

**Status**: Accepted  
**Date**: 2026-03-08  
**Deciders**: project owner

---

## Context and Problem Statement

`ADR-001` established a library-first architecture.  
`ADR-002` established a compact public API and context packet contract.

The project now needs an explicit production API boundary phase that keeps library-first semantics intact, while enabling service-style integration where hosts require process isolation or network access.

Without an explicit boundary design, hosts will create ad hoc wrappers and drift in behavior, validation, and safety signals.

---

## Decision

We define a dedicated production-boundary phase with two parts:

1. **Boundary ADR and scope definition** (this ADR)
2. **Minimal HTTP edge** that directly delegates to the existing runtime library API

The HTTP edge is intentionally thin and does not replace core library ownership of retrieval semantics.

---

## Scope

### In scope now

- minimal HTTP server wrapper over `semantic-code-indexing.core`
- health endpoint
- index creation endpoint
- context resolution endpoint
- same contracts, confidence, diagnostics, and guardrails as library API output

### Out of scope now

- multi-tenant auth, policy, or billing
- horizontal scaling and distributed job orchestration
- gRPC transport implementation (deferred)
- workflow-level orchestration beyond retrieval runtime responsibilities

---

## Boundary Rules

- HTTP layer **MUST** remain thin and delegate behavior to the library runtime.
- HTTP layer **MUST NOT** introduce alternate ranking, confidence, or guardrail logic.
- HTTP layer **MUST NOT** become the primary architecture; library remains canonical.
- New transports (including gRPC) **MUST** map to the same public runtime semantics.

---

## Why this shape

This preserves library-first correctness while enabling production-style deployment boundaries. It also gives hosts a stable edge contract without duplicating retrieval logic.

---

## Consequences

### Positive

- clearer migration path from embedded runtime to service boundary
- less integration drift across hosts
- keeps tests and benchmark governance anchored in one runtime behavior

### Tradeoff

- introduces one more wrapper surface to maintain
- does not yet provide advanced service features (auth, tenancy, gRPC)

---

## Follow-ups

- add gRPC edge with the same request/response semantics
- define auth and tenancy model as separate ADRs
- add service-level conformance tests for HTTP and future gRPC parity
