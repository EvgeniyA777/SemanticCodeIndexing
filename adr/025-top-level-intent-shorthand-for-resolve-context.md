# ADR-025: Top-level `intent` string shorthand for `resolve_context`

## Status

Accepted

## Date

2026-03-17

## Context

LLM agents frequently fail when calling `resolve_context` because the `query` parameter requires 3 levels of nesting: `args.query.intent.{purpose, details}`. The `inputSchema` declares `intent` as `type: "object"` with `required: ["purpose"]`, but agents see the schema, try to construct the nested object, and fail on enum values, nesting depth, or type mismatches.

The server-side code (`shorthand-intent->map`) already accepts `intent` as a plain string inside the `query` map, but agents must still construct the outer `{"query": {"intent": "..."}}` wrapper — one level of nesting that adds no semantic value and causes repeated integration failures.

## Decision

Add a flat `intent` string parameter at the same level as `index_id` in the `resolve_context` tool definition, so agents can call:

```json
{"index_id": "abc", "intent": "Find the MCP query normalization code"}
```

instead of:

```json
{"index_id": "abc", "query": {"intent": {"purpose": "code_understanding", "details": "Find the MCP query normalization code"}}}
```

### Resolution rules

- If `intent` is a string and `query` is absent: auto-promote to `{:query {:intent "..."}}`
- If both `intent` and `query` are present: ignore `intent`, use `query` (no ambiguity)
- If neither is present: reject with `"either intent (string) or query (object) is required"`
- If `intent` is not a string: ignore it (existing validation handles the rest)

### Schema changes

- `"intent"` added as a top-level `string` property in `resolve_context.inputSchema`
- `required` reduced from `["index_id", "query"]` to `["index_id"]`
- Tool description updated to guide agents toward the simpler form first

### Telemetry

Auto-promoted intent queries are tagged with `query_ingress_mode: "intent_shorthand"` (distinct from `"mcp_shorthand"` for `query.intent` and `"canonical"` for full structured queries).

## Consequences

### Positive

- Agents can call `resolve_context` with a single flat string — no nesting, no enum guessing
- Zero changes to normalization or validation pipeline (`normalize-mcp-query` and `shorthand-intent->map` are untouched)
- Full structured `query` path is fully preserved with no regressions
- `expand_context` / `fetch_context_detail` are untouched (they work via `selection_id`)

### Negative

- `query` is no longer required in the schema, so agents that omit both `intent` and `query` get a runtime error instead of a schema-level validation error. This is intentional: most MCP clients do not enforce `required` before dispatch, so the runtime check is the effective gate regardless.
- Three ingress modes (`intent_shorthand`, `mcp_shorthand`, `canonical`) add surface area to telemetry analysis

### Risks

- None identified. The auto-promote is a simple `assoc` before existing code paths. The schema relaxation matches actual server behavior.

## Alternatives Considered

1. **Make `query.intent` accept a string in the schema** — Would fix the enum/nesting issue but still requires one level of wrapping (`{"query": {"intent": "..."}}`). Agents still fail on constructing the outer object.

2. **Accept a top-level free-form `prompt` string** — Too broad; would create expectation of arbitrary NL query support that the retrieval pipeline cannot fulfill.

3. **Keep the status quo** — Agents continue to fail. The failure mode is structural (schema complexity), not a transient integration issue.
