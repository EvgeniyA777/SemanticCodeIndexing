#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
GRAMMARS_DIR="${SCI_TREE_SITTER_GRAMMARS_DIR:-$ROOT_DIR/.tree-sitter-grammars}"
CLOJURE_REPO="${SCI_TREE_SITTER_CLOJURE_GRAMMAR_REPO:-https://github.com/sogaiu/tree-sitter-clojure.git}"
JAVA_REPO="${SCI_TREE_SITTER_JAVA_GRAMMAR_REPO:-https://github.com/tree-sitter/tree-sitter-java.git}"
CLOJURE_REF="${SCI_TREE_SITTER_CLOJURE_GRAMMAR_REF:-e43eff80d17cf34852dcd92ca5e6986d23a7040f}"
JAVA_REF="${SCI_TREE_SITTER_JAVA_GRAMMAR_REF:-e10607b45ff745f5f876bfa3e94fbcc6b44bdc11}"
ENV_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --write-env-file)
      ENV_FILE="${2:-}"
      shift 2
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

mkdir -p "$GRAMMARS_DIR"

sync_grammar() {
  local name="$1"
  local repo="$2"
  local ref="$3"
  local dir="$GRAMMARS_DIR/$name"

  if [[ -d "$dir/.git" ]]; then
    git -C "$dir" fetch --tags --force --prune origin >/dev/null
  else
    git clone --filter=blob:none "$repo" "$dir" >/dev/null
  fi

  git -C "$dir" checkout --detach "$ref" >/dev/null
}

sync_grammar "tree-sitter-clojure" "$CLOJURE_REPO" "$CLOJURE_REF"
sync_grammar "tree-sitter-java" "$JAVA_REPO" "$JAVA_REF"

CLOJURE_PATH="$GRAMMARS_DIR/tree-sitter-clojure"
JAVA_PATH="$GRAMMARS_DIR/tree-sitter-java"

if [[ -n "$ENV_FILE" ]]; then
  {
    echo "SCI_TREE_SITTER_CLOJURE_GRAMMAR_PATH=$CLOJURE_PATH"
    echo "SCI_TREE_SITTER_JAVA_GRAMMAR_PATH=$JAVA_PATH"
  } > "$ENV_FILE"
  echo "wrote_env_file=$ENV_FILE"
fi

echo "tree_sitter_clojure_grammar=$CLOJURE_PATH"
echo "tree_sitter_java_grammar=$JAVA_PATH"
echo "tree_sitter_clojure_ref=$CLOJURE_REF"
echo "tree_sitter_java_ref=$JAVA_REF"

echo "export SCI_TREE_SITTER_CLOJURE_GRAMMAR_PATH=$CLOJURE_PATH"
echo "export SCI_TREE_SITTER_JAVA_GRAMMAR_PATH=$JAVA_PATH"
