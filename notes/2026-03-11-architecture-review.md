---
title: "Vertical And Horizontal Architecture Review"
doc_type: "architecture_review"
lifecycle: "completed"
status: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
file_type: working-note
topic: architecture-review
created_at: 2026-03-11T20:15:00-0700
author: codex
language: ru
---

# Working Note: Vertical + Horizontal Review and Meta-Architecture Critique

Краткая dated-заметка по итогам вертикального и горизонтального ревью репозитория после закрытия основного roadmap и post-roadmap semantic-deepening tranche.

## Короткий вывод

Репозиторий сейчас выглядит сильным как:

- bounded retrieval/runtime platform
- contract/governance/evaluation system
- pragmatic multi-language semantic indexing layer

Но он все еще не выглядит как полностью compiler-grade semantic platform. Главная системная проблема не в отсутствии фич, а в том, что semantic complexity растет быстрее, чем внутренняя архитектурная декомпозиция.

## Главные findings

### 1. TypeScript re-export synthetic units имеют хрупкую line привязку

В regex TypeScript path synthetic re-export defs вычисляют `:start-line` через поиск по содержимому строки, а не по индексу прохода.

Практический риск:

- одинаковые строки `export { ... } from ...` могут получить один и тот же `start-line`
- unit boundaries и body extraction начнут смещаться
- downstream linkage для alias units станет нестабильным

Рабочий вывод:

- это локальный implementation defect, не концептуальная проблема
- чинится переходом на `map-indexed` вместо content-based `.indexOf`

## 2. TypeScript parser parity не дотянута

Regex path уже умеет:

- object-literal methods
- class field arrow methods
- `export default foo`
- direct re-export alias units

Но tree-sitter path отстает и не материализует те же semantic surfaces как first-class units.

Практический риск:

- поведение зависит от parser mode
- docs/status уже звучат более цельно, чем фактическая parity
- это подрывает explainability и воспроизводимость retrieval behavior

Рабочий вывод:

- TypeScript lane функционально усилился
- но как engineering story он еще regex-first, а не parser-strengthened в полном смысле

## 3. Python nested-scope suppression остается грубой эвристикой

Локальная suppression логика собирает nested `def`/`class` names по телу unit слишком широко и не моделирует lifetime nested bindings достаточно точно.

Практический риск:

- реальный imported/global edge может быть скрыт после branchy или decorator-heavy nested structure
- на curated fixtures это выглядит нормально
- на шумном production Python коде может давать скрытые false negatives

Рабочий вывод:

- текущий slice полезен
- но это еще не strong lexical model

## 4. Java inheritance resolution пока только direct-super aware

Resolver учитывает только один `superclass_module` и на этом ранжирует inherited candidates.

Практический риск:

- multi-level inheritance
- interface-heavy APIs
- более сложные Java hierarchies

могут давать недостоверное narrowing.

Рабочий вывод:

- для roadmap/post-roadmap slice это приемлемо
- для compiler-grade claim это пока structural limitation

## Вертикальный разбор

Вертикально проект собран хорошо:

- концепт retrieval + governance + semantic indexing держится
- runtime/tests/docs/status в основном выровнены
- staged delivery discipline сильная

Главный вертикальный разрыв:

- глубина semantic correctness по language lanes неравномерна
- product story уже общая
- implementation reality еще сильно lane-dependent

То есть вертикальная целостность хорошая, но semantic depth пока неравномерна.

## Горизонтальный разбор

Горизонтально система стала намного более целостной:

- retrieval flow
- policy/governance loop
- capability ceilings
- status/docs/contracts/examples

теперь согласованы лучше, чем раньше.

Главный горизонтальный риск:

- все больше языковой логики концентрируется в одном hotspot `runtime/adapters.clj`
- semantic engine фактически существует, но без явного внутреннего IR и без clean split между extraction, normalization, and resolution

Это уже начинает выглядеть как масштабируемый technical debt, а не просто “один большой adapter file”.

## Meta-architect critique

### Концепт

Концепт продукта сильный и дифференцированный:

- compact-first retrieval
- governed quality loop
- semantic indexing as substrate for AI systems

Это выглядит осмысленно и не как commodity wrapper.

### Польза

Наибольшая польза сейчас не от “идеальной семантики”, а от того, что repo умеет:

- bounded retrieval
- contract-valid outputs
- replay/governance driven tuning
- operationally explainable runtime behavior

Semantic deepening приносит ценность только если реально улучшает selection quality, а не просто добавляет новый класс эвристик.

### Реализация

Реализация инженерно дисциплинированная, но архитектурно начинает упираться в следующие пределы:

- adapter monolith
- parser-mode drift
- language-lane asymmetry
- heavy reliance on heuristics without explicit semantic IR

Если продолжать тем же паттерном, то скорость добавления language semantics еще какое-то время сохранится, но explainability, maintainability и confidence calibration начнут деградировать.

## Практический next step

Перед следующим крупным semantic tranche лучше сделать не еще одну порцию эвристик, а внутреннюю архитектурную стабилизацию:

1. разрезать `runtime/adapters.clj` на per-language modules
2. ввести normalized semantic IR:
   `unit extraction -> ownership facts -> import facts -> call candidates -> ranking`
3. закрыть parser-parity хотя бы для TypeScript
4. измерять следующий tranche по benchmark/replay delta, а не по числу новых semantic cases

## Канонические соседние артефакты

- `MEMORY.md`
- `docs/roadmap-status.md`
- `plans/003_post_roadmap_semantic_deepening_plan.md`
- `src/semidx/runtime/adapters.clj`
- `src/semidx/runtime/index.clj`
