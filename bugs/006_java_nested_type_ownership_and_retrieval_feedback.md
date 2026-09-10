---
title: "Java Nested Type Ownership and Retrieval Feedback from ReaderLens"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-09-10"
---

# Java nested type ownership and retrieval feedback

**Confirmed defect:** methods of `SnapshotImporter` are attributed to its
nested interface `Clock`, including after a structured path-targeted retry,
expansion and detail fetch. This corrupts the returned symbol identity and makes
the structural context unreliable.

**Additional retrieval observation:** a natural-language request naming the
publication classes returned unrelated methods. A narrower retry did not select
the requested publication SQL. The cause of that ranking behavior is unproven;
do not assume it shares the ownership defect's root cause.

## Environment

- Consumer: ReaderLens, Java/Python index, reviewed source commit
  `a71cc9a40a6d4d4fdc0a5a5b8e2a5ff0967405db`.
- Initial indexing request: `create_index` with `root_path` set to the absolute
  ReaderLens checkout root and no custom parser or language options. Substitute
  that checkout's absolute path when replaying; local filesystem paths are
  intentionally omitted from this portable report.
- Index: `6039ea1c-a146-496b-9a68-b7a52e1a4e1f`.
- Snapshot: `907b472f-87c4-40e6-b8d3-1183a2cb27e5`.
- API version in the response: `1.0`. Local semidx checkout HEAD when filing:
  `b227454`; the running MCP process's source revision was not established.
- Java's documented normal confidence ceiling is medium. Observed selections
  have low confidence. Low confidence alone is not reported as a defect, and
  this report does not establish the active provider's effective ceiling.
- The original review already completed index creation, repository map,
  resolution, expansion and detail. The filing-time retry reused that snapshot;
  it did not force a rebuild or restart the provider.

## Original query and result

```json
{
  "index_id": "6039ea1c-a146-496b-9a68-b7a52e1a4e1f",
  "snapshot_id": "907b472f-87c4-40e6-b8d3-1183a2cb27e5",
  "intent": "Import publication deletes serving authors publishers books and inserts replacement snapshot. Does it reset numeric identity sequences or reuse book ISBN primary keys? Inspect ImportSql and PostgresImportLease publication."
}
```

The question's premise about deleting authors/publishers was an investigation
hypothesis, not a source fact: actual publication retains those dictionaries.
The expected relevant context is the publication SQL that establishes this.

Relevant result fields:

```json
{
  "result_status": "completed",
  "selection_id": "ec6cf539-6c2e-47cc-8f3e-6522df87f2a7",
  "confidence_level": "low",
  "next_step": {
    "recommended_action": "narrow_query",
    "reason": "Retrieval is ambiguous without explicit structural targets; narrow the query or provide paths, modules, or symbols."
  }
}
```

The three selected symbols were:

- `com.foxminded.readerlens.importing.application.Clock#reconcile`,
  `SnapshotImporter.java`, lines 363–390.
- `com.foxminded.readerlens.importing.source.ConsumedBytes#identity`,
  `CsvSnapshotReader.java`, lines 206–215.
- `com.foxminded.readerlens.importing.application.Clock#closeSuppressing`,
  `SnapshotImporter.java`, lines 153–336.

Neither named publication file was selected. The explicit ambiguity diagnostic
is useful and is preserved here; the result was not an undocumented hard error.

## Structured retry and persistent ownership defect

Executed on 2026-09-10 while filing:

```json
{
  "index_id": "6039ea1c-a146-496b-9a68-b7a52e1a4e1f",
  "snapshot_id": "907b472f-87c4-40e6-b8d3-1183a2cb27e5",
  "query": {
    "intent": {
      "purpose": "bug_investigation",
      "details": "Inspect snapshot publication SQL and actual owner of SnapshotImporter reconcile; narrow retry of the import publication query."
    },
    "targets": {
      "paths": [
        "src/main/java/com/foxminded/readerlens/importing/persistence/ImportSql.java",
        "src/main/java/com/foxminded/readerlens/importing/persistence/PostgresImportLease.java",
        "src/main/java/com/foxminded/readerlens/importing/application/SnapshotImporter.java"
      ]
    },
    "constraints": {
      "freshness": "current_snapshot",
      "token_budget": 1800
    }
  }
}
```

Result: `completed`, selection `4582ecf1-b7e4-4175-bc27-2c37096ad8c5`, same
snapshot, confidence `low`. Both selected units were marked `top_authority`
with `target_path_match` and `graph_module_neighbor`:

| Path | Returned symbol | Span |
| --- | --- | --- |
| `importing/application/SnapshotImporter.java` | `com.foxminded.readerlens.importing.application.Clock#alsoReporting` | 139–152 |
| `importing/application/SnapshotImporter.java` | `com.foxminded.readerlens.importing.application.Clock#closeSuppressing` | 153–336 |

For example, the retained unit id is:

```text
src/main/java/com/foxminded/readerlens/importing/application/SnapshotImporter.java::com.foxminded.readerlens.importing.application.Clock#alsoReporting$arity2$sig91db547e
```

`expand_context` with this selection preserved both wrong owners. Its status
was `truncated`, with `impact_hints_omitted` under the 360-token expansion
reservation; skeletons were returned. `fetch_context_detail`, `target_span`,
restricted to the `alsoReporting` unit, returned the real method body while
preserving the incorrect identity. Detail diagnostics included `confidence_low`;
no tool error code/category was returned by these successful calls.

The snapshot-bound literal slice of `SnapshotImporter.java`, lines 1–64,
establishes the declarations:

```java
public final class SnapshotImporter { // line 41
    // fields omitted
    @FunctionalInterface
    public interface Clock {          // line 51
        Clock SYSTEM = System::nanoTime;
        long nanoTime();
    }                                 // line 56
    // SnapshotImporter constructors and methods follow
```

**Expected:** `alsoReporting`, `closeSuppressing` and `reconcile` belong to
`com.foxminded.readerlens.importing.application.SnapshotImporter` after the
nested interface closes.

**Actual:** retrieval and expansion attribute those outer-class methods to
`com.foxminded.readerlens.importing.application.Clock`.

This is related to, but not established as the same cause as,
[bug 004](004_java_class_modifiers_produce_unknownclass_owner.md), which is
marked fixed and concerns class modifiers producing `UnknownClass`. Here a real
nested type becomes the incorrect owner. Earlier consumer evidence is in
[the September 8 note](../notes/2026-09-08_readerlens-java-provider-and-symbol-retrieval.md).

## Impact and focused follow-up

1. Correct owner identity across entry into and exit from nested Java types.
   Verify methods before, inside and after a nested interface/class, including
   symbol-targeted retrieval, expansion and detail. Do not encode the wrong
   owner as a golden expectation.
2. Replay the original publication question and a query restricted to the two
   publication paths. Measure whether publication constants/methods are selected
   or whether an explicit unsupported/unavailable-target diagnostic is needed.
   The three-path retry above has a small budget and multiple objectives; it
   does not establish a general ranking failure under every query shape.
3. Investigate the unusually broad reported `closeSuppressing` span separately;
   its end boundary was not verified in this filing and is not a confirmed
   additional finding.

## Usability feedback, not additional confirmed bugs

The practical assessment from this review was **6/10**, a subjective evaluation
without a timed or token-counted comparison against other navigation tools.
Stable snapshot references, retained selections and exact literal slices were
useful. Incorrect owners and missed targets required independent verification.

Responses repeatedly include workflow hints and project metadata. Consider a
compact response mode or one-time session metadata, and measure the resulting
context savings before changing the contract. One particularly large displayed
response also resulted from the client printing both `content` and
`structuredContent`; that duplication is not assigned to semidx as a bug.

The five-stage workflow adds interaction cost for tiny lookups. Preserve direct
access to already identified symbols/paths and clarify when expansion/detail
are optional. This is workflow feedback, not a claim that every stage is
technically mandatory or that staged retrieval lacks value.

## Verification limits

This filing adds a defect report only. Evidence comes from the live MCP calls
and exact consumer-source slices above. No semidx source was changed, no parser
root cause was proven, and no semidx test suite was run. Index and selection ids
are session artifacts; a replay on a fresh index must use its returned ids.
