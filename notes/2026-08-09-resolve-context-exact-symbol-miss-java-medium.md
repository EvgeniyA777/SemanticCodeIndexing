---
title: "resolve_context Misses Exact Java Symbols Present at the Given Path, Ranks Unrelated Methods Instead"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-08-09"
---

# resolve_context Misses Exact Java Symbols Present at the Given Path, Ranks Unrelated Methods Instead

## Reproduction

Index: `index_id: "3d913855-5a54-4d08-b3dc-404bc2d3cf09"`,
`snapshot_id: "c0cc77ff-3532-4baa-a85a-442b7afd6bae"`, repo
`/Users/ae/workspace/UniPlan`, active languages `["java","javascript","html","css"]`.

Both target classes exist exactly where queried:

```text
src/test/java/ua/com/foxminded/uniplan/repository/PersistenceLayerIT.java
src/test/java/ua/com/foxminded/uniplan/support/PostgresContainerHolder.java
```

### Attempt 1 — plain intent

```json
{
  "index_id": "3d913855-5a54-4d08-b3dc-404bc2d3cf09",
  "intent": "PersistenceLayerIT test class and PostgresContainerHolder base setup for PostgreSQL Testcontainers integration tests"
}
```

Result: `confidence_level: "low"`, `result_status: "completed"`. Every
`target_unit_id` returned was an HTML span from an unrelated research file:

```text
intake/task-4-1/research/raw/Современные нормативы расписаний университетов США (...).html::html-element::52::1
```

(and two sibling spans of the same file). Neither Java class appears anywhere
in the selection.

### Attempt 2 — structured retry, narrower per the skill's own escalation path

```json
{
  "index_id": "3d913855-5a54-4d08-b3dc-404bc2d3cf09",
  "query": {
    "intent": {
      "purpose": "test_targeting",
      "details": "Find the PersistenceLayerIT class and PostgresContainerHolder Testcontainers base class to follow conventions for a new PostgreSQL-backed integration test"
    },
    "targets": {
      "symbols": ["PersistenceLayerIT", "PostgresContainerHolder"],
      "paths": ["src/test/java/ua/com/foxminded/uniplan"]
    },
    "constraints": { "freshness": "current_snapshot" }
  }
}
```

Result: `confidence_level: "medium"` (the documented Java ceiling — so this is
the best the language can report). `target_unit_ids`:

```text
src/main/java/ua/com/foxminded/uniplan/controller/CourseSectionController.java::...CourseSectionController#toCreateCommand...
src/main/java/ua/com/foxminded/uniplan/controller/CourseSectionController.java::...CourseSectionController#toUpdateCommand...
```

Neither is `PersistenceLayerIT` or `PostgresContainerHolder`; neither is even
under the queried `src/test/...` path prefix. `toCreateCommand`/
`toUpdateCommand` share no lexical overlap with either requested symbol name
beyond generic Java/test vocabulary.

## Expected

Given exact, unambiguous class-name symbols (`PersistenceLayerIT`,
`PostgresContainerHolder`) plus a `targets.paths` prefix that both files
literally live under, at least one of the two should appear in the selection —
this is the canonical "narrow the query with structured targets" escalation
the skill instructs on low confidence, and it still misses.

## Actual

Both attempts returned zero overlap with the actual symbols, including a
result from entirely outside the requested path prefix on the structured
retry. Fell back to `find`/`Read` per the skill's own exhaustion clause
("only after a materially narrower structured retry still fails").

## Context

- Java ceiling is `medium`; this result reached that ceiling but was still
  substantively wrong (not just imprecise/low-signal).
- `include_tests` was left at its default (`false`) in both queries — both
  requested symbols are test-tree classes (`PersistenceLayerIT` under
  `src/test/.../repository`, `PostgresContainerHolder` under
  `src/test/.../support`). This may be the actual root cause: if
  `include_tests: false` excludes test-tree units from ranking entirely
  rather than just deprioritizing them, an exact-name match in the test tree
  would be structurally unreachable regardless of query specificity, which
  would explain why a completely unrelated main-tree method outranked two
  exact matches. Worth checking directly against the `include_tests` handling
  in `resolve_context` before assuming this is purely a ranking-quality issue.
- Discovered during a UniPlan test-coverage assessment (session date
  2026-08-09), immediately after fixing an unrelated `create_index` root-path
  mistake in the same session (see
  `2026-08-09-create-index-unknown-root-param-silent-fallback.md`) — the
  index itself was confirmed correct (`file_count: 258`, right `root_path`)
  before this symbol-lookup attempt, so a stale/wrong index is ruled out as
  the cause here.
