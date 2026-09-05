---
name: semidx-test-design
description: "Design the smallest sufficient risk-based test matrix for semidx changes. Use when choosing unit, integration, contract, fixture/golden, language-onboarding, provider-authority, MCP/HTTP/gRPC smoke, or gate verification."
---

# semidx Test Design

Read `docs/agent-policy/testing.md` and the accepted requirement, plan, ADR,
contract, fixture, or progress log that owns the behavior.

## Workflow

1. Build the risk matrix:

   | Requirement / invariant | Failure risk | Lowest sufficient level | Boundary proof | Negative or bypass case | Evidence |
   | --- | --- | --- | --- | --- | --- |

2. Choose the lowest level that proves each risk:
   - pure parser, normalizer, key, policy, ranker, or aggregator -> unit test;
   - provider orchestration, storage, runtime module interaction, generated
     classes, or language adapter interplay -> focused integration test;
   - JSON Schema, examples, MCP tool schema, HTTP/gRPC payloads -> contract
     validation;
   - language-lane identity, provider parity, semantic quality, stale artifact,
     and cross-provider authority -> fixture/golden test;
   - CLI, MCP stdio/HTTP, gRPC launcher, process runner, optional PostgreSQL, or
     external toolchain -> runtime smoke.
3. Cover only applicable positive, boundary, malformed, stale, unavailable,
   fallback, must-merge, must-not-merge, and regression cases. State why a risk
   category is not applicable instead of silently skipping it.
4. Avoid tests that only prove mechanical delegation. Focus on custom semantics,
   identity stability, contract shape, diagnostics, fallback, and failure risk.
5. Run focused checks first, then required lanes. Retain exact command evidence
   and disclose environment limitations.
6. Update the progress log with the matrix, commands, results, omissions, and
   residual risk when the task is plan-driven.

Do not use an easier mock or hand-built artifact as proof of a live provider,
toolchain, database, or transport signal unless the plan explicitly scopes the
check to pure logic.
