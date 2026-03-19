# Post-Roadmap Semantic Deepening Plan

Execution plan for the next semantic tranche after the main roadmap phases (`Phase 3` through `Phase 5`) were closed.

## Goal

Push the language adapters beyond the delivered roadmap scope toward more compiler-grade ownership, dispatch, inheritance, decorator, and parser-strength behavior, while keeping every slice independently shippable.

## Stage Checklist

- `[x]` Stage 1 Clojure lexical + destructuring deepening
- `[x]` Stage 2 Clojure multimethod / protocol / dispatch deepening
- `[x]` Stage 3 Java inheritance / lambda / method-reference deepening
- `[x]` Stage 4 Python decorator / class-scope deepening
- `[x]` Stage 5 Elixir pipelines / `with` / nested-module deepening
- `[x]` Stage 6 TypeScript parser-strengthening tranche
- `[x]` Stage 7 Cross-language confidence recalibration
- `[x]` Stage 8 Post-roadmap closure

## Delivery Rule

Every stage ends with:

1. code + tests
2. docs/status update
3. full `clojure -M:test`
4. one `git commit`
5. one `git push`

No batching of multiple stages into one commit.

## Current Active Stage

All stages in this tranche are now delivered.

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

Delivered scope:

- Elixir call-arity indexing is now pipeline-aware, so `|> normalize()` is treated as an arity-preserving call onto `normalize/1` instead of falling back to the zero-arg shape.
- `with` blocks and local function captures such as `&normalize/1` now reuse the same local/import-aware resolution path, which keeps locally owned functions from leaking onto imported targets.
- `__MODULE__.Nested.foo(...)` local qualification now has regression coverage proving nested-module calls link onto the nested module target.
- Regression coverage now proves pipeline, `with`, function-capture, and nested-module behavior without regressing existing local-vs-import precedence.

### Stage 6

Delivered scope:

- TypeScript regex extraction now emits first-class units for object-literal methods and class field arrow methods, so those surfaces no longer stay invisible to the semantic layer.
- `export default foo;` indirection now backfills the default-export call token onto the referenced local unit, which keeps default-import callers attached to the real function symbol.
- Direct re-export aliases now materialize as first-class alias units, giving barrel-style `export { foo as bar } from "./mod"` chains a provable semantic surface for downstream callers.
- Runtime and onboarding regression coverage now prove object methods, class field arrows, default-export aliasing, and re-export alias units while TypeScript still keeps its conservative public confidence ceiling.

### Stage 7

Delivered scope:

- Recalibration pass confirmed that the recent Java/Python/Elixir/TypeScript semantic gains still do not justify a public confidence-ceiling bump beyond the already published language strengths.
- TypeScript retains its explicit `low` ceiling even after object/class-field/default-alias/re-export improvements, and runtime regression coverage now proves that this non-bump is intentional rather than accidental drift.
- Runtime, MCP, README, onboarding, and memory wording are now aligned around the same “stronger surfaces, unchanged public ceiling” story.
- No contract fields changed: `selected_language_strengths` and `confidence_ceiling` remain stable, additive metadata only.

### Stage 8

Delivered scope:

- Final docs/status normalization is complete across README, runtime/MCP docs, onboarding notes, memory, and roadmap status pointers.
- This full post-roadmap semantic-deepening tranche is now marked delivered rather than active.
- The next explicit frontier after this tranche is deeper interprocedural/dataflow-sensitive semantic work plus stronger tree-sitter/compiler-grade ownership where the current regex-first lanes still stay conservative.

## Next Frontier

The next tranche after this delivered plan should focus on:

- deeper interprocedural/dataflow-sensitive semantic resolution
- stronger tree-sitter/compiler-grade ownership in the non-Clojure lanes, prioritized by `ADR-028`
- `Elixir` as the highest-priority next tree-sitter lane
- `Python` as the next structural-parser decision, without prematurely locking into tree-sitter over native AST
- `Lua` remaining on bounded regex-first parsing until its supported surface expands materially
- continued operational/docs alignment as those deeper semantic capabilities land
