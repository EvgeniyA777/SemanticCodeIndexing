#!/bin/sh
set -eu

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$repo_root"

if [ -f "docs/code-context.md" ] && [ -f ".ccc/state.edn" ]; then
  echo "CCC bootstrap: existing code-context artifacts found"
  exit 0
fi

echo "CCC bootstrap: artifacts missing, running ccc init --skip-hook"
clojure -M:ccc init --root "$repo_root" --skip-hook
