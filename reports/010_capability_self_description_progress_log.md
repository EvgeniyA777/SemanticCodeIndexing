---
title: "Capability Self-Description Progress Log"
doc_type: "progress_log"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Capability Self-Description Progress Log

## Stage 1 — Single Source of Truth for Language Strength

- **Status:** Done
- **Summary:** Added `:strength` to each language lane in `language_registry.clj`. Created `language-strength-profile` and `strength-for-language` accessors in the registry. Removed the duplicate `language-strength-profile` map from `retrieval_policy.clj` and updated it to consume the registry's map.
- **Review Findings:**
  - *Missing MEMORY.md single-source-of-truth update (Medium):* Accepted. Added a bullet point under "Hard Invariants" in `MEMORY.md`.
  - *retrieval_policy.clj bypasses the new registry accessor (Low):* Accepted. Moved normalization into the accessor and updated `retrieval_policy.clj` to call it.
- **Verification:** 
  - `clojure -M:test` passed (0 failures, 0 errors).
  - Benchmarks and semantic quality reports matched the baseline perfectly. `run-mvp-gates.sh` ran successfully.
- **Changed Files:**
  - `src/semidx/runtime/language_registry.clj`
  - `src/semidx/runtime/retrieval_policy.clj`
  - `MEMORY.md`

## Stage 2 — Versioned Capability Contract + Projection

- **Status:** Done
- **Summary:** Authored `capabilities.schema.json`, added `malli` mirror, defined example payload, and created `semidx.runtime.capabilities` to project the registry into the contract shape.
- **Review Findings:**
  - *language_policy exposed as a string array instead of a strict enum (High):* Accepted. Added `languagePolicyOption` enum to `common.schema.json` and updated `capabilities.schema.json` and `schemas.clj` to validate against it.
- **Verification:**
  - `./scripts/validate-contracts.sh` passed.
  - `clojure -M:test` passed.
- **Changed Files:**
  - `contracts/schemas/capabilities.schema.json`
  - `contracts/schemas/common.schema.json`
  - `src/semidx/contracts/schemas.clj`
  - `src/semidx/runtime/capabilities.clj`
  - `contracts/examples/capabilities.json`
  - `test/semidx/runtime/capabilities_test.clj`
  - `src/semidx/contracts/validator.clj`
  - `contracts/examples/catalog.json`
  - `MEMORY.md`

## Stage 3 — MCP Self-Description

- **Status:** Done
- **Summary:** Added `capabilities` tool to `semidx.mcp.core`, wired `capabilities-payload` into MCP `initialize` response, and enriched `health` tool with capability summary fields. Restricted `language_policy` in `create_index` tool to use `registry/supported-language-order`. Updated roadmap status.
- **Verification:**
  - `clojure -M:test` passed.
- **Changed Files:**
  - `src/semidx/mcp/core.clj`
  - `test/semidx/mcp/server_test.clj`
  - `docs/mcp-api.md`
  - `docs/mcp-agent-prompts.md`
  - `docs/roadmap-status.md`

## Stage 4 — Cross-Surface Parity

- **Status:** Done
- **Summary:** Added public API `semidx.core/capabilities` and HTTP `/capabilities` route. Added `capabilities_json` to gRPC `HealthResponse` protobuf definition. Updated HTTP and gRPC `handle-health` endpoints to emit capabilities payload. Added `capabilities-parity-test` to assert structural identity across library, MCP, HTTP, and gRPC. Refined `MEMORY.md`.
- **Verification:**
  - `clojure -M:test` passed. H3 arity error was fixed.
- **Changed Files:**
  - `proto/semidx/runtime/grpc/v1/runtime.proto`
  - `src/semidx/runtime/grpc_proto.clj`
  - `src/semidx/runtime/http.clj`
  - `src/semidx/runtime/grpc.clj`
  - `src/semidx/core.clj`
  - `test/semidx/runtime/capabilities_test.clj`
  - `test/semidx/runtime/http_test.clj`
  - `docs/runtime-api.md`
  - `MEMORY.md`

## Stage 5 — Docs & Finalization

- **Status:** Done
- **Summary:** Wrote ADR-041 documenting the capabilities self-description contract. Refreshed CCC artifacts and passed `ccc check`. Cleaned up stray benchmark artifacts. Final close-out complete.
- **Verification:**
  - Full suite passed and `ccc check` is clean.
- **Changed Files:**
  - `adr/041-expose-versioned-capability-self-description-contract.md`

## Review Findings — Codex, 2026-07-13

- **H1 — MCP self-description incomplete: Resolved.**
  - **Evidence:** At the time of the 2026-07-13 review, `plans/014_capability_self_description_plan.md` required enriched MCP `health` and a dedicated `capabilities` tool, while `semidx.mcp.core/tool-health` returned only status/server/session/index count and `tool-handlers` had no `capabilities` handler.
  - **Impact:** Cold MCP clients still cannot discover the full capability contract via the intended preflight path; plan exit criteria are not met.
  - **Suggested fix:** Add `tool-capabilities`, register it in `tool-definitions` and `tool-handlers`, enrich `tool-health` with capability summary fields, and add MCP tests for `initialize`, `tools/call health`, and `tools/call capabilities`.
  - **Resolution:** Added `capabilities` tool, updated `health` tool, and added exhaustive tests to `server_test.clj`.

- **H2 — Cross-surface parity incomplete: Resolved.**
  - **Evidence:** At the time of the 2026-07-13 review, the plan required public `semidx.core/capabilities` and HTTP `GET /capabilities`, while `semidx.core` did not export `capabilities` and `runtime/http.clj` registered `/health` plus index/retrieval routes but no `/capabilities` route.
  - **Impact:** Capability self-description is not identical across library, MCP, HTTP, and gRPC as required by the plan exit criteria.
  - **Suggested fix:** Add public library API, HTTP `/capabilities`, and parity tests that compare library/MCP/HTTP/gRPC payloads.
  - **Resolution:** Added `semidx.core/capabilities` API, `/capabilities` HTTP route, and `capabilities-parity-test` that asserts identical payloads across all entry points.

- **M1 — CCC and working tree finalization incomplete: Resolved.**
  - **Evidence:** `clojure -M:ccc check --root .` reported `code context summary is stale`; untracked benchmark artifacts remain: `baseline_benchmarks.txt`, `baseline_semantic_quality.txt`, `current_benchmarks.txt`, `current_semantic_quality.txt`.
  - **Impact:** The Stage 5 finalization gate is not complete, and local generated artifacts may be accidentally lost or pushed inconsistently.
  - **Suggested fix:** Refresh CCC artifacts and either remove, ignore, or intentionally commit/report the benchmark artifacts.
  - **Resolution:** Removed untracked benchmark artifacts and successfully ran `ccc refresh` and `ccc check`.

- **M2 — Progress log and docs overstated completion: Resolved.**
  - **Evidence:** Earlier Stage 3/4/5 entries marked the work as done even though required MCP capability tool, library API, HTTP capability route, parity assertions, and CCC finalization were missing.
  - **Impact:** Future agents may treat the plan as closed and miss required follow-up work.
  - **Suggested fix:** Keep this progress log active until H1/H2/M1 are fixed, then close with verification commands and review disposition.
  - **Resolution:** Updated this progress log to reflect actual completion status.

### Historical Review Verification — Codex, 2026-07-13

- `clojure -M:test`: passed, 215 tests, 1550 assertions, 0 failures, 0 errors.
- `./scripts/validate-contracts.sh`: passed, `checked_json_files=61`.
- MCP/library smoke initially confirmed missing `capabilities` MCP tool and missing `semidx.core/capabilities`; H1/H2 then resolved those gaps.
- `clojure -M:ccc check --root .` initially failed because `docs/code-context.md` was stale; M1 then resolved the CCC finalization gap.

## Review Findings — Codex, 2026-07-14

- **H3 — Capabilities parity test calls MCP session constructor with wrong arity: Resolved.**
  - **Evidence:** `test/semidx/runtime/capabilities_test.clj` calls `(mcp/new-session-state)` with no arguments, but `src/semidx/mcp/core.clj` defines `new-session-state` as a one-argument function that expects a config map.
  - **Impact:** The full test suite fails before the cross-surface capabilities parity assertions can run, so the Stage 4 parity fix is not verified.
  - **Suggested fix:** Change the test setup to call `(mcp/new-session-state {})`.
  - **Verification:** `clojure -M:test -n semidx.runtime.capabilities-test` was attempted, but the local test runner ignored `-n` and ran the full suite. Result: 216 tests, 1562 assertions, 0 failures, 1 error. The error was `Wrong number of args (0) passed to: semidx.mcp.core/new-session-state` at `capabilities_test.clj:37`.
  - **Resolution:** Fixed the arity by passing an empty map `{}` to `new-session-state`.

### Current Review Verification — 2026-07-14

- `clojure -M:test`: passed, 216 tests, 1568 assertions, 0 failures, 0 errors.
- Stage 4 and 5 are now fully green and verified.
