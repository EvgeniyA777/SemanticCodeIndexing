---
title: "create_index Silently Falls Back to a Cached Unrelated Repo on an Unknown Root Param"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-08-12"
---

# create_index Silently Falls Back to a Cached Unrelated Repo on an Unknown Root Param

## Reproduction

Target repository root:

```text
/Users/ae/workspace/UniPlan
```

Call (wrong field name — `root` instead of the documented `root_path`):

```json
{"root": "/Users/ae/workspace/UniPlan"}
```

Response (relevant fields):

```json
{
  "activation_state": "ready",
  "root_path": "/Users/ae/workspaces/semidx",
  "file_count": 182,
  "snapshot_id": "1e64e56c-4623-4dd6-b413-e24fd2cc6c11",
  "index_id": "e5fba928-c4bb-49cd-946e-99548001a3e0",
  "active_languages": ["clojure", "java", "elixir", "python", "typescript", "javascript"],
  "lifecycle_action": "full_rebuild"
}
```

Retried with `{"root": "/Users/ae/workspace/UniPlan", "force_refresh": true}` — same wrong
`root_path`, `lifecycle_action: "reuse"`, no error surfaced either time.

Only after correcting the field name did indexing target the right repo:

```json
{"root_path": "/Users/ae/workspace/UniPlan"}
```

```json
{
  "root_path": "/Users/ae/workspace/UniPlan",
  "file_count": 258,
  "snapshot_id": "c0cc77ff-3532-4baa-a85a-442b7afd6bae",
  "index_id": "3d913855-5a54-4d08-b3dc-404bc2d3cf09",
  "active_languages": ["java", "javascript", "html", "css"],
  "lifecycle_action": "incremental_update"
}
```

## Expected

An unrecognized/misspelled parameter (`root` is not in the documented schema —
`root_path` is) should either be rejected with a validation error, or at minimum
the response should make it obvious that the supplied `root`/`root_path` was
ignored and a different, already-cached repository was returned instead.

## Actual

The call silently succeeded with `activation_state: "ready"` and no error,
warning, or diagnostic field indicating the root was not honored. It quietly
returned the maintainer's own cached `semidx` index instead. The mismatch is
only detectable by manually comparing the requested path against the returned
`root_path` — nothing in the response flags it.

## Context

- Two consecutive calls (initial + `force_refresh: true`) both exhibited the
  same silent fallback; retrying alone does not surface or fix it.
- This is a client-side parameter-name mistake (using an old/guessed field
  name), but the resulting behavior — silently serving an unrelated cached
  repository as if the request succeeded — is exactly the kind of contract
  mismatch worth hardening against, since a caller with no other repo indexed
  yet would have no signal at all that anything was wrong.
- Discovered while assessing test coverage on
  `/Users/ae/workspace/UniPlan` (session date 2026-08-09); wasted one full
  `create_index` + downstream exploration cycle on the wrong repository before
  the mismatch was noticed by cross-checking `root_path` in the response.

## Second occurrence — 2026-08-12, different wrong field name

Still reproducible three days later, with a different misspelling. Adding it
here rather than as a new note, since it is the same defect.

Call (wrong field name `path`, plus a second unknown property `languages`):

```json
{"path": "/Users/ae/workspace/UniPlan"}
```

Response (relevant fields):

```json
{
  "activation_state": "ready",
  "root_path": "/Users/ae/workspaces/semidx",
  "file_count": 182,
  "unit_count": 3170,
  "index_id": "f48dc402-0520-4613-9061-8dafdcbd93b6",
  "snapshot_id": "bb1d32f1-ce26-4afc-a1a7-9ee7e8e8771c",
  "lifecycle_reason": "initial_build",
  "index_lifecycle": {
    "repo_identity": {
      "workspace_path": "/Users/ae/workspaces/semidx",
      "git_branch": "dev",
      "git_commit": "b79855e2a3237ec990142b2c21bd4d5faacdd5e7",
      "identity_source": "git_remote"
    }
  }
}
```

Retried with `{"path": "…", "languages": ["java"]}` — same wrong `root_path`,
plus `lifecycle_reason: "workspace_unchanged"`, `cache_hit: true`,
`reused_snapshot: true`. Both unknown properties (`path`, `languages`) were
swallowed without comment.

Correct call, same session, works exactly as documented:

```json
{"root_path": "/Users/ae/workspace/UniPlan"}
```

```json
{
  "root_path": "/Users/ae/workspace/UniPlan",
  "file_count": 344,
  "unit_count": 10539,
  "active_languages": ["java", "javascript", "html", "css"],
  "index_lifecycle": {"repo_identity": {"git_commit": "010235dfe534b57d9f69023a63e8d862c7384c72"}}
}
```

### New evidence: the MCP schema should already be rejecting this

The tool's own declared JSON Schema for `create_index` is:

```json
{
  "additionalProperties": false,
  "required": ["root_path"],
  "properties": {
    "root_path": {"type": "string"},
    "paths": {"items": {"type": "string"}, "type": "array"},
    "force_rebuild": {"type": "boolean"},
    "language_policy": {"…": "…"},
    "parser_opts": {"type": "object"}
  }
}
```

So `{"path": …}` violates the contract twice over: it omits a **required**
property and supplies an **unknown** one under `additionalProperties: false`.
A conforming MCP server should return a validation error before any indexing
work happens. This narrows the diagnosis from "should warn" to a concrete
schema-enforcement gap: request validation is either not applied or not
enforced for `create_index` arguments. Fixing that one gap closes both
occurrences and every future misspelling at once, without needing a
root-path-mismatch heuristic.

### Impact in this session

Worse than the 2026-08-09 case. Because the response looked healthy and the
index was plainly not the requested repo, semidx was judged **broken for this
workspace** and abandoned for the rest of a long task (Java + HTML + CSS repo,
exactly its supported set), in favour of direct `Read` plus guard-marked
`grep` — the precise fallback the tooling exists to prevent, and it was
reported to the user as a semidx defect. The tool was healthy the whole time:
the corrected call indexed 344 files / 10539 units on the first try. A
validation error would have cost one retry instead of an entire session's
exploration budget.
