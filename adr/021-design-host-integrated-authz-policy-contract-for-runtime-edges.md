# ADR-021: Design Host-Integrated Authz Policy Contract for Runtime HTTP/gRPC Edges

**Status**: Accepted  
**Date**: 2026-03-08  
**Deciders**: project owner

---

## Context and Problem Statement

`ADR-019` established baseline auth and tenancy checks (`x-api-key`, `x-tenant-id`) but did not authorize tenant access to specific repositories or path scopes.

Service deployments need an explicit contract to enforce tenant-to-repo/path permissions while keeping runtime edges thin and host-integrated.

---

## Decision

Introduce a pluggable authorization contract at the HTTP/gRPC edge:

1. Both edges accept an optional `:authz_check` callback in `start-server`.
2. Callback request shape:
   - `:operation` (`:create_index` or `:resolve_context`)
   - `:tenant_id`
   - `:root_path`
   - `:paths`
3. Callback response shape:
   - allow: `true` or `{:allowed? true}`
   - deny: `false` or `{:allowed? false :code :forbidden|:invalid_request|:internal_error :message "..."}`
4. CLI mode adds `--authz-policy-file` (env fallback `SCI_RUNTIME_AUTHZ_POLICY_FILE`) using a built-in EDN policy adapter as a reference host integration.

---

## Policy Adapter Shape (EDN)

```clojure
{:tenants
 {"tenant-001" {:allowed_roots ["<repo-a-root>"]
                :allowed_path_prefixes ["src/my/app" "test/my/app"]}}}
```

Adapter behavior:

- `allowed_roots` is required per tenant.
- request `root_path` must be within one allowed root.
- if `allowed_path_prefixes` is present, request must provide `paths`.
- each requested path must be relative, traversal-safe, and match an allowed prefix.

---

## Transport Mapping

- HTTP denials:
  - `:forbidden` -> `403`
  - `:invalid_request` -> `400`
  - `:internal_error` -> `500`
- gRPC denials:
  - `:forbidden` -> `PERMISSION_DENIED`
  - `:invalid_request` -> `INVALID_ARGUMENT`
  - `:internal_error` -> `INTERNAL`

---

## Consequences

### Positive

- explicit tenant-to-repo/path authorization boundary for service mode
- host systems can integrate custom policy engines without changing runtime core
- parity behavior across HTTP and gRPC edges

### Tradeoff

- built-in adapter is file-based and static; dynamic policy backends remain host responsibility
- callback contract is minimal and relies on host discipline for richer auth context

---

## Follow-ups

- provide reference integration with external PDP/OPA-style policy backends
- add deployment docs for policy rotation/reload behavior
