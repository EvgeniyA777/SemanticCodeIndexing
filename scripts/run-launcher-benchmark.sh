#!/usr/bin/env bash
# Compare one-shot runtime CLI cold start against launcher reuse.
#
# Three paths are measured for the same query, plus the first request after a
# start, which pays the runtime's index build and is reported separately:
#   cold_cli     `clojure -M:runtime`  - JVM start plus a full index build per run
#   warm_client  `clojure -M:launcher request` - JVM start plus a call to a warm runtime
#   warm_http    direct HTTP to the running runtime (resolve + fetch detail, the
#                same two stages as the other two paths) - no JVM in the client
#
# The three are reported separately on purpose: the launcher removes the repeated
# index build, not the client's own JVM start, and only `warm_http` shows what the
# warm path costs once the client is not a JVM.
set -euo pipefail

cd "$(dirname "$0")/.."

ROOT="."
RUNS="${RUNS:-3}"
PORT="${PORT:-8799}"
HOST="${HOST:-127.0.0.1}"
QUERY="contracts/examples/queries/symbol-target.json"
OUT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root) ROOT="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --host) HOST="$2"; shift 2 ;;
    --query) QUERY="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$QUERY" ]]; then
  echo "query file not found: $QUERY" >&2
  exit 2
fi

WORK="$(mktemp -d)"
trap 'clojure -M:launcher stop --root "$ROOT" --port "$PORT" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

# Start from a clean slot so the cold runs are genuinely cold.
clojure -M:launcher stop --root "$ROOT" --port "$PORT" >/dev/null 2>&1 || true

export BENCH_ROOT="$ROOT" BENCH_RUNS="$RUNS" BENCH_PORT="$PORT" \
       BENCH_HOST="$HOST" BENCH_QUERY="$QUERY" BENCH_WORK="$WORK" BENCH_OUT="$OUT"

python3 - <<'PY'
import json, os, statistics, subprocess, time, urllib.request

root = os.environ["BENCH_ROOT"]
runs = int(os.environ["BENCH_RUNS"])
port = os.environ["BENCH_PORT"]
host = os.environ["BENCH_HOST"]
query_path = os.environ["BENCH_QUERY"]
work = os.environ["BENCH_WORK"]
out_path = os.environ["BENCH_OUT"]

def timed(fn):
    started = time.monotonic()
    ok = fn()
    return (time.monotonic() - started) * 1000.0, ok

def run(cmd):
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit(f"command failed: {' '.join(cmd)}\n{proc.stderr[-800:]}")
    return proc

def summary(samples):
    if not samples:
        return None
    return {
        "runs": len(samples),
        "mean_ms": round(statistics.mean(samples), 1),
        "median_ms": round(statistics.median(samples), 1),
        "min_ms": round(min(samples), 1),
        "max_ms": round(max(samples), 1),
    }

cold = []
for i in range(runs):
    ms, _ = timed(lambda: run(["clojure", "-M:runtime", "--root", root,
                               "--query", query_path,
                               "--out", f"{work}/cold-{i}.json"]))
    cold.append(ms)

start_ms, start_proc = timed(lambda: run(["clojure", "-M:launcher", "start",
                                          "--root", root, "--port", port]))
start_report = json.loads(start_proc.stdout.strip().splitlines()[-1])

# The first request after a start pays the runtime's index build, so it is
# reported on its own instead of being averaged into the warm samples.
warm_client = []
for i in range(runs + 1):
    ms, _ = timed(lambda: run(["clojure", "-M:launcher", "request", "--root", root,
                               "--port", port, "--query", query_path,
                               "--out", f"{work}/warm-{i}.json"]))
    warm_client.append(ms)
first_request_ms = warm_client[0]
warm_client = warm_client[1:]

with open(query_path) as f:
    query = json.load(f)
root_abs = os.path.abspath(root)

def post(path, payload):
    request = urllib.request.Request(
        f"http://{host}:{port}{path}",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.loads(response.read())

def http_call():
    """Same two stages the CLI and the launcher perform: resolve, then detail.

    Timing only the resolve stage would compare a compact selection against a
    full detail payload and overstate the warm path."""
    selection = post("/v1/retrieval/resolve-context",
                     {"root_path": root_abs, "query": query})
    return post("/v1/retrieval/fetch-context-detail",
                {"root_path": root_abs,
                 "selection_id": selection["selection_id"],
                 "snapshot_id": selection["snapshot_id"]})

warm_http = []
for _ in range(runs):
    ms, _ = timed(http_call)
    warm_http.append(ms)

stop_ms, _ = timed(lambda: run(["clojure", "-M:launcher", "stop",
                                "--root", root, "--port", port]))

report = {
    "root_path": os.path.abspath(root),
    "runs": runs,
    "cold_cli": summary(cold),
    "warm_client": summary(warm_client),
    "warm_http": summary(warm_http),
    "launcher_start": {
        "total_ms": round(start_ms, 1),
        "reported": start_report.get("timings"),
        "decision": start_report.get("decision"),
    },
    "first_request_after_start_ms": round(first_request_ms, 1),
    "launcher_stop": {"total_ms": round(stop_ms, 1)},
}
cold_median = report["cold_cli"]["median_ms"]
report["speedup"] = {
    "warm_client_vs_cold": round(cold_median / report["warm_client"]["median_ms"], 2),
    "warm_http_vs_cold": round(cold_median / report["warm_http"]["median_ms"], 2),
}

text = json.dumps(report, indent=2)
if out_path:
    with open(out_path, "w") as f:
        f.write(text + "\n")
    print(f"wrote {out_path}")
print(text)
PY
