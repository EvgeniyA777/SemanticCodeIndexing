# Post-Roadmap Semantic Deepening Plan

Execution plan for the next semantic tranche after the main roadmap phases (`Phase 3` through `Phase 5`) were closed.

## Goal

Push the language adapters beyond the delivered roadmap scope toward more compiler-grade ownership, dispatch, inheritance, decorator, and parser-strength behavior, while keeping every slice independently shippable.

## Stage Checklist

- `[x]` Stage 1 Clojure lexical + destructuring deepening
- `[x]` Stage 2 Clojure multimethod / protocol / dispatch deepening
- `[ ]` Stage 3 Java inheritance / lambda / method-reference deepening
- `[ ]` Stage 4 Python decorator / class-scope deepening
- `[ ]` Stage 5 Elixir pipelines / `with` / nested-module deepening
- `[ ]` Stage 6 TypeScript parser-strengthening tranche
- `[ ]` Stage 7 Cross-language confidence recalibration
- `[ ]` Stage 8 Post-roadmap closure

## Delivery Rule

Every stage ends with:

1. code + tests
2. docs/status update
3. full `clojure -M:test`
4. one `git commit`
5. one `git push`

No batching of multiple stages into one commit.

## Current Active Stage

`Stage 3` is now the active slice.

## Stage Notes

### Stage 1

Delivered scope:

- Clojure fallback/tree-sitter call extraction now respects lexical local bindings more accurately.
- Local params, `let` bindings, destructured locals, `when-let` locals, comprehension bindings, `as->` bindings, and `letfn` helper names now suppress false global caller edges.
- Regression coverage now proves local lexical ownership beats same-name namespace vars in the regex fallback lane.

### Stage 2

Delivered scope:

- Literal dispatch-value `defmulti` calls now emit dispatch-aware call tokens so callers can link to the matching `defmethod` instead of the generic multimethod symbol alone.
- `defmethod` units now keep dispatch-specific identity without also polluting the generic symbol index, which prevents plain multimethod calls from over-linking every implementation.
- `defprotocol` forms now emit protocol method units directly so protocol-owned call sites can resolve against a first-class method surface even when only the protocol declaration is present.
- Regression coverage now proves both dispatch-specific multimethod targeting and protocol method caller resolution in the Clojure adapter.

### Stage 3

Planned scope:

- `super.` vs local method precedence
- inherited owner matching
- lambda-body caller ownership
- method-reference linkage
- conservative fallback when owner class is uncertain
