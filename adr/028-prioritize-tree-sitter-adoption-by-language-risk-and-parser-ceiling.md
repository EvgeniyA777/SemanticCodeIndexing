# ADR-028: Prioritize Tree-Sitter Adoption by Language Risk and Parser Ceiling

**Status**: Proposed  
**Date**: 2026-03-18  
**Deciders**: project owner

---

## Context and Problem Statement

The runtime currently supports six public language lanes:

- `clojure`
- `java`
- `elixir`
- `python`
- `typescript`
- `lua`

The repository already uses multiple parser strategies:

- `clj-kondo` as the default structural parser for Clojure
- regex/adapters for several language lanes
- optional `tree-sitter` paths for Clojure, Java, and TypeScript

That means the remaining question is not "should every language get tree-sitter?" but:

- which languages objectively need deeper structural parsing next
- which languages can remain on bounded adapter parsing for now
- where a non-tree-sitter structural parser is already a sufficient answer

Without a shared prioritization rule, parser work risks becoming ad hoc and driven by recent pain rather than by the actual parser ceiling of each language lane.

This ADR defines the criteria for that prioritization and applies them to the currently supported languages.

---

## Decision

We prioritize tree-sitter adoption by language risk and parser ceiling, not by unsupported-language count or by symmetry with existing lanes.

### Evaluation criteria

A language objectively needs a deeper structural parser when most of the following are true:

1. **Parser ceiling is low**  
   The current adapter quickly becomes heuristic-heavy on normal code, not only on edge cases.

2. **Syntax volatility is high**  
   The language frequently uses nested forms, alternate declaration styles, macros, indentation-sensitive scope, or other surfaces that regex/state scanning does not model reliably.

3. **Semantic extraction materially affects retrieval quality**  
   Better unit, import, and call extraction would produce meaningfully better symbol targeting, caller resolution, or ambiguity handling.

4. **Fallback parser options are weak or absent**  
   There is no strong language-native structural parser already serving the same role.

5. **Maintenance pressure is growing**  
   The lane requires many special-case rules and many targeted regression tests to keep behavior stable.

### Current ranking

#### Highest next priority: `elixir`

`elixir` is the highest-priority remaining candidate for tree-sitter adoption.

Reasons:

- the current lane depends on many syntax-specific heuristics for `alias`, `import`, `use`, `defdelegate`, pipelines, captures, nested modules, and arity-aware caller resolution
- the language is macro-heavy and surface forms are structurally rich even in ordinary code
- retrieval quality depends on accurately understanding imports and unqualified calls
- the current lane already carries a large targeted regression surface, which is a strong maintenance-pressure signal

#### Needs structural parsing, but tree-sitter is not the only valid answer: `python`

`python` objectively needs AST-backed parsing for long-term correctness, but the repo does not need to commit specifically to tree-sitter yet.

Reasons:

- the current parser already implements explicit indentation, class/function stacks, local-body scope tracking, relative import normalization, and call disambiguation
- that is evidence of real structural need, not of a language that is comfortably handled by regex alone
- Python has a strong language-native AST ecosystem, so the real requirement is "move beyond adapter heuristics", not necessarily "choose tree-sitter"

#### Keep regex-first MVP for now: `lua`

`lua` does not yet objectively require tree-sitter at the current scope boundary.

Reasons:

- the current Lua lane is intentionally narrow and bounded
- the supported surface is mostly module-table functions, method syntax, `require`, and simple call normalization
- current correctness goals do not yet depend on modeling metatables, computed fields, dynamic ownership, or Luau-specific syntax

Lua should be reconsidered only when the supported surface expands materially beyond the current adapter contract.

#### Already justified and implemented: `typescript`, `java`

`typescript` and `java` already justify the existing tree-sitter paths.

Reasons:

- both languages have declaration and call surfaces that become brittle under regex-only parsing
- the repository already carries dedicated tree-sitter paths for them
- TypeScript additionally has parity-style tests that validate the deeper parser path against more advanced surface forms

#### No urgent tree-sitter pressure: `clojure`

`clojure` does not currently rank as a priority for stronger tree-sitter adoption.

Reasons:

- the runtime already has a language-specific structural parser path via `clj-kondo`
- the practical need is already being addressed by a non-tree-sitter parser strategy
- tree-sitter remains an optional enhancement path, not an urgent parser-correctness gap

---

## Evidence in the Current Repository

- The supported-language set is defined in `src/semantic_code_indexing/runtime/language_activation.clj`.
- Shared tree-sitter probing, grammar lookup, and diagnostics wiring live in `src/semantic_code_indexing/runtime/adapters.clj`.
- Elixir’s current parser is heavily heuristic-driven in `parse-elixir-file`, including alias/use/import expansion and arity-aware call handling.
- Python’s current parser already models indentation, nested scope, and import/call expansion in `parse-python-file`.
- Lua’s current parser is explicitly narrow and module-table oriented in `parse-lua` / `parse-lua-file`.
- Tree-sitter paths already exist for Clojure and Java in `runtime/adapters.clj`.
- TypeScript has its own tree-sitter path in `src/semantic_code_indexing/runtime/languages/typescript.clj`.
- Runtime tests already reflect the different maturity levels:
  - tree-sitter parser-path and TypeScript parity tests for existing tree-sitter lanes
  - many focused regression tests for Elixir and Python heuristics
  - bounded Lua parser coverage for the MVP surface

---

## Consequences

### Positive

- parser-depth work can be prioritized by technical need instead of symmetry
- the next structural-parser investment target becomes explicit: `elixir`
- Python can be evaluated on parser quality grounds without prematurely locking into tree-sitter
- Lua can stay small and explainable until scope expansion justifies deeper parsing

### Tradeoffs

- language lanes will continue to use different parser strategies
- parser strategy discussions now require justification against explicit criteria rather than preference alone
- some languages will remain on adapter parsing for longer, even though tree-sitter support exists elsewhere in the repo

---

## Non-Goals

- This ADR does not change any default parser in the current implementation.
- This ADR does not require tree-sitter for every supported language.
- This ADR does not decide Python in favor of tree-sitter over the native Python AST.
- This ADR does not make Lua tree-sitter work part of the current Lua lane definition.

---

## Follow-Ups

1. Prepare a dedicated implementation ADR or working note for Elixir tree-sitter adoption when that work is scheduled.
2. Make a parser-strategy decision for Python separately if Python parser deepening becomes active work.
3. Re-evaluate Lua only when the supported Lua surface expands beyond the current bounded MVP.
