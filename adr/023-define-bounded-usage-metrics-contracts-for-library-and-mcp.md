# ADR-023: Define Bounded Usage Metrics Contracts for Library and MCP

**Status**: Accepted  
**Date**: 2026-03-10  
**Deciders**: project owner

---

## Context and Problem Statement

`ADR-001` зафиксировал library-first form и host-owned telemetry wiring.  
`ADR-010` и `ADR-013` зафиксировали structured diagnostics и bounded retrieval stage events.  

После добавления usage telemetry для `library` и `mcp` остаётся следующий риск:

- adoption метрики начнут храниться как ad hoc JSON;
- helpfulness feedback от host systems станет несовместимым между интеграциями;
- библиотечная и MCP usage telemetry будут несопоставимы;
- в telemetry быстро утекут raw paths, raw queries или другие лишние payloads.

Нужно зафиксировать:

- какой bounded contract использовать для usage events;
- какой bounded contract использовать для explicit feedback;
- какие поля обязательны для correlation и analytics;
- где проходит граница между полезной telemetry и утечкой host payloads.

---

## Decision Drivers

- Сопоставимость `library` и `mcp` usage telemetry
- Возможность считать adoption и effectiveness KPIs
- Correlation с retrieval traces и host sessions
- Bounded payload size
- Safe-by-default redaction posture
- Совместимость с optional PostgreSQL storage sink

---

## Considered Options

### Option 1. Reuse retrieval stage event schema for everything

Писать usage telemetry тем же contract, что и stage events.

### Option 2. Separate bounded usage event and feedback contracts

Ввести отдельные contracts для operational usage events и explicit feedback, не смешивая их с retrieval stage telemetry.

### Option 3. Leave usage telemetry schema-free

Считать usage metrics purely internal implementation detail без canonical contracts.

---

## Decision

Мы принимаем **Option 2: Separate bounded usage event and feedback contracts**.

Система должна иметь две новые contract families:

- `usage_event`
- `usage_feedback`

Они отделены от:

- `diagnostics_trace`
- `retrieval_stage_event`

Потому что usage telemetry отвечает на другой вопрос:  
не “как прошел retrieval stage?”, а “как именно surface использовался и помог ли результат downstream agent workflow”.

---

## Usage Event Contract

`usage_event` фиксирует bounded operational/product telemetry record.

### Required fields

- `schema_version`
- `event_id`
- `occurred_at`
- `surface`
- `operation`
- `status`
- `payload`

### Optional correlation and summary fields

- `trace_id`
- `request_id`
- `session_id`
- `task_id`
- `actor_id`
- `tenant_id`
- `root_path_hash`
- `latency_ms`
- `file_count`
- `unit_count`
- `selected_units_count`
- `selected_files_count`
- `cache_hit`
- `confidence_level`
- `autonomy_posture`
- `result_status`
- `raw_fetch_level`

### Surface vocabulary

Initial bounded set:

- `library`
- `mcp`
- `http`
- `grpc`

### Operation vocabulary

Initial bounded set:

- `server_start`
- `create_index`
- `update_index`
- `repo_map`
- `resolve_context`
- `impact_analysis`
- `skeletons`
- `cache_eviction`

---

## Usage Feedback Contract

`usage_feedback` фиксирует explicit downstream helpfulness feedback.

### Required fields

- `schema_version`
- `feedback_id`
- `occurred_at`
- `surface`
- `operation`
- `feedback_outcome`
- `payload`

### Optional correlation fields

- `trace_id`
- `request_id`
- `session_id`
- `task_id`
- `actor_id`
- `tenant_id`
- `root_path_hash`

### Bounded outcome vocabulary

- `helpful`
- `partially_helpful`
- `not_helpful`
- `abandoned`

### Optional bounded follow-up vocabulary

- `planned`
- `drafted`
- `patched`
- `discarded`

---

## Redaction and Safety Rules

Usage contracts must remain safe by default.

By default they must not contain:

- raw repository paths
- raw retrieval query payloads
- raw code
- full context packets
- arbitrary host task dumps

Instead:

- repository identity is represented by `root_path_hash`;
- payload is a bounded summary map with small scalar values only;
- host systems can correlate via IDs instead of embedding task contents.

---

## Consequences

### Positive

- Library and MCP usage telemetry become comparable.
- PostgreSQL usage sink can persist stable records without schema drift.
- Hosts can report helpfulness in a standard way.
- Product and operations metrics stay separated from retrieval diagnostics.

### Negative

- New vocabulary additions require deliberate contract evolution.
- Some host-specific analytics details must be summarized instead of copied verbatim.

---

## Definition of Done

Решение считается реализованным корректно, когда:

1. Есть canonical JSON Schema и Malli mirror для `usage_event` и `usage_feedback`.
2. Есть canonical examples для library usage, MCP usage, и explicit helpfulness feedback.
3. Contract validator знает новые example families.
4. Runtime telemetry implementation пишет bounded data, совместимую с этими contracts.
