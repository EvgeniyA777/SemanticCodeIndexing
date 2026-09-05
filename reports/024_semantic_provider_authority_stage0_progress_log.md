---
title: "Semantic Provider Authority Migration — Stage 0 Progress Log"
doc_type: "progress_log"
lifecycle: "active"
status: "in_progress"
agent_action: "reference_for_context"
updated: "2026-09-05"
---

# Progress Log: Semantic Provider Authority Migration (plans/018)

Companion log for
[`plans/018_semantic_provider_authority_migration_plan.md`](../plans/018_semantic_provider_authority_migration_plan.md).
This log opens with **Stage 0 — Independent Review And Compatibility Baseline**
and is appended by later stages.

## Execution Record

- **Executor**: Claude Code v2.1.212; lead reviewer with an independent review
  pass. Fable and the repository-prohibited Explore agent were **not** used. No
  sub-agents were spawned.
- **Model actually used**: **Claude Opus 4.8 Thinking, high effort.** This
  updates the plan's Stage 0 routing default (Claude Opus 4.6) with the owner's
  explicit authorization.
- **High-effort justification**: Stage 0 locks the provider-neutral
  `CanonicalFactKey` identity assumptions and challenges the migration before any
  source work. The plan's own routing table rates this row `high` because
  canonical identity is correctness-critical, and the plan explicitly allows a
  high-effort override without a separate exception when identity/arbitration
  risk justifies it. The review surfaced a High-severity identity finding (F1),
  confirming the risk was real.
- **Bootstrap performed**: read `RULES.md` in full; confirmed CCC artifacts
  (`docs/code-context.md`, `.ccc/state.edn`) exist and read the code-context
  layer; followed the MCP-first workflow (`create_index` → `repo_map` →
  `resolve_context` → `fetch_context_detail`) for all code discovery.

## Stage Status

| Stage | Status |
| --- | --- |
| 0 — Independent review and compatibility baseline | **completed** |
| 1 — Evidence model and arbitration kernel | **completed** 2026-08-28 — kernel, FactBatch, executable golden parity, and round-trip coverage all delivered |
| 2 — Provider plan and legacy adapter shadow path | **completed** 2026-08-28 |
| 3 — TypeScript SCIP vertical slice | **completed** 2026-09-01 — preflight, JVM SCIP reader, SCIP→CanonicalFactKey normalization, the shadow/default-off provider adapter (`scip-typescript`) with per-document stale gate, and the SCIP-vs-Stage-2 shadow comparison harness (`scip-shadow-compare`) with latency/size metrics all delivered. Named deferrals: SCIP `Relationship`/implementations and `call/*` facts (none in corpus / no relation type), and catalog/planner integration of a project-scoped provider. |
| 4 — Java SCIP vertical slice | **completed** 2026-09-05 (review findings S3/S4 fixed same day) — preflight (fixture corrected, Variant C invalidated for Java, same-arity defect reproduced), repo-managed external toolchain, language bridges in `scip-normalize`, the `scip-java` adapter, and the same-arity overload guard. Named deferrals: nested types, `Relationship`/implementations, and catalog/planner integration of a project-scoped provider. |
| 5–7 | not started |

## Scope Executed (Stage 0)

Documentation, baseline, and test fixtures only. **No production
authority/default extraction was changed.** Confirmed: `src/semidx/runtime/`
source is byte-identical to the pre-Stage-0 tree; only new artifacts under
`fixtures/provider-authority/` and this report were added.

### Deliverables produced

1. Independent review of ADR-046, plan 018, ADR-047, ADR-036 supersession, and
   the plans/007 amendment/closure criteria (below).
2. Protected Java + TypeScript corpus:
   `fixtures/provider-authority/corpus/` (definitions, references, calls,
   overloads, re-exports).
3. Cross-provider `CanonicalFactKey` identity fixtures:
   `fixtures/provider-authority/identity/` (Java overload; TypeScript re-export)
   — regex/tree-sitter/SCIP/LSP spellings → one key; distinct overloads/aliases
   stay distinct.
4. Protected extraction baseline (ground truth) and degradation/freshness
   expectations: `fixtures/provider-authority/behavior/`.
5. Deterministic retrieval/callers-callees/impact/confidence/snapshot baseline:
   `fixtures/provider-authority/baseline/`.

`reports/022_latest_active_plans_architecture_review.md` was read **as input
only**; it is untracked and was not modified, staged, or claimed.

## Independent Review

Posture per the plan's brief: ADR-046 is the accepted decision of record and
plan 018 the approved direction. The review challenges implementation
readiness, hidden assumptions, and unsafe sequencing rather than reopening the
authority decision. Findings are ordered by severity below; the 11 required
review questions are answered after them.

### Findings

| ID | Severity | Finding | Resolution |
| --- | --- | --- | --- |
| F1 | **High** | `CanonicalFactKey.overload_identity.signature_key` is underspecified and **not producible by the heuristic/structural tiers as written**. The plan's example uses fully-qualified, type-only `java.lang.String,int`. The current extractor (`java.clj` `java-normalized-params`, l.237–242; `java-method-unit-id`, l.254–262) emits **simple, parameter-name-inclusive** text: `handle(String order)` → `"Stringorder"` (ground truth via REPL). Regex/tree-sitter cannot resolve `String`→`java.lang.String` without import/classpath resolution, and they include the param name. SCIP/LSP emit `(java.lang.String)` type-only. Without a canonical signature form, the same overload gets **different keys per provider**, violating the Stage 1 exit criterion "same semantic fact from multiple providers produces one canonical fact key." | **Resolved — Variant C adopted** (owner decision, 2026-08-03). Residual same-arity ordinal rule tracked as **F1a**. See "F1 Resolution — Variant C" below. |
| F1a | Medium | Same-arity overloads (e.g. `handle(String)` vs `handle(int)`, both arity 1) cannot be individually attributed by the heuristic tier under Variant C, because the arity-only core key collides. A provider-neutral disambiguator (source-order ordinal) is deterministic but does not match SCIP's type-based identity. | **Open sub-decision** — Stage 1 must pick the same-arity arbitration rule (explicit ambiguity vs ordinal alignment); tracked below. |
| F2 | Medium | Source-identity granularity is unspecified: the plan requires a per-document digest or revision+verification, but not whether staleness invalidates a whole document's exact facts or only affected ranges. For overloads, a coarse per-document digest may over-invalidate (one edited method drops all SCIP facts) or, if mis-scoped, under-invalidate. | **Deferred to Stage 3 design** (SCIP slice); recorded as a named design question. |
| F3 | Medium | Stage 0 baseline captures dirty-file *expectations* but not an executed **incremental / `update-index` re-index** baseline over the corpus. The plan's verification gate names "incremental-index regressions," so a captured incremental baseline strengthens the Stage 1 kernel. | **Accepted gap** — add an incremental re-index baseline in Stage 1; dirty-file expectations already recorded in `behavior/degradation-expectations.json`. |
| F4 | Medium | Today both regex **and** tree-sitter emit `parser_mode: "full"` (ground truth). Structural (tree-sitter) and heuristic (regex) tiers are **output-indistinguishable**. ADR-046's authority ladder requires them separated. | **Named intentional future difference** — Stage 2 owns the structural/heuristic split; recorded so it is not mistaken for a regression. |
| F5 | Low | Residual code-vs-ADR-046 gap: regex facts still project `parser_mode: "full"` (not `heuristic`/`fallback`). This is expected — Stage 6 owns the switch — and there is **no active conflicting document-level rule** (see Q9). | **Resolved / no action.** Confirmed no conflicting active parser-authority rule remains across ADR-036/046/047 and plans/007. |
| F6 | Low (advisory) | Stages 1–2 introduce five new namespaces (catalog, planning, execution, arbitrator, freshness) before the first external provider. Consider collapsing catalog+planning until a second real provider exists (echoing plans/007 Decision 10 "data first, protocol later"). | **Deferred (advisory)** — the plan's data-first-registry defense is acceptable; not blocking. |

Supporting evidence for F1 (ground truth, current extractor, default opts):

- `example.OrderService#handle(String order)` → `method_signature_key
  "Stringorder"`, `unit_id … $arity1$sig5e75e42b`.
- `example.OrderService#handle(String order, int retries)` → `"Stringorder,intretries"`,
  `… $arity2$sig5902c043` (overloads correctly distinct).
- `example.Validator#validate(String order)` → `"Stringorder"`, `sig5e75e42b`
  — **byte-identical signature hash to `handle(String)`**. This confirms a
  hash-only merge key would be unsafe; the plan already mandates
  owner+symbol+path in the key, so the design is sound on this point and the
  fixture locks it in.

### F1 Resolution — Variant C (precision-aware overload identity)

Owner decision (2026-08-03): adopt **Variant C**. The `signature_key` stops
being one fixed string that every provider must reproduce. Instead
`overload_identity` becomes a precision-tagged value, and each tier commits only
to what it can honestly know.

```clojure
;; heuristic tier (regex / tree-sitter): knows arity only
{:arity 1 :signature_precision "arity_only" :signature_key nil}

;; exact tier (SCIP / LSP): knows precise parameter types
{:arity 1 :signature_precision "typed" :signature_key "java.lang.String"}
```

The canonical key splits into two layers:

- **core key** = `(language, path, fact_kind, owner, symbol, arity)` — every
  provider can produce it;
- **refinement** = the typed parameter signature — added only by the exact tier.

**Merge semantics.** Facts are grouped by core key.

1. *Common case (distinct arities, or a single method per arity — the whole
   protected corpus).* `handle(String)` = arity 1 and `handle(String, int)` =
   arity 2 have unique core keys. The regex arity-only fact and the SCIP typed
   fact land in the same group and merge trivially; no guessing occurs.
2. *Hard case (same-arity overloads, e.g. `handle(String)` vs `handle(int)`,
   both arity 1).* The core key collides. The exact tier still separates them by
   type; the heuristic tier cannot attribute its two arity-only facts to a
   specific typed overload. Arbitration must then apply an explicit rule (F1a):
   surface `arity_ambiguous` heuristic coverage rather than silently pick, or
   align by deterministic source-order ordinal. Variant C **isolates** this case
   and makes it explicit; it does not pretend a signature hash solved it.

**Why C over A/B.** Variant A (simple types, drop names) forces the exact tier
to *down-convert* `java.lang.String` → `String`, which itself collides
(`java.util.List` vs a local `List` → `List`), i.e. it degrades good data to
match weak data. Variant B (FQ + param names) cannot match SCIP/LSP monikers
(type-only, no names) and is unstable under parameter rename; FQ resolution at
the regex tier is heuristic (wildcard imports, implicit `java.lang`, type vars)
and would produce *wrong* keys, which the plan forbids ("reject/degrade when it
cannot produce a canonical key without guessing"). C keeps the exact tier at
full precision and lets the heuristic tier commit only to arity.

**Stage 1 consequence — unit identity re-anchoring.** Today `unit_id` embeds a
typed signature hash (`…$arity1$sig5e75e42b`). Under C a regex-only repository
has no typed hash, and later attaching SCIP must not change existing `unit_id`s
(that would break snapshots, retrieval refs, and relations). Therefore Stage 1
must anchor stable unit identity on the **core key** `(owner, symbol, arity[,
same-arity ordinal])` and treat the typed signature as **refining evidence**,
not as part of the primary id. This is cleaner than today's hash-in-id but is a
deliberate identity-schema change that the Stage 1 in-memory/PostgreSQL
round-trip gates must cover.

### Answers To The 11 Required Review Questions

1. **Per-operation authority vs whole-file?** Necessary. LSP can be exact for
   definitions/references yet lack a complete batch call hierarchy; SCIP can be
   exact for references yet miss a live-overlay operation. A single-winner
   whole-file model (plans/007 Decision 9 V1) cannot express "SCIP exact for
   refs + tree-sitter for call structure." Agree with the plan.
2. **SCIP and LSP as freshness/mode-dependent peers?** Yes; ADR-046's
   arbitration section models this correctly (batch-clean prefers SCIP; live/dirty
   matching version prefers LSP; agreement merges; equal+contradictory ⇒
   ambiguity). Agree.
3. **Is the source-identity rule sufficient to prevent stale exact facts?**
   Directionally yes, but granularity is unspecified — see F2.
4. **Does `CanonicalFactKey` normalize across spellings without collapsing
   distinct overloads/re-exports/dispatch?** Structurally yes for
   owner+symbol+path+arity; **not yet** for `signature_key` — see F1. Distinct
   overloads and the alias/origin re-export split are proven distinct by the
   identity fixtures.
5. **Does `FactEvidence` need a public contract now?** No. Keep it internal;
   `parser_mode` remains the compatibility projection through the shadow stages
   (ADR-024 additive discipline). Agree with the plan.
6. **Does LSP scope avoid a lifecycle manager / nondeterministic batch
   indexer?** Yes. Stage 5 + the High risk fix LSP as a source-validated live
   overlay, SCIP as the reproducible batch source, and "semidx does not
   implement an LSP server." Cancellation/timeout determinism is already a Stage
   5 deliverable.
7. **Are the Stage 0–6 gates sufficient?** Nearly; add an incremental re-index
   baseline (F3). Otherwise retrieval/confidence/storage/relation gates are
   present.
8. **TypeScript first, or reverse to Java?** Keep **TypeScript first**. Both
   lanes are regex-default today; TypeScript's confidence ceiling is lower
   (per-language ceiling: TS `low`, Java `medium`) and its regex brittleness is
   the most observable. Stage 0 toolchain evidence does **not** justify
   reversing. Requires explicit owner confirmation (owner decision #2).
9. **ADR-036 supersession / ADR-047 / plans/007 amendment — no conflicting
   rule?** Verified. ADR-036 is `superseded_by: [ADR-046, ADR-047]` with a clear
   supersession note; its regex-default text is historical only. ADR-046 amends
   plans/007 Decision 9, and plans/007 already reflects the bounded
   multi-provider plan (l.335–349, 73–75, 793–797). ADR-047 retains only the
   tree-sitter toolchain boundary. **No active document-level parser-authority
   rule conflicts.** The remaining gap is code-vs-target (F5), owned by Stage 6.
10. **Simpler design with less machinery?** The per-operation plan is close to
    minimal given exact-authority + deterministic fallback + explicit
    degradation. Only advisory simplification: F6 (defer catalog/planning split).
11. **Do provider ids / native payloads leak into plans/019 one-shot /
    rendering?** Not by design — the plan forbids branching on provider ids in
    the one-shot orchestrator/renderer and keeps provider-native detail out of
    the public retrieval contract. No implementation exists yet; this is a
    guardrail to enforce at the Stage 6 / plans-019 integration.

### Implementation-Readiness Recommendation

**Proceed after named revisions.** Specifically, Stage 1 must not begin until
owner decision #1 (canonical `signature_key` form, F1) and owner decision #2
(confirm TypeScript-first) are recorded, and the `CanonicalFactKey` contract +
identity fixtures are approved. Until then Stage 1 is **deferred**.

### Decisions Requiring Owner Approval

1. **Canonical `signature_key` form for Java overloads (F1).**
   **[RESOLVED 2026-08-03 → Variant C.]** Precision-aware `overload_identity`:
   heuristic tier commits `arity_only`, exact tier adds the `typed` signature;
   unit identity re-anchors on the core key. Residual **F1a** (same-arity
   arbitration rule) is delegated to Stage 1 design, not the owner.
2. **Confirm TypeScript as the first SCIP vertical slice** (plan Execution
   Admission; reports/022 gate).
3. **Approve the `CanonicalFactKey` normalization contract and the Stage 0
   identity fixtures** as the admission baseline.
4. **Accept the intentional `parser_mode`/confidence reduction** for regex-only
   repositories (F3/F4) as a planned Stage 6 change, subject to replay evidence.

## Verification

Stage 0 changed no production source (documentation, baseline, and test
fixtures only), so verification targets fixture integrity and the repo gate:

- **JSON validity** of all five new fixtures under
  `fixtures/provider-authority/`: **pass** (`python3 -m json.tool`).
- **`clojure -M:test`**: **289 tests, 1947 assertions, 0 failures, 0 errors** —
  unchanged from the reports/022 baseline; the new fixtures are not auto-consumed
  by any failing test.
- **`./scripts/validate-contracts.sh`**: **ok** (70 JSON files checked); no
  contract schema/example was touched.
- **`git diff --check`**: clean (no whitespace errors).
- **English-only scan** over the new/edited files: no Cyrillic.
- **Ground-truth capture** used the project nREPL (`clojure -M:nrepl`) driving
  `semidx.runtime.languages.{java,typescript}/parse-file`, and the semidx MCP
  retrieval/impact surfaces on a corpus index. All asserted identity/baseline
  values in the fixtures are captured from real runtime output, not guessed.
- **Not run (not applicable)**: full migration gate (semantic-quality report,
  protected retrieval replay, snapshot-diff parity, PostgreSQL round trips) —
  these gate later stages that change extraction; Stage 0 changes none.
- **Limitation**: SCIP/LSP provider spellings in the identity fixtures are
  representative, not verified against installed SCIP/LSP tools; Stage 3/5 must
  re-verify. Latency and snapshot size are recorded as observational, not
  deterministic gates.

## NextStageRoutingRecommendation

```text
completed_stage: 0 — Independent Review And Compatibility Baseline
recommended_next_stage: DEFER Stage 1 (Evidence Model And Arbitration Kernel)
recommended_executor: Claude Code team lead (Claude Code v2.1.212+), no Fable, no Explore agent
recommended_model: Claude Opus 4.8 Thinking (plan default Opus 4.6 is superseded by owner authorization)
effort: high
effort_justification: CanonicalFactKey normalization, same-key merge, ambiguity,
  and in-memory/PostgreSQL storage compatibility are correctness-critical
  contracts; Stage 0 surfaced a High-severity identity gap (F1) that Stage 1
  must encode carefully. Matches the plan's Stage 1 routing row.
rationale: Stage 0 is complete and deterministic. F1 (signature_key canonical
  form) is now RESOLVED as Variant C, but TypeScript-first is not yet
  owner-confirmed and the identity contract is not yet owner-approved. Starting
  Stage 1 before owner decision #2 would risk building the SCIP seam in the
  wrong lane.
prerequisites_or_blockers:
  - Owner decision #1: canonical signature_key form — RESOLVED 2026-08-03 (Variant C).
  - Owner decision #2: confirm TypeScript as first SCIP vertical slice (OPEN).
  - Owner approval of the CanonicalFactKey (Variant C) contract + identity fixtures (OPEN).
  - Stage 1 must encode: precision-aware overload_identity, core-key unit
    identity re-anchoring, and the F1a same-arity arbitration rule.
  - ADR-046 and ADR-047 accepted (DONE).
  - ADR-036 supersession + plans/007 amendment documented (DONE; verified, no conflict).
file_ownership_and_conflict_risk: LOW. Stage 0 touched only
  fixtures/provider-authority/** and reports/024. It did NOT touch SPEC.md,
  MEMORY.md, plans/020, reports/021, or reports/023 (plans/020 Stage 0 owned
  files), so there is no cross-plan file conflict.
fallback_executor_or_model: Claude Sonnet 5 for mechanical fixture upkeep only;
  NOT for the signature_key/arbitration identity decisions.
model_availability_checked_at: 2026-08-03
confidence: high (that Stage 1 must be deferred pending the two owner decisions)
```

Stage 0 recommended deferring Stage 1. The owner subsequently lifted the gate
(2026-08-03): TypeScript is confirmed as the first vertical slice and Variant C
+ the identity fixtures are approved as the admission baseline. Stage 1 then
started; its record follows.

---

# Stage 1 — Evidence Model And Arbitration Kernel

## Execution Record

- **Executor**: Claude Code team lead (v2.1.212), no Fable, no Explore agent.
- **Model**: Claude Opus 4.8 Thinking, high effort (matches the plan's Stage 1
  routing row: `CanonicalFactKey`, ambiguity, and identity are
  correctness-critical). Development was REPL-driven via the project nREPL.
- **Admission**: owner confirmed TypeScript-first and approved the Variant C
  `CanonicalFactKey` contract + Stage 0 identity fixtures.

## Scope

Additive and default-off. Commit boundary per the plan: **evidence contracts
and pure arbitration only.** No default extraction, storage schema, or transport
changed.

## Deliverables

New pure namespace `src/semidx/runtime/fact_arbitration.clj`:

- `fact-schema-version`, the ADR-046 authority ladder
  (`exact > structural > heuristic > fallback`), freshness vocabulary, and the
  fact-kind / signature-precision vocabularies.
- Provider-neutral **CanonicalFactKey** with the Variant C precision-aware
  overload identity. Unit identity anchors on the core key
  `(language, path, owner, symbol, dispatch_identity, arity, ordinal)`; the typed
  signature is a refinement, not part of the core key. Relation keys mirror
  ADR-039 / `semidx.runtime.relations` identity fields. Identity is a
  content-addressed hash (`canonical-fact-key-id`) using the same
  `canonical-value` + SHA-1 discipline as `relations.clj`.
- **FactEvidence** normalization + validation. Provider-native ids/symbols are
  retained as evidence only, never in the key. ADR-046 rule enforced:
  `exact` authority requires fresh source identity (`freshness=exact`), else a
  structured `:exact-without-fresh-identity` error.
- **Deterministic same-key arbitration** (`arbitrate-facts`): strongest
  authority wins; all evidence retained; lower authority never overwrites
  higher.
- **F1a same-arity rule** implemented: `<=1` distinct typed signature in a core
  group merges to one fact whose `fact_identity` equals the core key id (so a
  regex-only unit keeps its identity when SCIP/LSP attach later — the Variant C
  invariant); `>=2` distinct typed signatures split into distinct canonical
  facts, and any unattributable arity-only evidence surfaces an
  `:arity_ambiguous_heuristic` diagnostic instead of being silently merged.

## Verification

- **`clojure -M:test`**: **302 tests, 1997 assertions, 0 failures, 0 errors**
  (was 289/1947 at Stage 0; +13 tests in
  `test/semidx/runtime/fact_arbitration_test.clj`).
- **REPL** (`clojure -M:nrepl`) confirmed, before the suite run: 4-provider
  merge → one exact typed fact with all evidence retained; distinct arities and
  distinct owners never collide; same-arity typed overloads split; arity-only
  evidence under same-arity overloads yields the F1a diagnostic; the Variant C
  identity-stability invariant holds; arbitration is order-independent across
  reverse + 20+ shuffles; `exact+stale` evidence is rejected.
- **`git diff --check`**: clean. **English-only**: no Cyrillic.
- **Existing snapshots remain readable**: no storage schema changed; the full
  suite including `semidx.runtime.storage-test` is green.

## Deferred (named honestly)

- **FactEvidence → snapshot payload + PostgreSQL projection round-trip.** The
  kernel is pure and its output is plain serializable data, but nothing emits
  persisted FactEvidence yet. Wiring evidence into the snapshot payload belongs
  with the Stage 2 provider seam (when a provider first produces facts); adding
  an unused persistence path now would be speculative. Existing snapshot
  readability is already preserved (no schema change).
- **`FactBatch` framing** beyond "a collection of facts" — the per-provider
  batch envelope (source identity, coverage, diagnostics) lands with the Stage 2
  provider execution orchestrator that produces batches.

## NextStageRoutingRecommendation

```text
completed_stage: 1 — Evidence Model And Arbitration Kernel (pure kernel + tests)
recommended_next_stage: Stage 2 — Provider Plan And Legacy Adapter Shadow Path
recommended_executor: Claude Code team lead (v2.1.212+), no Fable, no Explore agent
recommended_model: Claude Sonnet 5 (plan default Sonnet 4.6, updated), with the
  FactEvidence-persistence sub-task escalated to Opus 4.8 if identity/round-trip
  parity proves tricky
effort: medium
effort_justification: Stage 2 is a bounded implementation behind a default-off
  seam (data-first catalog, ProviderPlan, execution orchestrator, tree-sitter +
  regex adapters wrapping the existing parse-file facade). It reuses the Stage 1
  kernel rather than designing new correctness-critical identity contracts. The
  one careful sub-task — persisting FactEvidence in the snapshot payload with
  in-memory + PostgreSQL round-trip parity — may warrant a high-effort spike.
rationale: The pure kernel and identity contract are proven and tested. Stage 2
  wires providers through the seam in shadow mode without changing default
  output, which is lower correctness risk than Stage 1.
prerequisites_or_blockers:
  - Stage 1 kernel merged (this commit).
  - Stage 2 must keep default output byte-identical (shadow only) and classify
    regex evidence as heuristic, never exact (ADR-046).
  - Carry forward the FactEvidence→snapshot persistence + PG round-trip
    deliverable listed under "Deferred" above.
file_ownership_and_conflict_risk: LOW-MEDIUM. Stage 1 touched only
  src/semidx/runtime/fact_arbitration.clj and its test. Stage 2 will touch the
  provider seam and the file-indexing path (index.clj / adapters.clj); no
  overlap with plans/020-owned files.
fallback_executor_or_model: Claude Opus 4.8 for the persistence/identity
  round-trip sub-task; Sonnet 5 for the bounded orchestrator/adapters.
model_availability_checked_at: 2026-08-03
confidence: high (kernel is solid; Stage 2 is a bounded seam over it)
```

---

# Stage 1 Completion (2026-08-28)

## Why this section exists

Stage 1 was recorded as delivered on 2026-08-03, but three of the plan's own
Stage 1 deliverables were not met. This session closed them rather than carrying
them into Stage 2 as the earlier handoff proposed.

| Plan deliverable | State on 2026-08-03 | Now |
| --- | --- | --- |
| "Additive FactEvidence and **FactBatch** normalization" | FactEvidence only; FactBatch deferred to Stage 2 | delivered |
| "**In-memory and PostgreSQL round-trip coverage**" | deferred to Stage 2 | delivered |
| Exit criterion: cross-provider golden parity for Java overloads and TypeScript re-exports | fixtures existed but no test read them; the test data was a hand copy | fixtures are executed as goldens |
| "Property-style tests proving registration and completion order do not change merged output" | delivered (`arbitration-is-order-independent`) | unchanged |
| "Additive multi-source relation evidence compatible with ADR-039" | delivered | extended with the fixture's re-export relation |

The parity gap was the sharpest of the three: `fact_arbitration_test.clj`
mirrored the Stage 0 identity fixtures as inline literals, so a correction to a
fixture could not fail a test. Parity that cannot be executed is not parity, and
the plan gates the first external provider slice on it.

## Delivered

- **FactBatch** in `src/semidx/runtime/fact_arbitration.clj`:
  `normalize-fact-batch`, `fact-batch-errors`, and `arbitrate-batches`. A batch
  is the provenance envelope around one provider run — provider identity, source
  identity, freshness, coverage, diagnostics — and fills in a fact's provenance
  only where the fact left it unset, so an envelope can never restate a fact's
  authority. Batch errors name the offending fact and evidence index; an invalid
  batch contributes no facts but stays visible in the result, so a provider that
  failed is distinguishable from one that legitimately found nothing.
- **Executable golden parity**: the tests now read
  `fixtures/provider-authority/identity/*.json` and assert the kernel against
  them — one merged fact per provider set, core key equal field by field, the
  exact tier's typed refinement winning without changing identity, every
  provider's evidence retained, and each fixture's distinct facts keeping
  distinct keys. The TypeScript fixture is checked in its own shape: every
  provider's native re-export moniker resolves to the one origin key, the
  re-export relation keys on ADR-039 identity, and an alias export never
  collapses into the origin unit.
- **Round-trip coverage**: EDN round trip of the arbitration output, a JSON round
  trip proving serialization does not change what facts identify, and a
  PostgreSQL `jsonb` round trip gated on `SEMIDX_TEST_POSTGRES_URL`.

## Fixture change (additive, claims unchanged)

The `distinct_facts_must_not_merge` entries in both identity fixtures carried
their claim in prose only (`fact`, `reason`), with no structured owner, symbol,
or path, so they could not be executed. Each entry gained an
`expected_canonical_key` that restates the same claim in machine-readable form,
and both fixtures record the change in their `notes`. No claim was altered and
no expectation was weakened. Their `status` moved from
`specification_for_stage_1` to `golden_executed_by_stage_1_tests`.

## Verification

- `clojure -M:test`: **456 tests, 2636 assertions, 0 failures, 0 errors**
  (was 447 / 2579 before this session's Stage 1 work; the arbitration namespace
  went from 15 to 22 tests).
- `clojure -M:test` with `SEMIDX_TEST_POSTGRES_URL` against a throwaway
  PostgreSQL 17.7 cluster: 456 tests, 2652 assertions, 1 failure — the
  pre-existing `semidx.integration.runtime-test/postgres-storage-roundtrip-test`
  documented in `reports/021`, which reproduces on a clean tree and is unrelated
  to this work. The new fact round-trip test passed: identities, authorities,
  and evidence counts survive `jsonb` unchanged.
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files.
- Both identity fixtures re-validated as JSON. `git diff --check`: clean.
  English-only scan: no Cyrillic.

## Exit criteria check (plan Stage 1)

- Same semantic fact from multiple providers produces one canonical key and one
  identity; provider-native ids remain evidence — **checked against the
  fixtures**, not only against inline test data.
- Java overloads, TypeScript re-exports, and dispatch-sensitive identities have
  cross-provider golden parity before any external provider slice — **met**.
- Lower authority cannot overwrite higher authority — covered.
- Equal-authority contradictions are observable — covered by the F1a
  `:arity_ambiguous_heuristic` diagnostic.
- Existing snapshots remain readable — no storage schema changed; the full suite
  including the storage tests is green.

Commit boundary respected: evidence contracts and pure arbitration only. No
default extraction, storage schema, or transport changed.

## NextStageRoutingRecommendation

```text
completed_stage: 1 — Evidence Model And Arbitration Kernel (now complete against
  the plan's own deliverables, including FactBatch and round-trip coverage)
recommended_next_stage: Stage 2 — Provider Plan And Legacy Adapter Shadow Path
recommended_executor: Claude Code team lead, no Fable, no Explore agent
recommended_model: Claude Sonnet 5, escalating to Opus 4.8 for the snapshot
  persistence sub-task if identity parity proves tricky
effort: medium
effort_justification: Stage 2 is bounded implementation behind a default-off
  seam (data-first catalog, ProviderPlan, execution orchestrator, tree-sitter and
  regex adapters wrapping the existing parse-file facade). It consumes the Stage 1
  kernel instead of designing new identity contracts. The care is in keeping
  default output byte-identical and classifying regex evidence as heuristic.
rationale: The identity contract is now proven against the committed goldens and
  survives serialization, so the seam can be wired without the risk that
  persistence or provider spelling changes what a fact identifies.
prerequisites_or_blockers:
  - Stage 2 must keep default output byte-identical (shadow only) and never
    classify regex evidence as exact (ADR-046).
  - The FactEvidence-in-snapshot-payload wiring is now unblocked: the kernel's
    output is proven round-trippable through jsonb, so the remaining work is the
    payload shape, not the identity question.
  - Stage 2 owns FactBatch production; the normalization and validation it must
    satisfy already exist here.
file_ownership_and_conflict_risk: MEDIUM. Stage 2 touches the provider seam and
  the file-indexing path (index.clj / adapters.clj). No overlap with the paused
  plans/020 files.
fallback_executor_or_model: Sonnet 5 for the orchestrator and adapters; not for
  the shadow-parity gate.
model_availability_checked_at: not checked in this session.
confidence: high
```

## Review Repair (2026-08-28)

External review of the Stage 1 completion found one High finding, reproduced and
fixed here before Stage 2 begins.

**High — exact authority could be claimed without source identity.**
`fact-evidence-errors` checked only that `freshness` said `exact`, so
`{:provider_id "scip" :authority "exact" :freshness "exact"}` validated with no
errors. ADR-046 is explicit that this is not enough: "a provider whose source
identity cannot be tied to the current content is stale and is excluded from
exact authority", and it names the acceptable evidence as a per-document content
digest, a matching LSP document version, or a revision-bound artifact, adding
that provider health alone is not acceptable. Freshness is the provider's own
claim; the anchor is what makes the claim checkable. Left unfixed, the Stage 2
provider seam could have admitted unanchored SCIP or LSP facts at exact
authority.

Fix: `anchored-source-identity?` plus a new `:exact-without-source-identity`
error. Exact authority now requires `source_identity` to carry
`content_digest`, `document_version`, or `revision`; lower authorities are
unaffected. The two requirements are reported independently, so a stale and
unanchored fact names both failures.

Test data was corrected in the same pass rather than worked around: the inline
exact-authority fixtures and the fixture-driven goldens now carry an anchor,
because unanchored exact evidence is not something a provider may legitimately
produce and test data should not model it. New coverage asserts each anchor
ADR-046 names is accepted, that a provider's freshness claim alone is rejected,
that `{:provider_healthy true}` is rejected, and that lower authority still needs
no anchor.

Verification: `clojure -M:test` — 456 tests, 2642 assertions, 0 failures
(the arbitration namespace went from 107 to 113 assertions).

---

# Stage 2 — Provider Plan And Legacy Adapter Shadow Path (2026-08-28)

## Scope

Additive and default-off. The seam runs beside default extraction and writes
nothing into a snapshot.

## Delivered

| Namespace | Role |
| --- | --- |
| `src/semidx/runtime/providers.clj` | data-first catalog: versioned descriptors, runtime status probes, source-identity digest, and the role functions that turn one provider's parse into facts |
| `src/semidx/runtime/provider_selection.clj` | planning policy: deterministic, bounded `ProviderPlan` per file and operation, with every exclusion recorded |
| `src/semidx/runtime/provider_execution.clj` | orchestrator: bounded concurrency, per-provider timeout, failure isolation, gap tracking, `FactBatch` emission, and the end-to-end shadow entry point |
| `test/semidx/runtime/{providers,provider_selection,provider_execution}_test.clj` | 24 tests, 101 assertions |

## Decisions

- **Descriptors are data; roles are a separate map.** The plan asks the catalog
  to own "status, freshness, and execution functions". Descriptors stay plain
  serializable data so the catalog can be diffed and persisted, and executable
  roles live beside them keyed by `provider_id`. A test asserts the descriptors
  survive `read-string . pr-str`.
- **Stage 2 claims `definitions` only.** The first draft of the catalog also
  claimed `document_symbols` and `call_hierarchy`, which these adapters do not
  produce: every run would have reported two permanent gaps for operations
  nothing implements. Capability claims now match what the runner emits, and the
  richer profile belongs to the providers that actually implement it.
- **Variant C is produced, not restated.** The Java adapters emit
  `signature_precision: arity_only` with a `nil` signature key, taking arity from
  the parser's own `method_arity`; TypeScript units expose no arity and so carry
  no overload identity. Both match the Stage 0 identity fixtures exactly, which
  was verified against real parser output before the adapters were written rather
  than assumed.
- **Evidence is anchored.** Each provider run digests the content it parsed
  (`sha256:...`) and attaches it as `source_identity`, so Stage 2 evidence
  satisfies the ADR-046 anchor rule added in the review repair. Freshness is not
  authority: these providers stay structural and heuristic regardless.
- **Failure is a batch, not an exception.** A provider that throws or hangs
  yields a batch with no facts, `coverage.complete false`, and a
  `:provider_failed` / `:provider_timeout` diagnostic. Gap tracking then
  distinguishes "no provider was admitted" from "providers ran and found
  nothing", so an all-failed run cannot look like an empty file.

## Deviation from the plan's wording (named, not glossed)

The plan places the orchestrator "invoked from the file-indexing path that
currently calls `adapters/parse-file` directly". **`index.clj` and
`adapters.clj` were not modified.** The seam is a standalone entry point
(`provider-execution/shadow-facts-for-file`).

Reason: Stage 2's own exit criterion is that default output does not change, and
shadow output must not affect the active snapshot. Editing the default indexing
path to call a shadow pass adds risk to the path under protection while
delivering nothing Stage 2 needs. Not touching it also makes the
"default output unchanged" criterion provable rather than argued. The call-site
wiring belongs with the stage that actually consumes provider facts (Stage 6's
default switch, or earlier if a consumer needs shadow output in the snapshot).
The compatibility deliverable is met in the same way: `adapters/parse-file`
remains exactly as it was and continues to serve the default path.

## Exit criteria check

- **Existing default output remains unchanged** — asserted with a control: two
  consecutive index builds differ only in `snapshot_id` and `indexed_at` (both
  minted per build), and a build/shadow-run/build sequence produces exactly the
  same difference set. Units, relations, files, diagnostics, and `parser_mode`
  are identical. An earlier version of this test compared `snapshot_id` directly
  and failed; the assertion was wrong, not the code, and was replaced with the
  control formulation.
- **Shadow provider output is deterministic** — two runs over the same content
  produce equal facts, batches, and plans; only status observation timestamps
  differ, which the test states explicitly.
- **Tree-sitter unavailability routes to regex with an explicit degradation** —
  covered twice: with injected statuses, and live on this machine, where
  `java-tree-sitter` is genuinely unavailable
  (`tree_sitter_grammar_missing`), regex is admitted, facts are produced, and no
  gap is reported.
- **Regex shadow facts carry heuristic authority and never exact** — asserted on
  the emitted facts and, structurally, on every lexical descriptor's capability
  claims.

## Verification

- `clojure -M:test`: **480 tests, 2743 assertions, 0 failures, 0 errors**
  (was 456 / 2642 after the review repair).
- REPL-first: the Java and TypeScript parser output shapes were inspected before
  the adapters were designed, so arity handling follows real output rather than
  an assumption.
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files.
- Compile probes after every source edit; `git diff --check` clean; English-only
  scan clean.

## NextStageRoutingRecommendation

```text
completed_stage: 2 — Provider Plan And Legacy Adapter Shadow Path
recommended_next_stage: Stage 3 — TypeScript SCIP Vertical Slice
recommended_executor: Claude Code team lead, no Fable, no Explore agent
recommended_model: Claude Opus 4.8 Thinking for the identity/verification work,
  Sonnet 5 for the toolchain plumbing
effort: high
effort_justification: Stage 3 introduces the first external provider and the
  first evidence that may legitimately claim exact authority. It must verify
  SCIP symbol spellings against real tool output (the Stage 0 fixtures mark
  SCIP/LSP spellings as representative, not verified) and prove that a SCIP
  moniker normalizes onto the same CanonicalFactKey the regex tier already
  produces. Getting that wrong silently splits identities.
prerequisites_or_blockers:
  - scip-typescript must be installed and pinned; its absence must degrade
    explicitly through the status probe rather than failing a run.
  - The Stage 0 TypeScript fixture must be re-verified against real
    scip-typescript output and its representative spellings replaced.
  - Exact authority now requires an anchored source identity; the SCIP adapter
    must carry a per-document digest or a verified revision-bound artifact.
file_ownership_and_conflict_risk: LOW-MEDIUM. Stage 3 adds a descriptor and a
  runner to providers.clj and a fixture update; the kernel and the orchestrator
  should not need changes.
fallback_executor_or_model: Sonnet 5 for the toolchain and packaging work only.
model_availability_checked_at: not checked in this session.
confidence: high
```

## Stage 2 Review Hardening (2026-08-28)

External review of Stage 2 accepted the seam as shadow-only and raised four
findings. All four are fixed here as a pre-flight before Stage 3, since each of
them would become load-bearing the moment an external provider can claim exact
authority.

**High — a tree-sitter provider could emit regex facts as structural.**
`parse-file` falls back to the lexical parser when tree-sitter is unavailable or
fails, and the fallback is indistinguishable from a regex parse: verified in the
REPL that asking for tree-sitter with no grammar returns `parser_mode "full"`
with units byte-identical to the regex parse, the only signal being a
`tree_sitter_missing_grammar` diagnostic. `run-provider` assigned authority from
the descriptor, so those lexical facts would have carried the structural claim —
laundering heuristic evidence.

Fixed by strict execution: a tree-sitter provider run is refused when the parse
carries any `tree_sitter_*` diagnostic other than the positive CLI probe, which
fails closed for unknown future codes. The refusal surfaces through the
orchestrator as a `:provider_failed` batch with no facts, and the separately
admitted regex provider contributes the same facts as heuristic — the shape the
review asked for. Covered deterministically for two independent degradations (no
grammar configured, unusable grammar path) rather than relying on this machine's
toolchain state.

**Medium — the source-identity digest used a different basis from workspace
freshness.** Provider evidence hashed newline-joined lines while
`workspace-state/sha256-file` hashes file bytes, so line-ending normalization and
a missing trailing newline made the two incomparable. Since Stage 3 gates exact
authority on source identity, comparing them later would have silently
mis-judged freshness.

Fixed: `source-identity` now digests the file's bytes wherever the file can be
read, reusing `workspace-state/sha256-file` so the basis is identical, and names
the basis in the record (`digest_basis: file_bytes_sha256`). A lines-only caller
gets `joined_lines_sha256` and is thereby marked as not comparable. A test
asserts the file digest equals the workspace-state digest for the same file.

**Medium — execution was not operation-aware.** Providers were deduplicated
across operations and every batch hard-coded `definitions`. Invisible while the
catalog claims one operation, wrong as soon as Stage 3 adds references,
implementations, and calls. Fixed: `planned-tasks` emits one task per
(operation, provider), each batch names the operation it answered, and gap
tracking is computed per operation from those batches.

**Low/Medium — an unobserved provider was treated as ready.** A partial status
map admitted providers on a `"ready"` default, which for an external SCIP or LSP
tool means admitting something that was never probed. Fixed: a provider with no
observed status is excluded with `provider_status_unknown` /
`status_not_observed` outside forced mode; forced mode still admits it and
records the state as `forced` rather than pretending it was observed.

### Verification

- `clojure -M:test`: **483 tests, 2770 assertions, 0 failures, 0 errors**
  (was 480 / 2743).
- Live re-check of the seam after the fixes: source identity reports
  `digest_basis file_bytes_sha256`, the batch is `["java-regex" "definitions"]`,
  facts stay heuristic, and no gap is reported.
- `./scripts/validate-contracts.sh`: ok, 72 JSON files. `git diff --check`:
  clean. English-only scan: clean.

Stage 3 admission is unchanged by this work: it still needs `scip-typescript`
pinned, the Stage 0 SCIP spellings re-verified against real tool output, and
exact facts gated by anchored source identity.

---

# Stage 3 — TypeScript SCIP Vertical Slice (2026-08-29)

## Scope of this session

Toolchain / admission preflight only. **No provider adapter was built and no
production source changed.** This closes two of the three Stage 3 admission
items ("`scip-typescript` pinned", "Stage 0 SCIP spellings re-verified"); the
adapter and its exact-authority gating are deferred to the next session by the
owner ("стоп после сверки фикстуры").

## Delivered

| Artifact | Purpose |
| --- | --- |
| `scripts/scip-toolchain/{package.json,package-lock.json}` | committed pin. `npm ci` against this lock fixes `scip-typescript` (0.4.0) **and** its transitive `typescript` (5.9.3) — scip-typescript depends on `typescript: ^5.6.2`, which would otherwise drift and change SCIP output while a golden still claims a version |
| `scripts/setup-scip-typescript.sh` | `npm ci` from the committed lock into gitignored `.scip-toolchain/`, mirroring the tree-sitter ADR-047 resolution pattern (explicit option → `SEMIDX_SCIP_TYPESCRIPT_CLI_PATH` → repo-managed bin → ambient PATH). Fails closed on version drift; reports the resolved scip-typescript and tsc versions |
| `scripts/scip-typescript-corpus-snapshot.sh` + `scripts/lib/decode-scip.js` | regenerate the decoded real-SCIP reference over the Stage 0 corpus; the decoder reuses scip-typescript's own bundled protobuf module, so the preflight needs no separate protobuf toolchain. Also fails closed if the installed `typescript` drifted from the lock |
| `fixtures/provider-authority/corpus/typescript/tsconfig.json` | committed so SCIP output over the protected corpus is deterministic (verified: byte-identical decoded output with explicit vs inferred tsconfig) |
| `fixtures/provider-authority/scip/typescript-corpus.observed.json` + `README.md` | decoded real output of `scip-typescript@0.4.0` (TypeScript 5.9.3) over the corpus, `metadata.project_root` host-stripped |
| `.gitignore` | `.scip-toolchain/`, `.scip.env`, `scripts/scip-toolchain/node_modules/` |

## Verified SCIP behaviour vs the seeded "representative" fixture

The Stage 0 `identity/typescript-re-export-canonical-key.json` seeded the
`scip-typescript` spelling as *representative*. Real output disagrees on two
points, both now corrected in the fixture (no claim weakened; the central
"alias canonicalizes to one origin `CanonicalFactKey`" claim is confirmed):

1. **No alias symbol.** `export { normalize as canonicalize } from './orders'`
   produces **no** `canonicalize` symbol. `src/index.ts` emits only the module
   symbol. The seeded form claimed an
   `... src/index.ts/canonicalize.` symbol that does not exist.
2. **No Relationship.** scip-typescript emits no SCIP `Relationship` for the
   re-export. Both the `normalize` and the `canonicalize` tokens are
   non-definition occurrences resolving to the origin symbol (see the grammar
   block below).

Consequence recorded for the adapter: the SCIP tier contributes **no**
re-export unit or relation fact. The distinct exported-symbol unit
(`src/canonicalize`) rests on the heuristic/structural tier; SCIP only
corroborates that the alias token resolves to the origin. The re-export edge is
recoverable from occurrence resolution on the export statement, added to the
fixture's `normalization_obligations` and `edge_source: occurrence_resolution` /
`relationship_emitted: false` on the spelling.

Other verified grammar details (see `scip/README.md`). File components are
backtick-wrapped, params carry their own symbol, `external_symbols` is empty
(stdlib refs are inline occurrences):

```text
module         scip-typescript npm . . src/`orders.ts`/
function       scip-typescript npm . . src/`orders.ts`/normalize().
param          scip-typescript npm . . src/`orders.ts`/normalize().(value)
class          scip-typescript npm . . src/`orders.ts`/OrderService#
field          scip-typescript npm . . src/`orders.ts`/OrderService#validator.
constructor    scip-typescript npm . . src/`orders.ts`/OrderService#`<constructor>`().
re-export tgt  scip-typescript npm . . src/`orders.ts`/normalize().   (both normalize and canonicalize tokens)
stdlib ref     scip-typescript npm typescript 5.9.3 lib/`lib.es5.d.ts`/String#trim().
```

Java SCIP and all LSP spellings stay representative (Stages 4/5 re-verify).

## Test impact

`test/semidx/runtime/fact_arbitration_test.clj` — the re-export relation
sub-test built a hypothetical relation fact for every provider spelling. It now
excludes spellings with `relationship_emitted: false` and asserts
`scip-typescript` is excluded from the edge providers, so the golden no longer
implies a SCIP relationship that the tool does not emit. The origin-resolution
sub-test still runs all four spellings (SCIP does resolve the alias token to the
origin).

## Verification

- `clojure -M:test`: **483 tests, 2771 assertions, 0 failures, 0 errors**
  (unchanged count from the Stage 2 review hardening; the arbitration namespace
  went 113 → 114 assertions).
- `clojure -M:test-direct` targeted `semidx.runtime.fact-arbitration-test`:
  22 tests, 114 assertions, 0 failures.
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files.
- `./scripts/setup-scip-typescript.sh` from a removed `.scip-toolchain/`
  (clean-checkout simulation) → `scip_typescript_status=managed`,
  `scip_typescript_version=0.4.0`, `scip_typescript_tsc_version=5.9.3` via
  `npm ci` against the committed lock; idempotent on re-run.
  `./scripts/scip-typescript-corpus-snapshot.sh` regenerates the observed
  artifact with no git diff (byte-stable).
- Both touched JSON fixtures re-validated. `git diff --check` clean.
  English-only scan of new files: clean.
- **Not run**: full migration gate (semantic-quality report, protected retrieval
  replay, snapshot-diff parity, PG round trips) — those gate stages that change
  extraction; this session changes none.

## NextStageRoutingRecommendation

```text
completed_stage: 3 (preflight only — toolchain pinned, SCIP spellings re-verified,
  fixture corrected; adapter NOT built)
recommended_next_stage: Stage 3 continued — SCIP artifact reader + shadow/default-off
  TypeScript SCIP provider adapter
recommended_executor: Claude Code team lead, no Fable, no Explore agent
recommended_model: Claude Opus 4.8 Thinking for the identity/normalization work
  (SCIP moniker → CanonicalFactKey, occurrence-resolution re-export edge),
  Sonnet 5 for the protobuf-reader plumbing
effort: high
effort_justification: the adapter introduces the first evidence that may claim
  exact authority. Mapping a SCIP moniker onto the same CanonicalFactKey the
  regex tier already produces, and deriving the re-export edge from occurrence
  resolution rather than a (non-existent) relationship, are identity-critical;
  getting either wrong silently splits or collapses identities.
prerequisites_or_blockers:
  - SCIP reader in the JVM: vendor a pinned scip.proto + generate stubs via the
    ADR-042 repo-managed protoc toolchain, OR read the bundled proto shape. Not
    yet decided.
  - exact facts must be gated by anchored source identity (per-document digest or
    verified revision) — the ADR-046 rule added in the Stage 1 review repair.
  - absence of a SCIP artifact must degrade to tree-sitter/regex without index
    failure.
file_ownership_and_conflict_risk: LOW-MEDIUM. Adds a descriptor + runner to
  providers.clj, a new src ns for the SCIP reader, and fixture/test updates. The
  kernel and orchestrator should not need changes.
fallback_executor_or_model: Sonnet 5 for the toolchain/reader plumbing only.
model_availability_checked_at: not checked this session.
confidence: high (preflight is deterministic; the adapter is a bounded shadow seam)
```

---

# Stage 3 continued — JVM SCIP Reader (2026-08-30)

## Scope of this session

The first of the Stage 3 "continued" slices: a JVM SCIP artifact reader, built
and tested in isolation before any normalization or provider wiring. **No
`providers.clj`, `provider_selection.clj`, `provider_execution.clj`,
`fact_arbitration.clj`, or default extraction path was touched.** The reader is
not yet reachable from any provider; it is a standalone transport-level parser.

Owner decisions carried in: `scip.proto` is vendored from upstream and Java
stubs are generated by the ADR-042 repo-managed protoc toolchain — the reader is
**not** coupled to `@sourcegraph/scip-typescript`'s bundled JS module (that
module remains a preflight-only decoder). Pin: `sourcegraph/scip` **v0.5.2**
(recommended and confirmed).

## Delivered

| Artifact | Purpose |
| --- | --- |
| `proto/scip/scip.proto` | verbatim `sourcegraph/scip` v0.5.2, SHA-256 `b855eb9abdb3fe7f07600ee92f24a6c40f75fceef37136ccdc6844b300936a31`. Last tag before the `scip-code/scip` rename; field-compatible with the schema bundled in `scip-typescript@0.4.0` (both carry `Document signature_documentation = 7`, `enclosing_symbol = 8`, `SymbolRole` up to `0x40`). v0.9.0 changed field 7 to a `Signature` message and would misparse it. |
| `proto/scip/PROVENANCE.md` | records the upstream URL, tag, SHA-256, the v0.5.2 rationale, and the "no local edits" rule. |
| `build.clj` | `proto-file` generalized to `proto-specs` (a list). Each spec names its `.proto`, whether the grpc-java plugin runs (`:grpc?` — false for scip), and a generated-file completeness probe. `grpc-generate` / `grpc-verify-generated` now cover both protos; the drift guard is unchanged in spirit. |
| `src-generated/java/scip/Scip.java` | committed generated Java (single outer class `scip.Scip`, `java_multiple_files` unset upstream). Picked up automatically by the existing `semidx.runtime.grpc-prep/ensure-grpc-classes!` idempotent javac step, which walks the whole `src-generated/java` tree. |
| `src/semidx/runtime/scip.clj` | `read-index` / `parse-index-bytes` / `parse-index-stream`: translate a `.scip` payload into plain, serializable Clojure data — metadata, documents (symbols + occurrences), external symbols. Enums become lower-kebab keywords; `symbol_roles` is exposed both as the raw bitset int and as a decoded set of role keywords (`decode-symbol-roles`). **No** normalization into Semantic IR / `CanonicalFactKey`, **no** source-identity or freshness validation — those belong to the adapter slice. |
| `scripts/lib/scrub-scip.js` + `scripts/scip-typescript-corpus-snapshot.sh` | the snapshot script now also emits `typescript-corpus.scrubbed.scip`, a committed binary fixture with `metadata.project_root` cleared (a raw `.scip` embeds the absolute `file://` indexing-machine path and must not be committed). |
| `fixtures/provider-authority/scip/typescript-corpus.scrubbed.scip` | 4518-byte real `scip-typescript@0.4.0` output over the protected corpus, project-root scrubbed. Verified: no `file://` / host path in the bytes. |
| `fixtures/provider-authority/scip/typescript-corpus.observed.json` | regenerated; byte-identical to the committed version (no diff). |
| `test/semidx/runtime/scip_test.clj` | 4 tests, 35 assertions. |

## Test coverage

- **`decode-symbol-roles`**: every `SymbolRole` bit individually, plus bitset
  combinations (`5` -> `#{:definition :write-access}`, `11` -> three roles).
- **Generated-builder round trip**: an `Index` assembled with the `scip.Scip`
  builders and re-parsed — metadata (incl. `ToolInfo`, `TextEncoding`), a symbol
  with a `Kind`, documentation, and a relationship, a definition occurrence
  (three-element range, roles bitset, enclosing range), a reference occurrence
  (four-element range, no roles, empty enclosing range), and an external symbol.
  No external toolchain involved, so this always runs.
- **`read-index` input types**: file path string, `File`, and `InputStream` all
  produce the same data as `parse-index-bytes`.
- **Real artifact vs decoded reference**: reads
  `typescript-corpus.scrubbed.scip` and asserts, field by field, against
  `typescript-corpus.observed.json` — document order and paths, every symbol
  string per document, and an occurrence projection (`range`, `symbol`,
  `symbol_roles`, decoded roles, `enclosing_range`). Also locks the observed
  re-export shape: `src/index.ts` has only its module symbol and no
  relationships; its only definition occurrence is the module itself, and the
  re-exported `normalize`/`canonicalize` tokens are non-definition occurrences
  resolving to `src/orders.ts` symbols; `external_symbols` is empty and the
  `lib.es5.d.ts` `String#trim` stdlib reference appears inline.

## Verification

- **`clojure -M:test`**: **487 tests, 2806 assertions, 0 failures, 0 errors**
  (was 483 / 2771 after the Stage 3 preflight; +4 tests in
  `semidx.runtime.scip-test`).
- **`clojure -T:build grpc-verify-generated`**: `grpc_generated_sources_verified=35`
  — no drift between the committed stubs (grpc + scip) and the pinned toolchain.
- **`clojure -T:build compile-java`**: `compiled_java_sources=35`.
- **`./scripts/validate-contracts.sh`**: `contracts_validation=ok`, 72 JSON files
  (no contract touched).
- **`./scripts/scip-typescript-corpus-snapshot.sh`**: regenerates both the
  observed JSON (no git diff) and the scrubbed `.scip`; host-path scan of the
  scrubbed bytes is clean.
- **`git diff --check`**: clean. English-only: docstrings use em dashes,
  consistent with the Stage 2 `providers.clj` / `provider_selection.clj`; no
  Cyrillic.
- REPL-first throughout (`clojure -M:test-direct:nrepl` + clojure-mcp): the
  builder API and every translated field were exercised in the REPL before the
  test file was written.
- **Not run** (gate later stages that change extraction): full migration gate —
  semantic-quality report, protected retrieval replay, snapshot-diff parity,
  PostgreSQL round trips. This slice changes no extraction and persists nothing.

## NextStageRoutingRecommendation

```text
completed_stage: 3 continued — JVM SCIP reader (transport-level parser only;
  no normalization, no provider wiring)
recommended_next_stage: Stage 3 continued — SCIP -> CanonicalFactKey normalization
  slice (definitions, references, implementations, available call facts; the
  re-export edge derived from occurrence resolution, not a Relationship)
recommended_executor: Claude Code team lead, no Fable, no Explore agent
recommended_model: Claude Opus 4.8 Thinking — the normalization slice is the
  identity-critical one (SCIP moniker -> the same CanonicalFactKey the regex tier
  already produces; distinct overloads/re-exports must stay distinct)
effort: high
effort_justification: this is where SCIP evidence first enters the arbitration
  kernel. A wrong moniker->key mapping silently splits or collapses identities,
  which the Stage 1 goldens are meant to catch but only if the mapping is built
  against them deliberately.
prerequisites_or_blockers:
  - the normalization slice must map onto the Variant C core key
    (language, path, owner, symbol, arity[, ordinal]) and treat any typed
    signature as refinement, matching the committed identity fixtures.
  - the SCIP symbol grammar parser (scip-typescript's
    `scip-typescript npm <name> <version> <descriptor>` form, backtick-wrapped
    file components, `#` for members, `().` for methods, `.(param)` for params)
    is new surface — build it as its own function with its own tests.
  - still shadow/default-off; still no exact-authority claim until the adapter
    slice adds anchored source identity.
file_ownership_and_conflict_risk: LOW-MEDIUM. The next slice adds a
  normalization ns (e.g. semidx.runtime.providers.scip-normalize) and tests;
  it should not need to touch scip.clj, the kernel, or the orchestrator.
fallback_executor_or_model: Sonnet 5 for the SCIP symbol-grammar parser plumbing;
  not for the moniker -> CanonicalFactKey decisions.
model_availability_checked_at: not checked this session.
confidence: high (the reader is deterministic and isolated; the next slice is
  bounded but identity-critical)
```

## Review Repair (2026-08-30)

External review of the JVM SCIP reader raised one Medium and two Low findings;
all three are fixed here.

| ID | Severity | Finding | Resolution |
| --- | --- | --- | --- |
| R1 | Medium | `read-symbol-information` dropped `SymbolInformation.signature_documentation`. The vendored proto keeps this field specifically (`Document signature_documentation = 7`), so the reader was not transport-complete and the normalization slice could silently lose signature payloads from a SCIP producer that emits them. | **Fixed.** `read-symbol-information` now returns `:signature-documentation` — `nil` when `hasSignatureDocumentation` is false, otherwise the full `read-document` map (the two functions are mutually recursive via a `declare`; a signature `Document` does not nest further signatures). The generated-builder round-trip test now sets a signature document and asserts it. `scip-typescript@0.4.0` does not emit this field for the protected corpus (it uses markdown code fences in `documentation` — 16 symbols), so the builder test is the coverage. |
| R2 | Low | `git diff --check` failed on ~250 trailing-whitespace lines in `src-generated/java/scip/Scip.java`; protoc emits trailing spaces. The committed gRPC stubs had the same latent issue. | **Fixed.** `build.clj` `generate-into!` now runs `strip-trailing-whitespace!` over every generated `.java` before the completeness probe and the copy/verify. `grpc-verify-generated` hashes the same normalized output, so committed sources and a fresh generation still match. Both generated trees were regenerated; `git diff --check` is clean over `b117442..HEAD`. |
| R3 | Low | `scip-typescript-corpus-snapshot.sh` honoured `SEMIDX_SCIP_TYPESCRIPT_CLI_PATH` but its drift check only validated the repo-managed `.scip-toolchain/node_modules/typescript`. An override pointing elsewhere could generate with an unpinned toolchain while the script still reported success. | **Fixed.** The drift check now resolves the CLI's own `node_modules` (`dirname "$CLI"/..`), validates **both** `@sourcegraph/scip-typescript` and `typescript` against the committed lock, and fails closed with a clear message when those sibling packages are absent (e.g. an ambient-PATH CLI). |

Verification after the repair:

- `clojure -M:test`: **487 tests, 2806 assertions, 0 failures, 0 errors**.
- `clojure -T:build grpc-generate` + `grpc-verify-generated` + `compile-java`:
  35 sources generated, verified (no drift), compiled.
- `git diff --check`: clean, working tree and over `b117442..HEAD`.
- `./scripts/scip-typescript-corpus-snapshot.sh`: regenerates both fixtures
  byte-stable; the tightened drift check passes against the repo-managed CLI.
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files.

---

# Stage 3 continued — SCIP -> CanonicalFactKey Normalization (2026-08-30)

## Scope of this session

The normalization slice: turn `semidx.runtime.scip/read-index` data into
provider-neutral facts for `semidx.runtime.fact-arbitration`. Additive, pure,
shadow. **Not touched:** `fact_arbitration`, `providers`, `provider_selection`,
`provider_execution`, and the default extraction path. The normalizer is not
wired into any provider yet.

Owner-confirmed decisions (this session):

- **Unit coverage** — emit facts only for the definition kinds semidx already
  models: top-level functions/vars and class methods. Classes, fields,
  constructors, parameters, and external/stdlib symbols are recorded as
  `:unmapped` with a reason and become no fact. Promoting SCIP-only kinds is a
  later decision.
- **Naming bridge** — make `semidx.runtime.languages.typescript/ts-module-name`
  public and reuse it, rather than forking a new naming primitive. One source of
  truth for how a path becomes an owner.

## Delivered

| Artifact | Role |
| --- | --- |
| `src/semidx/runtime/providers/scip_normalize.clj` | `parse-scip-symbol` (SCIP symbol grammar -> scheme/package/typed descriptors, backtick-escape aware), `scip-symbol->unit` (moniker -> semidx `{:owner :symbol :kind :path}` or `{:unmapped <reason>}`), `normalize-index` (SCIP index data -> `{:facts [...] :unmapped [...]}`). |
| `src/semidx/runtime/languages/typescript.clj` | `ts-module-name` is now public (`defn`), with a docstring naming its cross-provider role. No behaviour change; all TS-lane tests still green. |
| `test/semidx/runtime/providers/scip_normalize_test.clj` | 6 tests, 47 assertions. |

## How the bridge works

A SCIP moniker like
`scip-typescript npm . . src/`orders.ts`/normalize().` parses to package fields
plus descriptors `[ns "src", ns "orders.ts", method "normalize"]`. The leading
namespace descriptors up to and including the one with an ecmascript source
extension reconstruct the **path** (`src/orders.ts`); `ts-module-name` turns that
into the **owner** (`src.orders`); the descriptors after the file namespace name
the **symbol** — `<module>/<name>` for a bare method/term, `<module>.<Class>#<method>`
when a type descriptor precedes it. Identity therefore comes entirely from the
moniker, so a cross-file reference in `index.ts` to `src/`orders.ts`/normalize().`
still keys on `src.orders/normalize` while its evidence location records
`src/index.ts`.

`scip-symbol->unit` returns `:unmapped` for: `:external-symbol` (package name is
not the local-project `.`), `:local-symbol`, `:module-symbol` (the file itself),
`:type-symbol` (a bare class), `:field-symbol`, `:constructor-symbol`,
`:non-unit-descriptor` (parameter / type-parameter / meta / macro), and parse
errors.

## Facts and authority

Each occurrence of a mapped symbol becomes one fact keyed on that symbol's
canonical identity, with one `FactEvidence`: `authority "exact"`,
`operation "definitions"` when the occurrence carries the `:definition` role else
`"references"`, `freshness "exact"`. Arbitration then folds the definition and
every reference for one symbol into a single canonical fact.

`source-identity` is **injected** by the caller (one map, or a function of the
document's `relative-path`). ADR-046 requires an anchor for `exact` authority;
`normalize-index` does not compute a digest or run the stale-artifact gate —
that is the provider adapter's job. Passing an unanchored identity is a caller
error that `fact-arbitration/fact-evidence-errors` rejects
(`:exact-without-source-identity`), verified by a test.

Deferred (named): call-hierarchy facts (no `call/*` relation type in
`relations/relation-types`; the identity fixtures do not require one — the
re-export edge is covered by reference facts resolving to the origin),
implementations from SCIP `Relationship` (code path present, none in the
corpus), and the digest/freshness gate.

## Verification

- `clojure -M:test`: **493 tests, 2853 assertions, 0 failures, 0 errors**
  (was 487 / 2806; +6 in `semidx.runtime.providers.scip-normalize-test`).
- Against the committed `typescript-corpus.scrubbed.scip`: `normalize-index`
  emits 9 facts over exactly the four modelled symbols
  (`src.orders/normalize`, `src.orders/createOrder`,
  `src.orders.OrderService#handle`, `src.validator.Validator#validate`); the
  `normalize` fact carries a definition in `orders.ts` and references from both
  `orders.ts` and `index.ts` (the `canonicalize`/`normalize` re-export tokens
  corroborating the origin).
- Cross-provider parity: the SCIP-derived key for `src.orders/normalize` has the
  same `canonical-fact-key-id` as the regex-tier key, and arbitrating the SCIP
  facts with a synthetic regex heuristic fact yields one `exact` canonical fact
  that retains both providers' evidence and keeps the regex-only `fact_identity`
  (Variant C invariant).
- Identity-fixture parity: `scip-symbol->unit` on the fixture's verified
  `native_re_export_target` moniker resolves field for field to
  `expected_canonical_key_of_origin`, and stays distinct from the alias
  `distinct_facts_must_not_merge` keys.
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files.
  `git diff --check`: clean. English-only: no Cyrillic.
- REPL-first via `clojure -M:test-direct:nrepl` + clojure-mcp throughout.

## NextStageRoutingRecommendation

```text
completed_stage: 3 continued — SCIP -> CanonicalFactKey normalization (pure;
  no provider wiring, no digest/freshness gate)
recommended_next_stage: Stage 3 continued — TypeScript SCIP provider adapter
  (descriptor + runner in providers.clj, shadow/default-off), with anchored
  source identity and the stale-artifact gate
recommended_executor: Claude Code team lead, no Fable, no Explore agent
recommended_model: Claude Sonnet 5 for the adapter/runner plumbing; escalate the
  source-identity + stale-artifact gate to Opus if the freshness scoping (F2)
  proves subtle
effort: medium
effort_justification: the identity-critical mapping is now built and tested; the
  adapter is bounded plumbing over it — invoke scip-typescript (repo-managed
  CLI, ADR-047-style resolution), read the .scip via semidx.runtime.scip,
  normalize via scip-normalize, attach a real per-document digest, emit a
  FactBatch. The one careful part is F2: whether a stale document invalidates
  its whole exact contribution or only affected ranges.
prerequisites_or_blockers:
  - a descriptor for scip-typescript in providers.clj with capability claims
    matching what the adapter emits (definitions + references; not call
    hierarchy).
  - real per-document source identity: digest the workspace file the SCIP
    document covers, compare to the SCIP artifact's coverage; stale or missing
    => no exact facts (Stage 3 exit criterion).
  - absence of a .scip artifact must degrade to tree-sitter/regex without index
    failure.
  - still shadow/default-off; no default-path wiring until Stage 6.
file_ownership_and_conflict_risk: LOW-MEDIUM. The adapter adds a descriptor +
  runner to providers.clj and a status probe; scip.clj, scip-normalize.clj, the
  kernel, and the orchestrator should not need changes.
fallback_executor_or_model: Opus 4.8 for the freshness-scoping decision only.
model_availability_checked_at: not checked this session.
confidence: high (mapping proven against the goldens; the adapter is bounded)
```

## Review Repair (2026-08-30, normalization slice)

External review of `scip-normalize` raised two Medium findings; both fixed here.

| ID | Severity | Finding | Resolution |
| --- | --- | --- | --- |
| N1 | Medium | `read-name` / `read-descriptor` throw on an unterminated backtick or a malformed method descriptor, and `parse-scip-symbol` did not catch them. One bad SCIP occurrence could abort `normalize-index` instead of becoming `:unmapped`. | **Fixed.** `parse-scip-symbol` wraps the descriptor loop in a `catch clojure.lang.ExceptionInfo` and returns `{:error :unparseable-descriptors :raw sym :message ...}`; the message is surfaced, not swallowed. `scip-symbol->unit` already routes `:error` to `:unmapped`, so a malformed occurrence is now recorded with a reason and the rest of the index still normalizes. Covered by a parser test and a `normalize-index` test with a mixed good/bad document. |
| N2 | Medium | Every top-level `:term` descriptor was mapped to a `:kind "function"` unit, so an exact fact could be minted for a non-callable const (`export const VERSION = "1.0"`). | **Fixed, with a verified basis.** Probed real `scip-typescript@0.4.0`: it spells **every** top-level `const`/`let` — arrow functions, function expressions, and plain values alike — as a term descriptor (`name.`); only `function foo()` declarations and class methods get `().`. So the term path cannot be dropped (that would lose the arrow/function consts semidx's regex lane *does* model) and callability cannot be read off the symbol. A top-level term now maps to a unit with `:kind "term"` (not `"function"`): the arrow/function consts merge onto the regex-tier unit by canonical key (test asserts the key parity), and a plain value const becomes an honestly-labelled exact-only `term` unit for the Stage 6 authority review to accept or gate. Class-body `:term` (a field) stays `:unmapped :field-symbol`. |

Verification after the repair:

- `clojure -M:test`: **494 tests, 2862 assertions, 0 failures, 0 errors**
  (was 493 / 2853; +1 test, +9 assertions in `scip-normalize-test`).
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files.
  `git diff --check`: clean. English-only: no Cyrillic.

---

# Stage 3 continued — TypeScript SCIP Provider Adapter (2026-09-01)

## Scope of this session

The adapter slice: wire `semidx.runtime.scip` +
`semidx.runtime.providers.scip-normalize` behind a provider that runs the
repo-managed `scip-typescript` CLI, anchors each fact to real workspace content,
and gates stale documents out of exact authority. Additive, shadow,
default-off. **Not touched:** `fact_arbitration`, `providers`,
`provider_selection`, `provider_execution`, `scip`, `scip_normalize`, and the
default extraction path.

Owner-confirmed decisions (this session):

- **Source mode** — production is CLI-generated `.scip` only, resolved through
  the ADR-047 chain. An internal helper may accept an already-read SCIP index
  for tests/fixtures, but that is not a production source mode.
- **Missing CLI** — not an index-run error: an `:unavailable` provider result
  with diagnostics, and the caller degrades to tree-sitter/regex.
- **Stale gate granularity (reports/024 F2)** — document-level, not range-level,
  for this first slice. `Document.relative_path` -> workspace file sha256; a
  missing file or a digest mismatch drops the whole document's contribution from
  the exact `FactBatch`; regex/tree-sitter stay as fallback. Range-level
  invalidation is too fine for the initial guarantee; document-level is simpler
  and defensible.

## Delivered

| Artifact | Role |
| --- | --- |
| `src/semidx/runtime/providers/scip_typescript.clj` | the adapter. `resolve-cli` (ADR-047 chain: `:scip_typescript_cli_path` -> `SEMIDX_SCIP_TYPESCRIPT_CLI_PATH` -> `.scip-toolchain/node_modules/.bin/scip-typescript` -> ambient `PATH`), `provider-status` (`ready` / `unavailable` + `scip_cli_missing`, never runs the indexer), `cli-version`. `shadow-facts-for-project` — production: resolve CLI, run `scip-typescript index` once over the project into a temp `.scip` (always deleted), `scip/read-index`, then `facts-from-index`. `facts-from-index` — test/fixture seam: takes an already-read index + `:project-root`, runs the stale gate, normalizes the fresh documents via `scip-normalize/normalize-index`, groups facts into one `FactBatch` per operation, and returns `fact-arbitration/arbitrate-batches` output plus coverage/diagnostics. `descriptor` (tagged `:scope :project`, `:classification "semantic"`, `:operation_capabilities {:definitions "exact" :references "exact"}`) lives here, not in the `providers.clj` catalog. |
| `test/semidx/runtime/providers/scip_typescript_test.clj` | 9 tests, 38 assertions. Deterministic assertions run `facts-from-index` over the committed `typescript-corpus.scrubbed.scip` with the committed corpus as project root (no toolchain needed); one end-to-end test runs the real CLI and is skipped when `resolve-cli` returns nil. |

## How the stale gate works

`document-freshness` resolves each SCIP document's `relative-path` under the
project root and digests it with the same basis as
`workspace-state/sha256-file`. Three outcomes:

- **fresh** — file present; its `sha256:...` digest becomes the `content_digest`
  anchor on every `FactEvidence` from that document (so the exact-authority ADR-046
  anchor rule is satisfied with real content, not a placeholder);
- **missing** — no workspace file: the document contributes no facts,
  `:scip_document_source_missing` diagnostic, `coverage.complete false`;
- **mismatch** — a caller supplied `:expected-document-digests` (e.g. from a
  workspace-state snapshot taken before indexing) and it disagrees: same drop,
  `:scip_document_stale` diagnostic naming expected and actual.

Only fresh documents are passed to `normalize-index`. A cross-file reference
from a fresh document to a symbol defined in a stale one **survives** — it is
genuinely anchored to the fresh file where the reference occurs; the stale
document's own occurrences (including the definition) are gone. Verified against
the corpus: marking `src/orders.ts` stale drops
`src.orders.OrderService#handle` entirely and reduces `src.orders/normalize` to
its `index.ts` references only.

## Deviation from the plan's wording (named, not glossed)

The Stage 3 routing handoff said "descriptor + runner in `providers.clj` ...
emit a `FactBatch` through the orchestrator". **`providers.clj`,
`provider_selection.clj`, and `provider_execution.clj` were not modified.**

Reason: those three are a **per-file** seam — `provider-execution` calls
`run-provider` once per file, `provider-selection` builds a per-file
ProviderPlan. SCIP is a **project-level batch index**: one `scip-typescript
index` run covers the whole project. Adding the descriptor to the
`providers.clj` catalog would make per-file planning emit a task for it, and
`run-provider` would misroute `:scip` at the TypeScript regex/tree-sitter
parser. Running the CLI once per file instead would be absurd. So the adapter is
a standalone entry point, exactly as Stage 2 made `shadow-facts-for-file`
standalone rather than editing `index.clj`. Reconciling a project-scoped
provider with the per-file planning model (a project pre-pass that runs SCIP
once and feeds per-file lookups, or a `:scope`-aware planner) is its own slice;
the descriptor is kept in the adapter ns tagged `:scope :project` so that slice
has one source of truth.

## Exit criteria check (plan Stage 3)

- **Stale or mismatched SCIP artifacts never produce exact facts** — met: the
  document-level gate drops missing/mismatched documents before normalization;
  a fresh document's real digest is the anchor. Tested for both the missing-file
  and the digest-mismatch path.
- **SCIP facts merge with tree-sitter structure without duplicate semantic
  identities** — the SCIP-derived `canonical_fact_key_id` equals the regex-tier
  key for the same symbol (tested); `arbitrate-batches` folds definition +
  references into one canonical fact. Full parity against Stage 2 tree-sitter
  shadow output over the corpus is **not yet wired** (see below).
- **Absence of a SCIP artifact degrades to tree-sitter/regex without index
  failure** — met: `shadow-facts-for-project` returns `:result "unavailable"` /
  `"failed"` with diagnostics and no facts, never throws.
- **Protected TypeScript retrieval cases do not regress** — not applicable this
  session: the adapter is shadow-only and changes no extraction. The protected
  retrieval replay gates the stage that consumes SCIP facts.

## Deferred (named honestly)

- **Shadow comparison harness** — a side-by-side diff of SCIP facts vs the Stage 2
  tree-sitter/regex shadow facts over the protected corpus, with the approved
  improvements/differences recorded. Key parity is tested per symbol; the
  whole-corpus comparison is not built.
- **Implementations + call-hierarchy facts** — SCIP `Relationship` (none in the
  corpus; code path absent) and `call/*` relations (no such relation type in
  `relations/relation-types`). Both were already deferred by the normalization
  slice.
- **Latency and storage metrics** — the plan lists "provider coverage, conflict,
  stale-artifact, latency, and storage metrics". Coverage, conflict (via
  arbitration), and stale-artifact are covered; latency and snapshot-size
  measurement for a SCIP run are not.
- **Catalog / planner integration** — see the deviation above.

## Verification

- `clojure -M:test`: **503 tests, 2900 assertions, 0 failures, 0 errors**
  (was 494 / 2862; +9 tests in `semidx.runtime.providers.scip-typescript-test`).
- REPL-first via `clojure -M:test-direct:nrepl` + clojure-mcp: the full flow
  (real `scip-typescript@0.4.0` CLI over the corpus -> `read-index` ->
  per-document digest -> `normalize-index` -> `arbitrate-batches`) was exercised
  in the REPL before the adapter and tests were written; the end-to-end test
  produces the same four modelled symbols as the committed fixture path.
- Stale gate exercised live: wrong project root -> all three documents dropped
  with `:scip_document_source_missing`; a planted `sha256:0000` expected digest
  for `src/orders.ts` -> only that document dropped, its definition gone,
  `index.ts` references retained.
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files
  (no contract touched).
- `git diff --check`: clean. English-only scan of the two new files: no Cyrillic.
- Compile probe: the namespace and its test load clean under the `:test-direct`
  classpath (compiled `scip.Scip` stubs present).
- **Not run** (gate later stages that change extraction): full migration gate —
  semantic-quality report, protected retrieval replay, snapshot-diff parity,
  PostgreSQL round trips. This slice changes no extraction and persists nothing.

## NextStageRoutingRecommendation

```text
completed_stage: 3 continued — TypeScript SCIP provider adapter (shadow /
  default-off; CLI-resolved, per-document stale gate, arbitrated FactBatches)
recommended_next_stage: Stage 3 close-out — shadow comparison harness (SCIP vs
  Stage 2 tree-sitter/regex over the protected corpus) + coverage/latency/storage
  metrics; THEN Stage 4 (Java SCIP). The owner may also choose to move straight
  to Stage 4 and fold the shadow harness in there.
recommended_executor: Claude Code team lead, no Fable, no Explore agent
recommended_model: Claude Sonnet 5 for the shadow-comparison harness and metrics
  (bounded, reuses the built adapter + Stage 2 seam); escalate to Opus 4.8 for
  Stage 4's Java overload/constructor/import/relation identity parity
effort: medium (close-out) / high (Stage 4)
effort_justification: the close-out is a bounded diff harness over two existing
  shadow producers plus timing/size instrumentation — no new identity contract.
  Stage 4 is high because Java overload, constructor, static-import, and
  field-write relation identities need cross-provider parity that the shared SCIP
  boundary must not special-case for TypeScript.
rationale: the identity-critical mapping (moniker -> CanonicalFactKey) and the
  exact-authority anchor gate are built and tested. What is left for Stage 3 is
  observational (the shadow diff proving no duplicate identities at corpus scale)
  and metric collection, both lower risk than the mapping itself.
prerequisites_or_blockers:
  - the shadow-comparison harness needs the Stage 2 seam
    (`provider-execution/shadow-facts-for-file`) and this adapter to run over the
    same corpus and be diffed by canonical key; approved differences recorded.
  - Stage 4 must reuse `scip-normalize` / `scip-typescript` without adding a
    TypeScript-specific branch to the shared boundary; a Java SCIP indexer
    (scip-java) toolchain pin mirroring `scripts/setup-scip-typescript.sh` is a
    prerequisite and is not yet built.
  - catalog/planner integration of a `:scope :project` provider remains an open
    design slice; it is not a Stage 4 blocker but should be scheduled.
  - still shadow/default-off; no default-path wiring until Stage 6.
file_ownership_and_conflict_risk: LOW-MEDIUM. This session added one src ns and
  one test ns; it touched neither the kernel, the Stage 2 seam, nor plans/020
  files. The close-out harness would add a test/report ns and possibly a small
  timing helper.
fallback_executor_or_model: Sonnet 5 for the harness/metrics; not for the Java
  identity decisions.
model_availability_checked_at: not checked this session.
confidence: high (the adapter is bounded and tested; the remaining Stage 3 work
  is observational)
```

---

# Stage 3 close-out — Shadow Comparison Harness + Metrics (2026-09-01)

Owner direction: build the shadow-comparison harness (SCIP vs Stage 2
tree-sitter/regex over the corpus) plus latency/storage metrics, medium effort,
Sonnet 5. This closes Stage 3.

## Scope of this session

Additive, read-only, shadow. **Not touched:** `fact_arbitration`, `providers`,
`provider_selection`, `scip`, `scip_normalize`, and the default extraction path.
Two small additive passthroughs (`:raw_facts` on the SCIP result, `:raw_batches`
on `provider-execution/shadow-facts-for-file`) expose pre-arbitration facts so
both tiers can be co-arbitrated in one pass; both are new map keys, verified not
to change any existing assertion.

## Delivered

| Artifact | Role |
| --- | --- |
| `src/semidx/runtime/providers/scip_shadow_compare.clj` | `compare-fact-sets` (diff two arbitrated fact sets by `canonical_fact_key_id`: agreed / exact-only / legacy-only / authority-upgrade), `co-arbitrate` (feed raw SCIP + raw legacy facts through one `arbitrate-facts` and report the canonical-fact count and every multi-provider symbol), `fact-set-size` (fact / evidence / serialized-byte counts), `measure` (elapsed ms), `discover-ts-paths`, `compare-scip-run` (compare an already-computed SCIP result — the deterministic seam), and `shadow-report` (run the real CLI, compare, and time it). |
| `test/semidx/runtime/providers/scip_shadow_compare_test.clj` | 7 tests, 34 assertions. Deterministic assertions drive `compare-scip-run` from the committed `.scip` fixture; one end-to-end test runs the real CLI and is skipped when it does not resolve. |
| `src/semidx/runtime/providers/scip_typescript.clj` | `+ :raw_facts` on the `facts-from-index` / `shadow-facts-for-project` result. |
| `src/semidx/runtime/provider_execution.clj` | `+ :raw_batches` on the `shadow-facts-for-file` result. |

## Observed comparison over the protected corpus

`compare-scip-run` on the committed `typescript-corpus.scrubbed.scip` against the
Stage 2 seam over `src/index.ts`, `src/orders.ts`, `src/validator.ts` (this host
has no tree-sitter grammar, so the legacy tier is regex only — the comparison
shape is identical):

| Field | Value |
| --- | --- |
| `agreed` (same `canonical_fact_key_id`) | `src.orders/normalize`, `src.orders/createOrder`, `src.orders.OrderService#handle`, `src.validator.Validator#validate` |
| `exact_only` | *(none)* |
| `legacy_only` | `src/canonicalize`, `src/createOrder` — the `index.ts` re-export aliases; SCIP mints no re-export unit (preflight finding), and these are genuinely distinct alias identities, not the origin |
| `authority_upgrade` | all 4 agreed symbols `heuristic -> exact` |
| `co_arbitration.canonical_fact_count` | **6** = 4 agreed + 2 legacy-only — the shared symbols collapse, they do not double |
| `co_arbitration.diagnostic_count` | 0 |
| `co_arbitration.multi_provider_symbols` | all 4 agreed symbols, one `exact` fact each, evidence from **both** `scip-typescript` and `typescript-regex` |
| `size.scip` | 4 facts / 9 evidence / 5326 serialized bytes (~2.25 evidence per fact: definition + references) |
| `size.legacy` | 6 facts / 6 evidence / 4968 serialized bytes (one evidence per fact) |
| `latency.scip_run_ms` (CLI + read + normalize + gate + arbitrate, 3-file corpus) | ~0.5 s |

This is the Stage 3 exit-criterion evidence: **SCIP exact facts merge with the
legacy tier's facts under one identity** — the co-arbitration pass produces
exactly one canonical fact per key with no ambiguity diagnostic, and every
shared symbol keeps both providers' evidence at exact authority. The only
divergence (`legacy_only`) is the two re-export aliases, which are expected and
are not the origin identity.

## Exit criteria check (plan Stage 3) — final

- **Stale or mismatched SCIP artifacts never produce exact facts** — met (adapter
  slice; document-level gate tested for missing and mismatch).
- **SCIP facts merge with tree-sitter structure without duplicate semantic
  identities** — met: `compare-fact-sets` shows shared symbols on one key,
  `co-arbitrate` shows them collapsing to one canonical fact with 0 diagnostics.
- **Protected TypeScript retrieval cases do not regress; approved improvements
  recorded** — the provider is shadow-only and changes no extraction, so
  retrieval is byte-identical by construction; the improvement (heuristic ->
  exact for the 4 modelled symbols) is recorded above. Full protected retrieval
  replay gates the stage that consumes SCIP facts (Stage 6).
- **Absence of a SCIP artifact degrades to tree-sitter/regex without index
  failure** — met (adapter slice; `:unavailable` / `:failed` results, no throw).

## Deferred past Stage 3 (named, unchanged from the adapter slice)

- SCIP `Relationship` / implementations facts and `call/*` relation facts — none
  in the corpus; no `call/*` relation type exists. Revisit if a corpus needs them.
- Catalog / planner integration of the `:scope :project` SCIP provider — the
  adapter is a standalone entry point; reconciling a project-level provider with
  the per-file ProviderPlan is its own slice.
- Range-level (vs document-level) stale invalidation — reports/024 F2.

## Verification

- `clojure -M:test`: **510 tests, 2928 assertions, 0 failures, 0 errors**
  (was 503 / 2900; +7 tests in `semidx.runtime.providers.scip-shadow-compare-test`).
- Untouched Stage 2 suite (`semidx.runtime.provider-execution-test`) re-run
  green after the `:raw_batches` passthrough.
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files.
- `git diff --check`: clean. English-only scan of the new/edited files: no Cyrillic.
- REPL-first via `clojure -M:test-direct:nrepl` + clojure-mcp: the full report
  was produced against the real `scip-typescript@0.4.0` CLI and against the
  committed fixture; both agree on the four modelled symbols.
- **Not run** (gate later stages that change extraction): full migration gate —
  semantic-quality report, protected retrieval replay, snapshot-diff parity,
  PostgreSQL round trips.

## NextStageRoutingRecommendation

```text
completed_stage: 3 — TypeScript SCIP vertical slice (COMPLETE: preflight, JVM
  reader, normalization, provider adapter + stale gate, shadow comparison harness
  + metrics)
recommended_next_stage: Stage 4 — Java SCIP vertical slice
recommended_executor: Claude Code team lead, no Fable, no Explore agent
recommended_model: Claude Opus 4.8 Thinking for the Java identity/parity work
  (overloads, constructors, static imports, method references, entity/field-write
  relations), Claude Sonnet 5 for the scip-java toolchain pin and adapter
  plumbing
effort: high
effort_justification: Java re-introduces typed overload identity (Variant C
  `signature_precision: typed` on the exact tier) that TypeScript never exercised,
  plus constructor units, static-import ownership, method references, and the
  plans/017 field-write relations. A SCIP moniker that maps to the wrong Java
  overload key silently splits or collapses identities the Stage 0/1 Java
  overload goldens are meant to catch. The shared SCIP boundary
  (`scip-normalize`, `scip-typescript` adapter shape) must not gain a
  TypeScript-specific branch.
rationale: the SCIP seam is proven end to end for TypeScript — reader,
  normalization, adapter, stale gate, and a co-arbitration comparison showing no
  duplicate identity. Stage 4 reuses that seam for a harder language rather than
  designing new machinery.
prerequisites_or_blockers:
  - a repo-managed scip-java (or scip-semanticdb) toolchain pin mirroring
    `scripts/setup-scip-typescript.sh` + a committed Java corpus `.scip` fixture;
    NOT yet built.
  - the Java overload path must produce the Variant C typed signature on the
    exact tier and match the Stage 0 `java-overload-canonical-key` identity
    fixture; distinct overloads must stay distinct, same-arity overloads follow
    the F1a rule already in the kernel.
  - `scip-normalize` currently hard-codes `"typescript"` and the ecmascript path
    reconstruction; Stage 4 must factor the language-specific bridge out without
    a TypeScript regression (the identity fixtures and this session's comparison
    harness are the guard).
  - still shadow/default-off; no default-path wiring until Stage 6.
file_ownership_and_conflict_risk: LOW-MEDIUM. Stage 4 adds a Java corpus fixture,
  a scip-java setup script, a Java branch in the SCIP normalization boundary, a
  Java SCIP adapter, and tests. It should not need to touch the kernel, the
  Stage 2 seam, `scip.clj`, or the shadow-comparison harness (which is
  language-neutral and will diff Java the same way).
fallback_executor_or_model: Sonnet 5 for the toolchain/adapter plumbing; not for
  the Java overload/relation identity decisions.
model_availability_checked_at: not checked this session.
confidence: high (the seam is proven for TypeScript; Stage 4 is a reuse with a
  harder identity surface)
```

---

# Stage 4 — Java SCIP: Toolchain And Identity Preflight (2026-09-05)

## Scope of this session

Preflight only, **documentation and fixtures**. No production source changed and
no adapter was built. This session answered the two questions Stage 4 could not
start without, and both answers changed an owner-approved contract, so they were
recorded and confirmed before any code.

## What was verified, and how

`scip-java` was run end to end over the protected Java corpus and its output read
with the existing `semidx.runtime.scip` reader:

1. `javac` with the `semanticdb-javac` 0.12.3 compiler plugin over
   `fixtures/provider-authority/corpus/java` -> two `.semanticdb` files.
2. A ~20-line Java driver calling `ScipSemanticdb.run` from `scip-semanticdb`
   0.12.3 -> `index.scip`.
3. `semidx.runtime.scip/read-index` on that artifact.

No build tool, no `pom.xml`, no coursier, and no Scala CLI were needed.

## Finding S1 (High) — the Stage 0 scip-java spelling was false

The fixture seeded `scip-java maven . . example/OrderService#handle(java.lang.String).`
with `signature_key "java.lang.String"`, marked `ground_truth: false` and
explicitly flagged for re-verification. Real output:

| Claim | Fixture | Real scip-java 0.12.3 |
| --- | --- | --- |
| scheme | `scip-java` | `semanticdb` |
| `handle(String)` | `...#handle(java.lang.String).` | `...#handle().` |
| `handle(String,int)` | typed signature | `...#handle(+1).` |
| `signature_key` | `"java.lang.String"` | absent — no types in the symbol |

Overloads are disambiguated by a **source-order ordinal**, counted over the
method name across all arities. Neither parameter types nor arity are in the
moniker. Arity and a human-readable signature come only from
`SymbolInformation.signature_documentation.text`
(`"public String handle(String order, int retries)"`) — **simple** type names
with parameter names included, which is the shape F1 rejected as Variant B and
is close to what the regex tier already produces. Fully-qualified types exist
only as separate occurrences inside the method's declaration range.

Two further Java facts, both relevant to the adapter:

- a Java moniker carries the **package** path (`example/`), not the source file
  path, so the path must come from `Document.relative_path` — unlike TypeScript,
  where the moniker reconstructs it;
- JDK symbols carry a non-`.` package (`semanticdb maven jdk 17 java/lang/String#`)
  and are already excluded as external by the existing rule.

**Resolution — owner decision 2026-09-05.** Variant C's claim that "the exact
tier adds `signature_precision=typed` with a fully-qualified, type-only
`signature_key`" is **invalidated for Java**. The Java exact tier commits:

```clojure
{:arity n :signature_precision "arity_only" :signature_key nil :ordinal nil}
```

The native symbol, the `+N` disambiguator, and the raw signature documentation
are evidence only. Reconstructing FQ types from occurrence layout was considered
and rejected as guessing, which the plan forbids. Variant C's two-layer model
(core key + optional refinement) is unchanged and still correct; only the premise
that a Java provider can supply the refinement is withdrawn.

The fixture is corrected and the scip-java spelling is now ground truth. The
`java-lsp` spelling was **lowered** to the same `arity_only` floor rather than
left asserting a typed capability: it is still unverified, and asserting an
unverified exact-tier capability is precisely the mistake this finding repairs.
Stage 5 may raise it only against real jdtls output.

## Finding S2 (High) — same-arity overloads silently merge into a false exact fact

Raised by the owner against the handoff's claim that same-arity overloads "fall
into the already-implemented F1a rule". The claim was wrong, and the defect was
reproduced.

`arbitrate-facts` partitions a core-key group by distinct typed `signature_key`s.
The F1a split and its `:arity_ambiguous_heuristic` diagnostic fire only when the
group has **two or more** distinct typed signatures. With every Java exact fact
now at `arity_only`, a group of same-arity overloads has **zero** typed
signatures, so the common-case branch runs.

Reproduced with two arity-1 facts on `example.OrderService#handle`, both
`arity_only`, native symbols `handle().` and `handle(+1).`:

```text
canonical facts: 1
diagnostics:     []
authority:       exact
```

Two genuinely distinct overloads collapsed into one canonical fact at exact
authority with no diagnostic — a false exact identity.

**Resolution — owner decision 2026-09-05.** This is a hard Stage 4 requirement,
recorded in the plan's Stage 4 exit criteria and specified in the fixture's new
`same_arity_overloads_must_not_silently_merge` section. The Java adapter must
detect an all-`arity_only` group sharing
`(language, path, owner, symbol, arity)`, never emit one exact canonical fact for
it, emit a diagnostic naming the group, and withhold the exact contribution so
the lower tiers supply the units.

The protected corpus has no same-arity overload pair (`handle` is arity 1 and
arity 2). Adding one would change the Stage 0 extraction baseline, so the case is
specified and will be tested synthetically. The fixture section is marked
`specification_for_stage_4` and is executed by the adapter slice, not by this
one — an unexecuted golden is not a golden.

## Toolchain decision (owner, 2026-09-05): external process, repo-managed

`scip-semanticdb` is a small Java library (3 dependencies) with no CLI entry
point; the full `scip-java` CLI is a heavy Scala artifact pulling coursier and an
embedded Kotlin compiler. Two options were weighed: add `scip-semanticdb` to
`deps.edn` and call it in process, or install pinned jars into a gitignored
toolchain directory and invoke `javac`/`java` as external processes.

**External process, repo-managed, was chosen.** It mirrors ADR-047 and the
Stage 3 TypeScript pattern, keeps Sourcegraph's libraries and their
protobuf-java out of the semidx runtime classpath (the runtime already carries
protobuf 3.25.1 via gRPC; `scip-semanticdb` declares 3.15.6), keeps Java SCIP
indexing an external provider toolchain rather than part of the core runtime, and
gives a clean failure mode: toolchain missing or failing -> provider unavailable
diagnostic -> fallback to existing extraction.

## Deliverables

| Artifact | Change |
| --- | --- |
| `fixtures/provider-authority/identity/java-overload-canonical-key.json` | scip-java spelling corrected to ground truth; new `scip_java_verified_contract` section; `java-lsp` lowered to the unverified `arity_only` floor; expected merged key now `arity_only`; per-overload `scip_java_ground_truth` added to each distinct fact; new `same_arity_overloads_must_not_silently_merge` specification; normalization obligations rewritten |
| `plans/018_semantic_provider_authority_migration_plan.md` | `CanonicalFactKey` contract example annotated as illustrative-not-Java; Stage 4 gains the amendment, the external-toolchain deliverable, and the same-arity exit criterion |
| `reports/024` (this section) | findings S1 and S2, both owner decisions, and the evidence |

## Verification

- `clojure -M:test` targeted `semidx.runtime.fact-arbitration-test`: **22 tests,
  114 assertions, 0 failures** — the corrected fixture is still executed green as
  a golden, confirming the correction is consistent with the kernel.
- JSON validity of the corrected fixture: pass (`python3 -m json.tool`).
- Ground truth captured from real tool output via the project nREPL, not seeded:
  every claim in `scip_java_verified_contract` is read from the `.scip` artifact
  produced in this session.
- S2 reproduced in the REPL against the committed kernel before it was written up.
- **Not run**: full suite and contract validation — this session changed no
  source; they run with the adapter slice.

## NextStageRoutingRecommendation

```text
completed_stage: 4 preflight — Java SCIP toolchain proven end to end, symbol
  grammar verified, Stage 0 fixture corrected, Variant C invalidated for Java,
  same-arity false-identity defect reproduced and specified
recommended_next_stage: Stage 4 continued — repo-managed external Java SCIP
  toolchain (setup script + pinned jars + committed driver + corpus .scip
  fixture), the language bridge refactor in scip-normalize, the Java SCIP
  provider adapter, and the same-arity ambiguity guard
recommended_executor: Claude Code team lead, no Fable, no Explore agent
recommended_model: Claude Opus for the same-arity guard and the language-bridge
  refactor (identity-critical); Sonnet for the setup script, driver, and fixture
  plumbing
effort: high
effort_justification: the identity questions are now settled on evidence, but the
  same-arity guard is a correctness gate against a reproduced false-exact-identity
  defect, and the bridge refactor must factor TypeScript-specific path
  reconstruction out of scip-normalize without regressing the TypeScript goldens.
rationale: both blocking contract questions are answered and recorded, and the
  toolchain is proven runnable on this machine, so the remaining work is bounded
  implementation against a now-truthful specification.
prerequisites_or_blockers:
  - scip-normalize hard-codes "typescript" and reconstructs the path from the
    moniker; Java needs the path from Document.relative_path and an owner/symbol
    bridge over package+class descriptors. parse-scip-symbol needs NO change: it
    already parses the Java grammar including the +N disambiguator and the
    backtick-escaped <init>.
  - the same-arity guard must be executed against the fixture's
    same_arity_overloads_must_not_silently_merge section, which is currently
    specification_for_stage_4 and not yet a golden.
  - arity must be parsed from signature_documentation.text; a method whose arity
    cannot be determined must degrade, not guess.
  - still shadow/default-off; no default-path wiring until Stage 6.
file_ownership_and_conflict_risk: LOW-MEDIUM. The next slice adds a setup script,
  a committed Java driver, a Java corpus .scip fixture, a Java branch in the SCIP
  normalization boundary, an adapter, and tests. The shadow-comparison harness is
  language-neutral and needs no change.
fallback_executor_or_model: Sonnet for toolchain and fixture plumbing; not for
  the same-arity guard.
model_availability_checked_at: not checked this session.
confidence: high (both contract questions resolved on verified evidence)
```

---

# Stage 4 — Java SCIP Vertical Slice: Implementation (2026-09-05)

Continues the preflight above, which settled both contract questions before any
code was written. Delivered in three commits, each verified before the next.

## Delivered

| Artifact | Role |
| --- | --- |
| `scripts/scip-java-toolchain/dependencies.txt` | eight jars pinned by coordinate and sha256, with the rationale for each |
| `scripts/scip-java-toolchain/ScipJavaIndexer.java` | committed driver; `scip-semanticdb` ships no CLI entry point. Also owns the opt-in `--scrub-project-root` step, so Java fixture regeneration does not depend on the TypeScript toolchain's JS scrubber |
| `scripts/setup-scip-java.sh` | downloads and digest-verifies every jar, then compiles the driver; fails closed on mismatch, replaces a drifted cache |
| `scripts/scip-java-corpus-snapshot.sh` | regenerates the fixture, re-verifies the pin, refuses to keep an artifact containing a host path |
| `fixtures/provider-authority/scip/java-corpus.scrubbed.scip` | real scip-java output over the protected corpus, `project_root` scrubbed |
| `src/semidx/runtime/providers/scip_normalize.clj` | language **bridges** as data; the Java bridge plus `signature-arity` and `java-index-context` |
| `src/semidx/runtime/providers/scip_adapter.clj` | the shared, language-neutral adapter boundary, including the overload guard |
| `src/semidx/runtime/providers/scip_java.clj` | the Java adapter: toolchain resolution, the two subprocesses, entry points |
| `src/semidx/runtime/fact_arbitration.clj` | `:native_details` passthrough on `FactEvidence` |
| `test/.../scip_java_test.clj`, `scip_normalize_test.clj` | 13 + 6 new tests |

## The bridge seam

`scip-normalize` hard-coded TypeScript: it reconstructed the source path from
the moniker and stamped `"typescript"` on every fact. The language-specific part
is now a bridge — plain data in `bridges`, one per language — while
`parse-scip-symbol` stays language-neutral. That was verified, not assumed: the
existing parser already handles the Java grammar including the `+N`
disambiguator and the backtick-escaped `<init>`, so it needed no change.

The seam exists because the two indexers disagree about where identity lives. A
TypeScript moniker reconstructs the file path; a Java moniker carries only the
package. The Java bridge therefore builds a `symbol -> {path, arity}` table from
every document's `SymbolInformation` in one pass, which is also what makes a
**cross-file reference key on the defining file** rather than the referencing
one — verified on the corpus, where `Validator#validate` referenced from
`OrderService.java` keys on `src/example/Validator.java`.

Java specifics:

- owner and symbol reproduce the heuristic lane exactly
  (`example.OrderService#handle`, owner `example.OrderService`), checked against
  real `semidx.runtime.languages.java` output before the bridge was written. All
  four corpus units produce **byte-identical `canonical_fact_key_id`s across
  SCIP and regex**, both overloads included.
- `overload_identity` is `{:arity n :signature_precision "arity_only"
  :signature_key nil}`; the `+N` disambiguator and the signature documentation
  ride as evidence.
- arity is parsed from `signature_documentation.text` with depth-aware
  splitting, so a comma inside `List<String>` or
  `Map<String, List<Integer>>` is not a separator.
- constructors are translated from `<init>` to the class-name spelling the regex
  lane uses (`example.OrderService#OrderService`), rather than dropped as the
  TypeScript bridge drops them.
- degrade instead of guess: a symbol the index does not declare, a method with no
  readable arity, and a **nested type** are all `:unmapped` with a reason.
  Nested types are excluded deliberately — inventing an outer/inner owner
  spelling that has not been verified against the heuristic lane would split
  identity silently rather than merge it.

## The same-arity guard (Stage 4 exit criterion)

`withhold-ambiguous-arity-only-overloads` in the shared boundary. A group is
ambiguous when two or more of its definition facts share a core key, are
`arity_only`, and were spelled with different provider-native symbols. Every
fact in such a group is withheld — references included, since a reference cannot
be attributed either — a diagnostic names the symbol and both native spellings,
and `coverage.complete` goes false.

Verified as a contrast, which is what makes it self-documenting:

| | facts emitted | authority | diagnostics |
| --- | --- | --- | --- |
| guard on | **0** (2 withheld) | — | `same_arity_arity_only_overload_ambiguous` naming both native symbols |
| guard off | **1** | `exact` | **none** |

The guard-off row is the reproduced defect and is kept executable as a test, so
the guard cannot be quietly removed. The protected corpus has no same-arity pair
(`handle` is arity 1 and arity 2) and adding one would change the Stage 0
extraction baseline, so the case is supplied synthetically, exactly as the
fixture specifies. A separate test asserts the guard does **not** fire on the
real corpus.

## Evidence contract gap found while testing

`normalize-fact-evidence` built a fixed map and silently dropped any key it did
not know, including the native overload detail the owner required to be carried
as evidence. It gains `:native_details`, a single opaque passthrough map omitted
when empty. It is deliberately one slot rather than named fields such as
`:native_disambiguator`, so a language cannot leak its own vocabulary into the
language-neutral kernel. Existing evidence keeps its previous shape.

## Exit criteria check (plan Stage 4)

- **No TypeScript-specific rule enters the shared SCIP adapter boundary** — met:
  `scip-adapter` is language-neutral and the TypeScript adapter was rewritten to
  delegate to it; its tests are unchanged and green.
- **Same-arity overloads never produce a false exact identity** — met, with the
  contrast above.
- **Provider absence and stale artifacts degrade cleanly** — met: a missing
  toolchain is `:unavailable`, a partial install is treated as no install, a
  compile failure is `:failed` with the compiler output, and the document-level
  stale gate drops missing or mismatched documents. All four tested.
- **Java state-invariant facts preserve stable relation identity while gaining
  richer evidence** — **not applicable to this slice and deliberately not
  claimed.** The adapter emits unit facts only; no relation facts are produced,
  so the plans/017 `structure/declares-field` and `dataflow/writes-field`
  relations are untouched and their identity is trivially preserved. Emitting
  Java relations from SCIP would need a `Relationship` payload the corpus does
  not contain.

## Deferred (named honestly)

- **Nested types** — `:unmapped :nested-type-symbol`, pending ground truth on
  how the heuristic lane spells a nested-class owner.
- **`Relationship` / implementations facts and `call/*` relations** — none in the
  corpus; no `call/*` relation type exists. Unchanged from Stage 3.
- **Java-specific coverage the plan lists but the two-file corpus cannot
  exercise**: inheritance, static imports, method references, entity fields, and
  field-write relations. The corpus has no superclass, no static import, no
  method reference, and no entity class. Extending the protected corpus changes
  the Stage 0 baseline, so this needs its own decision rather than being smuggled
  into this slice.
- **Catalog / planner integration** of a `:scope :project` provider — unchanged
  from Stage 3.
- **Latency and storage metrics for the Java run** — the harness measures them
  for TypeScript via `shadow-report`; the Java path is compared through
  `compare-scip-run` but not timed.

## Verification

- `clojure -M:test`: **530 tests, 3053 assertions, 0 failures, 0 errors**
  (503 -> 510 -> 517 -> 530 across this session's commits).
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files,
  at every commit.
- Toolchain: clean-checkout install from scratch; idempotent re-run; a corrupted
  cached jar is refetched; an unsatisfiable pin fails closed with
  `sha256_mismatch`; the snapshot is byte-stable across reruns and under macOS
  system bash 3.2.
- The committed Java artifact reads back through `semidx.runtime.scip/read-index`
  with an empty `project_root`, and the end-to-end toolchain run produces the
  same facts as the committed fixture.
- Cross-tier parity checked twice: per-symbol in the REPL before the bridge was
  written, and at corpus scale through the shadow-comparison harness, which
  needed no change to diff Java.
- `git diff --check` clean; English-only scan of every new and edited file clean.
- **Not run** (gate later stages that change extraction): semantic-quality
  report, protected retrieval replay, snapshot-diff parity, PostgreSQL round
  trips. This slice changes no extraction and persists nothing.

## NextStageRoutingRecommendation

```text
completed_stage: 4 — Java SCIP vertical slice (preflight, toolchain, language
  bridges, adapter, same-arity overload guard)
recommended_next_stage: OWNER DECISION between two candidates, not an automatic
  start:
  (a) Stage 5 — LSP live overlay, the plan's next stage; or
  (b) a consolidation slice: catalog/planner integration of the two
      project-scoped SCIP providers, plus the Java corpus extension the Stage 4
      deferrals name (inheritance, static imports, method references, entity
      fields).
recommended_executor: Claude Code team lead, no Fable, no Explore agent
recommended_model: Claude Opus for Stage 5 (live freshness, cancellation, and
  deterministic batch behaviour interact, and the java-lsp identity claim is
  still unverified); Claude Sonnet for the consolidation slice
effort: high for Stage 5; medium for consolidation
effort_justification: Stage 5 introduces a stateful external process and the
  first evidence whose freshness changes under the user's hands, and it must
  re-verify the java-lsp spelling this session deliberately lowered to the
  arity_only floor. The consolidation slice is bounded work over proven seams.
rationale: the SCIP seam is now proven for both languages against real tool
  output, with cross-tier key parity and an explicit guard against the one
  identity failure the arity-only tier makes possible. Two structural debts are
  outstanding and neither blocks Stage 5, but both grow with each new provider:
  the project-scoped providers are still standalone entry points, and the Java
  corpus cannot exercise half the coverage Stage 4 nominally owns.
prerequisites_or_blockers:
  - Stage 5 must NOT assume the java-lsp typed-signature capability. The fixture
    now holds it at the arity_only floor precisely because an unverified exact
    claim is what Stage 4 had to repair; raising it requires real jdtls output.
  - if the corpus is extended, it changes the Stage 0 extraction baseline and
    the protected-behaviour fixtures must be regenerated in the same commit.
  - still shadow/default-off; no default-path wiring until Stage 6.
file_ownership_and_conflict_risk: LOW. Stage 4 touched only the SCIP provider
  namespaces, the arbitration evidence shape (additive), the Java fixtures, and
  the toolchain scripts. Stage 5 would add an LSP client and adapter; the
  consolidation slice would touch providers.clj and provider-selection.
fallback_executor_or_model: Sonnet for toolchain and fixture plumbing; not for
  LSP freshness or identity decisions.
model_availability_checked_at: not checked this session.
confidence: high for the delivered slice; the next-stage choice is the owner's
```

## Review Repair (2026-09-05, Stage 4)

External review of the Stage 4 slice raised one High and one Medium finding.
Both were reproduced before being fixed, and both turned out to be worse than
reported.

| ID | Severity | Finding | Resolution |
| --- | --- | --- | --- |
| S3 | **High** | `signature-arity` took the **first** `(` in `signature_documentation` as the Java parameter list, but scip-java prefixes the declaration with its annotations. An annotation with arguments is read instead of the parameter list, so an exact fact can land on the wrong arity bucket or collide with another overload. | **Fixed.** The parameter list is now located from the declaration name. |
| S4 | Medium | The stale gate resolved `Document.relative_path` against the project root without validating it, so a `..` segment anchored exact evidence to a file outside the project. | **Fixed.** Unsafe paths are `:invalid`, dropped unread, and reported. |

### S3 — verified, and wrong in both directions

The review reported an under-count. A scratch project run through the real
toolchain showed the error also over-counts, which is worse: it silently moves a
fact onto an arity bucket that a *different* overload legitimately owns.

| real signature documentation (scip-java 0.12.3) | real arity | old result |
| --- | --- | --- |
| `@Ann(x = 1)` + `public void f(String a, int b)` | 2 | **1** |
| `@Ann(x = 2, note = "two")` + `public String g(String a)` | 1 | **2** |
| `@Ann(x = 3)` + `public Annotated(String a, int b, int c)` | 3 | **1** |
| `public void plain(String a, int b)` (no annotation) | 2 | 2 |

`signature-arity` now takes the declaration name, matches it at an identifier
boundary, requires a directly following `(`, and takes the **last** such match —
everything that can repeat the name (annotations, the return type) precedes the
declaration, so the last match is the declaration's own. Constructors are
located by the class name, which is what the signature text repeats where the
symbol says `<init>`; `java-index-context` derives that name from the parsed
symbol. When the list cannot be located the function returns nil and the caller
degrades to `:arity-unavailable`, per the plan's rule that a canonical key is
never guessed.

All four probe cases are correct after the fix (2, 1, 3, 2).

### S4 — verified, and it threw rather than degraded

Two behaviours, both reproduced:

```text
"../typescript/src/orders.ts" -> {:state :fresh :content_digest "sha256:ef1db2..."}
"/etc/hosts"                  -> IllegalArgumentException: not a relative path
```

The first anchored exact evidence to a file in the *TypeScript* corpus, outside
the Java project root. The second took the whole run down instead of degrading —
a SCIP artifact is an external input and must never be able to do that.

`document-path-problem` now rejects blank, absolute, Windows-drive, and any
`..`-segment path, and a canonical containment check follows as defence in depth
against symlinks, which need no `..` to escape. Such a document is `:invalid`,
is dropped **without being read**, and surfaces as `:scip_document_path_invalid`
with its reason. It is tracked in a new `coverage.invalid_documents` field
rather than folded into `stale_documents`, because an unsafe path is a different
condition from a stale one. `workspace-digest` no longer throws.

### Verification

- `clojure -M:test`: **533 tests, 3077 assertions, 0 failures, 0 errors**
  (was 530 / 3053; +3 tests).
- Regression tests use the signature text captured from the real toolchain, and
  cover the adversarial case where the annotation repeats the method name
  and an unbalanced paren group.
- `./scripts/validate-contracts.sh`: ok, 72 files. `git diff --check` clean.
  English-only clean.
- `./scripts/scip-java-corpus-snapshot.sh` regenerates the committed fixture
  byte-identically after the change.
