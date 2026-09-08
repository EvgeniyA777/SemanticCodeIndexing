# Clojure Onboarding Status

Clojure is the repository's own implementation language and its most complete
lane. It predates `scripts/new-language-adapter.sh`, so this document was a
placeholder until `plans/023` brought every lane up to the same checklist.

## Current State

- Language key: `clojure`
- File extensions: `.clj`, `.cljc`, `.cljs`
- Static strength: `high` (`semidx.runtime.language-registry/language-lanes`)
- Language module: `semidx.runtime.languages.clojure/parse-file`, required
  statically by `semidx.runtime.adapters`
- Extraction engines: `clj-kondo` analysis by default with a regex fallback, and
  an optional tree-sitter extraction mode
- Fixture files:
  - `fixtures/retrieval/clojure-happy-path.json`
  - `fixtures/retrieval/clojure-ambiguity.json`
- Onboarding regression test:
  `test/semidx/integration/clojure_onboarding_test.clj`
- Unit tests: `test/semidx/runtime/languages/clojure_test.clj`

Clojure is outside the `plans/018` provider-authority scope: that migration
covers Java and TypeScript, and this lane keeps its single-parser default path.

## Next Steps (ADR-022)

1. Keep running `./scripts/validate-language-onboarding.sh clojure`.
2. Expand the ambiguity fixture if a same-name-across-namespaces case starts
   mattering for retrieval quality.
3. Treat the `high` strength as earned by the `clj-kondo` analysis path; a
   regex-only fallback run is weaker than the lane's number suggests.
