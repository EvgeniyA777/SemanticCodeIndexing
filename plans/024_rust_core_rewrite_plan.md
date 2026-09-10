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
| Inference | `fastembed` 6.x over `ort` 2.0-rc, behind an `Embedder` trait | `candle` is the fallback; the decision has a stated criterion at M3, see below |
| Vectors | `usearch` 2.26 for ANN | Vectors are stored as BLOBs in SQLite; the ANN index is a derived, rebuildable artifact. See the note below on why not `sqlite-vec` |
| Parallelism | `rayon` | Per-file work parallelizes without shared mutable state |
| Contracts | `serde` + `schemars` | Schema derived from types |
| MCP | `rmcp` 3.x (official Rust SDK) | Mainstream and actively maintained; the major line still moves, so keep the MCP layer thin |
| CLI / HTTP | `clap`, `axum` | HTTP only if a second transport is actually requested |
| Snapshot tests | `insta` | Replaces the REPL feedback loop for extraction output |

Dependency status was checked against crates.io on 2026-09-10 and is recorded
here so a later reader can tell a stale assumption from a current one:

| Crate | Version observed | Signal |
| --- | --- | --- |
| `rmcp` | 3.3.0, released 2026-09-10 | 25.5M downloads total, 13.0M recent. The official SDK is mainstream, not a bet. Three major versions in its history means the API does move; the mitigation is architectural, not a pin |
| `usearch` | 2.26.2, released 2026-08-31 | 1.05M downloads total, 397k recent. Stable 2.x line, Apache-2.0, actively released |
| `sqlite-vec` | 0.1.10-alpha.4, released 2026-05-18 | 2.78M downloads total, 1.06M recent — widely used, but still pre-v1 alpha with breaking changes expected to both the SQL API and the on-disk storage format |
| `fastembed` | 6.0.3, released 2026-09-07 | 3.29M downloads total, 1.86M recent. Actively released; pulls `ort` through feature flags and downloads ONNX Runtime binaries by default |
| `ort` | 2.0.0-rc.13, released 2026-07-28 | 17.9M downloads total, 6.76M recent. Formally a release candidate, in practice the standard ONNX Runtime binding for Rust |
| `candle` | 0.11.0, released 2026-06-26 | 7.85M downloads total, 2.67M recent. Pure Rust, no external runtime to link |

**Why `usearch` rather than `sqlite-vec`, despite the latter being the tidier
design.** A storage-format break in a dependency is not an upgrade chore here —
it is a forced reindex on every user's machine, for a tool whose entire adoption
argument is that it installs and works. `sqlite-vec` states that breakage is
expected before v1, and the most recent release as of this check is still an
alpha from May 2026. Keeping vectors as SQLite BLOBs and treating the ANN index as
derived means the format risk is confined to a file that can be rebuilt from the
database at any time, and it leaves the door open: when `sqlite-vec` reaches v1,
adopting it removes a dependency without a migration, because the source of
truth never moved out of SQLite.

**Inference engine: a decision with a criterion, not a preference.** The two
candidates fail in opposite directions, and the deciding constraint is M5, not
M3.

`fastembed` over `ort` is the faster path and covers more models, but ONNX
Runtime is a native library. Its default strategy downloads Microsoft's prebuilt
binaries, which is exactly the "install a toolchain first" failure this rewrite
exists to escape. A self-contained binary is achievable — `ort` exposes a
`compile-static` feature and an `ORT_LIB_PATH` for statically built libraries,
and its own documentation recommends static linking where the execution
providers allow it — but ONNX Runtime then has to be compiled per target, on
native CI runners rather than by cross-compilation, and the documentation is
explicit that this "will take a very long time".

`candle` is pure Rust with no external runtime to link, so cross-compilation to
all five targets is ordinary `cargo build`. The cost is a narrower set of
ready-made embedding models and, most likely, slower CPU inference.

**Criterion, evaluated at M3:** if a statically linked binary can be produced for
darwin-arm64, darwin-x64, linux-x64, linux-arm64 and windows-x64 within an
acceptable CI budget, ship `fastembed`/`ort`. If it cannot, ship `candle`.
Either way the inference call sits behind an `Embedder` trait in
`semidx-retrieve`, so the swap touches one crate and no contract.

**Model selection is a measurement, not a leaderboard lookup.** The M0 harness
already provides ground truth over real merged pull requests, so candidate
models are ranked by recall@k on that corpus rather than by general text
benchmarks, which measure prose retrieval and not code. Two hard filters apply
before any measurement:

1. **License must be permissive** (Apache-2.0 or MIT). Several models at the top
   of public embedding leaderboards — `jina-embeddings-v3` and `NV-Embed-v2`
   among them — are CC-BY-NC and cannot be shipped in a commercial tool.
2. **Quantized size must fit the distribution story.** A model that adds a
   multi-gigabyte download undoes M5. Candidates in range at the time of this
   check: `EmbeddingGemma-300M` (reported under 200 MB quantized),
   `Qwen3-Embedding-0.6B` (Apache-2.0, roughly 1.5 GB — likely fetched on first
   run rather than bundled), and `all-MiniLM-L6-v2` (~100 MB) as a cheap floor
   to measure against.

### Model delivery

"Bundle the model or download it on first run" is a false dilemma, and the
industry does not resolve it as one. The decision has three parts, and only the
third is load-bearing.

**1. Default: fetch on first use, not on install.** A small binary plus a
separately versioned model artifact is the settled pattern — Ollama, LM Studio,
and the Hugging Face ecosystem all work this way, needing the network exactly
once. The benefit is not size but decoupling: a model update stops being a tool
release, and a tool release stops being a model download. The fetch happens on
the first index that requests embeddings, never during installation and never
silently in the background, with visible progress and a SHA-256 check.

**2. Offline is a supported delivery mode, not a fallback flag.** Air-gapped
environments require the model to load with no runtime downloads and no
telemetry, shipped alongside its tokenizer, config, version, and model card, and
signed so the receiving side can verify integrity without reaching the internet.
Concretely:

- `SEMIDX_MODEL_DIR` overrides the cache location; the default is the XDG cache
  path.
- `SEMIDX_MODEL_URL` points at an internal mirror.
- `--offline` / `SEMIDX_OFFLINE=1` makes any network call an error rather than
  an attempt.
- Each release publishes `semidx-model-<name>-<version>.tar.zst` with a checksum
  and a signature, for transfer into a closed network.
- An unreachable network times out and degrades. It never hangs.

**3. The load-bearing decision: embeddings are an upgrade, never a
prerequisite.** Without a model, semidx runs in lexical-plus-graph mode — which
is exactly M2, and M2 is already required to beat the M0 baseline on its own.
With a model, retrieval quality improves. This is the same degradation ladder
the project already applies to facts (exact over structural over heuristic),
extended to the query side rather than invented for it.

That inversion is what dissolves the original question:

- The first run always works, including behind a corporate proxy that blocks
  public model hosts.
- Model download becomes a quality upgrade rather than an installation step, so
  it cannot block M5.
- A ~1.5 GB model stops being a distribution problem and becomes an opt-in
  choice for users who want maximum quality.

**Target class:** aim at a model at or under 400 MB quantized
(`EmbeddingGemma-300M` is reported under 200 MB), which is small enough that
bundling versus fetching stops being a painful call. `Qwen3-Embedding-0.6B` is
an explicit "quality" mode, never the default.

**Test the offline path in our own code.** The fastembed ecosystem has known
defects of exactly this class — `HF_HUB_OFFLINE=1` bypassing the local cache and
falling back to a remote host, and hangs behind a firewall despite a present
local model. Those issues are filed against the Python library rather than the
Rust crate this plan selects, so they are an ecosystem signal rather than direct
evidence; the conclusion is that offline behavior must be owned and tested here,
not assumed from a dependency.

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
unit_vectors(unit_id, embedding)  -- BLOB; source of truth for vectors
```

The ANN index (`usearch`) lives in a separate file alongside the database and is
**derived**: it is rebuilt from `unit_vectors` whenever it is missing, stale, or
written by an incompatible version. Nothing depends on it for correctness, only
for speed.

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

### M3 — Embeddings and fusion (~2 weeks, plus CI build time)

Local inference behind the `Embedder` trait, vector storage, reciprocal rank
fusion. Two decisions land here, both by the criteria stated above: the
inference engine (`fastembed`/`ort` if static linking fits the CI budget, else
`candle`) and the model (ranked by recall@k on the M0 corpus, filtered first by
permissive license and quantized size).

The estimate covers engineering time only. If static ONNX Runtime builds are
attempted, per-target CI compilation is measured in hours and should be set up
during M2 rather than discovered here.

**Exit**: the `reports/029` Finding 2 query — "where is the retrieval ranking
pipeline that scores code units" — returns the ranking module in the top three
on this repository's own successor. Measurable improvement over M2 on the
harness. Additionally: with `--offline` and no model present, retrieval still
returns M2-quality results and says so in the response, rather than failing or
hanging.

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
`docs/development-strategy.md`, finally testable. The same run is repeated on a
machine with no access to public model hosts, and must succeed with the same
setup time.

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
| `rmcp` major-version churn | Not a stability risk — the SDK is official and heavily used — but the 3.x line moves. Keep the MCP layer thin and transport-agnostic so the four tools are library calls first and an SDK upgrade touches one crate |
| Vector storage format churn | Resolved by construction: vectors are SQLite BLOBs, the ANN index is derived and rebuildable. `sqlite-vec` is revisited only after it reaches v1 |
| A native ONNX Runtime dependency reintroduces the install problem | The `Embedder` trait plus a decision criterion at M3; `candle` needs no external runtime, so the escape hatch is designed in rather than hoped for |
| Shipping a non-commercial-licensed model | License filter applied before measurement, not after; CC-BY-NC models are excluded from the candidate set outright |
| A blocked model host makes the tool unusable at a corporate pilot | Embeddings are an upgrade, not a prerequisite: no model means lexical-plus-graph retrieval with an explicit diagnostic. Verified as an M3 exit, not assumed |
| Grammar version skew | Pin grammar crate versions; extraction tests fail loudly on tree shape changes |
| Rewrite absorbs attention that fixes nothing | M0 gate; every milestone has a measured exit, not a feature checklist |
| Single maintainer, unfamiliar language | Two lanes only; four tools only; no optional infrastructure until there is a user |

## Open Questions

- Does the M0 harness use public repositories only, or is ReaderLens available
  as a fixed permissioned corpus?
- Does the offline model bundle need signing at M5, or is a published checksum
  sufficient until a pilot actually requires transfer into a closed network?
- Is Windows a supported target at M5, or does it wait for a request?
- Does the current Clojure implementation continue receiving fixes in parallel
  during M1-M4, and if so, for how long?
