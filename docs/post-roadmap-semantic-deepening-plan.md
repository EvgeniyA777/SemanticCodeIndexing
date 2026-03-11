# Post-Roadmap Semantic Deepening Plan

Execution plan for the next semantic tranche after the main roadmap phases (`Phase 3` through `Phase 5`) were closed.

## Goal

Push the language adapters beyond the delivered roadmap scope toward more compiler-grade ownership, dispatch, inheritance, decorator, and parser-strength behavior, while keeping every slice independently shippable.

## Stage Checklist

- `[x]` Stage 1 Clojure lexical + destructuring deepening
- `[x]` Stage 2 Clojure multimethod / protocol / dispatch deepening
- `[x]` Stage 3 Java inheritance / lambda / method-reference deepening
- `[x]` Stage 4 Python decorator / class-scope deepening
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

`Stage 5` is now the active slice.

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

Delivered scope:

- Java units now retain direct superclass metadata so resolver-side targeting can prefer inherited owners when the caller lives in a subclass.
- `super.method(...)` and `super::method` now resolve onto the parent implementation instead of collapsing back onto same-name overrides in the current class.
- Unqualified inherited calls inside subclass methods and lambda bodies now keep linking to the parent method when no local override should win.
- Regression coverage now proves `super.` precedence, inherited unqualified calls, lambda-owned inherited calls, and `super::method` references without regressing the local-override case.

### Stage 4

Delivered scope:

- Python decorated methods continue to resolve through `@classmethod` and `@staticmethod` ownership, so `cls.method(...)` and class-qualified static calls stay attached to class-owned targets.
- Nested local `def` and `class` declarations inside a Python unit now suppress false outward edges, which keeps imported symbols and same-name class methods from stealing locally scoped calls.
- Property access stays conservative: decorated `@property` methods remain indexable units, but plain attribute reads do not over-link as call edges.
- Regression coverage now proves decorated class/static method resolution plus nested local-scope and property-access suppression.

### Stage 5

Planned scope:

- pipeline-aware caller ownership
- `with` block target preservation
- nested module reference normalization
- anonymous function local ownership where statically provable
