---
title: "Persistent JVM Runtime Reuse Plan"
doc_type: "architecture_plan"
lifecycle: "completed"
status: "completed"
agent_action: "do_not_implement_again"
updated: "2026-08-28"
---

# Architecture Plan: Persistent JVM Runtime Reuse

This plan removes avoidable cold JVM startup from short-lived semidx
invocations. It does not change indexing semantics, retrieval ranking, staged
retrieval, or public context contracts.

## Decision Boundary

- Existing server modes are already long-lived once started:
  - MCP stdio keeps one request loop for the lifetime of the MCP process.
  - MCP HTTP keeps in-memory sessions inside one HTTP server process.
  - Runtime HTTP and runtime gRPC keep one server process alive.
- `clojure -M:runtime` remains a one-shot CLI: it starts a JVM, runs the
  request, writes the response, and exits.
- This plan owns the missing launcher/client path that can discover and reuse a
  local runtime before starting a new JVM.
- This plan must not introduce a second retrieval protocol or a competing
  index lifecycle policy.

## Goal

Provide a local command path that:

- checks whether a project-scoped semidx runtime is already healthy;
- reuses it for short-lived requests;
- starts it only when needed;
- handles stale process metadata safely;
- preserves existing runtime HTTP or MCP HTTP contracts as the actual request
  protocol.

## Scope

In:

- A small local launcher with `status`, `start`, `stop`, and `request`
  commands.
- Project-scoped runtime identity based on canonical root path plus repo
  identity where available.
- Health-check-based reuse before process start.
- Deterministic lock handling so two clients do not start two runtimes for the
  same project at the same time.
- Stale PID or stale port cleanup.
- First-class documentation for the difference between stdio process-lifetime
  reuse and HTTP/gRPC cross-invocation reuse.
- Tests for launcher state transitions using fake process and HTTP clients
  before any real process smoke test.

Out:

- Rewriting the indexer.
- Replacing MCP stdio, MCP HTTP, runtime HTTP, or runtime gRPC.
- Making one-shot `get_context` the canonical retrieval default.
- Solving distributed multi-host runtime coordination.
- Sharing one writable policy registry across multiple runtime processes.
- Introducing production service supervision.

## Assumptions

- The highest-value first slice is local developer and agent usage, not
  production deployment.
- Runtime HTTP is the simplest first reuse target for one-shot CLI-style
  requests because it already has health and JSON request endpoints.
- MCP HTTP remains the preferred reuse target for MCP clients that support
  Streamable HTTP.
- MCP stdio reuse is controlled by the MCP host process lifetime; a launcher can
  document and help configure alternatives, but it cannot force a host that
  restarts stdio per request to keep that process alive.
- The launcher-managed server should bind to `127.0.0.1` by default.

## Boundaries

### Runtime launcher

Responsibility: command orchestration for `status`, `start`, `stop`, and
`request`.

Knows about: project identity, local state file path, lock acquisition, selected
runtime profile, and the launcher-owned process runner interface.

Does not know about: retrieval ranking, parser internals, policy arbitration,
or ContextPacket shaping.

Why this boundary exists: startup/reuse policy should change without touching
the indexer or retrieval core.

### Runtime state store

Responsibility: read and write local runtime metadata.

Knows about: root identity, profile, pid, port, startup time, auth material
reference, and last health result.

Does not know about: JSON request payloads or retrieval semantics.

Why this boundary exists: stale process cleanup and lock behavior must be
testable without spawning real JVMs.

### Runtime health client

Responsibility: verify whether a recorded local runtime can serve requests.

Knows about: profile endpoint shape, timeout, and optional auth header.

Does not know about: process spawning or state-file mutation.

Why this boundary exists: process reuse should be based on observed health, not
only on PID existence.

### Runtime request client

Responsibility: forward one-shot request payloads to the selected long-lived
runtime protocol.

Knows about: runtime HTTP endpoint mapping in the first slice; later MCP HTTP
or gRPC adapters can plug in behind the same caller role if needed.

Does not know about: lock files, process startup, or stale cleanup.

Why this boundary exists: the launcher can reuse different transports without
rewriting command orchestration.

### Process runner

Responsibility: start and stop managed local runtime processes.

Knows about: command arguments, environment values explicitly passed by the
launcher, and process liveness checks.

Does not know about: retrieval requests or response bodies.

Why this boundary exists: unit tests need a fake runner; production code needs a
real JVM process runner.

## Contracts

### Launcher command contract

Client: developer, script, or agent wrapper.

Shape:

- `status --root <path> [--profile runtime-http|mcp-http]`
- `start --root <path> [--profile runtime-http|mcp-http] [--port <port>]`
- `stop --root <path> [--profile runtime-http|mcp-http]`
- `request --root <path> --query <file> [--out <file>]`

Variation strategy: direct command implementation over role-shaped internal
functions; no plugin system.

### Runtime state contract

Client: launcher orchestration.

Shape:

- `schema_version`
- `root_path`
- `repo_key`
- `profile`
- `pid`
- `host`
- `port`
- `started_at`
- `last_health_at`
- optional local auth token reference or token value with restrictive file
  permissions

Variation strategy: versioned EDN map with additive fields.

### Reuse decision contract

Client: launcher orchestration.

Shape:

- `:reused` when health check passes
- `:started` when no healthy runtime exists and start succeeds
- `:stale-cleaned` when recorded metadata was stale and safely replaced
- `:blocked` when lock, auth, port conflict, or process ownership prevents safe
  reuse

Variation strategy: pure function over state plus health observation.

### Request forwarding contract

Client: one-shot command path.

Shape: preserve existing runtime HTTP request and response bodies. The launcher
may translate CLI flags into HTTP requests, but it must not alter ContextPacket
or retrieval result semantics.

Variation strategy: composition over a narrow request-client role.

## Dependency Direction

- Launcher orchestration depends on state-store, health-client, request-client,
  and process-runner roles.
- HTTP/MCP/gRPC transport details plug into those roles.
- Runtime HTTP and MCP HTTP remain protocol owners.
- Retrieval core remains below all launcher code and has no dependency on the
  launcher.

## Risks

1. [High] Stale runtime reuse returns context from the wrong repository.
   Why it matters: this violates the project identity and snapshot honesty
   invariants.
   Mitigation: include canonical root and repo identity in state; require the
   health response or first request response to match the requested root.

2. [High] Two concurrent invocations start two runtimes.
   Why it matters: port conflicts, duplicate indexing cost, and ambiguous
   ownership.
   Mitigation: use an atomic lock around start-if-needed; re-check health after
   acquiring the lock.

3. [Medium] Local auth token leaks through committed files or logs.
   Why it matters: local-only services are still callable by local processes.
   Mitigation: keep state outside committed paths or under ignored runtime
   paths, use restrictive file permissions, and never log token values.

4. [Medium] Launcher turns into a second runtime API.
   Why it matters: duplicated contracts drift from runtime HTTP/MCP.
   Mitigation: forward to existing protocol endpoints and validate against
   existing contracts.

5. [Medium] MCP stdio clients expect process reuse the launcher cannot enforce.
   Why it matters: user-visible startup cost may remain for hosts that restart
   stdio servers.
   Mitigation: document the distinction and prefer MCP HTTP for clients that can
   reuse a local HTTP MCP server.

## Implementation Sequence

### Stage 0: Confirm launch path and choose first profile

Status: completed on 2026-08-27.

- Measure or log the actual path that repeatedly starts the JVM.
- Decide whether the first implementation targets runtime HTTP one-shot reuse,
  MCP HTTP reuse, or both profiles.
- Record the chosen profile and acceptance metrics in this plan before source
  implementation.

Stage 0 decision:

- First profile: `runtime-http`.
- Reason: runtime HTTP already exposes local health plus JSON request endpoints,
  so it can replace repeated one-shot CLI invocations without adding MCP session
  handling to the first slice.
- Repeated-start scenario: short-lived `clojure -M:runtime` and wrappers around
  it pay cold JVM startup per invocation. MCP stdio may also pay repeated
  startup when the MCP host restarts the stdio process; that remains host
  lifetime scoped until the MCP HTTP profile is implemented or documented for
  that host.

Acceptance:

- The repeated-start scenario is documented with exact command or host config.
- The first profile is selected with a clear reason.

### Stage 1: Pure launcher decision kernel

Status: completed on 2026-08-27.

- Add pure project identity, state normalization, health observation, and reuse
  decision functions.
- Add tests for missing state, healthy state, stale PID, stale port, root
  mismatch, and lock contention outcomes.

Delivered:

- `semidx.runtime.launcher/desired-runtime` builds a desired local runtime
  identity from canonical repo identity.
- `normalize-runtime-state`, `normalize-desired-runtime`, and
  `decide-runtime-reuse` provide the pure state/health/process/lock decision
  kernel.
- `semidx.runtime.launcher-test` covers missing state, healthy reuse, explicit
  root-confirmed health, root mismatch, profile mismatch, endpoint mismatch,
  dead PID, closed port, failed health, and lock contention.

Acceptance:

- Reuse decisions are deterministic and unit-tested without real process
  spawning.

### Stage 2: Runtime HTTP request reuse slice

Status: completed on 2026-08-27.

- Add a launcher command alias for `status`, `start`, `stop`, and `request`.
- Make `request` reuse a healthy runtime HTTP server or start one under lock.
- Forward the existing one-shot query file shape to runtime HTTP and preserve
  output behavior.
- Add a narrow smoke test that proves two sequential requests reuse one runtime
  process.

Delivered:

- `semidx.runtime.launcher-state` owns the per-slot state file, start lock, and
  log placement under `~/.cache/semidx/runtime/<workspace_key>-<profile>/`,
  outside the repository, with owner-only permissions.
- `semidx.runtime.launcher-http` owns the health client and the request client.
  `resolve-context-detail` mirrors `semidx.core/resolve-context-detail` by
  forwarding to `/v1/retrieval/resolve-context` and
  `/v1/retrieval/fetch-context-detail` without reshaping bodies.
- `semidx.runtime.launcher-process` owns command construction, PID liveness,
  port probing, process start, and graceful/forced stop.
- `semidx.runtime.launcher-cli` orchestrates `status`, `start`, `stop`, and
  `request` over injectable roles, re-checking health after acquiring the start
  lock.
- `semidx.runtime.launcher/runtime-slot-key` and an additive `owned` state field
  extend the pure kernel for slot addressing and adoption.
- A runtime already listening on the requested endpoint is adopted with
  `owned false`; `stop` refuses to terminate a runtime the launcher did not
  start.
- `deps.edn` gained the `:launcher` alias.

Acceptance:

- The second `request` invocation does not start a second JVM. Verified by the
  in-process reuse smoke test and by a manual two-invocation run against a real
  spawned runtime JVM.
- Output matches the existing runtime CLI for the same root and query, plus the
  additive `project_context` field that every runtime HTTP response already
  carries.

### Stage 3: MCP HTTP profile and docs

- Add or document launcher support for MCP HTTP when the client can use
  Streamable HTTP.
- Provide config snippets that point clients at the reused local MCP HTTP
  endpoint.
- Keep stdio documented as host-lifetime scoped.

Acceptance:

- Docs show which clients should use MCP HTTP reuse and which remain stdio
  process-bound.

### Stage 4: Hardening and benchmark gate

- Add startup, warm request, and stop latency measurements.
- Add stale state recovery tests around killed processes and occupied ports.
- Add a small benchmark comparing one-shot CLI cold start against launcher reuse.

Acceptance:

- Launcher reuse has a measured latency win and no contract drift from existing
  runtime HTTP/MCP behavior.

## Verification Plan

- Unit tests for pure launcher decision logic.
- Focused tests for command argument parsing.
- Contract validation for unchanged runtime response shapes.
- Runtime smoke proving process reuse over two sequential requests.
- `clojure -M:ccc check --root .` after docs updates.

## Documentation Updates

- `README.md`: explain process model and link this plan.
- `docs/runtime-api.md`: mark runtime CLI as one-shot and HTTP/gRPC as
  long-lived reuse targets.
- `docs/mcp-api.md`: mark stdio as process-lifetime scoped and MCP HTTP as the
  reuse-friendly MCP transport.
- `FEATURES.md`: track this as P1 until implemented.
- `docs/development-strategy.md`: keep this as Stage 2.5 integration work.
- `docs/roadmap-status.md` and `MEMORY.md`: record current status and next
  owner.
