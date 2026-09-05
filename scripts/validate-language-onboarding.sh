#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

usage() {
  cat <<'EOF'
Usage:
  ./scripts/validate-language-onboarding.sh <language> [--skip-gates]

Examples:
  ./scripts/validate-language-onboarding.sh ruby
  ./scripts/validate-language-onboarding.sh typescript --skip-gates
EOF
}

if [[ $# -ge 1 && ( "$1" == "-h" || "$1" == "--help" ) ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

LANG_INPUT="$1"
shift

RUN_GATES="true"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-gates)
      RUN_GATES="false"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

LANG_ID="$(echo "$LANG_INPUT" | tr '[:upper:]-' '[:lower:]_' | sed 's/[^a-z0-9_]/_/g' | sed 's/__*/_/g' | sed 's/^_//;s/_$//')"
LANG_NS="$(echo "$LANG_ID" | tr '_' '-')"

ADAPTERS_FILE="src/semidx/runtime/adapters.clj"
REGISTRY_FILE="src/semidx/runtime/language_registry.clj"
LANGUAGE_MODULE="src/semidx/runtime/languages/${LANG_ID}.clj"
TEST_FILE="test/semidx/integration/${LANG_ID}_onboarding_test.clj"
DOC_FILE="docs/language-onboarding/${LANG_ID}.md"
FIXTURE_HAPPY="fixtures/retrieval/${LANG_ID}-happy-path.json"
FIXTURE_AMBIG="fixtures/retrieval/${LANG_ID}-ambiguity.json"
CORPUS_FILE="fixtures/retrieval/corpus.json"

errors=0

ok() {
  printf 'ok: %s\n' "$*"
}

fail() {
  printf 'fail: %s\n' "$*" >&2
  errors=$((errors + 1))
}

check_file() {
  local path="$1"
  if [[ -f "$path" ]]; then
    ok "file exists: $path"
  else
    fail "missing file: $path"
  fi
}

check_contains() {
  local pattern="$1"
  local path="$2"
  local label="$3"
  if rg -q "$pattern" "$path"; then
    ok "$label"
  else
    fail "$label (pattern '$pattern' not found in $path)"
  fi
}

check_contains_any() {
  local path="$1"
  local label="$2"
  shift 2
  local pattern
  for pattern in "$@"; do
    if rg -q "$pattern" "$path"; then
      ok "$label"
      return 0
    fi
  done
  fail "$label (none of the accepted patterns were found in $path)"
}

check_file "$ADAPTERS_FILE"
check_file "$REGISTRY_FILE"
check_file "$LANGUAGE_MODULE"
check_file "$TEST_FILE"
check_file "$DOC_FILE"
check_file "$FIXTURE_HAPPY"
check_file "$FIXTURE_AMBIG"
check_file "$CORPUS_FILE"

check_contains "\"${LANG_ID}\"" "$REGISTRY_FILE" "language lane registered in registry"
check_contains "ns semidx\\.runtime\\.languages\\.${LANG_NS}" "$LANGUAGE_MODULE" "language module uses the canonical namespace"
check_contains "\\(defn parse-file " "$LANGUAGE_MODULE" "language module exposes parse-file"
check_contains "semidx\\.runtime\\.languages\\.${LANG_NS} :as [A-Za-z0-9_-]+-language" "$ADAPTERS_FILE" "language module is required by adapter facade"
check_contains_any "$ADAPTERS_FILE" "parse-file branch wired" \
  "\"${LANG_ID}\" \\([A-Za-z0-9_-]+-language/parse-file root-path file-path lines parser-opts\\)" \
  "\"${LANG_ID}\" \\(parse-${LANG_ID} root-path file-path lines parser-opts\\)"

check_contains "ns semidx\\.integration\\.${LANG_NS}-onboarding-test" "$TEST_FILE" "onboarding test uses mirrored integration namespace (auto-discovered)"

check_contains "\"retrieval_${LANG_ID}_happy_path_001\"" "$FIXTURE_HAPPY" "happy fixture id is correct"
check_contains "\"retrieval_${LANG_ID}_ambiguity_001\"" "$FIXTURE_AMBIG" "ambiguity fixture id is correct"
check_contains "\"${LANG_ID}-happy-path.json\"" "$CORPUS_FILE" "corpus references happy fixture"
check_contains "\"${LANG_ID}-ambiguity.json\"" "$CORPUS_FILE" "corpus references ambiguity fixture"
check_contains "ADR-022" "$DOC_FILE" "onboarding doc references ADR-022"

if [[ "$errors" -ne 0 ]]; then
  echo "validation_failed errors=$errors" >&2
  exit 1
fi

echo "validation_checks=ok language=$LANG_ID"

if [[ "$RUN_GATES" == "true" ]]; then
  ./scripts/validate-contracts.sh
  clojure -M:test
  ./scripts/run-benchmarks.sh
  ./scripts/run-mvp-gates.sh
  echo "validation_gates=ok language=$LANG_ID"
else
  echo "validation_gates=skipped language=$LANG_ID"
fi
