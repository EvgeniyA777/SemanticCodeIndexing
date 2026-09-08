---
title: "ReaderLens Java provider failure and class-symbol retrieval"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-09-08"
---

# Observed behavior

During documentation planning in ReaderLens, indexing returned a usable Java
heuristic index but failed the scip-java provider. Fully qualified class targets
did not resolve and returned unrelated search-policy methods. Concrete path
targets and literal slices worked. This report does not assert the cause of the
provider failure or that low confidence itself is a defect.

## Reproduction and evidence

1. `create_index` with
   `{"root_path":"/Users/ae/workspace/ReaderLens"}` at commit
   `0c0bdb764357304e00f1a8f2e0d7062f389bc179`.
2. Returned index `e1b463e3-9d9e-47fa-b024-9a9277fb1924`, snapshot
   `94c5ad0f-94ad-4ce0-a0b1-f6f4d06a9b9d`, active language `java`,
   166 files. Provider summary: scip-java `result: failed`, `uncovered: 166`,
   `complete: false`; heuristic authorities supplied the index. A new untracked
   `javac.20260908_093853.args` appeared in the consumer repository during
   indexing and was removed after inspection work; no compiler diagnostic was
   available in the index response.
3. Called `repo_map`, then a broad API/security query, expansion and detail.
   Refined the unresolved class targets with this query:

```json
{
  "index_id": "e1b463e3-9d9e-47fa-b024-9a9277fb1924",
  "query": {
    "intent": {
      "purpose": "code_understanding",
      "details": "SecurityConfiguration filter chain and public access tests"
    },
    "targets": {
      "symbols": [
        "com.foxminded.readerlens.security.SecurityConfiguration",
        "com.foxminded.readerlens.api.v1.ApiAccessRulesTest"
      ]
    },
    "constraints": {
      "freshness": "current_snapshot",
      "token_budget": 3500
    },
    "include_tests": true
  }
}
```

Selection `11729ab4-9598-45c8-8c19-a652bdd69ebe` was `completed`, confidence
`low`, with its four focus methods all in `search/SearchCriteria.java`,
attributed to `UnknownClass`: `hasAgeFilter`, `hasLocationFilter`,
`hasCohortFilter`, `hasYearFilter`. The normalized summary also reported
`include_tests: false`; whether the supplied test option is accepted at this
location needs checking against the current wire contract.

Expected: matching source class/method context, or a precise unsupported-target
diagnostic. Actual: successful-looking selection of unrelated methods.

4. Retried with exact paths to `security/SecurityConfiguration.java` and
   `api/v1/BookSearchController.java`. Selection
   `97130095-e134-4c9a-8609-099a2f76ed29` correctly returned the search handler
   and `apiSecurityFilterChain`; expansion/detail and literal slices completed.
5. A later broad path-based `impact_analysis` returned `result_status: degraded`,
   `degradation_reason: impact_seed_not_trustworthy`,
   `impact_seed_exact_target_missing`, and empty caller/test arrays. These were
   treated as missing evidence, not as a zero-impact finding.

The installed skill describes a Java medium ceiling; the observed detail packet
reported Java strength/ceiling low after provider degradation. No contradiction
is claimed without establishing whether that is the intended fallback ceiling.

## Scope and follow-up

Capture a stable structured provider failure reason and keep compiler scratch
files outside the consumer worktree. Check fully qualified class-target behavior
in the degraded lane and whether unrelated fallback selections should be
classified more explicitly. Code was not changed and no semidx tests were run;
this is a reproducible observation report only.
