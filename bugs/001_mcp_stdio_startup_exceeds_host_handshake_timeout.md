---
title: "MCP Stdio Startup Exceeds Host Handshake Timeout"
doc_type: "bug_report"
lifecycle: "active"
status: "open"
agent_action: "reference_for_context"
updated: "2026-09-07"
---

# MCP Stdio Startup Exceeds Host Handshake Timeout

Severity: high. When the host marks semidx as a required MCP server, the whole
host session fails to start, so the failure is not degraded retrieval but a
hard block on the tool that spawns semidx.

## Summary

The semidx MCP stdio server needs roughly 17-20 seconds to answer the
`initialize` request. Codex CLI enforces a 10 second handshake timeout per MCP
server. With `required = true` the timeout aborts session bootstrap entirely,
so `codex` cannot start at all in a repository that lists semidx.

Observed host error:

```text
Error: Failed to start a fresh session through the app server: thread/start
failed during TUI bootstrap: thread/start failed: error creating thread: Fatal
error: Failed to initialize session: required MCP servers failed to initialize:
semidx: timed out handshaking with MCP server after 9.999999625s (code -32603)
```

## Environment

- Host: Codex CLI, macOS 25.5.0 (Darwin), 2026-09-07.
- Server command: `/Users/ae/workspaces/semidx/scripts/start-mcp-server.sh`,
  which executes `clojure -M:mcp` from the repository root.
- Host configuration for this server: `required = true`,
  `startup_timeout_sec = 10.0`.
- semidx checkout: branch `dev`, clean working tree.

## Reproduction and measurement

A single `initialize` request was written to the server's stdin and the elapsed
time until the first JSON-RPC line on stdout was measured.

```bash
# stdin: one initialize request, then hold the pipe open
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}'
sleep 60
```

Piped into `scripts/start-mcp-server.sh` with
`SCI_MCP_ALLOWED_ROOTS=/Users/ae/workspace/ReaderLens`:

| Run | Time to `initialize` response |
| --- | --- |
| First (cold file cache) | 16.68 s |
| Second (warm) | 20.38 s |

The response itself is well formed, and stderr shows a normal start
(`semidx_mcp_started {:max_indexes 8}`). Nothing fails; the server is simply
slower than the handshake window. The second run was not faster than the first,
so this is steady-state startup cost, not a one-off cold start.

## Root cause

`scripts/start-mcp-server.sh` ends with `exec "$clojure_bin" -M:mcp`, which
starts the server from source on every host session. Each start therefore pays
JVM boot plus Clojure namespace loading for the whole MCP and runtime surface
before the first request can be answered.

Confidence note: the split between JVM boot and namespace loading was not
profiled. The total is measured; the attribution to compile/load cost is an
inference from the launch path and has not been verified.

The repository already treats process reuse as a known problem, but the
existing work does not cover this path.
[`reports/025_persistent_jvm_runtime_reuse_progress_log.md`](../reports/025_persistent_jvm_runtime_reuse_progress_log.md)
records that `runtime-http` was chosen as the first reuse profile and that MCP
reuse was deliberately deferred. The `:launcher` alias and
`scripts/run-launcher-benchmark.sh` exist for that profile. The MCP stdio entry
point still starts a fresh JVM per host session.

## Impact

- Any MCP host with a handshake timeout below roughly 25 seconds cannot use
  semidx over stdio without configuration changes on the host side.
- When the host also treats semidx as required, the failure escalates from
  "one server missing" to "session will not start", which is what happened
  here.
- Hosts with a larger window still pay 17-20 seconds of startup latency on
  every session, which is a poor first-use experience for a tool whose selling
  point is cheap orientation.
- The project rules mandate MCP-first exploration. A server that cannot be
  reached makes the mandated workflow unavailable rather than merely slower.

## Workarounds

These unblock the user but do not fix the defect.

- Raise the host timeout, for example `startup_timeout_sec = 45.0` in the Codex
  server entry. Every session then blocks for about 20 seconds on startup.
- Drop `required = true`, which lets the host session start but leaves it
  without semidx when the handshake loses the race.

## Suggested fixes

Ordered by how well each removes the cause rather than the symptom.

1. Ship a prebuilt artifact for the stdio entry point and launch it with
   `java -jar` instead of `clojure -M:mcp`. `build.clj` already exists, so the
   launcher script could prefer a built artifact and fall back to
   `clojure -M:mcp` only when it is absent.
2. Answer `initialize` before the full runtime is loaded, deferring heavy
   namespace loading to the first real `tools/call`. This keeps the handshake
   inside any reasonable host window regardless of total startup cost.
3. Extend the existing launcher and runtime-reuse work to the MCP stdio
   profile, so a warm runtime is reused across host sessions instead of being
   rebuilt each time.
4. Document the measured startup cost and the required host timeout in
   `README.md` and `docs/mcp-agent-prompts.md`, so integrators configure a
   sufficient window until the cause is fixed.

Options 1 and 2 are independent and can both apply. Option 2 alone fixes the
handshake failure without reducing total startup work.

## Open questions

- Whether Codex enforces an upper bound on `startup_timeout_sec` was not
  checked, so the workaround above is not confirmed to scale to arbitrary
  values.
- Whether other hosts in use (Claude Code, Antigravity) have handshake limits
  close enough to 20 seconds to fail intermittently was not measured. Claude
  Code accepted the same server in a parallel session, so its window is larger,
  but the margin is unknown.
- The startup profile was measured only on this machine with a warm Maven
  cache. A cold dependency cache would be slower.

## Verification limits

- Only the stdio profile was measured. `mcp-http` startup was not tested.
- No fix was implemented or verified as part of this report.
