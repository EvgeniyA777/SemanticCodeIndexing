---
title: "MCP Stdio Startup Exceeds Host Handshake Timeout"
doc_type: "bug_report"
lifecycle: "active"
status: "wont_fix"
agent_action: "reference_for_context"
updated: "2026-09-08"
---

# MCP Stdio Startup Exceeds Host Handshake Timeout

Severity as filed: high. Closed on 2026-09-08 as not reproducible **as written**,
with the measurements below replacing the numbers that could not be reproduced.
The underlying concern is real and the remedy for it already exists in this
repository; what does not exist is the 17-20 second handshake this report
describes.

## What the report claimed

The semidx MCP stdio server needed roughly 17-20 seconds to answer `initialize`,
against a 10 second handshake timeout in Codex CLI, so a session with
`required = true` failed to start at all.

## What is measured now (2026-09-08)

Four configurations, each timing a real `initialize` request written to the
server's stdin:

| Configuration | Time to answered `initialize` |
| --- | --- |
| ordinary run | **4.0 s** |
| `clojure -Sforce` (classpath cache recomputed) | 5.5 s |
| `target/classes` removed entirely | 4.0 s |

Inside the process, measured from the JVM's own uptime clock:

| Phase | Time |
| --- | --- |
| JVM start to first evaluated form | 522 ms |
| `require semidx.core` | **2742 ms** |
| `require semidx.mcp.core` | 64 ms |

So the handshake costs about 3.3 s of process time, and the load graph of
`semidx.core` is 82% of it. Nothing in the numbers approaches 17 seconds, and
every configuration leaves at least 2.5x headroom against a 10 second limit.

The original figure is not explained away here — it is simply not reproducible on
this machine today. The most likely cause is a first run against a cold Maven
cache, where the gRPC, Netty, protobuf and PostgreSQL artifacts are downloaded
before the JVM starts; that is a one-time cost of installation rather than a
property of the server. `plans/021` (persistent JVM runtime reuse) also landed
between the report and this measurement.

## What the ecosystem does about slow MCP startup

Checked against current practice rather than decided from first principles.

**On the client side this is a known rough edge, not our peculiarity.** Hosts are
moving toward lazy initialization and configurable thresholds rather than hard
failure — open requests exist against opencode, Claude Code and Copilot CLI — and
the specification has an open proposal on timeout coordination (SEP-1539). A
server that takes twelve seconds is slow, not broken, and treating a timeout as a
permanent failure is increasingly treated as a client bug.

**On the server side the canonical remedies are four**, and this repository's
position against each:

| Practice | Our state |
| --- | --- |
| Precompute the tool list; no work in `list_tools` | Already so — `tool-definitions` is a static `def` |
| No database or filesystem work during the handshake | Already so — the handshake answers from the capability projection |
| Keep a warm, long-lived process instead of paying startup per session | **Already implemented**: the launcher-managed `mcp-http` endpoint (`plans/021`, `docs/mcp-api.md`) survives host restarts; stdio cannot, by definition, because the host owns the process |
| Shrink the import graph; lazy-load what only some tools need | **The one lever left**, and it is small — see below |

## The one actionable lever, measured

Within `semidx.core`'s 2742 ms:

| Namespace | Load cost |
| --- | --- |
| `semidx.runtime.retrieval` | 1384 ms |
| `next.jdbc` | 596 ms |
| `semidx.runtime.storage` | 225 ms |
| `semidx.core` itself | 329 ms |
| compression, semantic-quality, snapshot-diff | 91 ms combined |

`next.jdbc` and the PostgreSQL driver are ~820 ms of the critical path for a
capability PostgreSQL is explicitly optional for — `RULES.md` keeps in-memory
storage a first-class path. Deferring them would need `PostgresStorage` moved to
its own namespace resolved on first use, because a record body referencing
`jdbc/*` forces the namespace to load at compile time.

That is a real 20% of startup, and it is deliberately **not** being done now:
4.0 s already fits every host limit known today with room to spare, and the
change restructures a persistence layer with its own tests to buy headroom
nothing is currently asking for. It becomes worth doing if a host with a five
second budget appears, or if `initialize` regresses past ~6 s.

## What to do if this recurs

1. Time it the way this report does — write one `initialize` line to the server's
   stdin and measure to the answer. A number without a configuration named beside
   it cannot be acted on.
2. Check whether the Maven cache is cold; that is installation, not startup.
3. Prefer the launcher-managed `mcp-http` endpoint over stdio for any host that
   restarts servers often. It is the ecosystem's own answer to this problem and
   it already works here.
