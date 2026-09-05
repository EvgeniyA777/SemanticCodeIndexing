---
title: "Capability Self-Description Plan"
doc_type: "implementation_plan"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
---

# Implementation Plan: Capability Self-Description

Make semidx a self-describing service: any client (MCP, HTTP, gRPC, library)
can discover, **before doing any work**, exactly which languages are supported
and at what semantic strength — from a single versioned capability contract
derived from one source of truth. No client should ever need to read server
source or rely on model memory to answer "what does this indexer support?".

## North Star

> A cold agent, on first contact, calls one preflight surface (`health` /
> `capabilities`) and knows the supported-language set, per-language semantic
> strength/confidence ceiling, file extensions, and provider identity — with a
> capability version it can reason about. Language choice and
> `no_supported_languages_found` handling become informed, not guessed.

## Problem Statement (evidence, not memory)

- `health` (`src/semidx/mcp/core.clj:1021`) returns only
  `status / server_name / server_version / session_id / uptime_ms / index_count`
  — no language capability, despite its own description telling clients to call
  it "before starting a workflow".
- `supported_languages` exists only **inside a per-index create_index/repo_map
  result** (`core.clj:414-416`), i.e. only *after* indexing — useless for
  preflight language selection.
- `create_index.language_policy` schema is a free object with **no `enum`** of
  valid languages, so `tools/list` documents nothing.
- The server exposes **no MCP resources** (`resources/list` is empty).
- **Two drifting sources of truth** for languages:
  1. `runtime/language_registry.clj` → `language-lanes` (9 languages with
     extensions + provider).
  2. `runtime/retrieval_policy.clj:64` → `language-strength-profile` (only 6
     languages explicit: clojure=high; elixir/java/python=medium;
     typescript/javascript=low; everything else silently defaults to `low`, so
     lua/html/css have an *implicit* ceiling never stated anywhere).

The real defect is architectural: capability is not machine-discoverable and
language strength is duplicated. An agent saying "I don't know" was the correct
honest fallback — the fix is to make the server declare its capabilities.

## Design Principles (product-grade)

1. **Single source of truth.** Language strength/ceiling becomes an attribute on
   the registry lane record. `retrieval_policy` *consumes* the registry; the
   duplicated `language-strength-profile` map is deleted. No drift by
   construction.
2. **Contract-first.** Capabilities are a first-class, bounded, versioned
   contract: `contracts/schemas/capabilities.schema.json` + `malli` mirror +
   `contracts/examples/`, validated like every other public output.
3. **Self-describing across all surfaces.** Expose capabilities consistently on
   MCP, HTTP, gRPC, and library, per the RULES invariant "keep MCP, library,
   HTTP, and gRPC behavior aligned".
4. **Preflight availability.** Capabilities resolve **without** indexing, so
   clients choose `language_policy` and handle `no_supported_languages_found`
   intelligently.
5. **Explicit versioning.** The payload carries a `capability_version` so clients
   can evolve without breakage.
6. **Additive / backward-compatible.** Every change is additive; no existing
   field or shape is removed. Enrichment of `health` is purely additive.
7. **Documented + observable.** ADR captures the decision; agent-facing docs and
   prompts tell agents to discover before acting; mirrored tests + cross-surface
   parity guard it.

## Non-Goals

- No new language lanes (that is `scripts/new-language-adapter.sh` / plan 013).
- No confidence-ceiling *re-tuning* (values are surfaced as-is from the
  authoritative source; any recalibration is a separate, evidence-backed change).
- No online capability *mutation* API (control-plane is plan 013 / gap 4).

## Relationship to Other Plans

Independent of and smaller than `plans/013`. It directly improves the
`no_supported_languages_found` user-guidance path named in `RULES.md` →
*MCP Failure Protocol*, and it should land before the `plans/013` adapter split
churns the registry, so the single-source-of-truth consolidation happens first.

## Per-Stage Delivery Loop (applies to every stage)

Each stage: **map (semidx MCP first) → implement → verify (narrowest gate first)
→ commit (explicit paths) → review (`/code-review` + stage-gated external
reviewer loop) → fixes → docs update → push (sequential)**. Record each stage in
the companion progress log `reports/010_capability_self_description_progress_log.md`.
ADR numbers are chosen at execution time by scanning `adr/` for the next free
number (033+).

---

## Execution Handoff (implementing agent)

This plan is written to be executed by an external agent (Antigravity), i.e. a
harness other than the one that authored it. Before touching code the executing
agent MUST:

1. Read `RULES.md` in full and treat it as the single source of truth for
   workflow, especially: *Mandatory CCC Bootstrap*, *MCP-First Workflow*,
   *Clojure Editing Rules*, *Testing And Verification*, *Git Workflow*,
   *Documentation Rules*, *Plan Execution Progress Logs*, *Review Response
   Format*.
2. Use the semidx MCP server for code exploration first
   (`create_index → repo_map → resolve_context → fetch_context_detail`); fall
   back to file reads only when MCP fails, and say so.
3. Follow the Per-Stage Delivery Loop above for every stage, in order. Do not
   batch stages, do not reorder, do not merge unrelated changes.
4. Stage only explicit paths in git; never `git add .` / `-A`. Commit and push
   only after that stage's gates and review are green. Keep the progress log in
   the same commit as the stage when practical.
5. Surface any conflict between this plan and `RULES.md` instead of resolving it
   silently.

---

## Review Protocol (per stage, mandatory)

Reviews are governed, two-tier, and **separated from implementation**. The agent
that wrote a stage's diff is never the sole approver of that diff.

### Tier 0 — Objective gates (must be green *before* any review)

Non-negotiable, machine-checked, blocking:

- `clojure -M:test` passes (auto-discovered suites).
- `./scripts/validate-contracts.sh` passes for any stage touching
  `contracts/` (Stages 2, 3, 4).
- **Behavior-parity gate** for Stage 1: `./scripts/run-benchmarks.sh` and
  `./scripts/run-semantic-quality-report.sh` outputs are byte-identical to the
  pre-stage baseline captured before the stage started. Any diff = stop.
- `./scripts/run-mvp-gates.sh` green before a stage is declared done.

If a Tier 0 gate cannot run in the executing environment, that is a blocker to
record — do not proceed on assumption.

### Tier 1 — Cross-agent code review (separation of duties)

After Tier 0 is green, the stage diff goes to a reviewer that is **a different
agent/model from the implementer**:

- Primary: run a structured diff review (e.g. Claude Code `/code-review`) on the
  stage diff, scaled by risk (see effort scaling below).
- Escalation for public-contract stages (2, 3, 4): also run the stage-gated
  external reviewer loop captured in
  `notes/2026-07-13-stage-gated-external-reviewer-loop.md`, or a deep multi-agent
  review, because these stages change the external contract / tool schemas / edge
  parity.

**Effort scaling by stage:**

| Stage | Risk | Minimum review effort |
| --- | --- | --- |
| 1 (single source of truth) | medium — silent-behavior risk | `/code-review medium` + parity gate |
| 2 (capability contract) | high — new public contract | `/code-review high` + external reviewer |
| 3 (MCP self-description) | high — tool schema + `tools/list` | `/code-review high` + external reviewer |
| 4 (HTTP/gRPC/library parity) | high — cross-surface contract | `/code-review high` + parity assertions |
| 5 (docs/ADR/CCC) | low | `/code-review low` |

### Review scope checklist (what reviewers must confirm)

- **No silent behavior change**, especially Stage 1: explicit lua/html/css
  `strength` equals today's implicit `low`; retrieval ceilings unchanged.
- **Single source of truth actually holds**: no duplicated language-strength map
  reintroduced; `retrieval_policy` reads the registry.
- **Contract conformance**: capability payload validates against
  `capabilities.schema.json`; `malli` mirror matches JSON Schema; payload is
  bounded.
- **Cross-surface identity** (Stage 4): MCP, HTTP, gRPC, and library return an
  identical capability payload.
- **Schema/handler agreement** (Stage 3): `tools/list` output and the runtime
  handler behavior agree (RULES invariant); `language_policy` enum matches the
  registry.
- **Additive/backward-compatible**: no removed field or shape; `health`
  enrichment is purely additive.
- **Scope discipline**: no changes beyond the stage's declared scope.
- **Honesty**: no fabricated ceiling values — every strength/ceiling traces to
  the authoritative source, not model memory.

### Recording findings (RULES Review Response Format)

Every review records, in the progress log for that stage: findings ordered by
severity, each with issue title, why it matters, file/line evidence, and the
smallest reasonable fix; then the disposition of each finding
(`accepted` / `rejected` / `deferred` / `fixed`) with rationale; then the fix
summary + changed files/commit hash + re-verification result. A stage does not
advance until all `accepted` findings are `fixed` and re-verified.

---

## Recommended Models

Model choice matters because the stages differ sharply in difficulty. The
implementer and the reviewer should be **different models** for a genuine
cross-check.

> Model catalog offered by Antigravity CLI 1.1.1 for this account: Gemini 3.5
> Flash (Low / Medium / High), Gemini 3.1 Pro (Low / High), Claude Sonnet 4.6
> (Thinking), Claude Opus 4.6 (Thinking), GPT-OSS 120B (Medium). The picks below
> map to these concrete options; if the catalog changes, fall back to the
> class-based rule — top reasoning tier for Stages 1–4, never a Flash tier.

### Implementer (Antigravity)

Stages 1–4 are reasoning-heavy: a behavior-parity refactor, contract design, MCP
tool-schema surgery, and cross-surface wiring.

- **Primary: `Claude Opus 4.6 (Thinking)`** — strongest reasoning/coding option
  in the catalog; best fit for the parity refactor (Stage 1) and the contract
  design (Stage 2), where a weaker model silently drifts behavior or
  under-specifies the schema.
- **Alternative: `Gemini 3.1 Pro (High)`** — use if you prefer to stay on Gemini
  or to cross model families with a Claude reviewer (see below).
- **Do not use any `Gemini 3.5 Flash` tier for Stages 1–4**, including the
  session's current `Gemini 3.5 Flash (High)`. Switch the model **before Stage 1**.
- Stage 5 (docs/ADR/CCC) is mechanical: `Gemini 3.5 Flash (High)` or
  `(Medium)` is acceptable there.

### Reviewer (separate from implementer, cross-family)

Cross model families (Gemini ↔ Claude) so the review is an independent check, not
the same family grading itself.

- If implementer = `Claude Opus 4.6 (Thinking)` → review with
  `Gemini 3.1 Pro (High)` **and/or** the separate Claude Code `/code-review`
  session (a different harness entirely).
- If implementer = `Gemini 3.1 Pro (High)` → review the public-contract stages
  (2, 3, 4) with an Opus-class Claude via Claude Code `/code-review high` (or its
  deep multi-agent `ultra` review) — a shipped schema/contract defect is
  expensive to walk back across four surfaces.
- `GPT-OSS 120B (Medium)` can add an independent third-voice review but should
  not be the sole/primary reviewer of a contract stage.
- Any `Gemini 3.5 Flash` tier is too light to be a primary reviewer for
  Stages 1–4.
- The reviewer must have repo + diff access and must run the Tier 0 gates itself
  rather than trusting the implementer's report.

### Rationale

Separation of implementer and reviewer models is standard practice in mature
review systems: it removes the shared-blind-spot failure mode where one model
both writes and blesses a subtly wrong contract. Pairing a strong implementer
with a strong, *different* reviewer on the high-risk contract stages is the
cheapest insurance against a bad public surface.

---

## Stage 1 — Single Source of Truth for Language Strength

**Goal:** One authoritative place declares each lane's semantic strength; zero
behavior change.

**Scope:**
- Extend each `language-lanes` record in `runtime/language_registry.clj` with a
  `:strength` (`"high" | "medium" | "low"`) attribute, making the currently
  implicit `low` for lua/html/css **explicit and reviewed**.
- Add a registry accessor (e.g. `strength-for-language`) + a derived
  `language-strength-profile` view.
- Refactor `runtime/retrieval_policy.clj` to consume the registry accessor;
  delete the local `language-strength-profile` map. Keep the same default
  semantics for unknown languages.

**Verify:** `clojure -M:test`; `./scripts/run-benchmarks.sh` and
`./scripts/run-semantic-quality-report.sh` must be byte-identical to the
pre-change baseline (capture baseline first) — this is a pure refactor.

**Docs:** MEMORY.md (single-source-of-truth note); ADR entry can be deferred to
Stage 2 where the contract lands.

**Risk:** low. Mitigation: benchmark/quality parity is the gate; explicit
strengths for lua/html/css must equal today's implicit `low` (no silent change).

---

## Stage 2 — Versioned Capability Contract + Projection

**Goal:** A first-class, bounded, versioned capabilities payload derived from the
registry.

**Scope:**
- Author `contracts/schemas/capabilities.schema.json`: `capability_version`,
  `server { name, version }`, `languages [ { language, extensions[], provider,
  strength, confidence_ceiling } ]`, plus `language_policy` allowed-values.
  Keep it bounded and contract-valid.
- Add the `malli` mirror in `src/semidx/contracts/schemas.clj` and a sample in
  `contracts/examples/`.
- Add `src/semidx/runtime/capabilities.clj` that projects `language_registry`
  (now strength-bearing) into the contract shape. This is the one function every
  surface calls.

**Verify:** `./scripts/validate-contracts.sh`; new mirrored
`test/semidx/runtime/capabilities_test.clj` (schema-valid output, all 9 lanes
present, versioned).

**Docs:** ADR "Expose a versioned capability self-description contract"; MEMORY.md
Known Gaps (capability discoverability line); begin roadmap-status update.

---

## Stage 3 — MCP Self-Description

**Goal:** MCP clients discover capabilities preflight and see valid languages in
the schema itself.

**Scope:**
- **Enrich `health`** (`tool-health`) additively with `supported_languages`
  (language + extensions + strength/ceiling) and `capability_version`, sourced
  from `runtime/capabilities.clj`.
- **Add a dedicated `capabilities` tool** returning the full contract payload.
- **Add `enum` + descriptions** to `create_index.language_policy` in the tool
  input schema so `tools/list` documents valid languages.
- Advertise capability availability in the `initialize` result where it fits the
  MCP capabilities shape.

**Verify:** assert both the machine-readable `tools/list` output **and** runtime
handler behavior (RULES invariant); `clojure -M:mcp` smoke with an `initialize`
+ `tools/call health` + `tools/call capabilities` handshake; mirrored MCP tests
under `test/semidx/mcp/`.

**Docs:** `docs/mcp-api.md` (new tool + enriched health + language_policy enum);
`docs/mcp-agent-prompts.md` (agents call `health`/`capabilities` before a
workflow and choose `language_policy` from the advertised set).

---

## Stage 4 — HTTP / gRPC / Library Parity

**Goal:** Same capability surface on every edge; nothing surface-specific.

**Scope:**
- Library: a public `capabilities` fn in `semidx.core`.
- HTTP: a `GET /capabilities` (and/or method) on `runtime/http.clj`, reusing the
  projection; keep error taxonomy + correlation headers consistent.
- gRPC: mirror on `runtime/grpc.clj` with the dedicated envelope + error
  trailers.

**Verify:** HTTP/gRPC conformance tests (`semidx.runtime-http-test`,
`semidx.runtime-grpc-test`) assert identical capability payloads across surfaces;
`clojure -M:runtime-http` / `-M:runtime-grpc` smoke.

**Docs:** `docs/runtime-api.md` (HTTP + gRPC capability surfaces + parity note).

---

## Stage 5 — Docs, ADR Close-Out, Roadmap, CCC

**Goal:** Durable decision recorded; agent guidance updated; artifacts fresh.

**Scope:**
- Finalize the ADR (decision, alternatives considered: enum-only vs health
  enrichment vs dedicated tool vs MCP resource — and why "all four, one source").
- `MEMORY.md`: flip the discoverability gap; note single-source-of-truth and the
  capability contract as an invariant.
- `docs/roadmap-status.md`: record capability self-description as delivered.
- Refresh CCC (`clojure -M:ccc check --root .`, regenerate if stale).
- Close the progress log with per-stage verification + review disposition.

**Verify:** full `clojure -M:test`, `./scripts/validate-contracts.sh`,
`./scripts/run-mvp-gates.sh` green.

---

## Success Criteria / Exit

- A cold client learns supported languages + per-language strength/ceiling +
  extensions + `capability_version` **before** any `create_index`, via at least
  MCP `health`/`capabilities` and the `create_index.language_policy` enum.
- Exactly **one** source of truth for language strength; the duplicated
  `retrieval_policy` map is gone; benchmark/quality parity holds.
- Capability payload is schema-validated and identical across MCP, HTTP, gRPC,
  and library.
- `RULES.md` failure-protocol `no_supported_languages_found` can be answered by
  the agent with a concrete supported-language list.
- ADR + docs + `docs/mcp-agent-prompts.md` tell future agents to discover before
  acting, closing the "agent must guess" gap for good.
