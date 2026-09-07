---
title: "Rebuild Reason Whitelist Reports Unrelated Rebuilds As initial_build"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-09-07"
---

# Rebuild Reason Whitelist Reports Unrelated Rebuilds As initial_build

Severity: medium. Nothing is computed wrongly — the rebuild that happens is the
right one — but the reason recorded for it is wrong, and that reason is what
telemetry, the runtime API response, and any operator debugging a slow workspace
actually read.

## Summary

`semidx.runtime.index-lifecycle/coordinate-index-lifecycle` resolves the reason
for a full rebuild through a `cond` that recognises a fixed set of reasons and
sends everything else to `"initial_build"`:

```clojure
resolved-rebuild-reason (cond
                          (= reason "force_rebuild_requested") "force_rebuild_requested"
                          (= reason "pinned_snapshot_missing") "pinned_snapshot_missing"
                          (= reason "snapshot_stale") ...
                          (= reason "authority_model_changed") "authority_model_changed"
                          (:manual_language_selection activation-state) "manual_language_selection"
                          (seq paths) "paths_subset_requested"
                          :else "initial_build")
```

`freshness/decide-freshness` can return reasons the `cond` does not name. Each of
these is reported as `initial_build`:

- `no_prior_manifest`
- `manifest_schema_incompatible`
- `provider_or_pipeline_version_changed`
- `delta_exceeds_threshold`

So a workspace that rebuilds every time because its delta keeps exceeding the
threshold reports the same reason as a workspace being indexed for the first
time, and a rebuild forced by a provider-registry version bump is
indistinguishable from a cold start.

## Impact

The lifecycle reason is not internal: it reaches `index_lifecycle.rebuild_reason`
on the create-index response and on the usage event. Anyone asking "why is this
workspace rebuilding instead of reusing" is answered `initial_build`, which
points the investigation at storage or snapshot loading rather than at the delta
ratio or a version bump that is the real cause.

## Discovery

Found on 2026-09-07 while delivering `plans/018` Stage 6.3. The new
`authority_model_changed` reason was reported as `initial_build` until it was
added to the `cond`; the four reasons above were already being swallowed the
same way and were left alone rather than fixed opportunistically, since they are
outside that stage's scope.

## Suggested fix

Pass the freshness reason through by default and reserve `"initial_build"` for
the case that is actually an initial build (no prior snapshot). The `cond` then
only needs to hold the reasons that are genuinely *derived* rather than
forwarded — `snapshot_stale` splitting into `max_age_stale` and
`staleness_rule_stale`, and the activation/paths cases that do not come from
`decide-freshness` at all.

A regression test should assert one forwarded reason end to end — for example a
`delta_exceeds_threshold` rebuild reporting that reason on the response — because
the current tests assert the rebuild action and never the reason attached to it.
