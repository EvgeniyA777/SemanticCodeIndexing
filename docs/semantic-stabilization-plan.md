# Semantic Stabilization Plan

This tranche shifts the repo from "more heuristics" toward internal architectural stabilization.

Canonical goals:

- split the current semantic adapter hotspot into thinner language-facing modules
- introduce a normalized internal semantic IR between extraction and resolver narrowing
- use TypeScript as the first Shadow IR lane, because it has the highest parser-mode drift risk
- include only high-leverage semantic fixes that materially affect graph correctness or parser parity

Execution defaults:

- keep public contracts and CLI names stable
- end each stage with tests, docs update, commit, and push
- treat every semantic fix as a new regression baseline rather than an opportunistic cleanup

Planned implementation order:

1. semantic IR scaffold + facade cleanup
2. TypeScript Shadow IR + parity fixes
3. shared resolver extraction + Java/Python stabilization
4. full adapter split + Clojure/Elixir migration
5. benchmark-backed closure and roadmap normalization
