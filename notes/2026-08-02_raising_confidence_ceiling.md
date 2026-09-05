---
title: "Raising Confidence Ceiling Mechanism"
doc_type: "note"
lifecycle: "active"
status: "final"
agent_action: "reference_for_context"
updated: "2026-08-02"
---

# Mechanism for Raising the Confidence Ceiling

To raise the `Confidence Ceiling` of a newly onboarded language (or an existing one like Java) to `high`, the project requires strict evidence because the confidence level directly controls AI agent autonomy and guardrails.

According to `ADR-022` and `MEMORY.md`, the mechanism consists of one technical change and three validation gates.

## 1. Technical Implementation

The single source of truth for language strength is `src/semidx/runtime/language_registry.clj`. 
You must update the `:strength` field in the `language-lanes` vector:

```clojure
{:language "java"
 :extensions [".java"]
 :provider {:provider_id "java-native" :provider_version "1" :classification "source"}
 :strength "high"} ;; Change from "low" or "medium" to "high"
```

## 2. Ambiguity Fixtures

To achieve a `high` ceiling, the parser must not guess randomly. You must add fixtures in `fixtures/retrieval/` proving the language handles **Ambiguity cases**:
- A scenario with identical function/method names across different files or classes.
- The parser must prove it correctly resolves imports, aliases, and context (e.g., `this.` or `super.`), linking the call to the exact correct target without over-linking.

## 3. Interprocedural Dataflow (Stage 3)

A simple file-to-file link is insufficient for `high` status. The language parser must extract precise calls and dataflow invariants (per ADR-037):
- `dataflow/local-binding-call-result`
- `dataflow/passes-argument`

Only a deep Semantic Graph justifies a `high` ceiling.

## 4. Calibration & Quality Gates

After updating the registry to `high`, you must pass the quality scripts to verify the engine's honesty:
1. `./scripts/run-benchmarks.sh` — Must pass without regressions.
2. `./scripts/run-semantic-quality-report.sh` — Measures "calibration". It verifies if the actual quality of extracted edges matches the claimed `high` level. If the parser emits too many generic/garbage edges, this report will block the promotion.

**Summary:** This is Test-Driven Onboarding. Write ambiguity fixtures, implement precise call resolution, flip the flag, and pass the semantic quality report.
