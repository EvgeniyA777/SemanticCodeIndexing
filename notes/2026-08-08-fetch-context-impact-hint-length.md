---
title: "fetch_context_detail Rejects Generated Long Caller Hints"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-08-08"
---

# `fetch_context_detail` rejects generated long caller hints

Date: 2026-08-08

## Reproduction

- Root: `/Users/ae/workspace/UniPlan`
- Index: `144fe1b6-3f15-4057-a3f4-c5d5d6f928e9`
- Snapshot: `2b4dba46-5f75-4a26-8952-bdce8b208ff0`
- Language: Java (medium confidence ceiling)
- Structured retry: yes, with exact Java paths and symbols after an initial broad
  HTML-heavy low-confidence selection.
- Selection: `ed685871-91fa-4834-94f7-aa2de97a13f3`, confidence `medium`.

The structured query requested `review_support` for these symbols and their
tests: `CourseSectionController`, `LessonOccurrenceService`,
`CourseSectionSchedulingService`, `CreateCourseSectionCommand`,
`UpdateCourseSectionCommand`, `ScheduleConflictPolicy`,
`ScheduleConflictCandidateAdapter`, `CourseSectionService`,
`ScheduleNavigationResolver`, `SampleDataInitializer`, and
`CourseSectionSchedulingServiceTransactionalIT`. It used exact production paths
plus `src/test/java/ua/com/foxminded/uniplan`, current-snapshot freshness, a
16,000-token budget, `include_tests: true`, and impact hints.

`expand_context` completed. Both an unrestricted `fetch_context_detail` at
`enclosing_unit` and a two-unit retry at `target_span` failed with:

```text
invalid context packet generated
code: internal_contract_error
category: internal
errors.impact_hints.callers: several generated entries "should be at most 240 characters"
```

## Expected

`fetch_context_detail` should return the selected source spans, truncating or
normalizing an overlong generated impact-hint label if necessary.

## Actual

An overlong server-generated caller hint makes the entire detail packet invalid,
so no selected source detail is returned.
