---
title: "Java Class Modifiers Beyond public Produced An UnknownClass Owner"
doc_type: "bug_report"
lifecycle: "active"
status: "fixed"
agent_action: "reference_for_context"
updated: "2026-09-08"
---

# Java Class Modifiers Beyond public Produced An UnknownClass Owner

Severity: high while it lasted. Every method of a Java class declared with any
modifier other than a bare `public` was attributed to a class the parser
invented, which is wrong in the two places it matters most: the unit's module and
symbol, and the canonical fact key built from them.

## Summary

`semidx.runtime.languages.java/java-class-re` was:

```
^\s*(?:public\s+)?(?:class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)...
```

Only an optional `public` was allowed before the keyword. Everything else —
`final`, `abstract`, `static`, `sealed`, `private`, `protected`, or any
combination — failed to match, so `java-class-spots` never recorded the class and
`java-class-at` fell back to `"UnknownClass"` for every method inside it.

Observed in the benchmark corpus, which seeds `public final class Normalizer` and
`public final class AuditNormalizer`:

```
src/com/acme/audit/AuditNormalizer.java::com.acme.audit.UnknownClass#normalize$arity1$...
src/com/acme/text/Normalizer.java::com.acme.text.UnknownClass#normalize$arity1$...
src/com/acme/text/Normalizer.java::com.acme.text.UnknownClass#normalize$arity2$...
```

Three of the four Java units in that corpus carried a fabricated owner.

## Impact

- **Retrieval**: a query for `com.acme.text.Normalizer#normalize` could not match
  the unit that implements it.
- **Identity**: the canonical fact key in `plans/018` is built from owner and
  symbol, so the heuristic tier's key for such a method could never agree with a
  SCIP or LSP key for the same method. Under the provider-authority migration
  those units would have compared as `legacy_only` and `exact_only` rather than
  `agreed` — a silent, systematic mismatch in exactly the lane Stage 6 makes
  provider-driven.
- **Relations**: `:module` on those units named a class that does not exist.

## Discovery

Found on 2026-09-08 while writing the Java retrieval fixtures required by
`plans/023`. The fixtures could not be written honestly without either fixing
this or recording `UnknownClass` as expected output, which would have enshrined
the defect in a gate.

It had gone unnoticed because the Java lane had no happy-path or ambiguity
fixture — the gap `plans/023` exists to close — and the corpus that did exercise
Java asserted only paths, never symbols.

## Fix

The regex now accepts any sequence of legal class modifiers before the keyword.
Covered by `test/semidx/integration/java_onboarding_test.clj`, which asserts that
`public final`, `public abstract`, and package-private `final` classes all own
their methods, and that no unit is attributed to an invented class name.

Records are still not recognised as a declaration form; that is a separate
feature rather than part of this defect.
