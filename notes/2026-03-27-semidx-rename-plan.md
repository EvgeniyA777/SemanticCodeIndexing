---
title: "semidx Rename Execution Plan"
doc_type: "implementation_note"
lifecycle: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
file_type: working-note
topic: semidx-rename-execution-plan
created_at: 2026-03-27T02:30:00-0700
author: claude
language: en
status: completed
---

# Rename Plan: semantic-code-indexing → semidx

## Context

The project name `semantic-code-indexing` (23 characters) inflates every namespace, require, path, and env var across 49 source files and 22k lines. When LLM agents work with this codebase, the long name costs ~500 extra tokens per full context dump. At the current scale (49 files, 37 namespaces), rename is still cheap. Every day it gets more expensive due to path dependence.

The short name **`semidx`** (6 characters) was chosen after evaluating: `semix`, `semidx`, `scix`, `semcix`, `codeidx`. `semidx` wins on readability (sem + idx), no ecosystem collisions, and namespace ergonomics (`semidx.core`, `semidx.runtime.adapters`).

## Decisions

| Item | Decision | Rationale |
|---|---|---|
| Env var prefix | `SCI_` → `SEMIDX_` | Full consistency with new name |
| MCP server name | `semantic-code-indexing` → `semidx` | Metadata, not wire protocol. Clients update `.mcp.json` |
| gRPC package name | `semantic_code_indexing.runtime.grpc.v1` → `semidx.runtime.grpc.v1` | Breaking but accepted by owner |
| gRPC service name | `semantic_code_indexing.RuntimeService` → `semidx.RuntimeService` | Breaking but accepted by owner |
| DB table names | No change (`semantic_index_*`) | Does not contain full project name |
| ADR files | Content unchanged | Historical records |
| Repo root directory | Deferred | Separate operation after internal rename stabilizes |

## Execution Stages

### Stage 1: Atomic namespace + directory + deps.edn rename (HIGH risk)

Directory moves and all namespace rewrites in one atomic commit. Partial state does not compile.

- `git mv src/semantic_code_indexing src/semidx`
- `git mv test/semantic_code_indexing test/semidx`
- All 49 .clj files: `(ns semantic-code-indexing.` → `(ns semidx.`, requires, quoted symbols
- `deps.edn`: 10 `:main-opts` entries

Verification: `clojure -M:test` — all 158 tests, 984 assertions, 0 failures.

### Stage 2: Environment variables (MEDIUM risk)

Global `SCI_` → `SEMIDX_` in all `System/getenv` calls (~45 occurrences across 7 source files, 3 test files, 1 script).

Verification: `clojure -M:test` green (env-gated tests skip as expected).

### Stage 3: Service names, MCP name, gRPC identifiers (MEDIUM risk)

- MCP server name: `"semantic-code-indexing-mcp"` → `"semidx-mcp"`
- gRPC package: `"semantic_code_indexing.runtime.grpc.v1"` → `"semidx.runtime.grpc.v1"`
- gRPC service: `"semantic_code_indexing.RuntimeService"` → `"semidx.RuntimeService"`
- HTTP service: `"semantic-code-indexing-runtime-http"` → `"semidx-runtime-http"`
- Log events: `"semantic_code_indexing_mcp_started"` → `"semidx_mcp_started"`
- Proto directory: `git mv proto/semantic_code_indexing proto/semidx`
- Proto file package declaration updated

Verification: `clojure -M:test` green.

### Stage 4: Scripts and CI paths (LOW risk)

- `scripts/new-language-adapter.sh`: namespace templates, path strings (~12 occurrences)
- `scripts/validate-language-onboarding.sh`: path checks, grep patterns (~5 occurrences)
- `scripts/run-mvp-smoke.sh`: output path (~1 occurrence)
- `scripts/mcp-healthcheck.py`: client info name (~2 occurrences)
- `.github/workflows/contracts-validation.yml`: path filters

Verification: `clojure -M:test` green.

### Stage 5: Documentation (ZERO risk)

Bulk replace in all `.md` files except `adr/` and `docs/code-context.md` (auto-generated).

Covers: README.md, CLAUDE.md, docs/*.md, notes/*.md, contract JSON schema `$id` URLs.

Verification: `grep -r "semantic.code.indexing" docs/ notes/ README.md CLAUDE.md | grep -v adr/ | grep -v code-context.md` returns zero.

### Stage 6: Regenerate CCC cache (ZERO risk)

`rm -rf .ccc/ && ./scripts/agent-bootstrap.sh`

Regenerated `docs/code-context.md` and `.ccc/` files now reference `semidx.*`.

### Stage 7: Update settings and MCP tool references (ZERO risk)

- `.claude/settings.local.json`: MCP tool names `mcp__semantic-code-indexing__*` → `mcp__semidx__*`
- `enabledMcpjsonServers`: `"semantic-code-indexing"` → `"semidx"`

## Summary

| Stage | Risk | Files changed |
|---|---|---|
| 1. Namespaces + dirs + deps.edn | HIGH | ~50 |
| 2. Env vars SCI_ → SEMIDX_ | MEDIUM | ~13 |
| 3. Service/MCP/gRPC names | MEDIUM | ~8 |
| 4. Scripts and CI paths | LOW | ~7 |
| 5. Documentation | ZERO | ~15 |
| 6. CCC cache regeneration | ZERO | ~5 |
| 7. Settings/MCP tool refs | ZERO | ~2 |

Total: ~100 files touched. Every stage verified with `clojure -M:test` (158 tests, 984 assertions, 0 failures).

## Items Frozen

- DB table names (`semantic_index_snapshots`, `semantic_index_units`, `semantic_index_call_edges`)
- ADR file contents
- Repo root directory name (`SemanticCodeIndexing`)

## Downstream Migration Notes

- **MCP clients**: Update `.mcp.json` server key and tool names (`mcp__semidx__*`)
- **gRPC clients**: Update to new package and service names (breaking)
- **Environment variables**: All `SCI_*` become `SEMIDX_*` (breaking for deployed environments)
- **Clojure library consumers**: All requires change from `semantic-code-indexing.*` to `semidx.*`
