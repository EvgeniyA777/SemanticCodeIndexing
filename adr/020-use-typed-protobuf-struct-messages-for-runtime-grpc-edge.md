# ADR-020: Use Typed Protobuf Struct Messages for the Runtime gRPC Edge

**Status**: Accepted  
**Date**: 2026-03-08  
**Deciders**: project owner

---

## Context and Problem Statement

The initial gRPC edge used JSON strings as request/response payloads for fast parity with HTTP semantics.

That shape reduced type safety at transport level and made gRPC wiring less idiomatic.

We need typed protobuf transport while preserving current runtime contract semantics and avoiding large migration cost.

---

## Decision

Use `google.protobuf.Struct` as the transport message type for unary gRPC methods:

- `Health`
- `CreateIndex`
- `ResolveContext`

This keeps payloads structurally typed as protobuf messages while preserving map-shaped runtime semantics.

---

## Why this shape

- typed protobuf transport without introducing a full generated message schema migration in one step
- parity with existing runtime semantics and test fixtures
- simpler migration path from JSON-string transport to fully typed proto messages later

---

## Consequences

### Positive

- gRPC method descriptors now use protobuf marshalling
- transport-level type discipline improves over raw strings
- existing runtime API behavior remains unchanged

### Tradeoff

- numeric values inside `Struct` are represented as protobuf number values (double semantics)
- still not domain-specific generated protobuf message types

---

## Follow-ups

- define dedicated domain proto messages for query/result contracts
- add explicit numeric-field normalization policy for strict type-sensitive consumers
- keep HTTP/gRPC contract parity tests as release gate
