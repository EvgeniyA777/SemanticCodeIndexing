---
title: "Test-targeting query excludes explicitly targeted test paths"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-08-21"
---

# Test-targeting query excludes explicitly targeted test paths

## Repro

Create the index:

```json
{"root_path":"/Users/ae/workspace/ReaderLens"}
```

Relevant result:

```json
{
  "index_id":"33cb47d0-1307-4a19-bbca-4ba5a0a46777",
  "snapshot_id":"82c6731e-3b19-4040-803f-c5a040ba85de",
  "active_languages":["java"]
}
```

Resolve test context with explicit test paths:

```json
{
  "index_id":"33cb47d0-1307-4a19-bbca-4ba5a0a46777",
  "query":{
    "intent":{
      "purpose":"test_targeting",
      "details":"Assess Stage 4 tests for missing coverage of duplicate create semantics, transaction ownership, named foreign-key translation, unknown failure propagation, and all seven service operations"
    },
    "targets":{
      "paths":[
        "src/test/java/com/foxminded/readerlens/service/CrudServiceIT.java",
        "src/test/java/com/foxminded/readerlens/rating/RatingServiceTest.java",
        "src/test/java/com/foxminded/readerlens/support/DeleteConflictTranslatorTest.java",
        "src/main/java/com/foxminded/readerlens/reader/ReaderTagService.java",
        "src/main/java/com/foxminded/readerlens/rating/RatingService.java"
      ],
      "symbols":[
        "ReaderTagService.create",
        "RatingService.rate",
        "DeleteConflictTranslator.translateDeleteConflict"
      ]
    },
    "hints":{"prefer_definitions_over_callers":false},
    "constraints":{"freshness":"current_snapshot","token_budget":12000}
  }
}
```

Relevant response fields:

```json
{
  "selection_id":"5b72b85d-da85-431e-8f64-716198ee91cf",
  "snapshot_id":"82c6731e-3b19-4040-803f-c5a040ba85de",
  "result_status":"completed",
  "confidence_level":"medium",
  "normalized_query_summary":{"purpose":"test_targeting","include_tests":false},
  "focus":[
    {"path":"src/main/java/com/foxminded/readerlens/rating/RatingService.java"},
    {"path":"src/main/java/com/foxminded/readerlens/reader/ReaderTagService.java"}
  ]
}
```

`expand_context` listed the requested tests only as `related_tests` or callers.
`fetch_context_detail` returned raw snippets only from the two main-source
services. None of the three explicitly targeted test files became a selected
focus unit or raw-context item.

## Expected versus actual

- Expected: `test_targeting` retains explicitly targeted test paths and selects
  their relevant test units for staged expansion and detail fetch.
- Actual: normalization set `include_tests` to `false`; test files were demoted
  to relation hints while main-source units alone were selected and fetched.

## Context

- Language: Java; confidence ceiling: `medium`.
- Snapshot: `82c6731e-3b19-4040-803f-c5a040ba85de`.
- Structured retry: yes; concrete test paths, production paths, and symbols were
  provided with `freshness: current_snapshot`.
- No tool error or degradation was reported.

