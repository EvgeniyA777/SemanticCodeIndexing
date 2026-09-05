---
title: "Git Workflow Policy"
doc_type: "policy"
lifecycle: "active"
status: "active"
agent_action: "reference_for_context"
updated: "2026-09-05"
---

# Git Workflow Policy

## Canonical Ownership

This document owns detailed git workflow rules for semidx agents. `RULES.md`
owns only the always-loaded summary, and `.agents/skills/semidx-git-delivery`
owns the task procedure for applying this policy.

## Default Branch Discipline

- Stay on the current branch by default.
- Do not create, switch, rename, merge, delete, reset, or rewrite branches unless
  the user explicitly asks for that git operation.
- Do not infer a new branch from a new task, stage, bug, finding, or handoff.
- If the user asks for work to continue after another agent finishes, stop
  before implementation until the user gives the next command.
- If branch isolation is needed for safety, propose it with the exact current
  branch, target branch name, and reason, then wait for approval.

## Concurrent Agent Safety

- Check `git status --short` before any file edit, staging operation, commit, or
  push.
- Treat pre-existing dirty files as user-owned or another-agent-owned unless
  the user says otherwise.
- Do not modify, stage, stash, commit, or revert another-owned dirty file.
- If another-owned changes overlap the files required for the task, stop and ask
  how to coordinate before editing.
- If another-owned changes are unrelated, continue with explicit file paths only
  and report the remaining dirty files in the handoff.
- Do not use `git add -A`, `git add .`, or broad pathspecs unless the user
  explicitly asks for that exact operation.

## Commit Discipline

- Commit each coherent repository mutation after verification.
- Keep commits atomic: one behavior, contract, policy, or documentation concern
  per commit.
- Stage only the files that belong to the current change.
- Inspect the staged diff before committing.
- Use commit messages that name the behavior, contract, policy, or bug fixed.
- Do not mix implementation work with unrelated notes, scratch files, or
  another agent's changes.
- If a task is intentionally analysis-only, do not create a commit.

## Push Discipline

- Push only when explicitly requested.
- Before pushing, fetch the intended remote, identify the current branch, HEAD
  SHA, configured upstream, intended target, and whether the remote branch
  exists.
- Report the exact `local@sha -> remote/branch` mapping before the push.
- Stop for confirmation if the push would create or recreate a remote branch,
  the upstream is missing, the intended target differs from the current branch,
  or local history diverges from the remote.
- Push with an explicit refspec and verify the remote SHA afterward.
- Never run `git commit` and `git push` in parallel.

## Recovery

- Prefer a new corrective commit for published or shared mistakes.
- Prefer `git revert` for committed mistakes when preserving history matters.
- Use destructive commands such as `git reset --hard`, branch deletion, or
  checkout-over-write only after explicit user authorization.
- Do not use stash as a coordination mechanism for user-owned work unless the
  user asks for it.

## Current Long Unit Id Bugfix Case

For the long `unit_id` contract bug recorded in
`notes/2026-09-04-fetch-context-long-unit-id-contract.md`:

- Treat the fix as a separate follow-up bugfix slice, not as part of Stage 4.
- Do not start the slice until the user explicitly commands it, because another
  agent may still be working on the branch.
- When authorized, stay on the current branch unless the user explicitly asks
  for a new branch.
- Re-check `git status --short` and preserve any unrelated or another-owned
  dirty files.
- Commit the bugfix as its own coherent commit or small series of coherent
  commits, with contract/schema changes, runtime changes, tests, and docs
  grouped by concern.
- Do not push the branch unless the user explicitly requests a push.
