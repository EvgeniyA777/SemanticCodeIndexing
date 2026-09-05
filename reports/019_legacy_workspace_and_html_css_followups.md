---
title: "Legacy Workspace And HTML/CSS Follow-Ups"
doc_type: "followup_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-08-02"
---

# Legacy Workspace And HTML/CSS Follow-Ups

This document carries unresolved findings out of completed progress logs. The
findings are preserved for triage; they are not implementation instructions
until their current relevance and severity are re-verified against the code.

## Workspace Freshness Follow-Ups

Source: `reports/005_stage_0_1_workspace_freshness_progress_log.md`.

1. **M1 - publication is not compare-and-set atomic.** Decide whether to
   implement CAS-before-publish or explicitly scope the concurrency guarantee to
   a single process.
2. **M3 - provider-catalog alignment test is incomplete.** Verify the catalog
   against the actual registered adapters rather than a duplicated expected set.
3. **L1 - staleness policy differs from the original plan.** Either document the
   implemented rule as the accepted behavior or narrow it to the planned scope.
4. **L2 - low-risk cleanup.** Re-check the original cleanliness items and close
   or discard them explicitly.

## HTML/CSS Follow-Ups

Source: `reports/006_html_css_language_lanes_progress_log.md`.

1. **RF1 - generic selector tokens may pollute cross-language call edges.** This
   was preliminary and blocked on verifying `narrow-targets`; confirm current
   behavior before assigning severity or changing token policy.
2. **RF2 - HTML file references may be emitted as unresolved calls.** Verify
   whether `href`/`src`/`action` references should resolve through module/import
   relations or stop contributing call tokens.
3. **RF3 - line-based HTML element symbols may create avoidable index churn.**
   Verify stability requirements and remove the unused attribute parameter if it
   still exists.

## Closure Rule

For each item, record one of: fixed, rejected, superseded, or promoted to a
dedicated implementation plan. When every item has a disposition, mark this
report `completed` and `historical_reference_only`.
