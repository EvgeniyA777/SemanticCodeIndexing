---
title: "Testing and Verification Policy"
doc_type: "policy"
lifecycle: "active"
status: "active"
agent_action: "reference_for_context"
updated: "2026-09-01"
---

# Testing and Verification Policy

## Risk-Based Selection

Before implementing or executing a non-trivial staged plan, record a compact
risk matrix in the plan, progress log, or handoff:

| Requirement / invariant | Failure risk | Lowest sufficient level | Boundary proof | Negative or bypass case | Evidence |
| --- | --- | --- | --- | --- | --- |
| Source document or code contract | What could fail | Unit / integration / contract / fixture / runtime smoke | Boundary uniquely proved here | Invalid, stale, unavailable, forbidden, divergent, or fallback case | Named test or command |

Choose the lowest level that reliably proves the behavior:

- Use plain unit tests for pure parsers, normalizers, policies, ranking helpers,
  identity functions, aggregation, and deterministic value transformations.
- Use focused integration tests when behavior depends on multiple runtime
  modules, provider orchestration, storage, generated classes, language adapters,
  or cross-module contracts.
- Use contract validation for JSON Schema artifacts, example payloads, MCP tool
  schemas, HTTP/gRPC boundaries, and public response shapes.
- Use fixture/golden tests for language lanes, provider-authority identity,
  source normalization, stale-artifact behavior, and semantic-quality
  regressions.
- Use runtime smoke checks for CLI, MCP stdio/HTTP, gRPC launchers, optional
  PostgreSQL paths, external toolchains, and process-runner behavior.

Do not test mechanical delegation merely to increase counts. Do test custom
branching, identity stability, merge/arbitration decisions, fallback behavior,
error translation, stale or unavailable inputs, and regressions for observed
defects.

## Mandatory Considerations

- Each changed public contract: valid payload, invalid payload, omitted optional
  fields, unknown fields or enum values where applicable, and compatibility with
  committed examples.
- Each provider or language-lane change: provider unavailable, unsupported
  language, stale source identity, external tool failure, empty result, malformed
  artifact, and fallback/degradation behavior.
- Each identity or arbitration change: same-key merge, must-not-merge fixture,
  provider-neutral key stability, authority precedence, freshness validation,
  and deterministic ordering.
- Each retrieval or ranking change: compact selection shape, expansion/detail
  continuity, token-budget behavior, impact hints, diagnostics, and low
  confidence handling.
- Each storage or service-edge change: in-memory path, PostgreSQL path when
  touched, serialization boundaries, lifecycle cleanup, and error reporting.
- Each external runtime dependency: local absence, CI/offline behavior, path or
  env-var resolution, generated-artifact freshness, and startup mode.

## Verification Lanes

Use the narrowest meaningful command first, then the required regression lane.
Common lanes include:

| Lane | Boundary | Purpose |
| --- | --- | --- |
| Focused unit | One namespace or pure function set | Fast proof for local behavior |
| Runtime integration | Multiple runtime namespaces, storage, providers, or adapters | Cross-module behavior and degradation |
| Contract validation | Schemas and examples under `contracts/` | Public contract compatibility |
| Language onboarding | `./scripts/validate-language-onboarding.sh <language>` | Language-lane parser and fixture confidence |
| MVP gates | `./scripts/run-mvp-gates.sh` or staged gate scripts | Release-facing regression confidence |
| Runtime smoke | MCP, HTTP, gRPC, CLI, or provider process commands | Startup and operational boundary proof |

Coverage reports are diagnostic. An arbitrary percentage does not replace
requirement and risk analysis.

## Isolation And Evidence

- Isolate data, clocks, ports, sessions, files, generated artifacts, and external
  state.
- Prefer deterministic fixtures and explicit cleanup.
- Do not hide nondeterminism with retries in a required gate. Record flakiness
  as a defect; quarantine only with owner, expiry, and visible status.
- Record the exact command, pass/fail result, test counts when available, and
  any environmental limitation.
- If a verification command cannot be run, report it explicitly and explain the
  remaining risk.
