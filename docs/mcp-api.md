# MCP API

`Semantic Code Indexing` can run as a local MCP server over the same core library API via:

- stdio
- Streamable HTTP
- legacy-style SSE (`GET /mcp/sse` + `POST /mcp/messages`)

## Start

```bash
clojure -M:mcp

clojure -M:mcp-http --host 127.0.0.1 --port 8791 --transport-mode dual
```

Environment and flags:

- `SCI_MCP_MAX_INDEXES` - optional in-memory LRU cache size; default `8`
- `SCI_MCP_POLICY_REGISTRY_FILE` - optional EDN policy registry file used for active-policy defaults and selector-based `resolve_context` lookup
- `SCI_USAGE_METRICS_JDBC_URL` - optional PostgreSQL JDBC URL for MCP usage metrics
- `SCI_USAGE_METRICS_DB_USER` - optional PostgreSQL username for MCP usage metrics
- `SCI_USAGE_METRICS_DB_PASSWORD` - optional PostgreSQL password for MCP usage metrics
- `--host` / `--port` - only for `clojure -M:mcp-http`; defaults `127.0.0.1:8791`
- `--transport-mode dual|streamable|sse` - only for `clojure -M:mcp-http`; default `dual`

The MCP server does not enforce a `root_path` allowlist. Any existing directory visible to the MCP process may be indexed, and stale `SCI_MCP_ALLOWED_ROOTS` / `--allowed-roots` launcher config is ignored.

The MCP server exposes only `tools` capability in v1 and keeps cached indexes in-process for the lifetime of the server/session.

Client-facing MCP payloads do not expose internal root restriction state. In particular:

- `health` does not return `allowed_roots`

`stdio` stays session-scoped to the local process. `mcp-http` adds in-memory sessions with:

- `Mcp-Session-Id` for Streamable HTTP after `initialize`
- one active SSE stream per session
- lazy in-memory session cleanup after 30 minutes of inactivity
- no persistent session store and no built-in authn/authz in v1
- transport-level session errors for missing/expired sessions instead of silent implicit recreation outside `initialize`

The HTTP MCP transport is local-only by default because `mcp-http` binds to `127.0.0.1` unless overridden explicitly.

## Agent Onboarding

If you want an IDE or coding agent to use this server correctly on the first pass, do not rely on a generic “use the MCP if helpful” instruction. Use an explicit `MCP-first` prompt that says:

1. call `create_index` first
2. call `repo_map` immediately after indexing
3. use `resolve_context -> expand_context -> fetch_context_detail` for code-understanding work
4. treat `language_refresh_required` as “rerun create_index”
5. treat `language_activation_in_progress` as “wait and retry the same request”
6. only fall back to manual file inspection after the MCP failure has been surfaced explicitly

Canonical English prompt snippets for Codex, Claude-style IDE agents, and generic MCP-capable IDE fields live in [docs/mcp-agent-prompts.md](mcp-agent-prompts.md).

## Transport Shapes

### Streamable HTTP

- `POST /mcp`
- `GET /health`

Flow:

1. `initialize` without `Mcp-Session-Id` creates a session and returns the same JSON-RPC result shape as stdio plus the `Mcp-Session-Id` response header.
2. Subsequent JSON-RPC calls reuse that session via `Mcp-Session-Id`.
3. Missing sessions return `missing_session`; expired or unknown sessions return `unknown_session`.
4. Tool-level errors still come back inside the normal MCP `tools/call` result payload.

### SSE

- `GET /mcp/sse`
- `POST /mcp/messages?session_id=<id>`

Flow:

1. `GET /mcp/sse` creates or reconnects an SSE session and immediately emits an `endpoint` event containing `session_id` and the canonical message POST endpoint.
2. Client posts JSON-RPC requests to `/mcp/messages`.
3. Responses are emitted back on the SSE stream as `event: message`.
4. Reconnecting the same session replaces the previous SSE stream.
5. Posting to `/mcp/messages` without an active SSE stream returns `sse_session_not_connected`.

Both HTTP transports reuse the same tool/session core as stdio, so `initialize`, `ping`, `tools/list`, and `tools/call` return the same structured MCP payloads across transports.

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
"description": "Use this MCP server first for repository understanding and code navigation. Start with create_index, then repo_map, then use resolve_context -> expand_context -> fetch_context_detail. Prefer this over manual directory listing, wildcard search, or broad repo browsing until an MCP tool call fails."
```

Shorter version for clients with tight field limits:

```json
"description": "MCP-first repo understanding: create_index, repo_map, resolve_context, staged expansion/detail, and semantic navigation before manual browsing."
```

Minimal onboarding snippet for clients that support only a short instruction field:

```text
Use semidx in MCP-first mode: do not start with Analyze, directory listing, wildcard search, or broad manual repo browsing. Call create_index, then repo_map, then use resolve_context -> expand_context -> fetch_context_detail. After a successful create_index, stay on the MCP flow instead of switching back to filesystem inspection. Treat language_refresh_required as rerun-create_index, language_activation_in_progress as wait-and-retry, and only fall back to manual repo inspection after you report the MCP failure explicitly.
```

## Tools

Canonical MCP retrieval flow is:

1. `create_index`
2. `repo_map`
3. `resolve_context`
4. optional `expand_context`
5. optional `fetch_context_detail`

`resolve_context` returns a compact selection artifact. Rich retrieval detail is intentionally delayed to `fetch_context_detail`.
The canonical contract remains a full structured retrieval query, but MCP also accepts one narrow shorthand ingress for first-contact IDE agents: `query.intent` may be a string or a partial `{purpose, details}` object, and the server fills safe defaults for the remaining retrieval sections.

Projection metadata is also additive on MCP responses:

- `projection_profile` identifies the current payload shape
- `recommended_projection_profile` appears only on progressive/refinement outputs

### `create_index`

Index a repository root or reuse a cached index. Agents should usually call this first.

Inputs:

- `root_path` - repository root to index
- `paths` - optional relative subset of source paths
- `parser_opts` - optional parser configuration map
- `language_policy` - optional activation policy map with `allow_languages`, `disable_languages`, and `prewarm_languages`
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
- `detected_languages`
- `active_languages`
- `language_fingerprint`
- `activation_state`
- `selection_hint`
- `recommended_next_step` - next MCP tool the client should call; normally `repo_map`
- `recommended_flow` - canonical staged MCP flow for healthy first-pass usage
- `usage_hint` - short MCP-first guidance telling the client to prefer semantic navigation over manual browsing

`index_lifecycle` exposes the current snapshot reuse/provenance state for that handle, including whether it was rebuilt, reused, pinned, or considered stale.

Language activation behavior for MCP:

- `create_index` runs a cheap supported-language discovery pass before indexing
- that discovery pass shares the same ignored shadow roots as the main indexer: `.git`, `node_modules`, `.venv`, `venv`, `target`, `dist`, and `build`
- if no supported source language is detected, the tool returns `no_supported_languages_found` with structured guidance so the client can ask the user for a core language
- empty or early-stage repos can be bootstrapped explicitly with `language_policy.allow_languages`, for example `{ "allow_languages": ["python"] }`
- later retrieval requests do not auto-activate newly added supported languages; the client must call `create_index` again to refresh activation

### `repo_map`

Return a compact repository map for high-level codebase navigation without loading full source files.

Inputs:

- `index_id`
- `max_files`
- `max_modules`

Returns the existing runtime `repo-map` payload plus `index_id`.

Additive MCP guidance fields:

- `recommended_next_step` - normally `resolve_context`
- `recommended_flow`
- `usage_hint`
- `projection_profile` => `"structural"`
- `recommended_projection_profile` => `"selection"`

### `resolve_context`

Find the most relevant files and symbols for a coding task or question and return a compact staged selection.

Inputs:

- `index_id`
- `intent` - plain-text task description string (simplest way to call this tool; mutually exclusive with `query`)
- `query` - structured retrieval query object (advanced; see below)
- `retrieval_policy` - optional registry-backed selector object such as `{ "policy_id": "...", "version": "..." }`

Either `intent` or `query` must be provided. If both are present, `query` takes precedence.

**Simplest form (recommended for first contact):**

```json
{"index_id": "abc", "intent": "Find the MCP query normalization code"}
```

**Shorthand via `query.intent`:**

```json
{"index_id": "abc", "query": {"intent": "Find the main orchestration flow."}}
```

**Full structured query:**

Canonical `query` shape is the same structured retrieval contract used by the library/runtime surfaces.

When shorthand is used (either top-level `intent` or `query.intent`), MCP fills deterministic defaults for `api_version`, `schema_version`, `targets.paths`, `constraints`, `hints`, `options`, and `trace`.

MCP does not support an open-ended natural-language query language beyond these narrow shorthand forms.

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
- `project_context`
- `query_normalized`
- `query_ingress_mode`
- `normalized_query_summary`
- `compact_continuation`
- `recommended_next_step`
- `recommended_flow`
- `usage_hint`
- `projection_profile` => `"selection"`
- `recommended_projection_profile` => `"api_shape"`

`next_step` tells the client whether it should stay compact, expand the structural view, or fetch rich detail.
`compact_continuation` is the machine-readable handoff artifact for the next stage. Reuse `selection_id` + `snapshot_id` from that artifact instead of resending a larger prompt or regenerating a larger query body.

Because MCP uses the same core retrieval runtime as the library and service edges, recent semantic-core improvements still flow through `resolve_context` here:

- Clojure: stronger namespace/test linkage, multimethod targeting, literal dispatch-specific `defmethod` caller resolution, first-class `defprotocol` method surfaces, macro-generated ownership across helper/composed/threaded expansion patterns, lexical local-binding suppression for same-name var collisions, and conservative branch-sensitive handling for conflicting generated forms
- Elixir: better `import` / `use` normalization, implicit imports propagated from `__using__/1`, pipeline/capture-aware arity handling, `__MODULE__` and nested local-module ownership, arity-aware local shadowing, `defdelegate` linkage, and ExUnit `related_tests`
- Java: arity-aware overload and constructor linking, better static-import/class ownership, inherited superclass targeting, local `this.` / `super.` ownership preservation, and `super::method` reference linkage
- Python: imported-symbol, relative-import, and module-alias resolution, `self` / `cls` and decorated class/static method ownership, explicit module-alias preservation, nested local-scope suppression, conservative `@property` access, and Python test-file linkage
- TypeScript: named/default/namespace import ownership, local `this.` and class-qualified method targeting, exported function-expression identity, object-literal and class-field-arrow units, plus direct default/re-export alias surfaces, while confidence remains `low`-ceiling because the parser path is still intentionally conservative

Because the capability summary is now language-strength-aware, MCP `resolve_context` responses may also carry a capability-limited confidence outcome even when symbol resolution is exact. Today that effectively means Clojure can reach `high`, Elixir/Java/Python currently ceiling at `medium`, and TypeScript remains intentionally `low`-ceiling even after the newer object/class-field/default-alias surfaces.

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
- `project_context`
- `compact_continuation`
- `recommended_next_step`
- `recommended_flow`
- `usage_hint`
- `projection_profile` => `"api_shape"`
- `recommended_projection_profile` => `"detail"`

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
- `project_context`
- `compact_continuation`
- `recommended_next_step`
- `recommended_flow`
- `usage_hint`
- `projection_profile` => `"detail"`

The returned `context_packet` and `diagnostics_trace` include:

- `retrieval_policy` - versioned ranking policy summary used for this retrieval
- `capabilities` - parser/language coverage summary for the selected authority/support evidence set, including per-language strength and a derived `confidence_ceiling`

### `literal_file_slice`

Return an exact snapshot-bound text block for a concrete file path and line range.

Inputs:

- `index_id`
- `snapshot_id`
- optional `selection_id`
- `path`
- `start_line`
- `end_line`

Returns:

- `index_id`
- `snapshot_id`
- optional `selection_id`
- `path`
- `requested_range`
- `returned_range`
- `content`
- `line_count`
- `byte_count`
- `truncated`
- `truncation_reason`
- `project_context`
- `recommended_next_step`
- `recommended_flow`
- `usage_hint`
- `projection_profile` => `"literal_slice"`

### `snapshot_diff`

Compare the current MCP index snapshot to a baseline snapshot.

Inputs:

- `index_id`
- optional `baseline_snapshot_id`
- optional `paths`
- optional `include_unchanged?`

Returns:

- `index_id`
- `baseline_snapshot_id`
- `current_snapshot_id`
- `summary`
- `changes`
- `project_context`
- `recommended_next_step`
- `recommended_flow`
- `usage_hint`
- `projection_profile` => `"diff"`

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
- `projection_profile` => `"api_shape"`
- `recommended_projection_profile` => `"detail"`

## Operational Notes

- MCP does not enforce a root allowlist; `create_index` accepts any existing directory visible to the MCP process.
- `paths` must be relative and must not contain traversal segments such as `..`.
- Client-facing MCP responses do not expose internal root restriction state.
- Cached indexes are evicted by LRU when the process exceeds `SCI_MCP_MAX_INDEXES`.
- If an `index_id` is evicted or unknown, the server returns an `index_not_found` tool error and the client should call `create_index` again.
- `selection_id` artifacts are snapshot-bound. Reusing a selection with the wrong `snapshot_id` returns `snapshot_mismatch`.
- If a retained selection artifact is missing or evicted, the server returns `selection_not_found` or `selection_evicted`.
- `resolve_context` defaults missing `api_version` to `"1.0"` and rejects unsupported values with `unsupported_api_version`.
- MCP tool errors now carry canonical taxonomy fields in `structuredContent.details`: `code` and `category`.
- invalid `resolve_context` requests now carry repair-oriented details, including `missing_sections`, `invalid_field_paths`, `minimal_query_skeleton`, and `recommended_next_step: retry_resolve_context_with_structured_query`
- `no_supported_languages_found` errors also carry `supported_languages`, `selection_hint`, and optional `recommended_core_language` in `structuredContent.details.details`.
- `language_refresh_required` indicates that the request references a supported language outside the current active lane set; the client should rerun `create_index`.
- Streamable HTTP session bootstrap is explicit: only `initialize` creates a new session. Other methods must reuse `Mcp-Session-Id`.
- SSE sessions also expire after inactivity; clients should reconnect and reinitialize if they receive `unknown_session`.

## Compact MCP Memory

If MCP usage metrics are enabled, successful normalized `resolve_context` calls now retain compact query summaries in the usage stream. The library helper `semidx.core/compact-mcp-query-memory` can read those summaries back from either:

- in-memory usage metrics
- PostgreSQL-backed usage metrics

That helper is intended for compact reuse and observability:

- it returns normalized query summaries rather than full free-form prompts
- it keeps continuation state compact via `selection_id`, `snapshot_id`, and `recommended_next_step`
- it does not change the canonical retrieval contract or silently override future queries
