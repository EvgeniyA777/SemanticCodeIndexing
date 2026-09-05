---
title: "Scope Online Policy Control-Plane API"
doc_type: "adr"
lifecycle: "accepted"
status: "accepted"
agent_action: "reference_for_context"
updated: "2026-08-02"
---

# ADR 043: Scope Online Policy Control-Plane API

## Context

Currently, `semidx` relies on offline governance for retrieval policies via server-configured registries and `clojure -M:eval` (or similar offline tooling) for lifecycle management (`draft`, `shadow`, `active`, `retired`). `authz.clj` provides local file/callback authorization. Broader online policy-management APIs have been intentionally absent.
As the operational maturity of the project grows (Stage 6 of the gaps closure program), there is a need for a bounded online policy-management/control-plane API to introspect the registry and safely promote or retire policies without bypassing the authoritative offline governance gates.

## Decision

We will add a bounded online policy control-plane surface with the following constraints:
1. **Introspection**: A read endpoint to list policies in the registry and view their state and governance metadata.
2. **Lifecycle Hooks**: Write endpoints to promote (`active`) and retire (`retired`) policies.
3. **Guardrails**: The online API MUST NOT bypass promotion gates, protected-case checks, or approval tiers. Offline shadow review emits a promotion decision bound to the candidate digest, active-baseline digest, registry revision, dataset revision, and gate version. Online promotion validates that artifact instead of rerunning evaluation or trusting a caller-supplied boolean.
4. **Manual Approval**: Restricted policies require a separate approval record bound to the current decision id and carrying an approver actor, the `policy_approver` role, and an approval timestamp. An arbitrary `approval_id` is not evidence of approval.
5. **Authorization Boundary**: These endpoints are protected by `tenant_id` authorization plus deny-by-default `allowed_operations` checks for `policy_read`, `policy_promote`, and `policy_retire`.
6. **Consistency Boundary**: Promotion and retirement run under one in-process registry lock. The runtime writes a same-directory temporary file, replaces the configured registry file, and only then publishes the new in-memory registry state.
7. **Contract First**: Requests and responses are defined via JSON Schema, mirrored in `malli`, and lifecycle request payloads are validated by the HTTP runtime.

The endpoints will be exposed over the runtime HTTP edge (and mirrored on gRPC/MCP where applicable). They will reuse the existing unified error taxonomy (`invalid_request`, `forbidden`, `internal_error`).

## Consequences

- **Positive**: Operators can introspect active/shadow policies and execute safe promotions/retirements without direct registry mutation. Every promotion is attributable to a concrete offline decision and, when required, an approver record.
- **Negative**: Decision artifacts and approval records add registry metadata and must be retained until the corresponding transition is completed or invalidated.
- **Constraints**: Offline governance remains authoritative. Any candidate, baseline, registry, or gate-version change invalidates the decision and requires a fresh offline review. Serialization is process-local; deployments must not run multiple writers against one registry file without an external coordinator.
