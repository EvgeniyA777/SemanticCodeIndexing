# ADR-026: Add JavaScript as a Separate Semantic Search Language

## Status

Accepted

## Date

2026-03-17

## Context

`ADR-004` defines parser adapters as capability-based fact extractors.
`ADR-014` governs retrieval fixtures and benchmarks.
`ADR-022` defines the standard onboarding flow for new language adapters.

The runtime currently exposes supported languages through `supported_languages`, `active_languages`, and `detected_languages`, and the current onboarding surface is built around the existing lanes:

- `clojure`
- `java`
- `elixir`
- `python`
- `typescript`

TypeScript already contains some JavaScript-family parsing behavior internally, including JS-like extension handling in the adapter layer and tree-sitter integration for the TypeScript grammar. That makes JavaScript support possible without inventing a new parsing architecture, but the public model is still incomplete:

- JavaScript source files are not a first-class public language lane.
- `.js`, `.jsx`, `.mjs`, and `.cjs` are not registered as a distinct supported language.
- onboarding docs, fixtures, and benchmark governance do not yet describe JavaScript as its own supported surface.

If we want JavaScript semantic search support to be stable and explainable, we need a clear public decision about whether JavaScript is:

- a separate language lane with its own activation metadata, or
- just an extension of the TypeScript lane.

This ADR chooses the first option.

## Decision

We add `javascript` as a separate public language lane for semantic search.

The implementation may reuse a shared JavaScript/TypeScript parsing core internally, but the public contract must treat `javascript` as its own language key with its own activation and documentation surface.

### Public contract changes

- Add `javascript` to the supported language set.
- Map `.js`, `.jsx`, `.mjs`, and `.cjs` to `javascript` during source discovery.
- Keep `typescript` as a separate language key.
- Expose JavaScript in project activation metadata, retrieval metadata, and onboarding docs.

### Parser and activation changes

- Add a dedicated `runtime/languages/javascript` entry namespace.
- Split shared JS/TS parsing logic so the JavaScript lane and the TypeScript lane can stay thin while reusing common extraction code internally.
- Keep regex parsing as the default mode for JavaScript.
- Add optional tree-sitter support for JavaScript using the same grammar family already used for TypeScript, but with a separate JavaScript grammar path/config entry.
- Add `jsconfig.json` as a JavaScript manifest hint for language discovery.

### Onboarding and governance changes

- Add JavaScript onboarding coverage following `ADR-022`.
- Add JavaScript happy-path and ambiguity fixtures.
- Add JavaScript benchmark entries so the new lane is governed the same way as the existing lanes.
- Update README, roadmap status, and operational memory so the public documentation matches the runtime behavior.

## Decision Drivers

- JavaScript is a distinct public language in user projects and should not be hidden behind TypeScript metadata.
- A separate lane makes language activation and retrieval results easier to explain.
- Reusing shared parsing internals keeps the implementation cost bounded.
- Fixture and benchmark governance must cover the new lane before it is treated as a stable capability.

## Considered Options

### Option 1. Treat JavaScript as a separate public language lane

Expose `javascript` as a first-class language key and give it its own discovery, activation, docs, fixtures, and benchmarks.

### Option 2. Fold JavaScript into the TypeScript lane

Keep a single public `typescript` lane and simply expand TypeScript file handling to include JavaScript extensions.

### Option 3. Add JavaScript only as an internal parsing alias

Let the runtime parse JavaScript internally, but do not expose it as a separate supported language.

## Decision

We accept **Option 1: treat JavaScript as a separate public language lane**.

The implementation should prefer shared code where it reduces duplication, but the user-visible model must remain distinct:

- `javascript` is a supported language
- `typescript` is a supported language
- both can reuse common parsing code internally
- both must retain their own onboarding and governance surface

## Consequences

### Positive

- JavaScript projects can be indexed and explained as JavaScript, not misclassified as TypeScript.
- The supported-language model stays honest to actual user projects.
- Shared parser internals can still keep maintenance cost controlled.
- The onboarding and benchmark flow stays consistent with the existing language-adapter contract.

### Negative

- The supported-language matrix grows, so activation and docs need another lane.
- Some parser logic will need to be split out of the current TypeScript module.
- Fixtures and benchmark coverage must be added before the lane should be considered stable.

## Definition of Done

This ADR is implemented correctly when all of the following are true:

1. `javascript` appears in the supported language set and activation metadata.
2. `.js`, `.jsx`, `.mjs`, and `.cjs` resolve to `javascript`.
3. JavaScript has its own onboarding doc, fixtures, and benchmark entries.
4. The runtime has a dedicated JavaScript lane, even if it reuses shared JS/TS parsing internals.
5. README, roadmap status, and operational memory match the runtime behavior.

## Alternatives Considered

1. **Keep JavaScript folded into TypeScript** - Simpler short-term, but it hides a real public language boundary and makes activation/output semantics harder to explain.
2. **Add JavaScript only after a full parser rewrite** - Unnecessary delay; the current adapter stack already has enough structure to support a separate lane.
3. **Expose JavaScript only in docs without changing activation** - That would create a documentation/runtime mismatch and should be avoided.
