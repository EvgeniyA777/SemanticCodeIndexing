---
title: "Evaluating a Full Zig Rewrite for Startup and Runtime Performance"
doc_type: "architecture_idea"
lifecycle: "concept"
status: "proposed"
agent_action: "use_as_input_for_future_plan_only"
updated: "2026-08-26"
---

# Evaluating a Full Zig Rewrite for Startup and Runtime Performance

## Executive Summary

Rewriting semidx in Zig would almost certainly improve cold process startup,
baseline memory consumption, binary distribution, and control over data layout.
It would not automatically produce the same improvement in fresh indexing or
steady-state retrieval latency.

The current implementation contains three different performance domains that
must be evaluated separately:

1. **Process startup.** Clojure namespace loading, JVM initialization, classpath
   construction, and loading of the full runtime dependency graph dominate the
   time before an MCP server becomes ready. Zig has a strong structural
   advantage here because it produces a native executable without a JVM
   bootstrap phase.
2. **Fresh indexing.** The pipeline performs filesystem discovery, file reads,
   language-specific parsing, semantic normalization, relation resolution, and
   construction of several in-memory indexes. Some of this work is sequential,
   and Clojure files currently cause a separate external `clj-kondo` process to
   be launched with caching disabled. A language rewrite alone does not remove
   those costs.
3. **Retrieval from an existing in-memory index.** This work consists primarily
   of hash-map lookups, graph traversal, candidate generation, ranking, bounded
   packet assembly, and optional file reads. It is already relatively fast for
   the observed repository size. Zig can improve throughput and memory locality,
   but the absolute user-visible latency reduction may be small unless semidx is
   operating on much larger repositories or under sustained concurrent load.

The recommended direction is therefore **not to authorize a full rewrite based
on language-level expectations alone**. First establish a reproducible
performance baseline, remove the obvious process and sequencing bottlenecks in
the current implementation, and then build a narrow Zig vertical slice against
the same contracts and fixtures. A full rewrite should proceed only if explicit
product requirements such as sub-100-millisecond cold startup, a small resident
memory target, standalone binary distribution, or high-throughput indexing
remain unattainable with a less disruptive architecture.

## Question

Would semidx start and run faster if the entire implementation were rewritten
from Clojure/JVM to Zig?

The short answer is:

- **Startup:** yes, probably dramatically faster.
- **Memory:** probably substantially lower, especially at idle and for large
  indexes with compact native representations.
- **Fresh indexing:** possibly faster, but only substantially faster if the
  rewrite also changes parsing, batching, caching, parallelism, and data layout.
- **Warm retrieval:** likely faster in microbenchmarks, but not necessarily
  enough to justify a full rewrite on its own.
- **Development and correctness risk:** significantly higher because the rewrite
  must reproduce multiple language lanes, contracts, transports, storage
  behavior, ranking semantics, guardrails, lifecycle rules, and diagnostic
  behavior.

## Current Implementation Shape

semidx is not a single parser or a small command-line tool. It is a semantic
indexing and retrieval system with several public and internal surfaces:

- library APIs;
- MCP stdio and MCP HTTP transports;
- HTTP and gRPC runtime edges;
- JSON Schema contracts and Malli validation mirrors;
- in-memory and optional PostgreSQL persistence paths;
- usage metrics;
- index lifecycle and snapshot reuse;
- compact selection, expansion, and late detail-fetch stages;
- language adapters for several source-language lanes;
- semantic relation, caller, callee, module, path, symbol, and test-target
  indexes;
- confidence, policy, budget, diagnostic, and guardrail behavior.

The runtime currently uses Clojure 1.12.4 on the JVM. Its dependency graph also
includes JSON, validation, JDBC, PostgreSQL, gRPC, Protobuf, and Netty libraries.
Even when a particular server surface does not actively use every subsystem,
namespace and class loading can make the cold-start boundary considerably larger
than the small stdio loop visible at the entry point.

## Observed Baseline

The following observations were collected from the working repository on
2026-08-26. They are directional measurements, not a controlled benchmark
report.

### MCP Stdio Cold Process Startup

The command below was executed three times with standard input immediately
closed so that the server exited after reaching its input loop:

```text
/usr/bin/time -lp clojure -M:mcp </dev/null
```

Observed wall-clock results were:

```text
4.75 seconds
3.91 seconds
3.24 seconds
```

The decreasing results indicate operating-system and JVM-related warm-cache
effects. This measurement includes command-line startup and process shutdown; it
does not isolate time-to-first-valid-MCP-response. It nevertheless establishes
that the present process boundary is measured in seconds rather than
milliseconds.

A native Zig executable should have a decisive advantage at this boundary. A
precise multiplier must be measured with a working prototype, but eliminating
the JVM and broad class-loading path can plausibly change startup by one or two
orders of magnitude.

### Fresh Index Construction

A fresh MCP `create_index` call against the current semidx repository reported:

- 182 indexed files;
- 3,170 semantic units;
- an initial full build rather than snapshot reuse;
- approximately 16 seconds of tool wall time.

This result includes MCP transport and tool orchestration and is affected by the
current repository contents, including vendored grammar sources. It should not
be treated as a stable benchmark number. It does show that fresh indexing is a
much larger operation than warm retrieval and that optimizing only process
startup will not make first-time indexing instantaneous.

### Warm Retrieval

Observed internal diagnostics for representative semantic retrieval operations
reported approximately 23 milliseconds across query validation, candidate
generation, ranking, packet assembly, raw code fetch, and finalization for the
selected context. This is not a complete latency study, but it indicates that
warm retrieval is already in a range where protocol overhead, filesystem access,
response size, and the consuming agent may matter more than a several-fold
improvement in the ranking loop.

## Where the Current Time Goes

### 1. JVM and Namespace Startup

The MCP stdio server entry point performs relatively little explicit work: it
parses arguments and environment variables, optionally creates a PostgreSQL
usage-metrics adapter, logs startup, and enters the server loop. The observed
multi-second startup therefore points primarily to the runtime and dependency
loading boundary rather than complex application initialization in `-main`.

Zig directly addresses this category. A native executable has no JVM bootstrap,
does not construct a Clojure runtime, and can link only the functionality needed
for a particular binary. Separate Zig executables could also be produced for
stdio, HTTP, and gRPC without loading unrelated surfaces.

### 2. Sequential File Parsing

Fresh indexing currently walks the selected paths with a sequential `reduce`.
For every path it reads the file, selects a language adapter, parses and
normalizes the result, and appends file records, units, relations, and
diagnostics to aggregate collections.

The files are mostly independent during this phase. Cross-file enrichment and
relation resolution occur later, so a bounded worker pool could parse many files
in parallel without changing public contracts. This opportunity exists in both
Clojure and Zig. A Zig rewrite does not automatically exploit it; it must be an
explicit architectural change.

### 3. Per-File `clj-kondo` Process Launches

The default Clojure parser runs an external command for each Clojure file:

```text
clj-kondo --lint <single-file> --cache false --config <analysis-config>
```

This is a high-probability indexing bottleneck because it combines:

- process creation for every Clojure file;
- repeated `clj-kondo` initialization;
- disabled `clj-kondo` cache reuse;
- EDN serialization and parsing for every invocation;
- sequential execution in the current file loop.

If a Zig implementation keeps the same per-file external invocation, it will
still pay almost all of this cost. The orchestration layer may become faster,
but it cannot optimize time spent inside child processes.

The first experiment should be to lint a repository or a language-specific
batch once, consume one analysis result, and partition that result by source
file. Cache-enabled incremental analysis should be evaluated separately. These
changes may provide a larger fresh-index improvement than changing the host
language.

### 4. Semantic Index Construction

After parsing, semidx performs additional whole-index work:

- semantic ID enrichment;
- creation of the unit-by-ID map and stable unit order;
- repository identity resolution;
- caller and callee index construction;
- relation target resolution and forward/reverse relation indexing;
- symbol, path, and module indexes;
- module-dependent and test-target indexes;
- file snapshots and lifecycle metadata.

This phase is more likely to benefit directly from Zig. Compact structs,
interned strings, arena allocation, contiguous arrays, numeric IDs, and
purpose-built hash tables can reduce allocation pressure and improve cache
locality. However, an exact port from persistent Clojure maps to general-purpose
Zig hash maps would leave part of that opportunity unused. The performance gain
depends on designing a native representation, not merely translating functions.

### 5. Retrieval and Ranking

Warm retrieval operates on the already-built index. Potential Zig improvements
include:

- denser graph and unit representations;
- fewer intermediate allocations;
- faster bounded traversals;
- explicit buffer reuse;
- lower serialization overhead when native response structures are emitted
  directly as JSON or Protobuf;
- predictable concurrency without JVM warmup.

The counterargument is absolute latency. If common retrieval requests already
complete in tens of milliseconds, reducing engine work from, for example, 20
milliseconds to 5 milliseconds is useful but may not materially change an
agent's end-to-end workflow. Retrieval becomes a stronger Zig justification if
large repositories, concurrent tenants, or sustained query throughput cause
the current latency distribution or garbage collection behavior to degrade.

### 6. Persistence and Network Boundaries

PostgreSQL, filesystem reads, HTTP, gRPC, and MCP serialization are external or
I/O-bound components. Zig may reduce adapter overhead, but it cannot make the
database or disk complete work faster. The useful questions are whether the
current runtime performs unnecessary round trips, copies too much data, or
serializes overly large intermediate structures.

## Expected Effect by Area

The following table describes hypotheses to test, not promised speedups.

| Area | Expected Zig effect | Main condition |
| --- | --- | --- |
| Cold executable startup | Very large improvement | Native binary does not eagerly initialize heavy optional subsystems |
| Idle resident memory | Large improvement | Runtime avoids general-purpose JVM and boxed persistent structures |
| Index memory footprint | Potentially large improvement | Native data layout uses interning, numeric IDs, arenas, and compact adjacency lists |
| Snapshot loading | Moderate to large improvement | Snapshot format supports direct, compact decoding or memory mapping |
| Fresh indexing with unchanged parser architecture | Small to moderate improvement | External parser launches and sequential work remain dominant |
| Fresh indexing with batch parsing and bounded parallelism | Potentially large improvement | Parser and graph construction are redesigned together |
| Incremental indexing | Architecture-dependent | Correct invalidation and snapshot reuse matter more than language choice |
| Warm retrieval latency | Moderate relative improvement, possibly small absolute improvement | Repository size and query complexity are high enough for CPU work to dominate |
| High-concurrency throughput | Potentially large improvement | Native implementation avoids allocation and contention bottlenecks |
| PostgreSQL and network latency | Limited direct improvement | External I/O remains dominant |
| Binary packaging and deployment | Large improvement | One or a small set of self-contained executables replaces JVM/classpath setup |

## Costs and Risks of a Full Rewrite

### Semantic Parity

semidx behavior is defined by more than response schemas. Ranking reasons,
confidence ceilings, fallback behavior, parser diagnostics, relation provenance,
budget truncation, lifecycle decisions, guardrails, and ordering stability all
affect consumers. A rewrite that returns valid JSON but changes those semantics
can silently degrade agent behavior.

Every current contract example, retrieval fixture, semantic-quality scenario,
language-onboarding test, and integration suite would need to run against both
implementations during migration.

### Parser Parity

Language adapters contain accumulated handling for imports, aliases, generated
ownership, dispatch, calls, test linkage, parser fallbacks, and incomplete or
ambiguous syntax. Reimplementing that logic is likely to be a larger effort than
reimplementing MCP or basic indexing data structures.

The Zig ecosystem has strong C interoperability, which is useful for native
Tree-sitter integration. Clojure-specific semantic analysis remains tied to
`clj-kondo` unless its analysis model is reimplemented, embedded through a new
boundary, or treated as an external provider.

### Dynamic Development Cost

Clojure is well suited to evolving symbolic data models and inspecting nested
maps at a REPL. Zig provides tighter control and stronger compile-time checks,
but changes to contracts and semantic IR generally require more explicit types,
memory ownership decisions, error handling, and serialization code.

The rewrite may improve runtime efficiency while slowing experimentation with
new languages, relation facts, ranking evidence, and guardrail policy. This is a
product tradeoff, not merely an implementation preference.

### Memory Safety and Complexity

Zig makes allocation and ownership explicit. That can produce an excellent
runtime, but large graph structures, cached selections, snapshot replacement,
concurrent parsing, and long-lived server state create opportunities for leaks,
use-after-free errors, invalid references, and unbounded arenas if ownership
boundaries are not carefully designed.

### Migration Duration and Split-Brain Behavior

A long rewrite can leave two implementations with subtly different contracts
and ranking behavior. New features then either stop until the rewrite finishes
or must be implemented twice. A compatibility harness and a clearly defined
authority for expected behavior are mandatory before beginning a broad port.

## Alternatives to a Full Rewrite

### Option A: Optimize the Existing Clojure Runtime

This is the lowest-risk first step:

1. Run `clj-kondo` once per repository or batch rather than once per file.
2. Evaluate safe cache reuse instead of forcing `--cache false` everywhere.
3. Parse independent files using bounded parallelism.
4. Measure and optimize whole-index relation and caller resolution separately.
5. Improve incremental indexing so unchanged files do not pass through the
   complete parser pipeline.
6. Keep MCP, HTTP, or gRPC servers long-lived and reuse loaded snapshots.
7. Split lightweight server surfaces from optional PostgreSQL and gRPC
   dependencies when possible.
8. Investigate Clojure AOT compilation and GraalVM native-image feasibility as
   experiments, with special attention to dynamic resolution, reflection,
   validation, JDBC, Netty, and gRPC behavior.

This option directly tests whether the observed problem is primarily a JVM
startup issue or an indexing architecture issue.

### Option B: Native Zig MCP Launcher or Proxy

A small Zig executable could provide an immediate, low-memory MCP process and
connect to a long-lived semidx daemon. This improves client-facing startup and
deployment ergonomics without duplicating semantic behavior.

Tradeoffs include daemon lifecycle management, connection discovery, version
compatibility, and the fact that first daemon startup still pays the JVM cost.
For editors and agent environments that issue many short-lived MCP launches,
this may deliver most of the perceived startup benefit at a fraction of the
rewrite cost.

### Option C: Zig Indexing Worker

The most CPU- and memory-sensitive indexing stages could move behind a stable
provider boundary:

- filesystem discovery and fingerprinting;
- native Tree-sitter parsing;
- semantic IR serialization;
- string interning and compact graph construction;
- snapshot encoding and loading.

Clojure would remain the contract, policy, retrieval, and orchestration layer
initially. This isolates semantic differences and makes it possible to compare
the worker with the current implementation on identical inputs.

The boundary must avoid converting every native structure into a large textual
intermediate representation, or serialization overhead may consume much of the
gain.

### Option D: Native Retrieval Core

Another hybrid is to keep language adapters in Clojure but store the resulting
semantic IR in a Zig-native index and expose retrieval operations through a
native library or local service. This is most attractive when profiling shows
that memory footprint, large-graph traversal, or concurrent retrieval is the
actual production constraint.

### Option E: Full Zig Rewrite

A complete rewrite gives maximum control over startup, memory, concurrency,
packaging, snapshots, and runtime surfaces. It also carries the maximum parity
and delivery risk. It should be the final option selected from evidence rather
than the first optimization attempted.

## Required Benchmark Program

The current benchmark runner primarily verifies semantic retrieval outcomes. It
creates indexes and evaluates fixture expectations, but it does not establish a
performance baseline or regression thresholds. A language-migration decision
needs a dedicated benchmark suite.

### Metrics

At minimum, collect:

- process start to first valid MCP `initialize` response;
- process start to `tools/list` response;
- idle and post-index resident memory;
- fresh index wall time, CPU time, and peak memory;
- snapshot reuse and snapshot load time;
- single-file incremental update time;
- multi-file incremental update time;
- `resolve_context` p50, p95, and p99 latency;
- expansion and detail-fetch p50, p95, and p99 latency;
- indexing throughput by files, bytes, and semantic units;
- retrieval throughput under bounded concurrency;
- snapshot size and serialization/deserialization cost;
- child-process count and time spent in external parsers;
- garbage-collection time for the JVM implementation;
- semantic parity and retrieval-quality pass rate.

### Repository Tiers

Use several representative corpora:

1. A small single-language repository for startup-dominated workflows.
2. The semidx repository for realistic Clojure and multi-language behavior.
3. A medium repository with thousands of source files.
4. A large repository with tens of thousands of files and a large relation
   graph.
5. A deliberately mixed-language repository exercising all active providers.
6. A repository with small incremental edits to measure invalidation quality.

Vendored grammars, generated files, ignored directories, and fixtures should be
explicitly classified. Otherwise repository discovery differences can make two
implementations appear faster while they are indexing different content.

### Methodology

- Pin hardware, operating-system version, repository commit, parser versions,
  and configuration.
- Separate cold OS-cache, warm OS-cache, fresh-process, and warm-process runs.
- Run enough iterations to report distributions rather than a single number.
- Record failed parses, fallbacks, unit counts, and relation counts beside every
  timing result.
- Compare identical contract outputs or normalized semantic IR, not merely
  successful process exit.
- Preserve raw benchmark data and the command lines used to produce it.
- Treat a faster result with worse retrieval quality or missing semantic units
  as a failed comparison.

## Proposed Experimental Sequence

### Phase 1: Instrument the Current Runtime

Add stage timing and counters around:

- language discovery;
- file reads;
- parser invocation by language;
- external process execution;
- semantic finalization;
- semantic ID enrichment;
- caller and callee construction;
- relation resolution and indexing;
- file snapshot construction;
- persistence and snapshot loading;
- retrieval stages and serialization.

This phase should answer whether fresh indexing is dominated by `clj-kondo`,
other parsers, graph construction, filesystem access, or persistence.

### Phase 2: Remove Known Architectural Bottlenecks

Prototype batch `clj-kondo` analysis, safe caching, bounded parallel parsing,
and improved incremental reuse. Re-run the same benchmark suite after every
change. These improvements establish the strongest Clojure baseline that a Zig
prototype must beat.

### Phase 3: Build a Zig Vertical Slice

The first Zig prototype should be deliberately narrow but end-to-end. A useful
slice would:

1. accept a repository root and a fixed language policy;
2. discover and parse one or two language lanes;
3. emit the canonical semantic IR or construct a minimal native index;
4. implement `create_index`, `repo_map`, and one representative
   `resolve_context` path;
5. return contract-compatible JSON;
6. run selected fixtures against both implementations;
7. report startup, memory, indexing, and retrieval metrics.

This is large enough to expose real parsing, ownership, serialization, and
contract costs but small enough to discard if the result is not compelling.

### Phase 4: Make the Architecture Decision

Choose among optimized Clojure, a hybrid native worker, a native launcher, a
native retrieval core, or a full rewrite using measured product-level outcomes.
Do not extrapolate a full rewrite from a parser-only microbenchmark.

## Decision Criteria

A full Zig rewrite becomes reasonable when several of the following are true:

- semidx must behave like a short-lived CLI and cold startup must be below an
  explicit threshold such as 100 milliseconds;
- JVM deployment or classpath management is unacceptable for target users;
- idle or indexed resident memory exceeds a hard product constraint;
- large-repository indexing remains too slow after batching, caching,
  incremental reuse, and bounded parallelism are implemented;
- profiling shows that in-process graph construction or retrieval, rather than
  external parsers or I/O, consumes most of the remaining time;
- high concurrency or predictable tail latency is a core deployment need;
- the team is prepared to maintain native parsing integrations, explicit memory
  ownership, and cross-platform builds;
- a Zig vertical slice demonstrates semantic parity on representative fixtures
  and a material end-to-end improvement, not only a synthetic microbenchmark.

Keeping Clojure or choosing a hybrid is preferable when:

- semidx normally runs as a long-lived MCP or HTTP service;
- startup is paid once and retrieval is already fast enough;
- fresh indexing is dominated by per-file child processes or filesystem work;
- semantic behavior is changing rapidly and REPL-driven development remains
  valuable;
- native integration would require duplicating most language-specific logic;
- a batch parser and incremental index meet the product latency target.

## Preliminary Recommendation

Do not begin with a full Zig rewrite.

The evidence currently supports the following order:

1. Create a real performance benchmark suite and capture a reproducible
   baseline.
2. Batch `clj-kondo` analysis and evaluate cache reuse.
3. Introduce bounded parallel parsing and improve incremental indexing.
4. Measure the best achievable long-lived Clojure runtime.
5. Prototype a small Zig launcher or indexing/retrieval vertical slice.
6. Compare end-to-end startup, memory, indexing, retrieval, and semantic parity.
7. Proceed to a full rewrite only if the measured remaining gap maps to an
   explicit product requirement.

The likely outcome is that Zig can make semidx an excellent native executable,
especially for cold-start and low-memory use cases. The uncertain part is not
whether Zig can execute loops and graph operations faster; it can. The uncertain
part is whether those operations are the dominant end-to-end cost after external
parsers, filesystem access, persistence, protocol handling, and semantic parity
are included. That question should be answered experimentally before accepting
the cost and risk of rewriting the entire system.
