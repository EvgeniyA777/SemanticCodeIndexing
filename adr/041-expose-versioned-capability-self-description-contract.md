---
file_type: adr
decision_id: ADR-041
title: Expose a Versioned Capability Self-Description Contract
status: accepted
date: 2026-07-19
deciders:
  - project owner
tags:
  - architecture
  - capabilities
  - contracts
summary: Expose language support and confidence ceilings through a versioned capability contract derived from the language registry.
agent_summary: Read this ADR before changing capability self-description. Runtime capability payloads are derived from the language registry and exposed consistently through supported public surfaces.
supersedes: []
superseded_by: null
renumbered_from: "ADR-025 (duplicate identifier)"
links:
  - plans/014_capability_self_description_plan.md
  - reports/010_capability_self_description_progress_log.md
  - contracts/schemas/capabilities.schema.json
---

# ADR-041: Expose a Versioned Capability Self-Description Contract

## Status
Accepted and implemented.

This decision was originally stored under a duplicate `ADR-025` filename. It was
renumbered to ADR-041 during the 2026-08-02 documentation lifecycle migration;
the decision itself was not changed.

## Context
SemIdx parses multiple languages, but extracts different levels of semantic detail for each. Some languages (like Clojure) have deep semantic graphing, while others (like HTML, CSS, or Lua) only have weak lexical coverage. 

Previously, SemIdx did not formally describe its parsing boundaries and confidence limits to consuming tools (such as MCP clients). This resulted in clients expecting full semantic capabilities for weakly-supported languages, leading to uncalibrated confidence and silent degradation.

## Decision
Expose a versioned capabilities contract (`capabilities.schema.json`) that explicitly defines language support levels and confidence ceilings.

1. **Projection**: We project the internal `language-registry` lanes into the external capabilities contract shape.
2. **Exposition**: We expose this payload in:
    * The MCP `initialize` response (`semidx_capabilities` field).
    * The HTTP and gRPC `handle-health` endpoints (`capabilities` or `capabilities_json`).
3. **Boundaries**: We restrict the `language_policy` options within tools (like `create_index`) to only allow known options mapped to this payload using the `languagePolicyOption` enum.

## Consequences
- **Positive**: External clients (including agents) can now read the self-described capabilities during the handshake or health check and adjust their behavior dynamically based on language confidence ceilings.
- **Positive**: Strict contract validation ensures that capabilities metadata remains tightly coupled to actual runtime behavior.
- **Negative**: Adds a tiny amount of overhead to initialization and health check responses.
