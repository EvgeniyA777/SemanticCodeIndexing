---
title: "Java Lane Is Missing The Onboarding Artifacts Its Own Validator Requires"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-09-07"
---

# Java Lane Is Missing The Onboarding Artifacts Its Own Validator Requires

Severity: medium. Nothing is broken at runtime — the Java lane indexes, retrieves
and is covered by tests — but the checklist that decides whether a language lane
is complete fails for it, and Java is one of the two languages the `plans/018`
authority switch makes provider-driven.

## Summary

`./scripts/validate-language-onboarding.sh java` reports ten failures:

```
fail: missing file: test/semidx/integration/java_onboarding_test.clj
fail: missing file: docs/language-onboarding/java.md
fail: missing file: fixtures/retrieval/java-happy-path.json
fail: missing file: fixtures/retrieval/java-ambiguity.json
fail: onboarding test uses mirrored integration namespace (auto-discovered)
fail: happy fixture id is correct (retrieval_java_happy_path_001)
fail: ambiguity fixture id is correct (retrieval_java_ambiguity_001)
fail: corpus references happy fixture (java-happy-path.json)
fail: corpus references ambiguity fixture (java-ambiguity.json)
fail: onboarding doc references ADR-022
validation_failed errors=10
```

`./scripts/validate-language-onboarding.sh typescript` passes, including its
gates.

## This is not a regression

None of the four missing files has ever existed:
`git log --all -- fixtures/retrieval/java-happy-path.json docs/language-onboarding/java.md`
returns nothing. The Java lane predates the checklist this validator encodes, and
the retrieval fixtures it does have use different names — for example
`fixtures/retrieval/java-overload-like-call-ambiguity.json`, which covers the
ambiguity case under a name the validator does not look for.

Found on 2026-09-07 while running the `plans/018` Stage 6.5 gates.

## Why it matters now

Stage 6 makes the provider plan authoritative for Java and TypeScript. The
per-language onboarding validation is one of the gates that answers "is this lane
complete enough to change its default behaviour", and for Java it currently
answers no — for reasons that have nothing to do with the provider work, which is
exactly what makes the signal useless in its present state: it cannot fail
usefully, because it already fails.

## Suggested fix

Two honest options, and the choice is the owner's:

1. **Produce the artifacts.** Add `docs/language-onboarding/java.md`, the two
   retrieval fixtures under the names the validator expects with the ids it
   expects, the corpus references, and the mirrored integration test. This is the
   path that makes the gate meaningful for Java.
2. **Correct the validator.** If the existing Java fixtures already cover the
   happy path and the ambiguity case under other names, teach the checklist those
   names rather than duplicating fixtures to satisfy a pattern match. The
   ADR-022 reference requirement should also be checked: other lanes may cite a
   different ADR today.

Whichever is chosen, it should be settled before the authority default flip, so
that a lane-level gate failure at that moment means something.
