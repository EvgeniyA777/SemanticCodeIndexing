---
file_type: adr
decision_id: ADR-047
title: Retain A Repo-Managed Tree-Sitter Toolchain For Structural Providers
status: accepted
date: 2026-08-02
deciders:
  - project owner
tags:
  - architecture
  - tree-sitter
  - providers
summary: Retain explicit repo-managed tree-sitter executable and grammar resolution as the operational boundary for structural providers, independently of parser-authority policy.
agent_summary: This ADR is the decision of record for tree-sitter toolchain resolution. Tree-sitter is a structural provider under ADR-046, not an optional regex acceleration policy. Resolve the CLI and grammars through explicit configuration, environment, repository-managed bootstrap, then PATH developer fallback; report unavailable or failed execution explicitly.
supersedes:
  - ADR-036
superseded_by: null
links:
  - adr/036-use-a-repo-managed-tree-sitter-toolchain.md
  - adr/046-prefer-semantic-evidence-providers-over-structural-and-lexical-fallbacks.md
  - plans/018_semantic_provider_authority_migration_plan.md
---

# ADR-047: Retain A Repo-Managed Tree-Sitter Toolchain For Structural Providers

**Status**: Accepted
**Date**: 2026-08-02
**Deciders**: project owner

---

## Context

ADR-036 combined two decisions: how tree-sitter executables and grammars are
resolved, and which parser has authority. ADR-046 supersedes the latter: fresh
SCIP/LSP evidence is primary, tree-sitter is structural gap filling, and regex is
degraded fallback. The operational toolchain problem remains independent:
tree-sitter execution must not depend on an ambient globally installed command
or unpinned grammar checkout.

The current shared helper boundary already resolves an explicit CLI path,
environment configuration, a repository-managed bootstrap link, and finally
ambient `PATH`. This ADR preserves that boundary without retaining the obsolete
regex-default policy.

## Decision Drivers

- Structural providers need reproducible executable and grammar resolution.
- Basic indexing must not require a globally installed tree-sitter command.
- Provider authority must not be inferred from tool availability.
- Unavailable, misconfigured, or failed tree-sitter execution must be explicit
  degradation, not a silent source substitution.
- Toolchain logic belongs outside language-specific semantic authority policy.

## Considered Options

### Option 1. Depend on an ambient PATH-installed CLI

Resolve `tree-sitter` only from the host environment.

### Option 2. Use a repo-managed resolution boundary

Resolve the CLI and grammar through explicit configuration, environment,
repository bootstrap, then an optional developer `PATH` fallback.

### Option 3. Embed a JVM parser binding immediately

Replace the CLI with a JVM-accessible binding and directly load grammars.

## Decision

We accept Option 2: retain a repo-managed tree-sitter toolchain boundary.

The structural-provider adapter resolves its executable in this order:

1. explicit parser/provider option such as `:tree_sitter_cli_path`;
2. environment configuration such as `SEMIDX_TREE_SITTER_CLI_PATH`;
3. repository-managed `.tree-sitter-grammars/bin/tree-sitter` created by
   `scripts/setup-tree-sitter-grammars.sh`;
4. ambient `PATH` as a developer convenience fallback.

Grammar paths follow the same explicit configuration and repository-bootstrap
discipline. If the CLI, grammar, or parse invocation is unavailable, the
tree-sitter provider reports an explicit structured degradation. Under ADR-046,
the provider planner may then use eligible lower-authority evidence; it must not
silently claim tree-sitter evidence ran or elevate regex output.

## Consequences

### Positive

- Tree-sitter remains reproducible and portable across development and agent
  environments.
- The toolchain boundary is available to the structural tier without deciding
  semantic-provider precedence.
- Failure is observable and compatible with ADR-046 degradation reporting.

### Negative

- The repository still owns bootstrap tooling and CLI-process lifecycle costs.
- The CLI model remains less embedded than a JVM binding.
- Grammar/version upkeep remains an operational responsibility.

### Follow-Up

- plans/018 adapts this toolchain behind the tree-sitter structural provider.
- Reconsider an embedded binding only when measured CLI latency, distribution, or
  reliability justifies a separate decision.

## References

- [ADR-036](./036-use-a-repo-managed-tree-sitter-toolchain.md)
- [ADR-046](./046-prefer-semantic-evidence-providers-over-structural-and-lexical-fallbacks.md)
- [plans/018](../plans/018_semantic_provider_authority_migration_plan.md)

## Definition Of Done

The decision is satisfied while tree-sitter structural-provider execution:

1. resolves explicit configuration before ambient host state;
2. can use the repository-managed bootstrap location;
3. reports unavailable/misconfigured/failed execution explicitly; and
4. remains independent from authority policy and regex classification.
