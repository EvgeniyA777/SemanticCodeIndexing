# semidx Features

This file is the root feature ledger for semidx. It tracks what exists, what is
in an active plan, and what remains concept or research input.

For recommended sequencing, see
[`docs/development-strategy.md`](docs/development-strategy.md).

It is intentionally a reference document:

- not a changelog;
- not an execution plan;
- not a replacement for ADRs, specs, or numbered plans.

Detailed implementation sequencing belongs in numbered plans after
prioritization. Release history belongs in a changelog if the project adds one.

Status values:

- `implemented` - available in the current codebase for the stated scope.
- `in_progress` - active plan or progress log exists and implementation is
  underway.
- `planned` - accepted or active plan exists, but the feature is not yet
  source-implemented.
- `concept` - idea-stage input; needs owner prioritization before planning.
- `research` - exploratory direction; validate before committing product work.

Priority values:

- `F` - implemented foundation to preserve and extend carefully.
- `P0` - current proof gate.
- `P1` - next high-value product or integration work.
- `P2` - later product expansion after core proof.
- `P3` - opportunistic deepening after repeated real misses.
- `P4` - performance or native-runtime experiment after measured need.
- `R` - separate research track.

## How To Use This File

For each feature, this file should answer five questions:

1. What is the feature?
2. Is it implemented, planned, concept, or research?
3. What is its current priority band?
4. Which document owns the detailed decision or plan?
5. What is the next decision gate?

Keep entries short enough to scan. Put deep rationale in ADRs, detailed
execution in `plans/`, and historical context in `notes/` or `reports/`.

## Feature Summary

| Priority | Feature | Status | Source | Next Gate |
| --- | --- | --- | --- | --- |
| F | Compact-first staged retrieval | implemented | `plans/002`, ADR-024 | Preserve as canonical unless a new ADR changes the default. |
| F | Semantic code graph | implemented | ADR-038, ADR-039, ADR-040 | Keep new graph semantics on typed relations. |
| F | Multi-language lanes | implemented | ADR-022, onboarding docs | Improve lanes by evidence, not language-count expansion. |
| F | Lifecycle and freshness | implemented | `plans/005`, `plans/008`, ADR-031, ADR-032 | Keep stale-state reporting honest across surfaces. |
| F | Runtime surfaces | implemented | ADR-018, ADR-020, ADR-042 | Maintain behavior parity across library, MCP, HTTP, and gRPC. |
| F | Policy governance loop | implemented | Phase 5 roadmap, ADR-023 | Feed real feedback into replay and promotion gates. |
| F/P3 | State-invariant context | implemented | `plans/016`, `plans/017`, ADR-045 | Preserve current Java support; deepen only from repeated real-task misses. |
| P0 | Retrieval value benchmark harness | in_progress | `plans/020` | Finish calibration, harness, aggregator, and first real-repo run. |
| P0 | Negative-utility retrieval corpus | in_progress | `plans/020`, `notes/2026-08-27-zig-negative-utility-triage.md` | Prove the harness catches losses, not only wins, starting with Zig failures. |
| P1 | Semantic provider authority migration | planned | `plans/018`, ADR-046, ADR-047 | Approve fact identity and provider arbitration before source rollout. |
| P1 | One-shot context delivery | planned | `plans/019` | Ship additive MCP slice only after budget/accounting invariants hold. |
| P1 | Persistent JVM runtime reuse | done | `plans/021`, runtime docs | Runtime-http (Stage 2), MCP HTTP profile (Stage 3), and hardening plus the measured reuse benchmark (Stage 4) are shipped. |
| P2 | Agent workflow context surfaces | concept | `ideas/011`, `ideas/008`, `ideas/009` | Pick one workflow surface after prioritization. |
| P2 | Documentation graph | concept | `SPEC.md` Phase 2, `ideas/013` | Start only through provider catalog and lifecycle-aware authority. |
| P3 | Code-documentation linkage | concept | `SPEC.md` Phase 3, `ideas/011` | Require deterministic anchors and false-positive evaluation. |
| P4 | Performance and native runtime experiments | concept | `ideas/014` | Measure Clojure bottlenecks before any full rewrite decision. |
| R | Semantic Core / S-Quant | research | `ideas/003`, `ideas/004`, `ideas/005` | Validate hash plus embedding slice before product work. |

## Current Priority Bands

| Band | Features | Reason |
| --- | --- | --- |
| Foundation | Compact-first staged retrieval; semantic code graph; multi-language lanes; lifecycle/freshness; runtime surfaces; policy governance; delivered Java state-invariant context | These are existing product contracts and should be preserved rather than re-planned. |
| Current gate | Retrieval value benchmark harness; negative-utility retrieval corpus | The Phase 1 value claim must be proven against competent baselines, including cases where semidx may lose. |
| Next planned tracks | Semantic provider authority migration; one-shot context delivery; persistent JVM runtime reuse | They improve fact quality, agent ergonomics, and runtime usability while preserving staged retrieval. |
| Later product bets | Documentation graph; code-documentation linkage; workflow context surfaces | They depend on a trustworthy code graph and clear prioritization. |
| Opportunistic experiments | Performance/native runtime work | Run when concrete latency, memory, or packaging targets justify it. |
| Separate research | Semantic Core / S-Quant | Keep separate until a small validation slice proves value. |

## Implemented Features

### Compact-First Staged Retrieval

Status: `implemented`.

semidx's canonical public retrieval contract is staged:

1. `resolve_context` returns a compact ranked selection.
2. `expand_context` widens that retained selection with skeletons and impact
   hints.
3. `fetch_context_detail` fetches raw code late and only for ranked spans.
4. `literal_file_slice` can fetch exact bounded source text for a known path and
   line range.

Implementation notes:

- Every staged continuation is bound to `selection_id` and `snapshot_id`.
- Raw code fetch is late, budgeted, and can degrade from whole file to smaller
  spans instead of returning unbounded source.
- MCP accepts a simple top-level `intent` as well as structured retrieval
  queries.

### Semantic Code Graph

Status: `implemented`.

The graph layer contains normalized units, call/import compatibility
projections, typed relations, relation diagnostics, and bounded traversal.

Implementation notes:

- Typed relations are the canonical path for new graph semantics.
- Relation identity is separated from mutable resolution and evidence.
- Bounded traversal is available through library, MCP, HTTP, and gRPC surfaces.
- `impact_analysis`, expansion, and detail packets can include relation-backed
  support while keeping ambiguous facts conservative.

### Multi-Language Lanes

Status: `implemented` for current documented scope.

Supported lanes include Clojure, Java, Elixir, Python, TypeScript, JavaScript,
Lua, Zig, HTML, and CSS.

Implementation notes:

- Clojure is the strongest lane and currently has the high confidence ceiling.
- Java, Elixir, and Python have medium confidence ceilings.
- TypeScript, JavaScript, Lua, Zig, HTML, and CSS remain conservative.
- Each language lane owns its parser entry namespace, with
  `semidx.runtime.adapters` acting as the thin facade.

### Lifecycle, Freshness, And Repository Identity

Status: `implemented`.

Index lifecycle surfaces repo identity, snapshot provenance, staleness, rebuild
reasons, and activation state.

Implementation notes:

- Workspace-root isolation prevents stale index handles from being reused across
  different roots.
- Snapshot lifecycle metadata flows through public responses.
- Language activation is explicit and exposes structured guidance when no core
  supported language is available.

### Runtime Surfaces

Status: `implemented`.

Current public surfaces include:

- Clojure library API in `semidx.core`.
- MCP stdio.
- MCP Streamable HTTP and legacy SSE transport.
- Minimal HTTP runtime edge.
- gRPC runtime edge using generated protobuf stubs.

Implementation notes:

- Shared error taxonomy fields flow across library, MCP, HTTP, and gRPC.
- HTTP and gRPC support optional tenant/authz/rate-limit boundaries.
- MCP remains the primary agent-facing local surface.

### Policy Governance And Usage Feedback

Status: `implemented`.

semidx records usage events, feedback, replay datasets, calibration reports,
policy comparisons, shadow reviews, promotion gates, retained governance runs,
and operator queues.

Implementation notes:

- Ranking policy is explicit, versioned, and replayable.
- Protected replay cases block regressions during policy promotion.
- Usage metrics support SLO-facing rollups.
- Real-feedback loops feed future policy evaluation.

### State-Invariant Context

Status: `implemented` for the Java field-fact tranche.

semidx can surface bounded state-invariant packets for stateful Java changes,
including entity fields, field writes, state-bearing hints, and guardrails.

Implementation notes:

- `structure/declares-field` and `dataflow/writes-field` relations are emitted
  for Java entity-like classes and writers.
- Packets are additive and versioned.
- Low-confidence stateful changes retain whole-file-read guardrails.
- Non-Java lanes, migration facts, and richer schema/column facts remain future
  work.

## Active Or Planned Features

### Retrieval Value Benchmark Harness

Status: `in_progress`.

Purpose: prove or falsify semidx's code-graph value claim on real repositories.

Planned capabilities:

- Run A/B/C/D strategy arms against the same task suite.
- Preserve immutable `BenchmarkRun` and `TaskAttempt` identity.
- Normalize raw provider usage into comparable cost fields.
- Aggregate success per normalized cost and wall-clock.
- Require at least one external repository.
- Write the result back into `SPEC.md` without moving the threshold after
  scoring begins.

Current implementation state:

- Stage 1 fixed returned-token fidelity.
- Stage 0 still needs calibration and final threshold lock.
- Task suite, four-arm harness, aggregator, and first evidence run remain.

### Semantic Provider Authority Migration

Status: `planned`.

Purpose: move Java and TypeScript toward provider-backed evidence quality.

Planned capabilities:

- Provider descriptors, provider runtime status, and provider plans.
- Fact batches and evidence records.
- Provider-neutral `CanonicalFactKey` normalization.
- Deterministic arbitration and conflict diagnostics.
- SCIP and LSP exact evidence when source identity is fresh.
- Tree-sitter structural gap filling.
- Regex as explicitly degraded heuristic fallback.
- Capability and degradation summaries across all public surfaces.

Current implementation state:

- Stage 0 review and baseline fixtures exist.
- Core source implementation for the provider pipeline is still pending.

### Negative-Utility Retrieval Corpus

Status: `in_progress`.

Purpose: ensure the benchmark harness can record semidx losses against competent
baselines instead of only confirming expected wins.

Current required cases:

- Zig API-surface/signature extraction compared against targeted lexical
  extraction, not whole-file reads.
- Zig container/config field discovery where missing field facts count as false
  negatives.
- Zig blast-radius discovery where an unrelated seed selection counts as a
  failure.
- Stale-snapshot-after-edit behavior separated from ranking quality.

Current implementation state:

- Routed into `plans/020` Stage 2.
- Initial triage note exists; exact MCP request/response repro data still needs
  capture before source-level Zig fixes.

### One-Shot Context Delivery

Status: `planned`.

Purpose: give LLM agents one request that composes the staged flow without
changing the staged contract.

Planned capabilities:

- `get_context` library operation over an existing `index_id`.
- Deterministic one-shot policy and response budget ledger.
- Structured ContextPacket response.
- Optional bounded Markdown projection.
- MCP vertical slice first.
- Later HTTP/gRPC parity after value is demonstrated.
- Strategy adapters for the benchmark harness.

Current implementation state:

- Plan exists.
- No source implementation yet.

### Persistent JVM Runtime Reuse

Status: `in_progress`.

Purpose: avoid paying Clojure JVM startup cost for every client request when the
caller uses a short-lived command path or restarts the runtime per invocation.

Current implementation state:

- MCP stdio runs a long-lived request loop for the lifetime of its server
  process.
- MCP HTTP, runtime HTTP, and runtime gRPC are long-lived server processes once
  started.
- The one-shot runtime CLI starts a JVM, creates or loads context, writes the
  response, and exits.
- The execution plan is
  [`plans/021_persistent_jvm_runtime_reuse_plan.md`](plans/021_persistent_jvm_runtime_reuse_plan.md).
- Stage 0 selected `runtime-http` as the first reuse profile.
- Stage 1 delivered the pure launcher decision kernel and focused unit tests.
- Stage 2 shipped the runnable `runtime-http` reuse slice behind the `:launcher`
  alias: `status`, `start`, `stop`, and `request`, with a per-slot state file
  and start lock outside the repository, health-based reuse, adoption of an
  already-running local runtime, stale-state cleanup, and request forwarding to
  the existing runtime HTTP endpoints.
- Stage 4 closed the track with command latency reporting, stale-state recovery
  tested against a real killed process and a real occupied port, and
  `./scripts/run-launcher-benchmark.sh`: cold CLI 11.6s versus 1.7s for a warm
  launcher request and 47ms for a direct HTTP call on this repository, with all
  three paths performing the same resolve + fetch-detail work.
- Stage 3 added the `mcp-http` profile end to end: the launcher starts, adopts,
  reuses, and stops a local MCP Streamable HTTP endpoint, refuses to adopt a
  server whose reported health `service` belongs to the other profile, refuses
  `request` for a profile it cannot serve, and documents client configuration
  for both HTTP and stdio hosts.
- Stage 3 (MCP HTTP profile and client config docs) and Stage 4 (hardening and
  benchmark gate) are still open.

Possible capabilities:

- Local launcher that checks a project or user-scoped runtime endpoint before
  starting a new JVM.
- Health check plus stale PID or stale port cleanup.
- `status`, `start`, `stop`, and `request` commands with deterministic lock
  handling.
- Reuse of existing MCP HTTP, runtime HTTP, or runtime gRPC servers instead of
  introducing a second request protocol.
- Clear documentation that stdio MCP reuse is bounded by the MCP host process
  lifetime, while HTTP/gRPC can be reused across client invocations.

Implementation direction:

- First confirm which host path is causing repeated JVM launches.
- Prefer a small launcher/client over changing the core indexer.
- Keep server process reuse separate from broad native-runtime experiments.

## Concept Features For Prioritization

### Agent Workflow Context Surfaces

Status: `concept`.

Possible features:

- `architecture_graph`: focused deterministic views over modules, symbols, and
  relations.
- `change_map` or `review_context`: diff plus impact map for PRs and planning.
- `architecture_lint`: cycles, forbidden layer crossings, orphans, hubs, and
  generated-code leaks.
- `dead_code_candidates`: review-only candidates from configured roots and
  graph reachability.
- `handoff_summary`: computed work-state summary from snapshots, git, checks,
  dirty files, blockers, and notes.

Implementation direction:

- Prefer focused staged views over full graph dumps.
- Keep deletions and lints as review candidates.
- Mark inferred state explicitly.

### Documentation Graph

Status: `concept`.

Possible features:

- Markdown provider through the provider catalog.
- Heading-section units with source spans.
- Frontmatter parsing for `lifecycle`, `status`, `agent_action`, and `updated`.
- Lifecycle-aware ranking that favors active/current docs.
- Broken-link and lifecycle-consistency checks.
- Document relation facts for specs, ADRs, plans, reports, and notes.

Implementation direction:

- Do not index Markdown as generic equal-weight text chunks.
- Do not add Markdown as a hardcoded adapter exception.
- Use frontmatter and explicit links as deterministic authority inputs.

### Code-Documentation Linkage

Status: `concept`.

Possible features:

- Link path and symbol mentions in documentation to code graph units.
- Detect docs that reference removed or moved code.
- Surface governing docs in retrieval, impact, and one-shot packets.
- Detect overlapping or contradictory active documents.
- Produce doc-health reports and redundant-doc clusters.

Implementation direction:

- Begin with deterministic anchors: paths, symbols, frontmatter references, and
  supersession links.
- Use LLMs only for labels, summaries, and explanation text.

### State-Invariant Deepening

Status: `concept`.

Possible features:

- Extend entity/model and state-writer facts beyond Java.
- Add schema, migration, enum, and column facts.
- Improve fixture-helper and assertion-test selection.
- Link state-invariant packets to governing documentation.

Implementation direction:

- Reopen only when a real task shows repeated missed invariants after the
  delivered Java tranche.
- Keep packets bounded and confidence-honest.

### Performance And Native Runtime Experiments

Status: `concept`.

Possible features:

- Controlled startup, indexing, retrieval, memory, and snapshot-load benchmarks.
- Batch `clj-kondo` analysis instead of per-file process launches.
- Safe parser cache reuse.
- Bounded parallel parsing.
- Better incremental indexing.
- Lightweight server packaging.
- Zig launcher, Zig indexing worker, or Zig retrieval vertical slice.

Implementation direction:

- Treat startup, fresh indexing, warm retrieval, and memory as separate
  performance domains.
- Optimize and measure the Clojure runtime before accepting full rewrite risk.
- Require semantic parity fixtures for any native slice.

## Research Track

### Semantic Core / S-Quant

Status: `research`.

Possible features:

- Versioned logical hash for normalized Clojure forms.
- Golden suite for same-meaning and different-meaning changes.
- Embedding validation after hash behavior is defined.
- Semantic diff over identity, contracts, and embeddings.
- Semantic ledger only after the small validation slice proves useful.

Implementation direction:

- Keep this separate from the main semidx implementation track.
- Do not implement the original broad S-Quant object directly.
- Continue only if a hash-plus-embedding slice beats existing structural
  retrieval on a concrete Clojure corpus.

## Planning Rule

After owner prioritization, create a new numbered plan for the selected feature
or feature group. This file should remain a root status ledger, not a stage
execution log.

## Maintenance Rule

Update this file when:

- a feature moves between `concept`, `planned`, `in_progress`, and
  `implemented`;
- a plan becomes the owner of a feature;
- a feature is explicitly rejected, narrowed, or moved to research;
- the next gate changes.

Do not record every commit here. This file tracks feature state, not release
history.

## Format Basis

This file follows common product-documentation practice:

- Root documentation should help a reader understand what the project is useful
  for and link to deeper docs.
- Reference docs should be factual and scannable.
- Roadmap-style records should expose status, area, source, and next phase.
- Changelogs and feature ledgers should stay separate.

External references:

- [GitHub public roadmap](https://github.com/github/roadmap): status/phase
  labels, feature areas, shipped-state linkage.
- [GitHub Projects roadmap layout](https://docs.github.com/en/issues/planning-and-tracking-with-projects/customizing-views-in-your-project/customizing-the-roadmap-layout):
  custom fields, grouping, sorting, and timeline views for work tracking.
- [Diataxis reference model](https://www.diataxis.fr/start-here/): factual
  reference docs are distinct from tutorials, how-to guides, and explanations.
- [Keep a Changelog](https://keepachangelog.com/en/2.0.0/): release history
  should be human-oriented and separate from feature status.
- [GDS README guidance](https://gds-way.digital.cabinet-office.gov.uk/manuals/readme-guidance.html):
  root docs should stay clear, useful, and point to deeper documents when detail
  grows.
