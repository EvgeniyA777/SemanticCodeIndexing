---
name: semidx-progress-log
description: "Create and maintain semidx staged-plan progress logs and clean handoffs. Use when executing a documented plan, recording stage results, commits, verification, review findings, blockers, residual risk, or preparing a short handoff."
---

# semidx Progress Log

Use this skill when a task executes or updates a documented staged plan.

## Durable Logs

- Store durable progress logs under root `reports/` unless the plan names
  another location.
- Follow `RULES.md` documentation rules for frontmatter, lifecycle, status, and
  English-only committed text.
- Create or update the companion log before or during the first implementation
  stage.
- Keep log updates in the same coherent commit as the implementation or
  documentation change when practical.

## What To Record

- stage name, status, and meaningful outcome;
- changed files and commit hash when available;
- exact verification commands and results, including skipped checks;
- review findings and disposition: accepted, fixed, rejected, deferred, or
  unresolved;
- blockers, environment limitations, and residual risk;
- next-stage handoff, including executor/model recommendation only when the plan
  requires one.

Do not paste chat transcripts, hidden reasoning, raw tool traces, secrets,
credentials, or unbounded logs.

## Closure

When a plan or stage finishes, mark completed work as historical instead of
leaving stale checklists. Update `MEMORY.md` when runtime behavior, invariants,
active assumptions, known gaps, or priorities materially changed.

For a temporary clean-context handoff, use root `progress.txt` only when needed:

```text
## Done
- <completed work and commits>

## In progress
- <current coherent task>

## Remaining
- <next steps>

## Current problems
- <blockers and exact evidence>
```

Remove `progress.txt` after it is consumed; durable state belongs in reports and
`MEMORY.md`.
