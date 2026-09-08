# Elixir Onboarding Status

Elixir predates `scripts/new-language-adapter.sh`, so this document was a
placeholder until `plans/023` brought every lane up to the same checklist.

## Current State

- Language key: `elixir`
- File extensions: `.ex`, `.exs`
- Static strength: `medium` (`semidx.runtime.language-registry/language-lanes`)
- Language module: `semidx.runtime.languages.elixir/parse-file`, plus the
  supporting namespaces under `src/semidx/runtime/languages/elixir/`
- Adapter wiring: reached **lazily**, through `requiring-resolve` inside
  `adapters/parse-elixir-language-file`, rather than through a static require.
  This is deliberate and is one of the spellings
  `scripts/validate-language-onboarding.sh` accepts.
- Fixture files:
  - `fixtures/retrieval/elixir-happy-path.json`
  - `fixtures/retrieval/elixir-ambiguity.json`
  - `fixtures/retrieval/elixir-aliased-local-ambiguity.json`,
    `elixir-mixed-def-forms.json`, `elixir-exunit-module-scenario.json` (all
    predate the checklist)
- Onboarding regression test:
  `test/semidx/integration/elixir_onboarding_test.clj`
- `use`-expansion imports are resolved across files during indexing, in
  `index/parse-files`, rather than inside the language module.

Elixir is outside the `plans/018` provider-authority scope: that migration covers
Java and TypeScript, and this lane keeps its single-parser default path.

## Next Steps (ADR-022)

1. Keep running `./scripts/validate-language-onboarding.sh elixir`.
2. Keep the `use`-expansion behaviour covered by the aliased-local and
   mixed-def-forms fixtures when the module changes.
