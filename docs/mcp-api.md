# MCP API

`Semantic Code Indexing` can run as a local stdio MCP server over the same core library API.

## Start

```bash
SCI_MCP_ALLOWED_ROOTS="<repo-a-root>:<repo-b-root>" \
clojure -M:mcp
```

Environment:

- `SCI_MCP_ALLOWED_ROOTS` - optional allowlist of canonical repository roots; use the platform path separator (`:` on macOS/Linux, `;` on Windows)
- `SCI_MCP_MAX_INDEXES` - optional in-memory LRU cache size; default `8`
- `SCI_MCP_POLICY_REGISTRY_FILE` - optional EDN policy registry file used for active-policy defaults and selector-based `resolve_context` lookup
- `SCI_USAGE_METRICS_JDBC_URL` - optional PostgreSQL JDBC URL for MCP usage metrics
- `SCI_USAGE_METRICS_DB_USER` - optional PostgreSQL username for MCP usage metrics
- `SCI_USAGE_METRICS_DB_PASSWORD` - optional PostgreSQL password for MCP usage metrics

If `SCI_MCP_ALLOWED_ROOTS` is missing, the server defaults the allowlist to the current working directory of the MCP process and prints a warning with:

- the current working directory seen by the JVM
- an example command that makes that directory explicit
- an example command that uses an explicit custom path

The server does not prompt interactively for this choice because `stdin`/`stdout` are reserved for MCP transport.

The MCP server exposes only `tools` capability in v1 and keeps cached indexes in-process for the lifetime of the server.

If `SCI_USAGE_METRICS_JDBC_URL` is configured, the server records:

- tool usage events by operation (`create_index`, `repo_map`, `resolve_context`, `expand_context`, `fetch_context_detail`, `impact_analysis`, `skeletons`)
- cache-hit/cache-miss behavior for `create_index`
- cache eviction events
- normalized correlation fields such as `session_id`, `task_id`, `trace_id`, `request_id`, and `actor_id` where available

Those MCP usage events now align with the same normalized event shape used by the library and service edges, so downstream reporting can aggregate `library`, `http`, `grpc`, and `mcp` surfaces without custom per-surface adapters.

That same normalized event shape now also feeds Phase 5 replay harvesting, calibration reporting, and weekly review artifacts, so MCP-originated `resolve_context` traces can contribute to automatically generated replay datasets and linked review summaries when query snapshots and feedback are present.

If `SCI_MCP_POLICY_REGISTRY_FILE` is configured, newly created cached indexes inherit that registry and `resolve_context` accepts an optional `retrieval_policy` object for selector-based lookup.

## Recommended Client Description

If your MCP client supports a per-server `description` field in its local config, use wording like this so AI clients route code-navigation and code-retrieval tasks here more reliably:

```json
"description": "Local MCP server for repository-aware code retrieval and codebase navigation. Use it to index a repository, build a compact repo map, find the most relevant files, functions, and symbols for a task, estimate change impact, and return lightweight code skeletons instead of loading full files. Best for large multi-file codebases when the agent needs precise context with lower token cost. Structure-aware and symbol/call-graph-guided; not a full compiler-grade semantic analyzer."
```

Shorter version for clients with tight field limits:

```json
"description": "Repository-aware code retrieval MCP: index a repo, generate repo maps, find relevant symbols and files, run impact analysis, and fetch code skeletons with low token cost."
```

## Tools

Canonical MCP retrieval flow is:

1. `create_index`
2. `resolve_context`
3. optional `expand_context`
4. optional `fetch_context_detail`

`resolve_context` returns a compact selection artifact. Rich retrieval detail is intentionally delayed to `fetch_context_detail`.

### `create_index`

Index a repository root or reuse a cached index. Agents should usually call this first.

Inputs:

- `root_path` - repository root to index
- `paths` - optional relative subset of source paths
- `parser_opts` - optional parser configuration map
- `force_rebuild` - optional boolean; when `true`, bypasses cache reuse

Returns:

- `index_id` - session-scoped handle for later calls
- `snapshot_id`
- `indexed_at`
- `index_lifecycle`
- `root_path`
- `file_count`
- `unit_count`
- `cache_hit`

`index_lifecycle` exposes the current snapshot reuse/provenance state for that handle, including whether it was rebuilt, reused, pinned, or considered stale.

### `repo_map`

Return a compact repository map for high-level codebase navigation without loading full source files.

Inputs:

- `index_id`
- `max_files`
- `max_modules`

Returns the existing runtime `repo-map` payload plus `index_id`.

### `resolve_context`

Find the most relevant files and symbols for a coding task or question and return a compact staged selection.

Inputs:

- `index_id`
- `query`
- `retrieval_policy` - optional registry-backed selector object such as `{ "policy_id": "...", "version": "..." }`

Returns:

- `index_id`
- `api_version`
- `selection_id`
- `snapshot_id`
- `result_status`
- `confidence_level`
- `budget_summary`
- `focus`
- `next_step`

`next_step` tells the client whether it should stay compact, expand the structural view, or fetch rich detail.

Because MCP uses the same core retrieval runtime as the library and service edges, recent semantic-core improvements still flow through `resolve_context` here:

- Clojure: stronger namespace/test linkage, multimethod targeting, macro-generated ownership across helper/composed/threaded expansion patterns, and conservative branch-sensitive handling for conflicting generated forms
- Elixir: better `import` / `use` normalization, implicit imports propagated from `__using__/1`, `__MODULE__` local ownership, arity-aware local shadowing, `defdelegate` linkage, and ExUnit `related_tests`
- Java: arity-aware overload and constructor linking, better static-import/class ownership, and local `this.` / `super.` ownership preservation
- Python: imported-symbol and module-alias resolution, `self` / `cls` and local class-qualified method ownership, explicit module-alias preservation, and Python test-file linkage

Because the capability summary is now language-strength-aware, MCP `resolve_context` responses may also carry a capability-limited confidence outcome even when symbol resolution is exact. Today that effectively means Clojure can reach `high`, Elixir/Java/Python currently ceiling at `medium`, and TypeScript remains `low`-ceiling compatibility coverage.

### `expand_context`

Expand a retained selection artifact with bounded structural detail.

Inputs:

- `index_id`
- `selection_id`
- `snapshot_id`
- `unit_ids` - optional subset of the selected units
- `include_impact_hints` - optional boolean, defaults to `true`

Returns:

- `index_id`
- `api_version`
- `selection_id`
- `snapshot_id`
- `result_status`
- `budget_summary`
- `skeletons`
- optional `impact_hints`

### `fetch_context_detail`

Fetch the rich detail payload for a retained selection artifact.

Inputs:

- `index_id`
- `selection_id`
- `snapshot_id`
- `unit_ids` - optional subset of the selected units
- `detail_level` - optional raw-code escalation bound

Returns:

- `index_id`
- `api_version`
- `selection_id`
- `snapshot_id`
- `raw_context`
- `context_packet`
- `guardrail_assessment`
- `diagnostics_trace`
- `stage_events`

The returned `context_packet` and `diagnostics_trace` include:

- `retrieval_policy` - versioned ranking policy summary used for this retrieval
- `capabilities` - parser/language coverage summary for the selected authority/support evidence set, including per-language strength and a derived `confidence_ceiling`

### `impact_analysis`

Estimate what is likely to be affected by a proposed change, bug fix, refactor, or target query.

Inputs:

- `index_id`
- `query`

Returns:

- `index_id`
- `impact_hints`

### `skeletons`

Return lightweight code skeletons for selected files or semantic units so an agent can inspect structure before loading raw code.

Inputs:

- `index_id`
- `paths` or `unit_ids`

Returns:

- `index_id`
- `skeletons`

## Operational Notes

- Every `root_path` must be inside `SCI_MCP_ALLOWED_ROOTS`.
- `paths` must be relative and must not contain traversal segments such as `..`.
- Cached indexes are evicted by LRU when the process exceeds `SCI_MCP_MAX_INDEXES`.
- If an `index_id` is evicted or unknown, the server returns an `index_not_found` tool error and the client should call `create_index` again.
- `selection_id` artifacts are snapshot-bound. Reusing a selection with the wrong `snapshot_id` returns `snapshot_mismatch`.
- If a retained selection artifact is missing or evicted, the server returns `selection_not_found` or `selection_evicted`.
- `resolve_context` defaults missing `api_version` to `"1.0"` and rejects unsupported values with `unsupported_api_version`.
- MCP tool errors now carry canonical taxonomy fields in `structuredContent.details`: `code` and `category`.
