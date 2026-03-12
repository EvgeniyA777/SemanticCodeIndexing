# Project Instructions

## MCP-first workflow (mandatory)

When `semantic-code-indexing` MCP is available, ALWAYS use it as the primary codebase exploration tool.
NEVER use Glob, Grep, or Read for first-pass codebase exploration when SCI MCP is available.

### Required startup sequence

Every task in this repo MUST begin with:
1. `create_index` — index the repo (cached if unchanged)
2. `repo_map` — get project structure overview
3. `resolve_context` — find relevant code for the task
4. `expand_context` — get structural detail if needed
5. `fetch_context_detail` — get raw code only when staged flow is insufficient

### Retrieval-first budget rule

After `resolve_context`, use `expand_context` and `fetch_context_detail` BEFORE reading raw files.
Read raw files only when the staged retrieval flow is exhausted or returns an error.

### Query format

- Use the structured retrieval contract for `resolve_context`. Only the narrow `query.intent` shorthand is accepted for first contact.
- After `resolve_context` succeeds, continue with `selection_id` and `snapshot_id` — do not expand the prompt manually.
- Send `clientInfo` as an object and `tools/call.arguments` as an object.

### Failure protocol

- If MCP returns `no_supported_languages_found`, ask the user to choose the core language from the supported set.
- If MCP returns `language_refresh_required`, rerun `create_index`.
- If MCP returns `language_activation_in_progress`, wait and retry the same request.
- If MCP returns error or timeout after 2 attempts, say "SCI MCP unavailable, switching to manual" and proceed with filesystem tools.
- NEVER silently abandon MCP. Always state the failure explicitly before falling back.

### Reference

Canonical prompt snippets: `docs/mcp-agent-prompts.md`
