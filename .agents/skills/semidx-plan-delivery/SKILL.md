---
name: semidx-plan-delivery
description: "Execute or prepare semidx staged implementation plans from RULES.md, MEMORY.md, ADRs, plans, reports, contracts, and fixtures through readiness validation, implementation, verification, review, progress logging, and commit."
---

# semidx Plan Delivery

Use this skill for non-trivial staged implementation work in semidx.

## Workflow

1. Resolve current sources of truth.
   - Read `RULES.md`, then the active plan, relevant ADRs, `MEMORY.md`, current
     progress log, contracts, fixtures, and handoff notes named by the task.
   - Apply the Plan Readiness Gate from
     `docs/agent-policy/documentation.md` before execution.
   - If a hard fail exists, fix the plan or stop with the exact blocker.

2. Establish the work boundary.
   - State scope and non-scope before editing.
   - Use `semidx-code-exploration` before changing existing source.
   - Create or update the companion progress log with `semidx-progress-log`.

3. Implement the smallest coherent slice.
   - Preserve existing module boundaries and provider contracts.
   - Keep new behavior default-off or shadow when the plan requires it.
   - Keep source identity, freshness, provider authority, and contract changes
     explicit; do not let provider-native identifiers become stable merge keys.

4. Prove the slice.
   - Use `semidx-test-design` to map risks to the lowest sufficient checks.
   - Run focused checks first, then the required regression lane from the plan.
   - Use `semidx-code-review` on the final diff or when risk is high.

5. Commit and hand off.
   - Use `semidx-git-delivery` for staging, commit, and any push request.
   - Update the progress log and `MEMORY.md` when required by `RULES.md`.
   - Record exact checks, skipped checks, residual risk, and next steps.

## Completion Gate

A stage is not complete until its observable DoD is met, verification evidence is
recorded, relevant docs are current, unrelated worktree changes are preserved,
and a coherent commit exists for the touched files.
