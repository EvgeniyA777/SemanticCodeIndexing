# fetch_context_detail fails on long targets_summary

## Repro

Repository root:

`/Users/ae/workspace/ReaderLens`

Index response:

- `index_id`: `037293e1-90b6-4291-a637-16113eda2b6b`
- `snapshot_id`: `3c537c9d-d635-4395-9026-4e49a4b6e18e`
- language: Java
- confidence ceiling: medium

Resolve query body:

```json
{
  "schema_version": "1.0",
  "intent": {
    "purpose": "edit_preparation",
    "details": "Prepare package refactor for API model DTO records and annotation from com.foxminded.readerlens.api to com.foxminded.readerlens.api.model. Need controller/validator imports and tests impacted."
  },
  "targets": {
    "paths": [
      "src/main/java/com/foxminded/readerlens/api/AuthorRequest.java",
      "src/main/java/com/foxminded/readerlens/api/AuthorResponse.java",
      "src/main/java/com/foxminded/readerlens/api/BookCreateRequest.java",
      "src/main/java/com/foxminded/readerlens/api/BookUpdateRequest.java",
      "src/main/java/com/foxminded/readerlens/api/BookResponse.java",
      "src/main/java/com/foxminded/readerlens/api/PublisherRequest.java",
      "src/main/java/com/foxminded/readerlens/api/PublisherResponse.java",
      "src/main/java/com/foxminded/readerlens/api/PageQuery.java",
      "src/main/java/com/foxminded/readerlens/api/PageResponse.java",
      "src/main/java/com/foxminded/readerlens/api/SearchQuery.java",
      "src/main/java/com/foxminded/readerlens/api/SearchResultResponse.java",
      "src/main/java/com/foxminded/readerlens/api/OrderedSearchRanges.java",
      "src/main/java/com/foxminded/readerlens/api/AuthorController.java",
      "src/main/java/com/foxminded/readerlens/api/BookController.java",
      "src/main/java/com/foxminded/readerlens/api/BookSearchController.java",
      "src/main/java/com/foxminded/readerlens/api/PublisherController.java",
      "src/main/java/com/foxminded/readerlens/api/OrderedSearchRangesValidator.java"
    ],
    "symbols": [
      "AuthorRequest",
      "AuthorResponse",
      "BookCreateRequest",
      "BookUpdateRequest",
      "BookResponse",
      "PublisherRequest",
      "PublisherResponse",
      "PageQuery",
      "PageResponse",
      "SearchQuery",
      "SearchResultResponse",
      "OrderedSearchRanges",
      "OrderedSearchRangesValidator"
    ]
  },
  "hints": {
    "preferred_paths": [
      "src/main/java/com/foxminded/readerlens/api"
    ],
    "prefer_definitions_over_callers": false,
    "prefer_breadth_over_depth": true
  },
  "constraints": {
    "freshness": "current_snapshot",
    "language_allowlist": [
      "java"
    ],
    "token_budget": 5000
  },
  "options": {
    "include_tests": true,
    "include_impact_hints": true
  }
}
```

Relevant resolve response fields:

- `result_status`: `completed`
- `selection_id`: `b38349cf-edb5-41a2-8bb4-7aef36eaa627`
- `snapshot_id`: `3c537c9d-d635-4395-9026-4e49a4b6e18e`
- `confidence_level`: `medium`
- `recommended_next_step`: `expand_context`

`expand_context` completed for the selection.

`fetch_context_detail` request:

```json
{
  "index_id": "037293e1-90b6-4291-a637-16113eda2b6b",
  "selection_id": "b38349cf-edb5-41a2-8bb4-7aef36eaa627",
  "snapshot_id": "3c537c9d-d635-4395-9026-4e49a4b6e18e",
  "detail_level": "enclosing_unit"
}
```

Actual response:

```json
{
  "message": "invalid context packet generated",
  "details": {
    "code": "internal_contract_error",
    "category": "internal",
    "errors": {
      "query": {
        "targets_summary": [
          "should have at most 25 elements"
        ]
      }
    }
  }
}
```

## Expected

`fetch_context_detail` should return detail for a valid `selection_id` produced by `resolve_context`, or `resolve_context` should reject/normalize the query before emitting a selection that later violates the packet contract.

## Actual

`resolve_context` completed and `expand_context` completed, but `fetch_context_detail` failed with `internal_contract_error` because the generated `targets_summary` exceeded its maximum length.

## Context

Java's confidence ceiling is medium; the result reached that ceiling. A structured retry had already been used after an earlier broad DTO query selected adjacent exception-handler units instead of the DTO/model targets.
