# Semantic Stabilization Plan

This tranche shifted the repo from "more heuristics" toward internal architectural stabilization.

Delivered goals:

- introduce a normalized internal semantic IR between extraction and resolver narrowing
- move TypeScript onto a dedicated language module and close its highest-leverage parser-parity gaps
- tighten Java superclass ancestry and Python immediate-scope local suppression where graph correctness was leaking
- expose dedicated language entry namespaces for all supported languages while keeping `runtime/adapters` as the canonical facade

Execution defaults used:

- keep public contracts and CLI names stable
- end each stage with tests, docs update, commit, and push
- treat every semantic fix as a new regression baseline rather than an opportunistic cleanup

Delivered implementation order:

1. semantic IR scaffold + facade cleanup
2. TypeScript Shadow IR + parity fixes
3. Java/Python graph-correctness tightening
4. dedicated language entry namespace split across all supported languages
5. docs/status normalization for the delivered stabilization slice
