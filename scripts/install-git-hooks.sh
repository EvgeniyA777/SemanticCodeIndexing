#!/bin/sh
set -eu

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$repo_root"

hook_dir="$(git rev-parse --git-path hooks)"
mkdir -p "$hook_dir"

chmod +x scripts/git-hooks/pre-commit scripts/git-hooks/commit-msg scripts/git-hooks/pre-push scripts/check-agent-attribution.sh scripts/check-memory-freshness.sh
ln -sf "$repo_root/scripts/git-hooks/pre-commit" "$hook_dir/pre-commit"
ln -sf "$repo_root/scripts/git-hooks/commit-msg" "$hook_dir/commit-msg"
ln -sf "$repo_root/scripts/git-hooks/pre-push" "$hook_dir/pre-push"

echo "Installed versioned pre-commit hook -> $hook_dir/pre-commit"
echo "Installed versioned commit-msg hook -> $hook_dir/commit-msg"
echo "Installed versioned pre-push hook -> $hook_dir/pre-push"
