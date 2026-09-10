---
title: "External Architecture Review: Positioning, Stack, And Retrieval Quality"
doc_type: "architecture_review"
lifecycle: "active"
status: "final"
agent_action: "reference_for_context"
updated: "2026-09-10"
---

# External Architecture Review

An outside review of semidx as a product and as a system, requested on
2026-09-10. It answers four questions: is the stack the right one, does the
tool work, can it earn a place, and what is the shortest path there.

The review is deliberately adversarial. Every claim below is backed either by a
measurement taken during the review or by a document already in this
repository. Where a claim is an opinion about the market rather than a
measurement, it is marked as such.

## Method

The tool was run against its own repository through the MCP surface, because
that is the cheapest honest test available: a code retrieval system that cannot
orient an agent inside its own source has no defensible claim on anyone else's.

Session artifacts, for replay:

- `index_id`: `9c869180-8405-44c7-9540-294796814e00`
- `snapshot_id`: `ebcbb7ca-dbf5-4e5d-b44b-55161cba61e5`
- Reviewed source: branch `dev` at `1f6e1f8`, working tree dirty
- Flow exercised: `create_index` -> `repo_map` -> `resolve_context`
  (natural-language form) -> `resolve_context` (structured form)

Repository scale at review time:

| Measure | Value |
| --- | --- |
| Source | 87 Clojure files, 32,623 lines |
| Tests | 68 files, 18,633 lines, 685 tests / 3,652 assertions |
| Markdown under `adr/ plans/ reports/ notes/ ideas/ docs/ bugs/` | 45,039 lines |
| Root instruction documents | 1,818 lines (`MEMORY.md` alone is 135 KB) |
| Indexed for the session | 253 files, 5,091 units, 5,204 ms |
| Commits since 2026-03-08 | 408 |

## Verification Commands Run

| Command | Result |
| --- | --- |
| `clojure -M:test` | Passed. 685 tests, 3,652 assertions, 0 failures, 0 errors. |
| MCP `create_index` on repository root | Completed. Providers degraded, see Finding 4. |
| MCP `repo_map` | Completed. Output unusable, see Finding 1. |
| MCP `resolve_context`, intent shorthand | Completed. Target missed, see Finding 2. |
| MCP `resolve_context`, structured targets | Completed. Target hit, see Finding 3. |
| `./scripts/run-mvp-gates.sh` | Skipped. Not required for the questions asked. |

## Findings

Ordered by severity. Severity reflects impact on adoption, not on correctness
of any single function.

### 1. Critical — `repo_map` returns vendored and generated paths, not the project

The second step of the canonical flow is the first impression every agent gets,
and on this repository it contains no project module at all. The returned
module list is `scip.Builder`, `scip.Descriptor`, `scip.DocumentOrBuilder`,
`Foo.Bar.Baz`, `jdtls-toolchain.bin.jdtls`, and fixture corpus namespaces.
Every one of the twenty returned file paths is under `.tree-sitter-grammars/`.
Not one `semidx.runtime.*` module appears.

Why it matters: an agent that follows `RULES.md` exactly — `create_index`, then
`repo_map` — receives zero orientation and every incentive to fall back to
manual crawling, which is the single failure mode the project exists to
prevent. It also inflates the index: of 253 indexed files, roughly 100 are
toolchain checkouts, downloaded grammars, and generated protobuf sources.

Smallest fix: an ignore policy at indexing time — honor `.gitignore`, and
exclude generated and vendored roots (`.tree-sitter-grammars/`,
`.jdtls-toolchain/`, `.scip*-toolchain/`, `src-generated/`, `target/`) unless
explicitly requested. Rank remaining modules by unit count so project code
outranks fixtures.

### 2. Critical — natural-language retrieval misses the target on the project's own code

Query: *"Where is the retrieval ranking pipeline that scores code units for
`resolve_context`?"* The correct answer is
[`src/semidx/runtime/retrieval.clj:166`](../src/semidx/runtime/retrieval.clj)
(`combine-score`) and its neighbors. None of the five returned units was in
`retrieval.clj`. The selection was:

| Returned unit | `why_selected` |
| --- | --- |
| `evaluation/history-aware-selection-vector` (919-926) | `graph_callee_neighbor`, `graph_module_neighbor` |
| `evaluation/apply-history-aware-ranking` (928-935) | `lexical_overlap`, `graph_module_neighbor` |
| `evaluation/run-policy-review-pipeline-command` (1912-1930) | `lexical_overlap`, `graph_module_neighbor` |
| `contracts.schemas/usage-feedback` (539-560) | `graph_caller_neighbor`, `graph_module_neighbor` |
| `mcp.core/handle-tools-call` (1345-1386) | `graph_module_neighbor`, `graph_path_neighbor` |

`confidence_level` was `medium` and `recommended_action` was `narrow_query`, so
the failure is disclosed rather than hidden — that part of the design works.
But the word "ranking" pulled in the *governance* ranking code rather than the
*retrieval* ranking code, which is what a lexical-overlap seed does when two
subsystems share vocabulary.

This is the same behavior recorded independently in
[`bugs/006`](../bugs/006_java_nested_type_ownership_and_retrieval_feedback.md)
against ReaderLens, where a natural-language request naming the publication
classes returned unrelated methods. Two independent observations on two
repositories make this a property of the ranker, not an unlucky query.

Smallest fix: none available at this severity — see Direction, Phase 2. The
seeding stage needs a real relevance model, not a wider set of heuristics.

### 3. High — the structured path succeeds only when the caller already knows the answer

The structured retry named `targets.paths` (`retrieval.clj`,
`retrieval_policy.clj`) and `targets.symbols` (`combine-score`,
`lexical-seed-units`, `expand-graph-score-map`). It returned exactly those
units at `rank_band: top_authority` with `why_selected: exact_target_resolved`.

That is correct behavior and it is also the problem. The documented recovery
from a bad natural-language result is to supply paths and symbols — that is,
to supply the retrieval result as the retrieval query. Under that protocol the
tool is a precise lookup service, not a discovery service, and discovery is
what the value claim rests on.

Why it matters: `RULES.md` instructs agents never to fall back to text search on
a `low` confidence result, and to narrow the query instead. On a repository the
agent has not seen, it has nothing to narrow *with*. The instruction is only
satisfiable when the agent already knows where to look.

Smallest fix: treat "intent-only recall" as the product metric and gate
releases on it, rather than treating the structured retry as an adequate
answer. See Phase 3.

### 4. High — the exact-evidence providers fail by default, and the failure is quiet

`create_index` on this repository reported `scip-java: failed`,
`scip-typescript: failed`, `diagnostic_codes: {:scip_index_failed 2}`,
`authorities: {heuristic: 1592}`, `mode: "shadow"`. Every fact served in this
session came from the bottom rung of the authority ladder defined in
[`adr/046`](../adr/046-prefer-semantic-evidence-providers-over-structural-and-lexical-fallbacks.md).

Why it matters: the authority ladder is the central trust argument of Phase 1
in [`SPEC.md`](../SPEC.md). If the top two rungs fail silently on the author's
own machine, they are unlikely to be present on a pilot team's machine, and
the ladder is decorative in practice. `bugs/006` records the same situation
from the consumer side: Java selections observed at `low` confidence against a
documented ceiling of `medium`.

Smallest fix: surface provider failure as a first-class, user-visible index
state with a remediation hint, rather than a nested field in
`provider_summary`. An index built entirely from heuristic facts should say so
where an agent will read it.

### 5. High — six of ten language lanes extract structure with regular expressions

Regex call counts per lane: `java` 24, `typescript` 29, `python` 24, `lua` 29,
`zig` 14, `css` 8, `html` 2. Tree-sitter is present as a *format*, not as a
parser binding: `languages/elixir/tree_sitter.clj` parses indented
S-expression output rather than binding a grammar.

Why it matters: the defect class this produces is unbounded.
[`bugs/004`](../bugs/004_java_class_modifiers_produce_unknownclass_owner.md)
(any modifier beyond `public` produced an invented `UnknownClass` owner) and
the nested-type ownership defect still open in `bugs/006` are not independent
bugs; they are two samples from an infinite series. Each fix widens a regex and
the next Java feature reopens it. The Java lane cannot converge this way.

Smallest fix: stop fixing them individually. Bind tree-sitter properly for the
two lanes that are kept, and delete the rest (see Phase 0 and Phase 1).

### 6. High — a fully green suite coexists with all of the above

685 tests and 3,652 assertions pass with zero failures while `repo_map` returns
grammar fixtures and `resolve_context` misses its own ranker. The suite
verifies contract shape, snapshot invariants, diagnostic codes, and lifecycle
mechanics. It contains no test of the form "for this question, this symbol must
appear in the top *k*".

Why it matters: CI cannot currently detect a regression in the only thing the
product sells. Retrieval-quality defects are found by hand, on ReaderLens,
after the fact, and filed into `bugs/`. That is an expensive and slow feedback
loop for the highest-risk part of the system.

Smallest fix: the recall harness in Phase 3 is not a benchmark for a report —
it is the missing test class. Wire it into `scripts/run-mvp-gates.sh` with a
floor.

### 7. Medium — surface area was built ahead of validation

Shipped and maintained with no external user, per this repository's own
[`docs/development-strategy.md`](../docs/development-strategy.md) ("Participation
and schedules are not yet confirmed"): a gRPC edge, PostgreSQL persistence,
PostgreSQL usage metrics, a policy registry with shadow review and automated
promotion, `scheduled-governance-cycle`, `phase5-status-report`, a benchmark
agent, and fourteen MCP tools.

Why it matters twice over. First, `evaluation.clj` is the largest namespace in
the project at 2,060 lines and implements a quality-optimization loop whose
input signal — real usage — does not exist. Second, the gRPC and PostgreSQL
dependencies sit in the root `deps.edn`, so netty, protobuf and the JDBC driver
are loaded on the classpath of every process; `bugs/001` measures
`require semidx.core` at 2,742 ms, which is 82% of a 3.3 s MCP handshake.

Smallest fix: move the unvalidated surfaces behind aliases or out of the
default classpath, and stop maintaining the governance loop until there is a
usage signal to feed it.

### 8. Medium — process artifacts now outgrow the product

46,857 lines of Markdown against 32,623 lines of source. 51 ADRs, 24 plans, 28
reports, 43 notes, 17 idea documents. `RULES.md` is 25 KB and is loaded at the
start of every agent session; `MEMORY.md` is 135 KB.

Why it matters: a tool whose purpose is to reduce an agent's context cost
requires roughly ten thousand tokens of rules before an agent can work on it.
The documentation discipline is genuinely high quality — `bugs/006` is a better
defect report than most commercial teams produce — but the volume is now a tax
on iteration speed, and iteration speed is the scarce resource for a
single-maintainer project with an unproven core claim.

Smallest fix: freeze new governance documents until Phase 3 produces a number.
Fold `MEMORY.md` down to what changes behavior.

## Stack Assessment

**Clojure is not the mistake it is sometimes assumed to be, and it is also not
free.**

What it earned: 32,623 lines of coherent, data-oriented code in six months by
one person; a graph and selection model that is just data; `malli` contracts
mirroring external JSON Schema; a REPL loop that shortens the parser work. On a
Go or Java stack the same functionality would have cost materially more time.

What it costs: JVM startup (2,742 ms of it in `require semidx.core` alone),
a first-run Maven fetch of gRPC, netty, protobuf and PostgreSQL artifacts, and
mandatory external toolchains (jdtls, scip-java, tree-sitter CLI) that already
fail on the author's own machine. An external team cannot install this in ten
minutes, and "an external team installs the build and completes the first
retrieval workflow without the author's intervention" is acceptance criterion
number one in the project's own strategy document. There is also no
contributor pool: the AI-tooling ecosystem does not staff Clojure.

Conclusion: **the stack does not block the core; it blocks distribution.**
Therefore the correct move is not to rewrite the system, but eventually to
rewrite the delivery edge — and only after the core claim is proven. See
Phase 4. The one genuinely excellent architectural decision in this project —
keeping `contracts/schemas/` as language-independent external source of truth —
is what makes such a move cheap when the time comes.

## Stack, If Starting Over Today

This section answers a follow-up question asked during the review: given
everything above, what stack would a rewrite use? It is a design
recommendation, not a call to start one — see the sequencing note at the end of
the section.

**Recommendation: a Rust core, SQLite for storage, tree-sitter compiled in, and
distribution as a single binary through npm and Homebrew.**

### The five constraints that decide it

These are not abstract preferences. Each one is a place where the current
implementation is limited by its stack rather than by its design.

1. **Install in under a minute with no prerequisites.** This is acceptance
   criterion one in `docs/development-strategy.md`. Today it requires a JVM, a
   Maven fetch, jdtls, scip-java and a tree-sitter CLI — and two of those
   providers already fail on the author's own machine (Finding 4).
2. **Process start under 300 ms.** An MCP server restarts with every host
   session. `bugs/001` measures 3.3 s, of which 2,742 ms is `require
   semidx.core`.
3. **Real parsing rather than regular expressions.** Finding 5. The defect
   series is a property of the extraction method, not of individual lanes.
4. **Parallel indexing of a monorepo.** 5,204 ms for 253 files is roughly 20 ms
   per file, single-threaded. At 50,000 files that is over fifteen minutes.
5. **Local embeddings with no external inference API.** Corporate source cannot
   be sent to a third-party endpoint; requiring it ends most enterprise
   conversations at the first call.

No other candidate stack satisfies more than three of the five.

### Composition

| Layer | Choice | Rationale |
| --- | --- | --- |
| Core | Rust | tree-sitter is a first-class native API here, not an FFI wrapper |
| Parsing | `tree-sitter` with grammars as compiled-in crates | No runtime grammar downloads; `.tree-sitter-grammars/` and half of Finding 1 disappear |
| Name resolution | SCIP indexes as optional input, LSP bridge second | Precision becomes an upgrade rather than an install prerequisite |
| Storage | SQLite (`rusqlite`, bundled) | One file replaces PostgreSQL, the in-memory path, and the storage layer; transactions give honest snapshots |
| Lexical search | SQLite FTS5 | BM25 in the same file; a separate search engine is premature |
| Vectors | ONNX/`fastembed` plus `sqlite-vec` or `usearch` | Local inference, model shipped or cached alongside the binary |
| Parallelism | `rayon` | Per-file indexing parallelizes trivially |
| Contracts | `serde` + `schemars` | Types generate JSON Schema; contract drift stops being a category |
| MCP | Official Rust SDK (`rmcp`) | Fastest-moving part of this choice; verify current status before committing |
| CLI / HTTP | `clap`, `axum` | Conventional, no surprises |
| Distribution | Binary plus thin npm wrapper and Homebrew tap | The delivery model used by esbuild, ast-grep, and biome |

### What the choice deletes rather than rewrites

This matters more than the performance argument.

- **The `contracts/` subsystem stops existing.** Today JSON Schema is the
  external truth, `malli` is a hand-maintained mirror, and a validator plus a CI
  gate exist to detect drift between them. With `schemars` the schema is derived
  from the type. One subsystem, one gate, and a class of defects removed.
- **`storage.clj`, PostgreSQL, JDBC, and `usage_metrics.clj` stop existing.**
  SQLite covers snapshots, graph projections, and telemetry in one file.
- **`plans/021` (persistent JVM runtime reuse), the launcher, its benchmark, its
  exclusive locks and profiles stop existing.** That entire track exists to
  fight JVM cold start. A binary starts in tens of milliseconds and the problem
  it solves is gone.
- **The gRPC edge stops existing.** It was a serious transport for a JVM
  deployment story; MCP plus HTTP is sufficient for a single binary.
- **The regex lanes stop existing**, and with them the `bugs/004` series.

Roughly eight thousand of the current 32,623 source lines carry over as logic.
The rest is either absorbed by the stack or should not have been built —
which is also the reason a rewrite here is cheaper than it looks.

### What carries over and is worth real money

- `contracts/schemas/` and `contracts/examples/` as the behavioral
  specification.
- `fixtures/retrieval/` as a ready test corpus.
- Staged retrieval (`adr/024`) and the typed relation model (`adr/039`,
  `adr/040`) — the strongest assets in the project, and language-independent.
- **`bugs/001` through `bugs/009` as the acceptance suite.** Nine reproducible
  defects are a regression set that a new project normally does not have on day
  one.

### Alternatives considered

- **Go** — the honest second choice, and the right one if Rust looks too
  expensive. Single binary, fast builds, simple concurrency. It breaks on
  tree-sitter: cgo only, which undermines cross-compilation across the five
  target platforms and taxes every parser call. The local vector ecosystem is
  also thin.
- **TypeScript / Bun** — native `npx` distribution and the largest contributor
  pool, in the ecosystem where MCP itself lives. But parsing millions of lines
  in JS is slow, tree-sitter arrives via WASM or native bindings, and real
  parallelism means `worker_threads` with serialization at every boundary.
  Viable if the product narrows to TS/JS repositories and sells speed of entry
  rather than graph depth.
- **Java / Kotlin with GraalVM native-image** — the tempting "stay on the JVM
  and still ship a binary" path. In practice, native-image with JNI to
  tree-sitter and reflection is a multi-month project of its own, ending where
  Rust starts.
- **Python** — best ML ecosystem, worst distribution story of the group.
- **Staying on Clojure** — not a retrospective mistake. It produced 32,623 lines
  of coherent code in six months from one maintainer, and the data-oriented
  graph model suits it. It loses only at the delivery boundary. If semidx is
  never handed to an external team, staying is defensible.

### Sequencing note

**No stack fixes Finding 2.** The ranking miss is a relevance model problem, not
a language problem; rewritten in Rust it would return the same wrong five units
in 20 ms instead of 5 s.

So the order does not commute:

1. Build the recall@k harness of Phase 3 first, on the current Clojure code —
   about one week.
2. If semidx loses to a text-search agent, there is nothing to port and three
   months are saved.
3. If it wins on impact-shaped work, the rewrite is no longer a refactor but a
   deliberate replacement of the delivery boundary under a proven core.

A rewrite before step 1 is the most expensive available way to not learn the
answer to the project's central question.

## Direction

### The positioning problem

semidx currently competes on "find the relevant code for this task". That is
the one thing 2026-era coding agents already do well, for free, with no
install, using fast text search plus iteration. Competing there means winning on
retrieval quality by a wide margin, and Findings 1-3 show the current margin is
negative on the project's own repository.

The gap agents cannot close by reading is different: **not "find the code" but
"prove the consequences"**. Which callers exist, what breaks, which tests cover
this path, which configuration, SQL, or schema is bound to this name. An agent
cannot compute that by reading files; it needs a real graph, which it cannot
build on the fly. This is where agents visibly fail today, and it is what
teams pay to prevent.

semidx already owns the assets for that position: typed relations
([`adr/039`](../adr/039-separate-relation-identity-from-resolution-and-evidence.md),
[`adr/040`](../adr/040-expose-bounded-relation-traversal-as-a-public-query-surface.md)),
`impact_analysis`, `traverse_relations`, `snapshot_diff`. They are buried under
eleven other tools and under a "replace text search" narrative.

Market judgement here is opinion, formed against knowledge current to mid-2026,
not a measurement. It should be re-checked against the present state of
LSP-backed MCP servers, hosted code-graph services, and IDE-native indexing
before it is treated as settled.

### Phase 0 — Amputation (1 week)

Remove from the maintained surface, keeping history:

- gRPC edge (`grpc.clj`, `grpc-launcher`, `proto/`, generated classes) — no
  consumer, and its dependencies tax every process start.
- PostgreSQL usage metrics and the entire Phase 5 governance loop
  (`evaluation.clj`, `scheduled-governance-cycle`, `phase5-*`,
  `promote-policy`, policy registry) — an optimization machine with no input
  signal.
- Seven of ten language lanes. Keep Java (ReaderLens is a live proving ground)
  and TypeScript (largest market).
- MCP surface from fourteen tools to four: `index`, `find`, `impact`, `read`.

Expected effect: roughly 40% less code to maintain, ~2 s off process start, and
for the first time the ability to iterate quickly.

### Phase 1 — Repair the fact substrate (3-4 weeks)

- Replace regex extraction with tree-sitter bindings for the two remaining
  lanes, plus SCIP/LSP for name resolution. Stop fixing `bugs/004`-class
  defects one at a time.
- Make provider failure loud (Finding 4).
- Ship the ignore policy (Finding 1).

### Phase 2 — Hybrid retrieval (2-3 weeks)

The current `lexical_overlap` + graph-neighbor seeding is the root cause of
Finding 2. The minimum honest replacement:

1. BM25 over symbols, docstrings, and paths — cheap, robust, and strictly
   better than the current overlap count.
2. Local embeddings (bundled model, no external API) for natural-language
   queries where vocabulary does not match.
3. The graph as expander and verifier, not as candidate source — which is what
   it is already good at.
4. Reciprocal rank fusion over the above.

`SPEC.md` currently files embeddings under "R — research only". That is a
strategic error worth naming: determinism of the graph and embeddings for query
entry are not in conflict. The graph remains the source of truth about
relations; embeddings only find the door into it. Refusing them is the direct
cause of the miss recorded in Finding 2.

### Phase 3 — Measure the core claim (1 week, highest priority of all)

`docs/development-strategy.md` proposes pilots with 2-3 external teams as the
first validation. That is too expensive and too late to be the first test.
A cheaper, reproducible one exists:

> Take N merged pull requests from large open-source Java/TypeScript
> repositories. The query is the issue text. Ground truth is the set of files
> and symbols the PR actually changed. The metric is recall@k and tokens spent
> until full coverage, measured against a text-search agent baseline.

Five hundred PRs and one evening of harness work answer the question that has
been marked `[hypothesis-under-test]` in `SPEC.md` since March. If semidx loses
to a text-search-driven agent on this metric, no pilot will rescue it. If it
wins on impact-shaped tasks, that result is the only evidence the project needs.

This harness is also the missing test class from Finding 6 and belongs in
`scripts/run-mvp-gates.sh` with a floor value.

### Phase 4 — Distribution, conditional on Phase 3

Only if Phase 3 is positive: move the *edge* — indexer, retrieval, MCP server —
into a single self-contained binary with native tree-sitter and instant start,
installable without a JVM or manual toolchain setup. Not a full rewrite; the
external contracts already make the boundary portable. If Phase 3 is negative,
there is nothing to port, and three months were saved.

## Summary Judgement

A well-built engine with a sound contract architecture, honest defect
discipline, and one genuinely valuable asset — the typed relation graph. Also:
an over-wide surface, regex parsers standing in for semantics, an optimization
loop with no users, and documentation growing faster than the product. It will
not win as a smarter text search, because it is competing with a free tool
already in every agent's hands. It can win as the layer that proves
consequences — what an agent cannot compute by reading. That requires no new
stack. It requires dropping most of what is built and running the one
measurement that has been deferred since March.

## Open Questions

- Is the "prove the consequences" positioning still open in the current market?
  This review asserts it from knowledge current to mid-2026 and did not verify
  it against live sources.
- Is ReaderLens available as a fixed, permissioned corpus for the Phase 3
  harness, or does the harness need public repositories only?
- Does dropping seven language lanes conflict with any commitment already made
  to a prospective pilot participant?
