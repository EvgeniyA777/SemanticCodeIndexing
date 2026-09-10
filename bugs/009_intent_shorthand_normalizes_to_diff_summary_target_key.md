---
title: "A Flat intent String Normalizes to target_keys diff_summary While the Engine Asks for Structural Targets"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-09-10"
---

# A flat `intent` string normalizes to `target_keys: ["diff_summary"]`

**Confirmed defect:** a `resolve_context` call using the documented flat
`intent` shorthand, with no `targets` of any kind, came back with
`normalized_query_summary.target_keys: ["diff_summary"]`. The same response
advised `narrow_query` because *"Retrieval is ambiguous without explicit
structural targets"*. A request that supplied no targets should normalize to no
target keys, and certainly not to a diff-shaped one.

**Additional retrieval observation:** the selection contained adjacent methods
from the right files but not the definitions holding the searched-for string.
That behavior overlaps the observation already filed as
`bugs/006_java_nested_type_ownership_and_retrieval_feedback.md`; it is recorded
here as a second data point, not as a separate ranking claim.

## Environment

- Consumer: a Java/Python application repository, 170 files, 1094 units.
  Substitute any checkout root when replaying.
- Index: `6afe5254-fc21-42db-968f-945c8a2767f5`.
- Snapshot: `629b1109-760f-4652-accd-7f51eb081c0a`.
- `api_version` in the response: `1.0`. Local semidx checkout HEAD when filing:
  `0b81e03`; the running MCP process's source revision was not established.
- Provider state: `scip-java` `result: "failed"`, `uncovered: 169`, all 1086
  facts `heuristic`, `mode: "shadow"`, `gap_count: 14`. Java's documented normal
  ceiling is medium and the observed `confidence_level` was `medium`; low or
  medium confidence is not itself reported as a defect here.

## Query and result

```json
{
  "index_id": "6afe5254-fc21-42db-968f-945c8a2767f5",
  "intent": "EntityNotFoundException message text produced when an author, publisher or book identifier resolves to no row in AuthorService, PublisherService and BookService"
}
```

Relevant response fields:

- `selection_id: "9edb7d7f-da6c-4eed-aee5-c877bbed61f0"`
- `result_status: "completed"`, `confidence_level: "medium"`
- `query_normalized: true`, `query_ingress_mode: "intent_shorthand"`
- `next_step.recommended_action: "narrow_query"`, reason *"Retrieval is
  ambiguous without explicit structural targets; narrow the query or provide
  paths, modules, or symbols."*
- `normalized_query_summary`:
  `{"purpose": "code_understanding", "details": "<the intent text>",
  "target_keys": ["diff_summary"], "token_budget": 3200,
  "include_tests": false}`
- In the follow-up `fetch_context_detail`, `query.targets_summary` was `[]`.

Expected: `target_keys: []` — or the keys the caller actually supplied — for a
request carrying only an intent string.
Actual: `target_keys: ["diff_summary"]`, alongside an "no explicit structural
targets" diagnosis in the same payload. The two statements contradict each
other, and a `diff_summary` target has no relationship to the request.

`confidence.missing_evidence` reported
`exact_target_resolution_missing: "No exact symbol target resolved from query"`,
which is consistent with no usable target having been derived. That makes the
reported `diff_summary` key look like a normalization artifact rather than
something retrieval acted on — but if any ranking stage does read it, the
shorthand path is silently steering queries.

## The selection, for the record

Four units came back, all `rank_band: "useful_support"`, with `why_selected`
values `graph_module_neighbor`, `graph_path_neighbor`, `lexical_overlap`,
`graph_caller_neighbor`, `graph_callee_neighbor`:

- `AuthorService#update`
- `PublisherService#update`
- `BookService#listByAuthor`
- `BookService#requireAuthor`

The definitions carrying the requested message text are three small private
methods — `AuthorService#getExisting`, `PublisherService#getExisting`,
`BookService#getExisting` — each containing
`new EntityNotFoundException("Author %s not found".formatted(identifier))` or
its publisher/book equivalent. None was selected. `BookService#requireAuthor`
does contain a matching literal, so one third of the answer was present.

`fetch_context_detail` on two of the selected units returned the
`requireAuthor` literal, which answered the question partially; the remaining
message texts were then read directly from the three known file paths with
`sed -n`, about 40 lines. That fallback deviates from the documented refinement
procedure — no structured retry with `targets.paths` was attempted — and is
recorded as a deviation rather than defended. It is also the practical outcome:
the file paths were already known from a review report, so a refined query would
have had to name the very files the fallback read.

## Impact and focused follow-up

1. Fix the intent-shorthand normalization so a target-free request reports no
   target keys. A contract-level assertion that `target_keys` is a subset of the
   keys the caller supplied would prevent the class of defect.
2. Establish whether any ranking stage consumes `target_keys`. If it does, the
   shorthand path has been feeding it a spurious `diff_summary` key, which would
   make this more than a reporting artifact.
3. Separately from the normalization: consider whether `medium` is the right
   confidence for a selection that contains no definition matching a literal the
   query names, in a lane whose semantic provider failed. That question is
   shared with `bugs/006`.

## Verification limits

One query, one session, no structured retry. The claim about `target_keys` rests
on the response fields quoted above; the claim about ranking consumption is
explicitly left open. No semidx source was changed and no semidx tests were run.
