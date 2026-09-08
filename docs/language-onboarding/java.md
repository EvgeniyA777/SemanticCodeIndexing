# Java Onboarding Status

Java predates `scripts/new-language-adapter.sh` and had no onboarding document
until `plans/023` brought every lane up to the same checklist. It is one of the
two lanes the provider-authority migration in `plans/018` targets, so its current
state is described here alongside the parser lane itself.

## Current State

- Language key: `java`
- File extensions: `.java`
- Static strength: `medium` (`semidx.runtime.language-registry/language-lanes`)
- Language module: `semidx.runtime.languages.java/parse-file`, required
  statically by `semidx.runtime.adapters`
- Extraction engines: regex by default, tree-sitter when
  `:tree_sitter_enabled` is set and a grammar resolves. Both report
  `parser_mode: "full"` on their own; the provider pipeline is what tells them
  apart.
- Fixture files:
  - `fixtures/retrieval/java-happy-path.json`
  - `fixtures/retrieval/java-ambiguity.json`
  - `fixtures/retrieval/java-overload-like-call-ambiguity.json` (predates the
    checklist and covers overload-shaped call ambiguity)
- Onboarding regression test:
  `test/semidx/integration/java_onboarding_test.clj`
- Provider corpus: `fixtures/provider-authority/corpus/java`, used by the
  `plans/018` provider and gate tests rather than by retrieval benchmarks.

## Provider Authority (ADR-046 / plans/018)

Java is one of the two lanes whose default extraction path the provider plan
takes over. Fresh SCIP evidence (`semidx.runtime.providers.scip-java`) and a
jdtls-backed LSP overlay (`semidx.runtime.providers.lsp-java`) are the exact
tier, tree-sitter is structural, and regex is heuristic and says so.

Two Java-specific facts are worth knowing before working on this lane:

- **The exact tier is arity-only.** scip-java disambiguates overloads with a
  source-order ordinal rather than parameter types, so the Java exact tier
  commits `signature_precision "arity_only"` exactly like the heuristic tier
  (reports/024 finding S1).
- **Same-arity overloads are withheld rather than merged.** Because no tier can
  tell them apart, claiming one exact identity for two distinct methods would be
  a false identity, so the shared guard withholds them (finding S2).

## Next Steps (ADR-022)

1. Keep running `./scripts/validate-language-onboarding.sh java`.
2. Expand the ambiguity fixture beyond the current single-overload case once the
   exact tier can distinguish typed signatures.
3. Raise the static `medium` strength only on evidence; since `plans/018` Stage
   6.2 a wholly exact selection already rises above it at retrieval time, which
   is the intended mechanism rather than editing the lane's number.
