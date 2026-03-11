# Repository Agent Notes

- Never run dependent git commands in parallel.
- `git commit` and `git push` must always run sequentially.
- Use parallel tool execution only for independent reads or checks, never for state-changing commands that depend on each other.
- If uncommitted files remain in the repo from previous agent runs, explicitly surface them and offer to commit and push them separately; only do that after user approval.
