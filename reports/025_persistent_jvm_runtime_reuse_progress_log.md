---
title: "Persistent JVM Runtime Reuse Progress Log"
doc_type: "progress_log"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-28"
---

# Persistent JVM Runtime Reuse Progress Log

Execution log for
[`plans/021_persistent_jvm_runtime_reuse_plan.md`](../plans/021_persistent_jvm_runtime_reuse_plan.md).

## Stage 0: Launch Path And First Profile

Status: completed on 2026-08-27.

Summary:

- Confirmed the process-model gap: existing server paths are long-lived once
  started, while `clojure -M:runtime` remains a one-shot CLI.
- Selected `runtime-http` as the first reuse profile.
- Deferred MCP HTTP reuse to a later stage because MCP HTTP adds session
  handling that is unnecessary for the first CLI-style request path.

Evidence:

- `src/semidx/runtime/cli.clj` starts the JVM, handles one request, and exits.
- `src/semidx/runtime/http.clj` starts an HTTP server and blocks.
- `src/semidx/mcp/server.clj` runs a stdio request loop for the process
  lifetime.
- `src/semidx/mcp/http_server.clj` runs a local HTTP MCP server.

## Stage 1: Pure Launcher Decision Kernel

Status: completed on 2026-08-27.

Summary:

- Added `semidx.runtime.launcher` as the pure decision kernel for local runtime
  reuse.
- Added normalization for profiles, desired runtime identity, and persisted
  runtime state.
- Added pure reuse decisions for:
  - missing state;
  - healthy runtime reuse;
  - root mismatch;
  - health-confirmed root mismatch;
  - health-confirmed repo-key mismatch;
  - profile mismatch;
  - endpoint mismatch;
  - dead PID;
  - closed port;
  - failed health check;
  - start lock contention.
- No process spawning, HTTP calls, daemon lifecycle, or runtime protocol changes
  were added in this stage.

Changed files:

- `src/semidx/runtime/launcher.clj`
- `test/semidx/runtime/launcher_test.clj`
- `plans/021_persistent_jvm_runtime_reuse_plan.md`
- `reports/025_persistent_jvm_runtime_reuse_progress_log.md`
- `FEATURES.md`
- `MEMORY.md`
- `docs/code-context.md`

Verification:

- `clojure -M:test -n semidx.runtime.launcher-test` passed: 3 tests, 34
  assertions, 0 failures, 0 errors.
- `clojure -M:test` passed: 314 tests, 2089 assertions, 0 failures, 0
  errors.
- `clojure -M:ccc check --root .` passed after refreshing changed code-context
  artifacts with `clojure -M:ccc refresh --root . --changed`.

## Stage 2: Runtime HTTP Request Reuse Slice

Status: completed on 2026-08-27.

Summary:

- Added the runnable `runtime-http` reuse slice behind the new `:launcher`
  alias: `status`, `start`, `stop`, and `request`.
- Split the side-effecting roles named by the plan into their own namespaces so
  the pure kernel stays pure and every role is fakeable in tests:
  - `semidx.runtime.launcher-state`: per-slot state file, start lock, log file.
  - `semidx.runtime.launcher-http`: health client and request client.
  - `semidx.runtime.launcher-process`: command, liveness, start, stop.
  - `semidx.runtime.launcher-cli`: command orchestration over injectable roles.
- `request` forwards to `/v1/retrieval/resolve-context` then
  `/v1/retrieval/fetch-context-detail`, mirroring
  `semidx.core/resolve-context-detail`. No request or response body is reshaped.
- Start-if-needed re-observes health after acquiring the exclusive per-slot
  lock, so two concurrent clients cannot start two runtimes for one project.
- Added runtime adoption: a healthy runtime already listening on the requested
  endpoint is recorded with `owned false` instead of being duplicated, and
  `stop` refuses to terminate a runtime the launcher did not start.
- Extended the pure kernel additively: `runtime-slot-key` for slot addressing
  and an optional `owned` field in `normalize-runtime-state`.

Design decisions worth recording:

- Launcher state lives under `~/.cache/semidx/runtime/<workspace_key>-<profile>/`,
  outside the repository, with owner-only permissions on the directory and the
  state file. `SEMIDX_RUNTIME_LAUNCHER_HOME` overrides it for tests and
  sandboxes. This addresses plan risk 3 without adding ignored repo paths.
- Plan risk 1 (wrong-repository reuse) is handled by two independent
  mechanisms: state is scoped per workspace/profile slot, and every forwarded
  request carries `root_path` explicitly, which the runtime HTTP edge uses to
  key its project registry. `GET /health` does not report a root, so the kernel
  correctly treats root confirmation as unavailable rather than mismatched.
- API key material is passed as `x-api-key` on forwarded requests and as
  `SEMIDX_RUNTIME_API_KEY` to a launcher-started child. It is never written to
  state, reports, or logs.

Changed files:

- `src/semidx/runtime/launcher.clj`
- `src/semidx/runtime/launcher_state.clj`
- `src/semidx/runtime/launcher_http.clj`
- `src/semidx/runtime/launcher_process.clj`
- `src/semidx/runtime/launcher_cli.clj`
- `test/semidx/runtime/launcher_state_test.clj`
- `test/semidx/runtime/launcher_cli_test.clj`
- `deps.edn`
- `docs/runtime-api.md`
- `plans/021_persistent_jvm_runtime_reuse_plan.md`
- `reports/025_persistent_jvm_runtime_reuse_progress_log.md`
- `FEATURES.md`
- `MEMORY.md`

Verification:

- `clojure -M:test -n semidx.runtime.launcher-state-test` passed: 5 tests, 24
  assertions, 0 failures, 0 errors.
- `clojure -M:test -n semidx.runtime.launcher-cli-test` passed: 11 tests, 63
  assertions, 0 failures, 0 errors.
- `clojure -M:test` passed: 330 tests, 2176 assertions, 0 failures, 0 errors.
- Manual end-to-end smoke against a real spawned runtime JVM on port 8899, with
  `SEMIDX_RUNTIME_LAUNCHER_HOME` pointed at a scratch directory:
  - `status` on a cold slot: `running false`, no process started.
  - `request` #1: `decision started`, 12.9s wall (JVM start plus initial index).
  - `request` #2: `decision reused`, 1.4s wall, no second runtime JVM.
  - Baseline `clojure -M:runtime` for the same query: 11.4s wall.
  - `status` while running: `running true`, `reason healthy_runtime`.
  - `stop`: `decision stopped`, `forced false`; a second `stop` reported
    `decision noop`, `reason no_state`; no runtime process was left behind.
  - Output diff against the one-shot CLI for the same root and query: identical
    `raw_context` unit ids and identical top-level keys except the additive
    `project_context` that the runtime HTTP edge already returns.

Known limitations:

- The automated reuse smoke test (`sequential-requests-reuse-one-runtime-smoke-test`)
  fakes the process runner and boots the runtime HTTP server in-process, so it
  proves state, locking, health, and forwarding, but not real JVM spawning. Real
  spawning was verified manually as recorded above; a scripted process smoke is
  left to Stage 4 hardening.
- Two projects that both accept the default port `8787` will adopt one another's
  runtime rather than start a second one. This is safe because the runtime HTTP
  edge is multi-project and each request carries `root_path`, but it means
  `stop` from the non-owning project reports `not_launcher_owned` instead of
  stopping anything. Pass `--port` per project when independent runtimes are
  wanted.
- The `mcp-http` profile is accepted by the kernel and by the process alias map,
  but it is untested end to end and is owned by Stage 3.

## Stage 3: MCP HTTP Profile And Docs

Status: completed on 2026-08-28.

Summary:

- The `mcp-http` profile now works end to end. Stage 2 left it accepted by the
  kernel and the alias map but unexercised; `start`, `status`, and `stop` were
  verified against a real MCP HTTP server, and the endpoint was driven with real
  MCP traffic while the launcher managed it.
- Added a profile/service guard to the pure kernel. Both local servers answer
  `GET /health` with `status: "ok"`, so liveness alone could not tell them
  apart and a launcher asking for one profile would have adopted the other one
  listening on the requested port. `launcher/profile-services` maps each profile
  to the `service` its server reports, and a mismatch is blocked with
  `health_service_mismatch` instead of being adopted. An absent service is not a
  mismatch, so a server that does not report one stays adoptable.
- `request` is refused for `mcp-http` with `request_unsupported_for_profile`
  and starts nothing. It forwards the runtime HTTP retrieval contract, which the
  MCP server does not serve; without the refusal it would have posted to a
  missing path and surfaced a 404 as a retrieval failure.
- Documented which clients get which transport, with configuration snippets for
  both, and stated why stdio cannot be launcher-managed: its lifetime belongs to
  the host that spawned it, so there is no process to reuse.

Changed files:

- `src/semidx/runtime/launcher.clj` — `profile-services`, `health-service`,
  the service-mismatch decision branch.
- `src/semidx/runtime/launcher_cli.clj` — profile guard in `request!`, usage
  text covering both profiles.
- `test/semidx/runtime/launcher_test.clj` — kernel coverage for matching,
  mismatching, raw-body, and absent service.
- `test/semidx/runtime/launcher_cli_test.clj` — `mcp-http` start on its own
  port, reuse, refusal to adopt a `runtime-http` server, and the `request`
  refusal.
- `docs/mcp-api.md` — "Launcher-managed MCP HTTP reuse" plus client config.
- `docs/runtime-api.md`, `README.md`, `FEATURES.md`,
  `docs/development-strategy.md`, `docs/roadmap-status.md` — status and
  profile table.

Verification:

- `clojure -M:test`: 442 tests, 2556 assertions, 0 failures, 0 errors
  (Stage 2 baseline was 437 / 2533).
- Live smoke on port 8795: `start` reported `decision: started` with
  `service: semidx-mcp-http`; `status` reported `reused`; `initialize` over
  Streamable HTTP returned a session id and `serverInfo`; `tools/list` returned
  12 tools; `stop` reported `stopped` and the port was closed afterwards.
- Live cross-profile guard: asking for `runtime-http` on the port serving
  `mcp-http` returned `blocked` / `health_service_mismatch`.
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files.
- Compile probes after every source edit, and `git diff --check`: passed.

Known gaps carried into Stage 4:

- No latency measurement yet for cold CLI versus warm launcher reuse.
- Stale-state recovery is covered by unit tests with injected roles but not by a
  test that kills a real process or occupies a real port.
- `lsp`-style readiness of the MCP endpoint is assumed from `/health`; a slow
  first index build is still charged to the first client request.

## Stage 4: Hardening And Benchmark Gate

Status: completed on 2026-08-28.

Summary:

- Every launcher command now reports `timings`. `start` separates `spawn_ms`
  from `health_wait_ms` and `health_attempts`, `request` adds `request_ms`, and
  all commands carry `total_ms`. The clock is an injectable `:now-ms` role, so
  timing is asserted in tests without sleeping.
- `request` prints its measurement to stderr and keeps stdout exactly the
  runtime payload, so the reuse claim is checkable without breaking pipes.
- Stale-state recovery is now tested against real OS resources rather than only
  mocked ones: a real spawned process is killed and its dead PID drives the
  cleanup-and-replace path, and a real occupied port with the real health client
  produces `runtime_start_unhealthy`, cleans up the process it started, and
  leaves no state behind.
- Added `scripts/run-launcher-benchmark.sh`, which times three paths for one
  query and reports them separately instead of collapsing them into a single
  speedup claim.

### Measured latency (2026-08-28, this repository, 189 files, 3 runs each)

> **Corrected 2026-08-28 after review.** The first published table measured the
> direct-HTTP path with `resolve-context` only, while the cold CLI and the
> launcher `request` both perform resolve **and** fetch-detail. That compared a
> compact selection against a full detail payload, so the direct-HTTP figure was
> not comparable to the other two and should not have carried a speedup claim.
> The script now runs the same two stages on all three paths and the table below
> is the re-measured result. The conclusion did not change — the detail stage is
> cheap against a warm index — but the earlier number was measuring the wrong
> thing.

| Path | Median | Note |
| --- | --- | --- |
| `clojure -M:runtime` (cold CLI) | 11643 ms | JVM start plus a full index build every run |
| `clojure -M:launcher request` (warm, JVM client) | 1730 ms | 6.7x faster than cold |
| direct HTTP to the runtime (no JVM client) | 47 ms | 247x faster than cold |
| `launcher start` to healthy | 1901 ms | `spawn_ms` 2, `health_wait_ms` 1899, 8 health attempts |
| first `request` after a start | 11674 ms | pays the index build once |
| `launcher stop` | 1303 ms | |

All three paths run resolve + fetch-detail. These are single-session
observations on one machine, not a deterministic gate: across two runs of the
script the warm client moved between 1.5 s and 1.8 s and the direct HTTP path
between 47 ms and 59 ms. Quote them as an order of magnitude, not as a
constant.

Two honest readings of those numbers:

- **The launcher removes the repeated index build, not the client's JVM start.**
  A launcher `request` still pays ~1.5-1.8 s of its own JVM startup, which is the
  floor for any `clojure -M:` client. The direct HTTP measurement shows what the
  warm path actually costs once the client is not a JVM, and that is where the
  two-orders-of-magnitude difference lives.
- **The win begins at the second request.** The first request after a start
  costs about as much as a cold run, because the runtime builds its index then.
  Reuse pays off across a session, not within a single invocation.

Changed files:

- `src/semidx/runtime/launcher_cli.clj` — injectable `:now-ms`, timings on every
  command, request measurement on stderr, `stop` body extracted so the timing
  wrapper stays one concept.
- `test/semidx/runtime/launcher_cli_test.clj` — timing assertions, killed-process
  recovery against a real process, occupied-port failure against a real socket.
- `scripts/run-launcher-benchmark.sh` — the benchmark.
- `docs/runtime-api.md`, `README.md`, `RULES.md` — timings and benchmark entry
  points.

Verification:

- `clojure -M:test`: 447 tests, 2579 assertions, 0 failures, 0 errors
  (Stage 3 baseline was 442 / 2556).
- `./scripts/run-launcher-benchmark.sh --root . --runs 3`: the corrected table
  above (re-measured after the review finding).
- `./scripts/validate-contracts.sh`: `contracts_validation=ok`, 72 JSON files.
- `bash -n scripts/run-launcher-benchmark.sh`, compile probes after every source
  edit, and `git diff --check`: passed.

### Exit decision (plan's own gate)

The plan says to keep the launcher path only if it measurably removes cold-start
latency without adding ambiguous runtime ownership or stale-server failure modes.
Both halves are now evidenced: a 7.9x measured win for the CLI client and 195x
for a non-JVM client, with ownership recorded per slot (`owned true/false`,
`stop` refusing runtimes it did not start) and stale servers handled by the
recovery paths tested above. The track can close.

## Next Stage

None. `plans/021` is complete through Stage 4. Remaining launcher work is
optional and not planned here: a gRPC profile, supervision/restart policy, and
readiness that waits for the first index build rather than for the HTTP port.
