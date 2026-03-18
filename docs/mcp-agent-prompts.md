# MCP Agent Prompts

These prompts are the canonical copy-ready snippets for IDEs and coding agents that should use `semantic-code-indexing` correctly on the first pass.

Use them when:

- an IDE has an MCP server instruction field
- an agent supports repo-level onboarding prompts
- you want agents to prefer MCP over manual file crawling for initial repo understanding

Design goal:

- make the agent choose MCP before `Analyze`, directory listing, wildcard search, or broad file reading
- keep the agent on the semantic flow after `create_index` instead of drifting back to filesystem crawling

## Universal Strict Prompt

```text
Use the `semantic-code-indexing` MCP server as the primary tool for first-pass repository understanding.

Operational rules:
1. Always call `create_index` first for the repository root.
2. Immediately call `repo_map` after a successful `create_index`.
3. For code understanding or implementation work, use the canonical retrieval flow:
   - `resolve_context`
   - optional `expand_context`
   - optional `fetch_context_detail`
4. Do not switch back to directory listing, wildcard search, or broad file reads after a successful `create_index`; continue with `repo_map` and semantic retrieval.
5. If the server returns `no_supported_languages_found`, ask the user which core language the project uses. Recommend selecting one core language first and activating additional languages later if needed.
6. If the server returns `language_refresh_required`, rerun `create_index` instead of abandoning MCP.
7. If the server returns `language_activation_in_progress`, wait briefly and retry the same request.
8. Do not silently fall back to manual file inspection. If MCP fails, explicitly state that MCP failed, then continue with manual repository inspection only if necessary.

Wire-shape requirements:
- Send `initialize.params.clientInfo` as an object, not a string.
- Send `tools/call.arguments` as a JSON object, not a JSON-encoded string.

Retrieval-query rule:
- `resolve_context` accepts a top-level `intent` string (simplest): `{"index_id": "...", "intent": "Describe your task"}`. The full structured `query` object is also supported for advanced use.
- For first contact, prefer the flat `intent` form. MCP also accepts `{"query":{"intent":"Find the main orchestration flow."}}`.
- After a successful `resolve_context`, keep the context compact by reusing `selection_id` and `snapshot_id` for `expand_context` / `fetch_context_detail` instead of restating a larger prompt.
```

## Codex Prompt

```text
For this repository, use `semantic-code-indexing` in MCP-first mode.

Required first-pass workflow:
1. `create_index`
2. `repo_map`
3. `resolve_context`
4. optional `expand_context`
5. optional `fetch_context_detail`

Behavior rules:
- Use MCP before broad manual file reads whenever the server is available.
- Do not begin with directory listing, wildcard search, or broad manual repo browsing.
- After a successful `create_index`, continue with `repo_map` instead of switching back to filesystem exploration.
- If `no_supported_languages_found` is returned, ask the user to choose the core language from the supported list and recommend enabling additional languages later only as needed.
- If `language_refresh_required` is returned, rerun `create_index`.
- If `language_activation_in_progress` is returned, retry the same request after a short wait.
- Do not silently abandon MCP. Explicitly report MCP failure before switching to manual repository inspection.

Protocol rules:
- `initialize.params.clientInfo` must be an object.
- `tools/call.arguments` must be a JSON object.
- `resolve_context` accepts a flat `intent` string (simplest): `{"index_id": "...", "intent": "your task"}`. The structured `query` object is also supported.
- After a successful `resolve_context`, continue with `selection_id` + `snapshot_id` rather than rebuilding a larger prompt.
```

## Claude / IDE-Agent Prompt

```text
Use the `semantic-code-indexing` MCP server first, not manual repo browsing, for initial codebase understanding.

Workflow:
1. call `create_index`
2. call `repo_map`
3. use `resolve_context`
4. use `expand_context` only when you need structural widening
5. use `fetch_context_detail` only when you need richer evidence or raw code

Do not start with `Analyze`, directory listing, wildcard search, or broad file reading when MCP is healthy.
Do not switch back to manual browsing after a successful `create_index`; continue with `repo_map`.

Error handling:
- `no_supported_languages_found`: ask the user to choose the core language; recommend activating other languages later
- `language_refresh_required`: rerun `create_index`
- `language_activation_in_progress`: wait and retry the same request

Fallback:
- if MCP fails, say that MCP failed
- only then switch to manual file inspection

Protocol constraints:
- `clientInfo` must be an object
- `tools/call.arguments` must be an object
- `resolve_context` accepts a flat `intent` string (simplest): `{"index_id": "...", "intent": "your task"}`. The structured `query` object is also supported.
- after `resolve_context`, continue through `selection_id` / `snapshot_id` instead of broadening the prompt
```

## Generic IDE Field Prompt

```text
Use semantic-code-indexing in MCP-first mode: do not start with Analyze, directory listing, wildcard search, or broad manual repo browsing. Call create_index, then repo_map, then use resolve_context -> expand_context -> fetch_context_detail. After a successful create_index, stay on the MCP flow instead of switching back to filesystem inspection. Treat no_supported_languages_found as a prompt to ask for the core language, language_refresh_required as rerun-create_index, and language_activation_in_progress as wait-and-retry. Report MCP failure explicitly before falling back to manual repo inspection. Send clientInfo and tools/call.arguments as JSON objects, not strings.

`resolve_context` accepts a flat `intent` string (simplest): `{"index_id": "...", "intent": "your task"}`. The structured `query` object is also supported for advanced use.
```

## Antigravity / MCP-Only Prompt

```text
Use the semantic-code-indexing MCP server only for the first repository pass.

Do not start with Analyze, directory listing, wildcard search, or broad file reading.

Required sequence:
1. create_index
2. repo_map
3. resolve_context
4. optional expand_context
5. optional fetch_context_detail

If create_index succeeds, stay on the MCP flow and do not switch back to filesystem browsing.
Only use manual repository inspection after you explicitly report an MCP failure.
If language_refresh_required is returned, rerun create_index.
If language_activation_in_progress is returned, wait briefly and retry the same request.
`resolve_context` accepts a flat `intent` string (simplest): `{"index_id": "...", "intent": "your task"}`. The structured `query` object is also supported.
After resolve_context succeeds, continue with `selection_id` and `snapshot_id` instead of sending a larger follow-up prompt.
```

## Description-Field Snippet

Use this only when the client gives you a tiny MCP `description` field and nothing else:

```text
Use this MCP server first for repository understanding and code navigation. Start with create_index, then repo_map, then use resolve_context -> expand_context -> fetch_context_detail. Prefer this over manual repo browsing until an MCP tool call fails.
```
