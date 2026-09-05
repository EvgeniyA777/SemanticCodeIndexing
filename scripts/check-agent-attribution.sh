#!/bin/sh
set -eu

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$repo_root"

zero_sha="0000000000000000000000000000000000000000"
agent_terms="codex|claude([[:space:]-]+code)?|anthropic|openai|chatgpt|copilot|github[[:space:]-]+copilot|antigravity|cursor|windsurf|aider|gemini"
action_terms="generated|created|built|authored|written|made"
agent_suffix="($agent_terms)([^[:alnum:]_]|$)"
action_word="(^|[^[:alnum:]_])($action_terms)"
agent_prefix="(^|[^[:alnum:]_])($agent_terms)"
action_full_word="($action_terms)([^[:alnum:]_]|$)"
pattern="$action_word[[:space:]]+(by|with)[[:space:]]+.*$agent_suffix|$agent_prefix[[:space:]]+$action_full_word|^[[:space:]]*co-authored-by:"

usage() {
  cat >&2 <<'EOF'
Usage:
  scripts/check-agent-attribution.sh --all
  scripts/check-agent-attribution.sh --staged
  scripts/check-agent-attribution.sh --message <commit-message-file>
  scripts/check-agent-attribution.sh --text <scope-label> <text-file>
  scripts/check-agent-attribution.sh --range <git-range>
  scripts/check-agent-attribution.sh --pre-push <local-sha> <remote-sha>
EOF
}

fail_with_matches() {
  scope="$1"
  matches="$2"
  if [ -n "$matches" ]; then
    cat >&2 <<EOF
Agent attribution gate failed in $scope.

Remove agent, harness, model, vendor, tool attribution, promotional boilerplate,
and agent-added Co-authored-by footers before committing, pushing, or preparing
PR/MR text.

Matches:
$matches
EOF
    return 1
  fi
  return 0
}

changed_files_for_range() {
  range="$1"
  case "$range" in
    *..*)
      git diff --name-only --diff-filter=ACMRT "$range" --
      ;;
    *)
      git diff-tree --no-commit-id --name-only -r --diff-filter=ACMRT "$range" --
      ;;
  esac
}

tip_for_range() {
  range="$1"
  case "$range" in
    *..*) printf '%s\n' "${range##*..}" ;;
    *) printf '%s\n' "$range" ;;
  esac
}

default_branch_ref() {
  git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null || true
}

range_for_new_branch() {
  local_sha="$1"
  default_ref="$(default_branch_ref)"

  if [ -n "$default_ref" ]; then
    base="$(git merge-base "$local_sha" "$default_ref" 2>/dev/null || true)"
    if [ -n "$base" ]; then
      printf '%s..%s\n' "$base" "$local_sha"
      return 0
    fi
  fi

  parent="$(git rev-parse --verify --quiet "$local_sha^" 2>/dev/null || true)"
  if [ -n "$parent" ]; then
    printf '%s..%s\n' "$parent" "$local_sha"
  else
    printf '%s\n' "$local_sha"
  fi
}

scan_text_file() {
  scope="$1"
  text_file="$2"
  if [ ! -f "$text_file" ]; then
    echo "text file not found: $text_file" >&2
    exit 2
  fi
  matches="$(grep -n -E -i "$pattern" "$text_file" || true)"
  fail_with_matches "$scope" "$matches"
}

scan_message_file() {
  message_file="$1"
  scan_text_file "commit message" "$message_file"
}

scan_staged() {
  files="$(git diff --cached --name-only --diff-filter=ACMRT --)"
  if [ -z "$files" ]; then
    return 0
  fi

  # Repository paths are controlled here and do not contain shell metacharacters.
  # shellcheck disable=SC2086
  matches="$(git grep --cached -I -n -E -i "$pattern" -- $files || true)"
  fail_with_matches "staged content" "$matches"
}

scan_all() {
  if ! git rev-parse --verify --quiet HEAD >/dev/null; then
    return 0
  fi
  matches="$(git grep -I -n -E -i "$pattern" HEAD -- . || true)"
  fail_with_matches "tracked content at HEAD" "$matches"
}

scan_range() {
  range="$1"
  if [ -z "$range" ]; then
    usage
    exit 2
  fi

  files="$(changed_files_for_range "$range")"
  tree="$(tip_for_range "$range")"
  status=0

  if [ -n "$files" ]; then
    # Repository paths are controlled here and do not contain shell metacharacters.
    # shellcheck disable=SC2086
    matches="$(git grep -I -n -E -i "$pattern" "$tree" -- $files || true)"
    if ! fail_with_matches "tracked content at $tree" "$matches"; then
      status=1
    fi
  fi

  case "$range" in
    *..*)
      matches="$(git log --format='%H %s%n%b' "$range" | grep -n -E -i "$pattern" || true)"
      ;;
    *)
      matches="$(git log -1 --format='%H %s%n%b' "$range" | grep -n -E -i "$pattern" || true)"
      ;;
  esac
  if ! fail_with_matches "commit messages in $range" "$matches"; then
    status=1
  fi

  return "$status"
}

scan_pre_push() {
  local_sha="$1"
  remote_sha="$2"

  if [ "$local_sha" = "$zero_sha" ]; then
    return 0
  fi

  if [ "$remote_sha" = "$zero_sha" ]; then
    range="$(range_for_new_branch "$local_sha")"
  else
    range="$remote_sha..$local_sha"
  fi

  scan_range "$range"
}

case "${1:-}" in
  --all)
    if [ "$#" -ne 1 ]; then
      usage
      exit 2
    fi
    scan_all
    ;;
  --staged)
    if [ "$#" -ne 1 ]; then
      usage
      exit 2
    fi
    scan_staged
    ;;
  --message)
    if [ "$#" -ne 2 ]; then
      usage
      exit 2
    fi
    scan_message_file "$2"
    ;;
  --text)
    if [ "$#" -ne 3 ]; then
      usage
      exit 2
    fi
    scan_text_file "$2" "$3"
    ;;
  --range)
    if [ "$#" -ne 2 ]; then
      usage
      exit 2
    fi
    scan_range "$2"
    ;;
  --pre-push)
    if [ "$#" -ne 3 ]; then
      usage
      exit 2
    fi
    scan_pre_push "$2" "$3"
    ;;
  *)
    usage
    exit 2
    ;;
esac
