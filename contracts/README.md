# Contracts

Canonical contracts, examples, and fixture-ready artifacts for the retrieval library.

## Layout

- `schemas/` - JSON Schema contracts (draft 2020-12)
- `examples/` - canonical example payloads for hosts, diagnostics, events, and policy records
- `examples/catalog.json` - metadata index for canonical examples

## Authoring Conventions

These files follow the official JSON Schema best-practice direction from the JSON Schema documentation:

- declare `$schema` and stable `$id`
- use `title` and `description`
- close object shapes with `additionalProperties: false` unless extension is intentional
- reuse shared building blocks through `$defs` and `$ref`
- keep payloads bounded and safe by default
- version contracts explicitly with `schema_version`

Reference sources:

- <https://json-schema.org/learn/getting-started-step-by-step>
- <https://json-schema.org/learn/miscellaneous-examples>
- <https://json-schema.org/understanding-json-schema/structuring>
- <https://json-schema.org/understanding-json-schema/reference/object>
- <https://json-schema.org/understanding-json-schema/reference/annotations>

## Current Contract Families

- retrieval query
- context packet
- confidence
- guardrail assessment
- diagnostics trace
- retrieval stage event
- usage event
- usage feedback
- override record
- human review record

## Notes

- Canonical examples are raw payloads intended to validate against sibling schemas where applicable.
- Usage feedback now supports structured retrieval judgments such as issue codes and optional ground-truth unit/path hints for replay-oriented quality loops.
- Policy flows currently use record examples (`override-record`, `human-review-record`) rather than a separate policy-event schema.
- Retrieval fixtures live under `fixtures/` and capture expected behavior bands rather than implementation accidents.

## Validation

- Local gate: `./scripts/validate-contracts.sh`
- CI gate: `.github/workflows/contracts-validation.yml`
