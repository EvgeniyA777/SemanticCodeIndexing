---
title: "Java Annotation Metadata And Retrieval"
doc_type: "idea"
lifecycle: "concept"
status: "draft"
agent_action: "use_as_input_for_future_plan_only"
updated: "2026-09-03"
---

# Java Annotation Metadata And Retrieval

## Problem

The Java lane can index classes and methods while failing to expose the
annotations attached to those declarations as useful retrieval targets. This
creates friction for Spring repositories, where annotations carry important
routing, security, transaction, dependency-injection, and framework-registration
semantics.

A query for `@PreAuthorize`, `@Transactional`, `@RequestMapping`, or
`@RestController` may require a broad file read even when the relevant class or
method is already known. Structured retrieval attempts may also fail when the
annotation is not represented in the returned unit model.

## Recommendation

Treat annotations first as structured metadata on their owning class, method, or
field units. Do not immediately model every annotation as an independent unit:
small detached units could increase retrieval noise and remove the declaration
context needed to interpret the annotation.

A later phase may add first-class annotation units if evidence shows that
metadata and filtering are insufficient for exact usage or impact queries.

## Proposed scope

1. Parse annotation names, arguments, source spans, and the owning declaration.
2. Store normalized annotation metadata on the parent unit.
3. Support retrieval constraints or hints by annotation name.
4. Return annotation metadata together with the owning declaration in compact and
   detailed context.
5. Preserve provenance so callers can distinguish source evidence from inferred
   framework behavior.
6. Add focused fixtures for `@PreAuthorize`, `@Transactional`,
   `@RequestMapping`, and `@RestController`.

## Evaluation gate

Before implementing standalone annotation units, compare the current behavior,
metadata retrieval, and standalone-unit retrieval on a small task suite. Measure
precision, recall, context size, and whether an agent can complete Spring
security, REST, and transaction-boundary tasks without broad file reads.

Promote annotations to independent units only if the metadata approach cannot
support exact usage search or impact analysis without unacceptable retrieval
noise.

## Expected value

This should provide high targeted value for Java/Spring repositories while
keeping the normalized unit model compact. It is not expected to improve all
indexing workloads proportionally; its value should be validated through the
retrieval value benchmark before entering an implementation plan.

## Non-goals

- Inferring framework behavior solely from an annotation name.
- Replacing the existing class, method, or field units.
- Adding annotation-specific behavior to individual framework integrations.
- Treating annotations as unit tests or as independent semantic nodes by default.
