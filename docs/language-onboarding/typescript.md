# TypeScript Onboarding Status

Created with `scripts/new-language-adapter.sh` and now upgraded with strategic semantic-core coverage on a dedicated TypeScript language module.

## Current State

- Language key: `typescript`
- File extensions: `.ts,.tsx`
- Current legacy parser function: `semidx.runtime.languages.typescript/parse-file`
  (regex default and tree-sitter option retained only during migration)
- Fixture files:
  - `fixtures/retrieval/typescript-happy-path.json`
  - `fixtures/retrieval/typescript-ambiguity.json`
- Current semantic coverage:
  - named imports
  - namespace imports
  - default-import ownership for both `export default function ...` and `export default foo;`
  - `this.` and local class-qualified method targeting
  - exported arrow/function-expression bindings as first-class units
  - object-literal methods and class field arrow methods
  - direct re-export alias units for simple barrel-style `export { foo as bar } from "./mod"` lines
  - advanced-surface parity across regex and tree-sitter for object methods, class field arrows, default-export aliases, and direct re-export aliases

## Next Steps (ADR-022)

### Authority Migration (ADR-046 / plans/018)

TypeScript is the first planned semantic-provider slice. Fresh SCIP/LSP evidence
becomes primary per operation, tree-sitter fills structural gaps, and regex is
explicit degraded fallback. The current parser-engine defaults are implementation
compatibility behavior, not target lane policy.

1. Expand ambiguity fixtures beyond direct one-hop re-export aliases into deeper barrel chains.
2. Keep the dedicated module thin enough that future Shadow IR work can land without re-growing a monolith.
3. Raise the public confidence ceiling only after stronger parser-quality evidence proves it.
4. Keep running `./scripts/validate-language-onboarding.sh typescript`.
