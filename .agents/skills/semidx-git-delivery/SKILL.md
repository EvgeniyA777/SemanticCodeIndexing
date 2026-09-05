---
name: semidx-git-delivery
description: "Apply safe semidx git delivery discipline for repository mutations, checkpoint commits, explicit staging, branch/push checks, and recovery while preserving unrelated user work."
---

# semidx Git Delivery

Use this skill after coherent verified changes or whenever inspecting or
changing branch, upstream, remote, commit, push, merge, or recovery state.

## Worktree Ownership

- Check `git status --short` before staging or committing.
- Preserve unrelated and user-owned changes. Do not stage them as part of the
  task.
- If unrelated dirty files remain, surface them and continue only with explicit
  paths that belong to the current change.
- Do not silently create, switch, merge, delete, reset, or rewrite branches.

## Checkpoint Commits

- Commit every coherent repository mutation required by the task, as directed by
  `RULES.md`.
- Complete and verify the smallest internally consistent change before
  committing.
- Stage explicit paths only. Do not use `git add -A` or `git add .` unless the
  user explicitly asked for that exact operation.
- Inspect the staged diff before committing and use a message that states the
  behavior, policy, or contract change.
- Run dependent git commands sequentially. Never run commit and push in
  parallel.

## Push Discipline

Push only when explicitly requested. Before each push:

1. Fetch the named remote with pruning.
2. Resolve current branch, HEAD, configured upstream, intended remote, and
   remote-branch existence.
3. Report the exact `local@sha -> remote/target` mapping.
4. Stop for explicit confirmation if the push creates or recreates a branch, the
   upstream is gone, or current branch and intended target disagree.
5. Push an explicit refspec, then verify the remote SHA and synchronized status.

## Recovery

Prefer `git revert` for committed mistakes and targeted manual fixes for
uncommitted files. Do not use destructive reset or checkout without explicit
authorization.
