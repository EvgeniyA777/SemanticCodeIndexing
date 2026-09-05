---
title: "Documentation Policy"
doc_type: "policy"
lifecycle: "active"
status: "active"
agent_action: "reference_for_context"
updated: "2026-09-01"
---

# Documentation Policy

## Canonical Ownership

- `RULES.md` owns repository-wide agent rules and is the source of truth for
  always-loaded instructions.
- `docs/agent-policy/` owns cross-cutting engineering policies that are too
  detailed for the always-loaded rule kernel.
- `MEMORY.md` owns current implementation reality, active assumptions, known
  gaps, and near-term priorities.
- ADRs own durable technical decisions.
- Plans own future staged execution.
- Reports own historical progress, evidence, and handoff records.
- Runtime behavior belongs in source and tests; documentation must not override
  committed implementation contracts silently.
- Give each rule or decision one canonical owner and link to it instead of
  copying the same normative text across multiple documents.

## Plan Readiness Gate

A staged implementation plan is ready when a fresh agent can execute it to an
accepted result without guessing product behavior, architectural intent, work
order, verification scope, or stop conditions.

Validate a plan against this gate before executing it. If a hard fail exists,
fix the plan first. If only cosmetic wording or small defensive clarifications
remain, move to execution instead of continuing review loops.

Hard fail conditions:

- Product or runtime behavior is unclear or conflicts with `RULES.md`,
  `MEMORY.md`, ADRs, current plans, contracts, fixtures, or implementation
  behavior.
- Scope boundaries are missing, vague, or allow unrelated refactoring or
  features.
- A key technical decision is implicit, unjustified, or depends on external
  approval without a documented stop and resume rule.
- Branches such as provider, profile, migration, policy, or execution variants
  are mentioned but not carried through stages, files, verification, and DoD.
- Stages depend on outcomes from later stages or lack concrete output
  artifacts.
- DoD cannot be verified through files, commands, tests, API behavior, contract
  examples, fixtures, docs, or committed artifacts.
- The test strategy does not cover the main behavioral, integration, security,
  provider-authority, freshness, or regression risks.
- Runtime constraints are ignored, including profiles, env vars, external
  services, local toolchains, generated artifacts, CI/offline behavior,
  persistence, or startup mode.
- Documentation targets contradict each other or contain stale bookkeeping that
  changes execution meaning.
- The plan requires guessing to decide what to implement, what to skip, what to
  test, or when to stop.

Ready criteria:

- Contract changes are explicit and tied to source-of-truth documents.
- Scope and non-scope are explicit.
- Key decisions and rationale are recorded.
- Blockers and decision branches have precise stop and resume behavior.
- Each stage has a clear purpose, ordered dependencies, and concrete outputs.
- Verification commands and acceptance checks are named.
- DoD is observable and falsifiable.
- Risk-based tests are mapped to the behavior they prove.
- Runtime and environment traps have been considered.
- The document is internally consistent and not overloaded with irrelevant
  implementation detail.

## Language And Links

- Write repository documentation, code comments, agent instructions, and
  `AGENTS.md` files in English.
- Use repository-relative links inside committed documentation. Do not commit
  machine-specific absolute paths or `file://` links.
- Keep prose concise. Put behavior in source/tests, operational rules in
  `RULES.md` or `docs/agent-policy`, active state in `MEMORY.md`, and durable
  decision rationale in ADRs.

## Lifecycle

- Use `active` or `accepted` documents as current sources.
- Treat completed, archived, and superseded documents as historical unless their
  frontmatter explicitly says they remain a current reference.
- Mark executed plans and reports as historical in the same commit that
  completes or supersedes them.
- Preserve useful evidence; do not leave completed checklists looking like
  pending work.
