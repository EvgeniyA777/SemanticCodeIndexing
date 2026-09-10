---
title: "Rust Core Rewrite Plan"
doc_type: "architecture_plan"
lifecycle: "active"
status: "draft"
agent_action: "reference_for_context"
updated: "2026-09-10"
---

# Rust Core Rewrite Plan

Target architecture and staged delivery for reimplementing semidx as a
single-binary Rust core. Companion to
[`reports/029`](../reports/029_external_architecture_review.md), which
establishes the findings this plan responds to.

This plan is a draft: milestone M0 is a gate, not a formality, and nothing
after it starts until M0 produces a number.

## Why A Rewrite Rather Than A Refactor

The current implementation is not failing at logic. It is limited at three
boundaries that a stack change removes outright rather than improves:

| Boundary | Current cost | Source |
| --- | --- | --- |
| Installation | JVM, Maven fetch, jdtls, scip-java, tree-sitter CLI; two providers fail on the author's machine | `reports/029` Finding 4 |
| Process start | 3.3 s handshake, 2,742 ms of it in `require semidx.core` | `bugs/001` |
| Structure extraction | Regular expressions in six of ten lanes, producing an open-ended defect series | `reports/029` Finding 5, `bugs/004`, `bugs/006` |

Roughly eight thousand of the current 32,623 source lines carry over as logic.
The remainder is either absorbed by the target stack or should not have been
built before validation. That ratio is what makes the rewrite cheaper than it
appears.

## Non-Goals

- Not a port. Code is not translated; contracts and behavior are.
- Not a feature-parity exercise. The gRPC edge, PostgreSQL persistence, usage
  metrics, the policy registry, and the Phase 5 governance loop are out of
  scope permanently, not deferred.
- Not a language-count exercise. Two lanes ship. Additional lanes are earned by
  demand, never by symmetry.
- Not an LLM-in-the-loop system. The graph stays deterministic, per `SPEC.md`.

## Gate: What Must Be True Before M1 Starts

M0 below runs on the **existing Clojure implementation**. No Rust work begins
until it reports. This ordering is the whole point: a rewrite before the
measurement is the most expensive available way to not learn whether the core
claim holds.

## Target Architecture

### Workspace layout

```text
crates/
  semidx-core/       domain types, contracts (serde + schemars), errors
  semidx-parse/      tree-sitter lanes, unit and relation extraction
  semidx-index/      discovery, incremental pipeline, SQLite persistence
  semidx-retrieve/   BM25, vectors, graph expansion, fusion, budget packing
  semidx-mcp/        MCP server, four tools
  semidx-cli/        clap binary, single entry point
xtask/               schema export, fixture replay, recall harness
```

The split exists so that `semidx-retrieve` can be benchmarked without a
transport and `semidx-core` can emit JSON Schema without pulling a parser.

### Dependency choices

| Concern | Crate | Note |
| --- | --- | --- |
| Parsing | `tree-sitter` plus grammar crates | Grammars compiled in; no runtime download |
| Storage | `rusqlite` with `bundled` feature | No external database, no server |
| Lexical | SQLite FTS5 | BM25 in the same file as the graph |
| Vectors | `fastembed` or `ort` for inference; `sqlite-vec` or `usearch` for ANN | Maturity of `sqlite-vec` to be confirmed at M3; `usearch` is the fallback |
| Parallelism | `rayon` | Per-file work parallelizes without shared mutable state |
| Contracts | `serde` + `schemars` | Schema derived from types |
| MCP | `rmcp` (official Rust SDK) | Fastest-moving dependency; confirm current status before M1 |
| CLI / HTTP | `clap`, `axum` | HTTP only if a second transport is actually requested |
| Snapshot tests | `insta` | Replaces the REPL feedback loop for extraction output |

### Graph representation

Arena-based, not pointer-based: units and relations live in `Vec` storage
addressed by `u32` indices. This is both the idiomatic way to avoid fighting
the borrow checker on a cyclic graph and the faster layout. Persisted ids are
content-addressed and stable across runs; arena indices are per-process only
and never leave the crate.

### Storage schema

```sql
snapshots(id, repo_key, git_commit, git_dirty, created_at)
files(snapshot_id, path, lang, content_hash, mtime, parse_status)
units(id, snapshot_id, file_id, symbol, kind, module,
      start_line, end_line, authority, doc)
relations(snapshot_id, src_unit, dst_unit, kind, evidence,
          authority, resolved)
units_fts  -- FTS5 over symbol, module, path, doc
unit_vectors(unit_id, embedding)
```

`files.content_hash` is what makes reindexing incremental: unchanged files keep
their units and relations, and only changed files are reparsed. The current
implementation rebuilds fully (`lifecycle_action: "full_rebuild"` on every
observed run), which is the single largest avoidable cost on a large
repository.

### Indexing pipeline

1. **Discovery** — walk the root honoring `.gitignore`, plus a built-in deny
   list for vendored and generated roots. This closes `reports/029` Finding 1
   at the source rather than in the presentation layer.
2. **Parse** — tree-sitter per lane, in parallel via `rayon`.
3. **Extract** — units and typed relations from the concrete syntax tree, with
   an explicit `authority` on every fact.
4. **Resolve** — optional SCIP index ingestion and, later, an LSP bridge, both
   as upgrades over the tree-sitter facts, never as startup prerequisites.
5. **Persist** — one transaction per snapshot, so a snapshot is either whole or
   absent.

Provider failure is a visible index state with a remediation hint, not a nested
field. `reports/029` Finding 4.

### Retrieval pipeline

The current `lexical_overlap` plus graph-neighbor seeding is the root cause of
Finding 2 and is not carried over.

1. **Candidates** — FTS5/BM25 top 200, vector top 200, plus exact symbol
   matches promoted unconditionally.
2. **Fusion** — reciprocal rank fusion over the candidate lists.
3. **Expansion** — bounded traversal (depth 2) from the fused seeds, weighted
   by relation type. The graph expands and verifies; it does not generate
   candidates.
4. **Packing** — greedy selection under the token budget, deduplicated by file
   locality, preserving the staged-selection contract from `adr/024`.

Embeddings are in scope from M3. `SPEC.md` currently files them under
"R — research only". This plan **proposes** reclassifying them, on the reasoning
that graph determinism and embedding-based query entry are not in conflict: the
graph remains the truth about relations, embeddings only locate the entry point
into it. Until `SPEC.md` is amended, `SPEC.md` wins and this paragraph is a
proposal, not a decision — the conflict is deliberate and must be resolved
before M3, ideally by an ADR.

### MCP surface

Four tools, down from fourteen:

| Tool | Replaces | Contract |
| --- | --- | --- |
| `index` | `create_index`, `repo_map`, `health`, `capabilities` | Returns index state, language coverage, provider health, and a usable module map in one response |
| `find` | `resolve_context`, `expand_context`, `skeletons` | Takes intent plus optional structural targets; returns a budgeted selection |
| `impact` | `impact_analysis`, `traverse_relations`, `snapshot_diff` | Takes a symbol or a diff; returns callers, affected tests, and blast radius with evidence |
| `read` | `fetch_context_detail`, `literal_file_slice` | Returns exact spans without a retrieval envelope |

Schemas are generated from Rust types via `schemars`, which removes contract
drift as a category and with it the `contracts/` validator, its CLI, and its CI
gate. Property names are ASCII-safe by construction, which also prevents the
`bugs/007` class of failure.

## Milestones

### M0 — Recall harness on the current implementation (gate, ~1 week)

Build the measurement described in `reports/029` Phase 3 against the existing
Clojure runtime: merged pull requests from large open-source Java and
TypeScript repositories, issue text as the query, changed files and symbols as
ground truth, recall@k and tokens-to-coverage as the metric, a text-search agent
as the baseline.

**Exit**: baseline numbers exist and are reproducible. If semidx loses
decisively and the gap is not explainable by Findings 1-5, stop here — the
rewrite has nothing to carry.

### M1 — Skeleton, parsing, storage (~3 weeks)

Workspace, `semidx-core` types with generated schemas, tree-sitter Java and
TypeScript lanes, discovery with ignore policy, SQLite persistence with
incremental reuse, `index` tool over MCP.

**Exit**: indexes ReaderLens end to end; `bugs/004` and `bugs/006` ownership
cases produce correct owners; index of a 50k-file repository completes in under
two minutes; cold start under 300 ms.

### M2 — Lexical retrieval and graph expansion (~2 weeks)

FTS5 candidates, typed-relation expansion, budget packing, `find` tool.

**Exit**: beats the M0 baseline on recall@10 without embeddings. If it does
not, the problem is the relation model and M3 will not rescue it.

### M3 — Embeddings and fusion (~2 weeks)

Local inference, vector storage, reciprocal rank fusion.

**Exit**: the `reports/029` Finding 2 query — "where is the retrieval ranking
pipeline that scores code units" — returns the ranking module in the top three
on this repository's own successor. Measurable improvement over M2 on the
harness.

### M4 — Impact surface (~2 weeks)

`impact` tool: callers, covering tests, diff-aware blast radius, each with
evidence and authority.

**Exit**: on a set of real merged PRs, predicted blast radius contains the
files the PR actually touched, measured as recall with a stated precision
floor.

### M5 — Distribution (~1 week)

Cross-compiled binaries for darwin-arm64, darwin-x64, linux-x64, linux-arm64
and windows-x64; npm wrapper package; Homebrew tap; install and MCP setup
documentation.

**Exit**: a person who has never seen the project installs it and completes a
first retrieval on their own repository in under ten minutes with no
assistance. This is acceptance criterion one from
`docs/development-strategy.md`, finally testable.

## What Carries Over

| Asset | Use in the rewrite |
| --- | --- |
| `contracts/schemas/`, `contracts/examples/` | Behavioral specification and cross-implementation conformance |
| `fixtures/retrieval/` | Test corpus from day one |
| `adr/024`, `adr/039`, `adr/040` | Staged retrieval and the typed relation model, both language-independent |
| `bugs/001`-`bugs/009` | Acceptance suite; nine reproducible defects a new project would not otherwise have |
| `docs/mcp-agent-prompts.md` | Client-side guidance, largely unchanged |

## What Does Not Carry Over

`grpc.clj` and the proto surface, PostgreSQL persistence and usage metrics,
`evaluation.clj` and the Phase 5 governance loop, the policy registry, the
launcher and `plans/021` in full (JVM cold start is the problem it solves, and
that problem disappears), and eight language lanes.

## Risks

| Risk | Mitigation |
| --- | --- |
| Borrow checker friction on a cyclic graph | Arena indices from the start; no reference-linked nodes |
| Loss of the REPL feedback loop | `insta` snapshot tests over extraction output; fixture corpus available at M1 |
| `rmcp` instability | Keep the MCP layer thin and transport-agnostic; the four tools are library calls first |
| `sqlite-vec` maturity | `usearch` as a drop-in fallback; decision deferred to M3 |
| Grammar version skew | Pin grammar crate versions; extraction tests fail loudly on tree shape changes |
| Rewrite absorbs attention that fixes nothing | M0 gate; every milestone has a measured exit, not a feature checklist |
| Single maintainer, unfamiliar language | Two lanes only; four tools only; no optional infrastructure until there is a user |

## Open Questions

- Does the M0 harness use public repositories only, or is ReaderLens available
  as a fixed permissioned corpus?
- Which embedding model is acceptable to ship: size, license, and whether it
  can be bundled or must be fetched on first run?
- Is Windows a supported target at M5, or does it wait for a request?
- Does the current Clojure implementation continue receiving fixes in parallel
  during M1-M4, and if so, for how long?
