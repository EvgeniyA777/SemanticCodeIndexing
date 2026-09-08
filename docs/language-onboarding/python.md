# Python Onboarding Status

Python predates `scripts/new-language-adapter.sh`, so this document was a
placeholder until `plans/023` brought every lane up to the same checklist.

## Current State

- Language key: `python`
- File extensions: `.py`
- Static strength: `medium` (`semidx.runtime.language-registry/language-lanes`)
- Language module: `semidx.runtime.languages.python/parse-file`, required
  statically by `semidx.runtime.adapters`
- Fixture files:
  - `fixtures/retrieval/python-happy-path.json`
  - `fixtures/retrieval/python-ambiguity.json`
  - `fixtures/retrieval/python-method-function-collision.json` (predates the
    checklist and covers a method-versus-function name collision)
- Onboarding regression test:
  `test/semidx/integration/python_onboarding_test.clj`

Python is outside the `plans/018` provider-authority scope: that migration covers
Java and TypeScript, and this lane keeps its single-parser default path.

## Next Steps (ADR-022)

1. Keep running `./scripts/validate-language-onboarding.sh python`.
2. Decide whether a semantic provider is worth onboarding for this lane before
   widening its fixtures; `plans/018` requires evidence rather than symmetry for
   any further language migration.
