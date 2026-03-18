# Repository Agent Notes

- Never run dependent git commands in parallel.
- `git commit` and `git push` must always run sequentially.
- Use parallel tool execution only for independent reads or checks, never for state-changing commands that depend on each other.
- If uncommitted files remain in the repo from previous agent runs, explicitly surface them and offer to commit and push them separately; only do that after user approval.
- For `.clj`, `.cljc`, `.cljs`, and `.edn` structural edits, prefer form-aware Clojure editing tools when available; avoid large raw `apply_patch` rewrites of deeply nested forms when a narrower edit will work.
- After any manual `apply_patch` that changes Clojure forms, run an immediate syntax or compile probe before continuing with more edits.
- If Clojure reports `Unmatched delimiter`, `EOF while reading`, or `defn` spec errors after an edit, inspect the just-edited form tail first and repair delimiters before making additional changes.
- Keep Clojure patches scoped to one top-level form where possible; session history shows multi-form raw patches are the recurring source of delimiter regressions.


## MCP-First Workflow For This Repo

## Mandatory CCC Bootstrap

- Before first-pass repo exploration, check `docs/code-context.md` and `.ccc/state.edn`.
- If both exist, read `docs/code-context.md` first and treat it as the architecture-summary layer before broader exploration.
- If either file is missing, run `./scripts/agent-bootstrap.sh` before any broader exploration.
- `./scripts/agent-bootstrap.sh` is the canonical bootstrap entrypoint; it runs `clojure -M:ccc init --root . --skip-hook` only when the CCC artifacts are missing.
- Do not refresh CCC artifacts on every task by default; only refresh them when a task explicitly needs regenerated compression outputs or when the user asks for it.

- If the `semantic-code-indexing` MCP server is available, do not begin with `Analyze`, directory listing, wildcard search, or broad manual file crawling.
- Use MCP before manual file crawling.
- First-pass flow is strict:
  1. `create_index`
  2. `repo_map`
  3. `resolve_context`
  4. optional `expand_context`
  5. optional `fetch_context_detail`
- Treat `no_supported_languages_found` as a user-guidance path: ask for the core language and suggest activating other languages later.
- Treat `language_refresh_required` as a signal to rerun `create_index`, not as a reason to abandon MCP.
- Treat `language_activation_in_progress` as a wait-and-retry signal for the same request.
- A successful `create_index` is not a reason to switch to filesystem browsing; continue with `repo_map` and semantic retrieval.
- `resolve_context` accepts a flat top-level `intent` string (simplest: `{"index_id": "...", "intent": "your task"}`), a `query.intent` shorthand, or the full structured `query` object.
- After a successful `resolve_context`, keep the context compact: continue with `selection_id` and `snapshot_id` for `expand_context` / `fetch_context_detail` instead of restating a larger prompt.
- Do not silently fall back to manual inspection if MCP fails; state that MCP failed, then continue manually if needed.
- MCP wire-shape requirements:
  - `initialize.params.clientInfo` must be an object, not a string.
  - `tools/call.arguments` must be a JSON object, not a JSON-encoded string.
- Canonical client prompts live in [docs/mcp-agent-prompts.md](docs/mcp-agent-prompts.md).
