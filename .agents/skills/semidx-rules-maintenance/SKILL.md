---
name: semidx-rules-maintenance
description: "Maintain semidx repository rules, policy documents, and skill routing with one canonical owner per rule. Use when adding standing rules, moving detailed procedures into docs/agent-policy or skills, or resolving duplicated agent instructions."
---

# semidx Rules Maintenance

Use this skill when adding or reorganizing durable agent instructions.

## Canonical Ownership

- `RULES.md` is the single source of truth for always-loaded repository agent
  rules.
- `docs/agent-policy/*` owns detailed cross-cutting engineering policy.
- `.agents/skills/*` owns task procedures that should load only when relevant.
- Plans, reports, ADRs, and `MEMORY.md` may reference rules but must not become
  competing normative copies.

## Adding Or Moving Rules

- Add a short imperative rule to `RULES.md` when it must be always visible.
- Add detailed cross-cutting guidance to the relevant `docs/agent-policy/*`
  document and link it from `RULES.md`.
- Add task workflow to a repo-local skill when it should activate only for that
  kind of work.
- Prefer amending an existing canonical owner over creating a new duplicate.
- Keep all committed instructions in English.

## Restructuring Checks

Before committing a rule or skill change:

- verify there is one canonical owner for the rule;
- update routing references in `RULES.md` when a new policy document or skill is
  added;
- check frontmatter for new policy documents and `SKILL.md` frontmatter for new
  skills;
- run whitespace and language checks on touched instruction files;
- stage explicit paths only and preserve unrelated worktree changes.

If current documents conflict, follow the current source named by `RULES.md`.
Ask only when multiple current sources make the intended rule impossible to
determine.
