#!/usr/bin/env bash
set -euo pipefail

# Regenerate the committed real-SCIP reference artifact for the plans/018
# provider-authority TypeScript corpus.
#
# This is a preflight/verification aid, not part of the runtime. It proves the
# Stage 0 identity fixtures against real `scip-typescript` output and gives a
# reviewer a stable JSON to diff. The Stage 3 provider adapter reads SCIP in the
# JVM and does not depend on this script.
#
# Requires: scripts/setup-scip-typescript.sh has been run (repo-managed CLI),
# and node on PATH.

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CORPUS_DIR="$ROOT_DIR/fixtures/provider-authority/corpus/typescript"
OUT_JSON="$ROOT_DIR/fixtures/provider-authority/scip/typescript-corpus.observed.json"
OUT_SCIP="$ROOT_DIR/fixtures/provider-authority/scip/typescript-corpus.scrubbed.scip"
TOOLCHAIN_DIR="${SEMIDX_SCIP_TOOLCHAIN_DIR:-$ROOT_DIR/.scip-toolchain}"
CLI="${SEMIDX_SCIP_TYPESCRIPT_CLI_PATH:-$TOOLCHAIN_DIR/node_modules/.bin/scip-typescript}"

if [[ ! -x "$CLI" ]]; then
  echo "scip-typescript CLI not found at $CLI" >&2
  echo "run scripts/setup-scip-typescript.sh first" >&2
  exit 1
fi

# Fail closed if the CLI actually in use drifted from the committed lock: SCIP
# output depends on both the scip-typescript and the transitive `typescript`
# version, and the fixture records them. Validate against the node_modules that
# belongs to the resolved CLI, not a fixed path, so an override via
# SEMIDX_SCIP_TYPESCRIPT_CLI_PATH cannot smuggle in an unpinned toolchain.
LOCK="$ROOT_DIR/scripts/scip-toolchain/package-lock.json"
LOCKED_SCIP="$(node -p "require('$LOCK').packages['node_modules/@sourcegraph/scip-typescript'].version")"
LOCKED_TS="$(node -p "require('$LOCK').packages['node_modules/typescript'].version")"

CLI_NODE_MODULES="$(cd "$(dirname "$CLI")/.." && pwd)"
SCIP_PKG_JSON="$CLI_NODE_MODULES/@sourcegraph/scip-typescript/package.json"
TS_PKG_JSON="$CLI_NODE_MODULES/typescript/package.json"
if [[ ! -f "$SCIP_PKG_JSON" || ! -f "$TS_PKG_JSON" ]]; then
  echo "cannot verify pinned versions for the resolved CLI: $CLI" >&2
  echo "expected sibling packages under $CLI_NODE_MODULES" >&2
  echo "use the repo-managed install (scripts/setup-scip-typescript.sh)" >&2
  exit 1
fi
INSTALLED_SCIP="$(node -p "require('$SCIP_PKG_JSON').version")"
INSTALLED_TS="$(node -p "require('$TS_PKG_JSON').version")"
if [[ "$LOCKED_SCIP" != "$INSTALLED_SCIP" || "$LOCKED_TS" != "$INSTALLED_TS" ]]; then
  echo "scip toolchain version drift for CLI $CLI" >&2
  echo "  locked   scip-typescript=$LOCKED_SCIP typescript=$LOCKED_TS" >&2
  echo "  resolved scip-typescript=$INSTALLED_SCIP typescript=$INSTALLED_TS" >&2
  echo "re-run scripts/setup-scip-typescript.sh" >&2
  exit 1
fi

TMP_SCIP="$(mktemp -t scip-typescript-corpus.XXXXXX.scip)"
trap 'rm -f "$TMP_SCIP"' EXIT

(cd "$CORPUS_DIR" && "$CLI" index --no-progress-bar --output "$TMP_SCIP" >/dev/null)

node "$ROOT_DIR/scripts/lib/decode-scip.js" "$TMP_SCIP" > "$OUT_JSON"
echo "wrote $OUT_JSON"

# Committed binary fixture for the JVM SCIP reader tests
# (semidx.runtime.scip). `metadata.project_root` is scrubbed so the fixture
# carries no absolute indexing-machine path.
node "$ROOT_DIR/scripts/lib/scrub-scip.js" "$TMP_SCIP" "$OUT_SCIP"
echo "wrote $OUT_SCIP"
