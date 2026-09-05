---
title: "Explicit Java Test Targets Are Normalized Away and Rank Unrelated CRUD Methods"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-08-23"
---

# Explicit Java test targets are normalized away and rank unrelated CRUD methods

This is a narrower follow-up to
[`2026-08-21-test-target-paths-excluded.md`](2026-08-21-test-target-paths-excluded.md).
The new reproduction explicitly passes `include_tests: true`, but the normalized
query still reports `include_tests: false` and returns unrelated main-source
CRUD methods.

## Repro

Create or reuse the ReaderLens index:

```json
{"root_path":"/Users/ae/workspace/ReaderLens"}
```

Relevant result:

```json
{
  "index_id":"34f2c146-2ecd-4936-9697-25e5d160ab6c",
  "snapshot_id":"4b0ec855-fb89-4a0a-9c0f-6023d85ae9ae",
  "active_languages":["java"],
  "lifecycle_reason":"workspace_unchanged"
}
```

### Attempt 1: exact paths in a plain intent

```json
{
  "index_id":"34f2c146-2ecd-4936-9697-25e5d160ab6c",
  "intent":"Inspect exactly src/test/java/com/foxminded/readerlens/testsupport/PostgresIntegrationTest.java, src/test/java/com/foxminded/readerlens/testsupport/DatabaseModeImportSelector.java, and src/test/java/com/foxminded/readerlens/testsupport/PostgresContainerConfiguration.java to determine whether adding @ActiveProfiles(\"import\") to import integration tests creates a distinct Spring context and PostgreSQL container."
}
```

Relevant response:

```json
{
  "selection_id":"9d625613-c1ab-4d1d-b2c8-e159f44a93ae",
  "result_status":"completed",
  "confidence_level":"low",
  "normalized_query_summary":{"include_tests":false},
  "next_step":{"recommended_action":"narrow_query"},
  "focus":[
    {"path":"src/main/java/com/foxminded/readerlens/catalog/AuthorService.java","symbol":"AuthorService#create"},
    {"path":"src/main/java/com/foxminded/readerlens/catalog/AuthorService.java","symbol":"AuthorService#find"},
    {"path":"src/main/java/com/foxminded/readerlens/catalog/AuthorService.java","symbol":"AuthorService#update"},
    {"path":"src/main/java/com/foxminded/readerlens/catalog/AuthorService.java","symbol":"AuthorService#delete"}
  ]
}
```

### Attempt 2: structured retry with exact paths, symbols, and tests enabled

```json
{
  "index_id":"34f2c146-2ecd-4936-9697-25e5d160ab6c",
  "query":{
    "intent":{
      "purpose":"code_understanding",
      "details":"Inspect Spring integration-test context and container sharing for an import profile."
    },
    "targets":{
      "paths":[
        "src/test/java/com/foxminded/readerlens/testsupport/PostgresIntegrationTest.java",
        "src/test/java/com/foxminded/readerlens/testsupport/DatabaseModeImportSelector.java",
        "src/test/java/com/foxminded/readerlens/testsupport/PostgresContainerConfiguration.java"
      ],
      "symbols":[
        "PostgresIntegrationTest",
        "DatabaseModeImportSelector",
        "PostgresContainerConfiguration"
      ]
    },
    "hints":{"prefer_definitions_over_callers":true},
    "constraints":{"freshness":"current_snapshot"},
    "include_tests":true
  }
}
```

Relevant response:

```json
{
  "selection_id":"a3de4376-ba22-4500-8dbd-c03b8e153896",
  "result_status":"completed",
  "confidence_level":"medium",
  "normalized_query_summary":{
    "purpose":"code_understanding",
    "target_keys":["paths","symbols"],
    "include_tests":false
  },
  "focus":[
    {"path":"src/main/java/com/foxminded/readerlens/catalog/AuthorService.java","symbol":"AuthorService#create"},
    {"path":"src/main/java/com/foxminded/readerlens/catalog/AuthorService.java","symbol":"AuthorService#find"},
    {"path":"src/main/java/com/foxminded/readerlens/catalog/AuthorService.java","symbol":"AuthorService#update"},
    {"path":"src/main/java/com/foxminded/readerlens/catalog/AuthorService.java","symbol":"AuthorService#delete"}
  ]
}
```

## Expected versus actual

- Expected: the structured query retains `include_tests: true`; the three exact
  path and class targets dominate the selection, or the tool returns a clear
  validation error if the flag is in an unsupported location.
- Actual: the request completes successfully, normalizes `include_tests` to
  `false`, ignores every exact test target, and ranks four unrelated
  `AuthorService` CRUD methods.

## Context

- Language: Java; confidence ceiling: `medium`.
- Snapshot: `4b0ec855-fb89-4a0a-9c0f-6023d85ae9ae`.
- Structured retry: yes; it supplied exact file paths, exact class symbols,
  `freshness: current_snapshot`, and `include_tests: true`.
- The structured retry reached Java's maximum confidence while selecting zero
  requested units.
- No error, degradation, or ignored-field diagnostic was returned.
- Snapshot-bound `literal_file_slice` can read all three requested paths, so
  the units are present in the index and the fallback is usable after the
  ranking failure.
