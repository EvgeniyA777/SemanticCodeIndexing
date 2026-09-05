---
title: "Add Bounded Runtime-Edge Rate Limiting"
doc_type: "adr"
lifecycle: "accepted"
status: "accepted"
agent_action: "reference_for_context"
updated: "2026-08-02"
---

# ADR-044: Add Bounded Runtime-Edge Rate Limiting

## Context

Ingress, proxies, and hosts remain responsible for primary traffic shaping, but
the HTTP and gRPC runtimes currently have no local defense when they are exposed
directly or upstream controls are misconfigured. Both edges already extract
tenant and actor correlation fields and share usage-metrics and error-taxonomy
infrastructure.

## Decision

Add one shared, optional fixed-window limiter used by HTTP and gRPC runtime
edges. A positive `requests_per_window` enables it; absent configuration keeps
the existing default behavior. `subject_scope` selects tenant-wide buckets or
tenant-and-actor buckets, with explicit anonymous components when a selected
field is absent. State is local to a runtime process, uses a monotonic clock, and
is bounded by `max_subjects`; expired buckets are removed first and the
earliest-ending bucket is evicted when the bound is reached.

Authentication runs before limiting so unauthenticated callers cannot consume a
claimed tenant/actor bucket. HTTP health/capabilities and gRPC Health remain
exempt for liveness. Rejections use the unified `rate_limited` taxonomy entry:
HTTP `429` plus `Retry-After`, and gRPC `RESOURCE_EXHAUSTED` plus
`x-sci-retry-after-seconds`. When enabled, every limiter decision emits a
`rate_limit_decision` usage event carrying its allow/reject outcome, the limited
operation, and correlation context. SLO rejection rate is therefore computed
against limiter decisions rather than unrelated usage-event volume.

Configuration is identical on both edges:

- `--rate-limit-requests` / `SEMIDX_RUNTIME_RATE_LIMIT_REQUESTS`
- `--rate-limit-window-ms` / `SEMIDX_RUNTIME_RATE_LIMIT_WINDOW_MS`
- `--rate-limit-max-subjects` / `SEMIDX_RUNTIME_RATE_LIMIT_MAX_SUBJECTS`
- `--rate-limit-subject-scope` / `SEMIDX_RUNTIME_RATE_LIMIT_SUBJECT_SCOPE`
  (`tenant_actor`, the default, or `tenant`)

## Consequences

- Default local and in-memory behavior remains unchanged.
- HTTP and gRPC cannot drift onto different limiting algorithms or key rules.
- Limiter state is intentionally process-local and approximate across replicas;
  deployments requiring a global quota must continue using an ingress or shared
  rate-limit service.
- Fixed windows permit a boundary burst of up to twice the configured rate;
  this is accepted for a defense-in-depth limiter with bounded implementation
  and operational cost.
