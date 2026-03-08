# ADR-019: Define Service Auth, Tenancy, and Rate-Limit Boundary for Runtime HTTP/gRPC Edges

**Status**: Accepted  
**Date**: 2026-03-08  
**Deciders**: project owner

---

## Context and Problem Statement

`ADR-018` introduced minimal HTTP boundary over the library runtime and established edge wrappers as thin transport layers.

After adding HTTP and gRPC edges, service-mode policy boundaries must be explicit for:

- authentication
- tenant scoping
- request throttling/rate limiting

Without explicit policy, hosts can run edges in ambiguous security posture and produce unsafe multi-tenant behavior.

---

## Decision

Service edges adopt the following baseline policy model:

1. **Authentication**: optional shared API key gate at the runtime edge (`x-api-key`).
2. **Tenancy boundary**: optional required tenant identity (`x-tenant-id`).
3. **Rate limiting boundary**: handled by deployment edge/proxy (not by retrieval runtime core).

---

## Policy Details

### Authentication

When configured, protected endpoints/methods require an API key match.

- HTTP protected endpoints: `POST /v1/index/create`, `POST /v1/retrieval/resolve-context`
- gRPC protected methods: `CreateIndex`, `ResolveContext`
- Health checks remain unauthenticated (`/health`, `Health`)

Configuration:

- CLI flags: `--api-key <token>`
- env: `SCI_RUNTIME_API_KEY`

### Tenancy

When configured, protected requests must include tenant identity.

- HTTP: header `x-tenant-id`
- gRPC: metadata `x-tenant-id`

Configuration:

- CLI flag: `--require-tenant`
- env: `SCI_RUNTIME_REQUIRE_TENANT=true`

### Rate Limiting

Rate limiting is intentionally external to runtime retrieval semantics.

- Must be implemented at ingress/proxy/API-gateway or host orchestration layer.
- Runtime library and thin transport wrappers do not implement quota logic.

---

## Why This Shape

- preserves deterministic retrieval behavior in core runtime
- avoids mixing transport security concerns with retrieval semantics
- allows deployment-specific auth/rate-limit policy without forking core logic

---

## Consequences

### Positive

- explicit minimum security posture for service mode
- parity policy across HTTP and gRPC edges
- clear integration points for production hardening

### Tradeoff

- API-key model is a baseline, not full identity system
- tenant identity is presence-checked, not fully authorized against policy backend

---

## Follow-ups

- add host-integrated authz backend contract (tenant to repo/path policy)
- add deployment templates documenting ingress rate-limit defaults
- evaluate stronger authn (mTLS/JWT/OIDC) at edge while preserving thin-wrapper principle
