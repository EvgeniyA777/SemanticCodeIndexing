---
title: "Java Class Is Attributed to UnknownClass"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-08-20"
---

# Java class attributed to `UnknownClass`

## Repro

`create_index`:

```json
{"root_path":"/Users/ae/workspace/ReaderLens"}
```

Relevant index response fields:

```json
{
  "index_id": "5876cf77-797a-48d5-826e-98d7258d1bae",
  "snapshot_id": "9ae02cd5-1ac5-4442-b8e1-687e3c762c89",
  "active_languages": ["java"],
  "git_commit": "fbc1bb5161ab0f30457d538d0446f4a6a9a5d03d"
}
```

Narrow structured `resolve_context` retry:

```json
{
  "index_id": "5876cf77-797a-48d5-826e-98d7258d1bae",
  "query": {
    "intent": {
      "purpose": "review_support",
      "details": "Inspect exact delete conflict translation implementation, its exception contract, callers from Stage 4 services, constraint name matching, and unknown failure propagation."
    },
    "targets": {
      "paths": [
        "src/main/java/com/foxminded/readerlens/support/DeleteConflictTranslator.java",
        "src/main/java/com/foxminded/readerlens/support/ReferencedEntityException.java"
      ]
    },
    "hints": {
      "prefer_breadth_over_depth": true,
      "prefer_definitions_over_callers": false
    },
    "constraints": {
      "freshness": "current_snapshot",
      "token_budget": 10000
    }
  }
}
```

Relevant response fields:

```json
{
  "result_status": "completed",
  "selection_id": "cbe2fc9d-1127-441c-8aef-641230a8365e",
  "snapshot_id": "9ae02cd5-1ac5-4442-b8e1-687e3c762c89",
  "confidence_level": "medium",
  "selected_unit": {
    "path": "src/main/java/com/foxminded/readerlens/support/DeleteConflictTranslator.java",
    "symbol": "com.foxminded.readerlens.support.UnknownClass#translateDeleteConflict",
    "span": {"start_line": 39, "end_line": 49}
  }
}
```

`expand_context` and `fetch_context_detail` preserve the same incorrect unit id:

```text
src/main/java/com/foxminded/readerlens/support/DeleteConflictTranslator.java::com.foxminded.readerlens.support.UnknownClass#translateDeleteConflict$arity1$sigd292c470
```

## Expected vs actual

- Expected: the method symbol is `com.foxminded.readerlens.support.DeleteConflictTranslator#translateDeleteConflict`.
- Actual: the correct method and source span are returned, but ownership is attributed to the nonexistent `com.foxminded.readerlens.support.UnknownClass`.

## Context

- Language: Java; confidence ceiling: `medium`.
- Snapshot: `9ae02cd5-1ac5-4442-b8e1-687e3c762c89`.
- A structured path-targeted retry was used after the original broad symbol query also returned `UnknownClass`.
- The source declares `public final class DeleteConflictTranslator` at line 19; the method is a public static member of that class.
