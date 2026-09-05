---
name: semidx-code-review
description: "Review semidx diffs with findings-first, evidence-backed analysis across contracts, CanonicalFactKey identity, provider authority, source freshness, staged retrieval, MCP wire shape, language lanes, storage/runtime edges, and tests."
---

# semidx Code Review

Lead with findings ordered by severity. Do not begin with an implementation
summary when defects exist.

## Evidence Standard

Each finding includes severity, practical impact, exact file/line evidence, and
the smallest reasonable fix. Classify uncertain items as:

- `Confirmed finding` - reproduced or proven by an unambiguous code path;
- `Hypothesis needing verification` - runtime/tool behavior needs proof;
- `Open question` - the plan, ADR, contract, or architecture source is
  insufficient;
- `Rejected / false positive` - evidence disproved the concern;
- `Fixed` - correction and verification are present.

Do not upgrade provider, parser, database, transport, or toolchain speculation
into a confirmed defect without code evidence, committed contract evidence, or a
focused repro.

## Review Workflow

1. Read the applicable plan, ADRs, `MEMORY.md`, progress log, policy documents,
   contracts, fixtures, and final diff.
2. Use `semidx-code-exploration` to inspect changed symbols, callers,
   dependents, and related tests before broad manual search.
3. Check provider-neutral identity: `CanonicalFactKey`, must-merge and
   must-not-merge fixtures, overload/dispatch identity, deterministic ordering,
   and evidence-only native ids.
4. Check provider authority: source identity anchors, freshness gates,
   exact/structural/heuristic precedence, fallback behavior, diagnostics, and
   unavailable providers.
5. Check staged retrieval and public contracts: compact selection, expansion,
   detail fetch, MCP wire shape, schema mirrors, examples, HTTP/gRPC alignment,
   and token-budget behavior.
6. Check language-lane behavior: parser confidence, fixture coverage,
   tree-sitter/regex/provider parity, generated artifacts, and onboarding gates.
7. Check storage and runtime edges when touched: in-memory path, PostgreSQL path,
   process runners, CLI/MCP/HTTP/gRPC startup, cleanup, and error reporting.
8. Check whether tests prove behavior at the lowest sufficient level and whether
   higher-level checks add distinct evidence.

## Response Shape

1. Findings, highest severity first.
2. Open questions or assumptions that affect correctness.
3. Verification commands and results.
4. Residual risk or missing tests.

If no finding remains, say so plainly and still state verification limits.
