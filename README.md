# Semantic Code Indexing

Library-first architecture for structure-aware code retrieval and context packaging for AI development systems.

## Purpose

`Semantic Code Indexing` is a Clojure-first retrieval subsystem designed to be injected into larger AI orchestration systems as a library.

The project defines how a host system should request code context, how retrieval results should be packaged, and how confidence/guardrail signals should be emitted for safe downstream automation.

## What This Project Does

- defines external contracts for retrieval (`JSON Schema`)
- defines canonical request/response examples and diagnostics examples
- defines retrieval fixture corpus for behavior-based validation
- mirrors contracts in Clojure (`malli`) for runtime validation
- provides a local and CI gate to prevent contract drift
- provides a working in-memory MVP runtime for `create-index`, `update-index`, `repo-map`, `resolve-context`, `impact-analysis`, `skeletons`
- includes lightweight parser adapters for `Clojure + Java` and emits diagnostics/guardrails outputs

## What This Project Does Not Do (Yet)

- does not implement full repository indexing/parsing runtime yet
- does not implement ranking engine, graph storage, or parser adapters yet
- does not expose production API server endpoints yet

Current scope is contract architecture and validation infrastructure.

## Repository Layout

- `adr/` - architecture decisions (`ADR-001` .. `ADR-017`)
- `contracts/schemas/` - JSON Schema contracts (external source of truth)
- `contracts/examples/` - canonical examples for contract families
- `fixtures/retrieval/` - retrieval fixture corpus (behavior bands)
- `src/semantic_code_indexing/contracts/` - Clojure `malli` mirror and validator CLI
- `scripts/` - local validation entrypoints

## Contract Validation

- Local: `./scripts/validate-contracts.sh`
- CI: `.github/workflows/contracts-validation.yml`

## Runtime Validation and Smoke

- Unit/integration tests: `clojure -M:test`
- Resolve context from query file: `clojure -M:runtime --root . --query contracts/examples/queries/symbol-target.json --out /tmp/sci.json`
- Full MVP gates: `./scripts/run-mvp-gates.sh`
- CI runtime gates: `.github/workflows/mvp-runtime.yml`

## Agent Limit Policy

- If execution/model/tool limits are exhausted, the agent MUST stop work immediately.
- No retries, no fallback execution, no bypass attempts.
- The agent MUST send a short status message: `limit reached, waiting for user instruction`.
- After that message, the agent MUST wait for explicit user instruction before continuing.
- This local policy applies to this repository workflow.

## Current Contract Strategy

1. `JSON Schema` is the external contract source of truth.
2. `malli` is the Clojure-side runtime mirror.
3. `examples/fixtures` are shared verification artifacts across languages.

## Status

- contract layer established (`contracts/schemas`)
- canonical example set established (`contracts/examples`)
- seed fixture corpus established (`fixtures/retrieval`)
- Clojure validation gate implemented (`src/semantic_code_indexing/contracts`)
- MVP runtime implemented (`src/semantic_code_indexing/core.clj`, `src/semantic_code_indexing/runtime/*`)

## License

Apache License 2.0. See [LICENSE](/Users/ae/workspaces/SemanticСodeIndexing/LICENSE).
