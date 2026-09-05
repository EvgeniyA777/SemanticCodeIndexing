# fetch_context_detail Fails On Long Unit Id In ReaderLens

## Repro

Repository root:

```text
/Users/ae/workspace/ReaderLens
```

Index response:

```json
{
  "index_id": "9aabe37c-2629-4bd8-9435-1ac1e173a2ef",
  "snapshot_id": "83c0b0e4-c40a-41ac-9dce-1848166c9e15",
  "active_languages": ["java"],
  "lifecycle_reason": "workspace_changed",
  "git_commit": "61a6013dfe0cd89d22dbc91f3127f1ab92702742"
}
```

Structured resolve query:

```json
{
  "schema_version": "1.0",
  "intent": {
    "purpose": "bug_investigation",
    "details": "Find exact security configuration and test meta-annotations controlling OAuth properties in CI verify after multi-issuer migration."
  },
  "targets": {
    "paths": [
      "src/main/java/com/foxminded/readerlens/security/SecurityConfiguration.java",
      "src/main/java/com/foxminded/readerlens/security/TrustedIssuersProperties.java",
      "src/test/java/com/foxminded/readerlens/testsupport/PostgresIntegrationTest.java",
      "src/test/java/com/foxminded/readerlens/testsupport/WithRealSecurityChain.java",
      "src/test/java/com/foxminded/readerlens/ReaderLensApplicationTests.java"
    ],
    "symbols": [
      "SecurityConfiguration",
      "TrustedIssuersProperties",
      "PostgresIntegrationTest",
      "WithRealSecurityChain",
      "ReaderLensApplicationTests"
    ]
  },
  "hints": {
    "prefer_definitions_over_callers": true,
    "preferred_modules": [
      "com.foxminded.readerlens.security",
      "com.foxminded.readerlens.testsupport"
    ]
  },
  "options": {
    "include_tests": true,
    "include_impact_hints": true
  },
  "constraints": {
    "freshness": "current_snapshot",
    "language_allowlist": ["java"],
    "token_budget": 16000
  }
}
```

`resolve_context` completed with:

```json
{
  "selection_id": "0d0230c1-41fc-4fd0-a742-a161063f9085",
  "snapshot_id": "83c0b0e4-c40a-41ac-9dce-1848166c9e15",
  "confidence_level": "medium",
  "result_status": "completed"
}
```

`expand_context` completed and returned a risky neighbor with a very long unit id,
including:

```text
src/test/java/com/foxminded/readerlens/security/TrustedIssuerAuthenticationManagerResolverTest.java::com.foxminded.readerlens.security.TrustedIssuerAuthenticationManagerResolverTest#aTokenForAnotherAudienceIsRejectedEvenFromATrustedIssuer
```

Then:

```json
{
  "index_id": "9aabe37c-2629-4bd8-9435-1ac1e173a2ef",
  "selection_id": "0d0230c1-41fc-4fd0-a742-a161063f9085",
  "snapshot_id": "83c0b0e4-c40a-41ac-9dce-1848166c9e15",
  "detail_level": "local_neighborhood"
}
```

returned:

```json
{
  "message": "invalid context packet generated",
  "details": {
    "code": "internal_contract_error",
    "category": "internal",
    "errors": {
      "skeletons": [null, null, null, null, null, null, { "unit_id": ["should be at most 240 characters"] }],
      "relevant_units": [null, null, null, null, null, null, { "unit_id": ["should be at most 240 characters"] }]
    }
  }
}
```

## Expected

`fetch_context_detail` should either return the detail packet or truncate/encode
long internal identifiers before validating the outgoing response.

## Actual

The tool returned an internal contract error because a selected Java test unit id
exceeded the outgoing response schema's 240-character limit.

## Context

Language: Java. Confidence ceiling: medium. A structured retry was used after a
broader bug-investigation query selected importer bootstrap methods first.
