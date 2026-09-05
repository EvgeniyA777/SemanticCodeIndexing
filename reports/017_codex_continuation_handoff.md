---
title: "Codex Continuation Handoff"
doc_type: "handoff"
lifecycle: "completed"
status: "consumed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Codex Continuation Handoff

Repo: `/Users/ae/workspaces/semidx`
Branch: `dev`
Latest pushed commit at handoff update: `646ebb9 feat: expose relation traversal on HTTP and gRPC edges`
Branch note: active work continues on `dev`; `main` is intentionally left one
commit behind (`2fcb5ec`) and is fast-forwarded to `dev` manually by the
maintainer. Continue on `dev`.
Expected worktree state: clean.

## Mandatory Rules

1. Read `RULES.md` first.
2. Read `docs/code-context.md` before first-pass exploration.
3. Use semidx MCP-first for code exploration:
   `create_index -> repo_map -> resolve_context -> expand_context -> fetch_context_detail`.
4. Use manual file reads only after semidx MCP narrowing or if MCP fails.
5. For Clojure structural edits, use `clojure-mcp` tools when connected. If no
   nREPL is running, start it with `clojure -M:nrepl`, then `list_nrepl_ports`.
6. Repository documentation and rule files must be written in English.
7. After each staged implementation change: verify, update docs/progress, commit, then push.
8. Run `git commit` and `git push` sequentially, never in parallel. Stage explicit paths only.

## Current Program

Plan: `plans/013_open_gaps_closure_program.md`
Progress log: `reports/014_open_gaps_closure_program_progress_log.md`
Operational memory: `MEMORY.md`
Relevant ADRs:

- `adr/034-*`: relation-first fork for interprocedural/dataflow work.
- `adr/037-scope-interprocedural-dataflow-v1.md`.
- `adr/038-make-typed-relations-the-canonical-semantic-graph.md`.
- `adr/039-separate-relation-identity-from-resolution-and-evidence.md`.
- `adr/040-expose-bounded-relation-traversal-as-a-public-query-surface.md`.

## Completed

- Stage 3.2: typed relation substrate and empty snapshot indexes.
- Stage 3.3: Clojure `dataflow/*` producer.
- Stage 3.4: Python `dataflow/*` producer.
- Stage 3.5: relation identity/evidence split and validation hardening (`ADR-039`).
- Stage 3.6: pure bounded relation traversal kernel.
- Stage 3.7: relation-backed impact projection (Stage 3 is now code-complete).

Both Clojure and Python emit top-level snapshot relations:

- `dataflow/local-binding-call-result`
- `dataflow/returns-call-result`
- `dataflow/passes-argument`

`semidx.runtime.index` resolves relation `target_key` values to `target_unit_ids`
and marks relations `resolved`, `ambiguous`, or `unresolved`.

Stage 3.5 (`ADR-039`): `relation_id` now derives only from `relation_type`,
`source_unit_id`, `target_key`, and flow payload (`local_name`/`arg_index`)
scoped by `relation_schema_version`; mutable resolution/evidence are excluded so
resolving or enriching a fact keeps one edge. `relation-errors` is the explicit
internal schema; `index-relations` surfaces invalid facts as snapshot
`:relation_diagnostics` instead of dropping them silently.

Stage 3.6: `semidx.runtime.relations/traverse-relations` is a pure,
storage-independent, cycle-safe, deterministic breadth-first walk over
`:relations` / `:relation_forward_index` / `:relation_reverse_index`. Request
keys: `:direction` (`:downstream` source->target / `:upstream` target->source),
`:start_nodes`, `:relation_types` allow-list, `:resolved_only` (default true so
ambiguous/unresolved edges are skipped), and `:max_depth`/`:max_nodes`/`:max_paths`
clamped into `[0, default-traversal-bounds]` (depth 4 / 200 nodes / 50 paths).
It returns `{:direction :start_nodes :relation_types :budgets :nodes :edges
:paths :truncated}` with per-budget truncation flags. No public graph-query API
exists yet; its only consumer is the Stage 3.7 projection.

Stage 3.7: `semidx.runtime.retrieval/build-impact-hints` consumes
`traverse-relations` and attaches an optional, reason-coded `:relation_support`
field to `impact_hints` (shared by `impact_analysis`, detail, and expansion
packets). From the selected units it runs the bounded, `:resolved_only true`
kernel under a conservative local sub-ceiling (`relation-projection-bounds`:
depth 2 / 24 nodes / 12 paths) in both directions: `:downstream` dataflow
dependencies and `:upstream` dataflow dependents, as distinct `path::symbol`
strings excluding the selected units, plus `:reasons` codes
(`relation_downstream_dataflow`, `relation_upstream_dataflow`,
`relation_traversal_truncated`). The field is omitted entirely when no resolved
relation-backed unit is found, so legacy
`:callers`/`:dependents`/`:related_tests`/`:risky_neighbors` outputs stay
byte-identical; ambiguous/unresolved relations are never surfaced. The
`context_packet` and `expansion-result` `impact_hints` contracts carry an
optional `relation_support` object (`{downstream, upstream, reasons}`) in both
the JSON schema and the malli mirror. No public graph-query API was added;
confidence ceilings are unchanged (documented non-bump). Stage 3 is code-complete.

Stage 4 (`plans/013`, gap 7, `ADR-040`) is now fully code-complete across all
four runtime surfaces:

- 4.1: `ADR-040` plus the `relation-traversal-query` / `relation-traversal-result`
  JSON Schema + `malli` mirror and validated examples.
- 4.2: the batched frontier provider seam
  `semidx.runtime.relations/traverse-relations-with` (byte-identical to the pure
  in-memory `traverse-relations`), plus the public library
  `semidx.core/relation-traversal` and MCP `traverse_relations` tool. The result
  carries a `selection_id` (a stored selection over the discovered units) so
  `expand_context` / `fetch_context_detail` deliver code.
- 4.3: the forward-only PostgreSQL `semantic_index_relations` projection
  (`init-storage!` migration + `save-index-tx!` writes; no historical backfill)
  and `storage/pg-relation-neighbor-provider`, proven at parity with the pure
  kernel (with-redefs + a `SEMIDX_TEST_POSTGRES_URL`-gated real-PostgreSQL test).
- 4.4: HTTP (`POST /v1/retrieval/traverse-relations`) and gRPC
  (`TraverseRelations`) exposure of the same contract and kernel.

One graph walk owns the semantics (the Stage 3 kernel); storage only optimizes
neighbor fetch. `usage-operation` gained `traverse_relations`.

## Next Stage

Stage 4 is done. Pick the next frontier from `plans/013` and
`docs/roadmap-status.md`:

- The post-Stage-4 product sequence (its own plan): provider catalog / arbitration
  / discovery (`plans/007` Stage 2-3), additive endpoint/entity references, then
  one Protobuf/OpenAPI contract-linking vertical slice, then a SCIP
  evidence-provider spike over the same relation contract.
- The independent operational stages, order-independent among themselves:
  - Stage 5 — gRPC generated stubs (replace runtime descriptor-built messages
    with generated Java/Kotlin stubs from
    `proto/semidx/runtime/grpc/v1/runtime.proto`). Note: the `.proto` is
    currently a partial artifact (no `service` block; missing `LiteralFileSlice`
    / `SnapshotDiff` / `TraverseRelations` service wiring) — reconciling it is
    part of this stage.
  - Stage 6 — online policy control-plane API.
  - Stage 7 — runtime-edge rate limiting.

Optional residual for Stage 4: an explicit reprojection command for snapshots
saved before the `semantic_index_relations` projection existed (older snapshots
have no projection rows until re-saved).

Before PostgreSQL parity tests: detect a running instance -> stop it cleanly ->
start fresh with the test config -> only then run (`SEMIDX_TEST_POSTGRES_URL`).
Do not touch unrelated local PostgreSQL instances/containers; spin a dedicated
ephemeral cluster on a free port.

## Last Known Verification

For commit `646ebb9` (Stage 4.4):

- `clojure -M:test` passed (`262 tests / 1766 assertions`, 0 failures).
- `./scripts/validate-contracts.sh` passed (`checked_json_files=65`, `contracts_validation=ok`).
- `./scripts/run-mvp-gates.sh` passed with `mvp_gates=ok`.
- `clojure -M:ccc check --root .` passed.
- Real-PostgreSQL relation-traversal parity verified against an ephemeral
  PostgreSQL 17 cluster via `SEMIDX_TEST_POSTGRES_URL` (`storage-test`
  `9 tests / 50 assertions`).
- Benchmarks / semantic-quality were last confirmed unchanged at the Stage 4.2a
  checkpoint (`21/21`; advisory metrics at baseline); Stages 4.2b-4.4 are
  additive surface/storage work with no extraction/ranking/resolution impact.

## Suggested First Prompt

Read `reports/017_codex_continuation_handoff.md`, then continue
`/Users/ae/workspaces/semidx` from the current `dev` branch. Follow `RULES.md`
and use semidx MCP-first. Stage 4 (relation-traversal surface, gap 7) is fully
delivered on library/MCP/HTTP/gRPC plus the PostgreSQL projection. Choose the
next frontier: the post-Stage-4 product sequence (provider catalog/discovery,
then a contract-linking vertical slice) or an operational stage (`plans/013`
Stage 5 gRPC generated stubs, Stage 6 policy control-plane, Stage 7 runtime-edge
rate limiting). Keep the one-kernel/one-contract discipline from `ADR-040` and
run the full delivery loop (implement -> verify -> commit -> review -> docs ->
push) per stage.
