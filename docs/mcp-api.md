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

- tool usage events by operation (`create_index`, `repo_map`, `resolve_context`, `impact_analysis`, `skeletons`)
- cache-hit/cache-miss behavior for `create_index`
- cache eviction events
- correlation fields such as `session_id`, `trace_id`, and `request_id` where available

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
- `root_path`
- `file_count`
- `unit_count`
- `cache_hit`

### `repo_map`

Return a compact repository map for high-level codebase navigation without loading full source files.

Inputs:

- `index_id`
- `max_files`
- `max_modules`

Returns the existing runtime `repo-map` payload plus `index_id`.

### `resolve_context`

Find the most relevant files, symbols, and code context for a coding task or question.

Inputs:

- `index_id`
- `query`

Returns the existing runtime retrieval payload plus `index_id`:

- `context_packet`
- `guardrail_assessment`
- `diagnostics_trace`
- `stage_events`

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
