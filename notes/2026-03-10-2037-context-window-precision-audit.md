---
file_type: working-note
topic: context-window-precision-audit
created_at: 2026-03-10T20:37:10-0700
author: codex
language: ru
---

# Working Note: Audit of Context-Window Savings vs Retrieval Precision

Краткая dated-заметка по архитектурной сверке исходного promise репозитория с тем, что реально реализовано в коде на текущий момент.

## О чём файл

Вопрос проверки был таким:

- действительно ли текущее репо экономит окно контекста для AI-агентов
- действительно ли оно даёт точные результаты при проходе по коду
- насколько это уже соответствует заявлению в README и ADR-слое

Ниже зафиксирован итоговый вывод по коду, тестам и контрактам, а не по README-only формулировкам.

## Короткий вердикт

Репозиторий уже хорошо выглядит как `contract/governance/retrieval-packaging framework` для AI-систем.

Он пока заметно слабее как доказанный `semantic indexing engine`, который сам по себе:

- существенно экономит context window
- надёжно локализует authority code path на реальных репозиториях
- полноценно исполняет весь declared retrieval-query contract

Итоговая практическая оценка:

- экономия контекста: частично реализована
- точность code-walk: хороша на curated structured queries, но не доказана как general-case
- инженерная зрелость обвязки: высокая

## Что подтверждено

- В репозитории есть реальный runtime, а не только контракты:
  - индексатор
  - multi-language adapters
  - retrieval ranking
  - impact hints
  - guardrails
  - storage
  - MCP/HTTP/gRPC edges
- Retrieval опирается на structural-first heuristics, caller/callee graph, import/owner-aware disambiguation и bounded raw-code escalation.
- Проект хорошо покрыт собственными regression/integration тестами.
- Локальный прогон `clojure -M:test` на момент аудита прошёл:
  - `95` tests
  - `546` assertions
  - `0` failures
  - `0` errors

## Главный архитектурный вывод

На текущем состоянии проекта лучше говорить так:

- это сильная инфраструктура для bounded retrieval around AI agents
- это ещё не сильное доказательство того, что сам retrieval-core уже решает задачу "экономить context window и при этом стабильно давать точный semantic walk по коду"

То есть strongest part проекта сейчас находится в:

- контрактах
- safety/guardrails
- policy/governance
- diagnostics/events
- usage metrics
- replay/evaluation loop

А weakest part относительно исходного promise находится в:

- полноте исполнения query contract
- реальном budget-aware context packing
- fail-safe поведении при слабом сигнале
- доказательной базе на non-toy repositories

## Основные разрывы между promise и implementation

### 1. Query contract шире, чем реально используемый runtime

Схема retrieval query объявляет богатую управляющую поверхность:

- `snapshot_id`
- `language_allowlist`
- `allowed_path_prefixes`
- `suspected_symbols`
- `focus_on_tests`
- `prefer_definitions_over_callers`
- `prefer_breadth_over_depth`
- `include_tests`
- `include_impact_hints`
- `favor_compact_packet`
- `favor_higher_recall`

Но в retrieval runtime реально используются главным образом:

- `targets.symbols`
- `targets.paths`
- `targets.modules`
- `targets.tests`
- `targets.changed_spans`
- `targets.diff_summary`
- `hints.preferred_paths`
- `hints.preferred_modules`
- `constraints.token_budget`
- `constraints.max_raw_code_level`
- `constraints.freshness`

Практический смысл этого разрыва:

- declared host control model шире фактического control model
- часть contract surface сейчас скорее policy-shaped documentation, чем operational behavior

### 2. Token budget в основном диагностический, а не управляющий

Runtime считает `requested_tokens` и `estimated_tokens`, но основной пакет не подстраивается жёстко под budget.

На текущей реализации retrieval всё равно:

- берёт до `20` ranked units
- возвращает `relevant_units`
- возвращает `skeletons`
- возвращает `impact_hints`

Даже если budget превышен, это в основном отмечается через `truncation_flags`, а не через реальное урезание итогового packet surface.

Практический смысл:

- bounded packaging заявлено сильнее, чем фактически enforced packing
- экономия context window есть, но пока мягкая и эвристическая, а не строгая

### 3. При слабом сигнале fallback ухудшает точность

Если scoring не нашёл сильных кандидатов, runtime не возвращает честный "insufficient evidence" packet как основной путь.

Вместо этого он берёт первые `10` units по сортировке файлов как fallback candidates.

Практический смысл:

- система может выглядеть полезной там, где она на самом деле просто не нашла релевантный ответ
- это опасно именно для agent use-case, где ложная уверенность хуже пустого ответа

### 4. Система ожидает уже структурированный query от внешнего planner

Проект хорошо решает bounded retrieval для `structured host query`.

Он не решает сам по себе задачу:

- взять свободный вопрос агента
- правильно выделить target symbols / paths / modules
- затем выполнить retrieval

Практический смысл:

- как standalone answer engine для агентского code reasoning проект переоценивать нельзя
- как library component внутри более крупного orchestration stack проект выглядит уместно

### 5. Качество retrieval доказано в основном на synthetic and curated scenarios

Тесты хорошие, но значимая часть regression coverage строится на small generated repos внутри test suite.

Fixtures тоже прямо описаны как compact and repository-agnostic, а не как large real-repo benchmark corpus.

Практический смысл:

- repo уже хорошо защищён от локальных регрессий
- repo пока не доказывает generalizable precision на реальных больших кодовых базах

## Что в проекте реально сильное

- Чёткая архитектурная декомпозиция: parser adapters, index, retrieval, policy, storage, edges.
- Хорошая дисциплина bounded outputs и contract validation.
- Внятная safety model: confidence, capability ceiling, guardrails, stale-index signals.
- Сильная observability surface: diagnostics trace, stage events, usage metrics, replay scoring.
- Грамотная эволюционная рамка для retrieval tuning через fixtures, benchmarks и policy governance.

## Что это значит для позиционирования репозитория

Если формулировать честно, текущее позиционирование должно звучать ближе к следующему:

"Library-first bounded retrieval subsystem with strong contracts, diagnostics, governance, and heuristic multi-language indexing for AI development systems."

А не как более сильное утверждение вида:

"Already proven semantic engine that reliably saves context and returns precise code understanding results across real repositories."

## Практический итог

Если судить именно по целевой формулировке "должно было экономить окно контекста для AI агентов и выдавать точные результаты проходясь по коду", то текущее состояние лучше описывать как:

- `частично соответствует`
- ближе к `retrieval infrastructure with disciplined safety/governance`
- ещё не дотягивает до `fully convincing semantic retrieval engine`

Рабочая оценка на момент аудита:

- context-window savings: `6/10`
- code-walk precision: `6/10`
- engineering rigor of the surrounding system: `8/10`

## Трасса проверки

Проверка опиралась на:

- `README.md`
- `contracts/schemas/retrieval-query.schema.json`
- `src/semantic_code_indexing/runtime/retrieval.clj`
- `src/semantic_code_indexing/runtime/index.clj`
- `src/semantic_code_indexing/runtime/adapters.clj`
- `src/semantic_code_indexing/runtime/retrieval_policy.clj`
- `src/semantic_code_indexing/runtime/evaluation.clj`
- `test/semantic_code_indexing/runtime_test.clj`
- `fixtures/README.md`

## Канонические соседние артефакты

- `README.md`
- `docs/runtime-api.md`
- `docs/roadmap-status.md`
- `MEMORY.md`
- `adr/005-rank-context-using-structural-signals-first-and-semantic-signals-second.md`
- `adr/006-fetch-raw-code-late-and-only-for-ranked-spans.md`
- `adr/009-define-a-structured-host-query-contract-with-soft-hints-by-default.md`
- `adr/014-govern-retrieval-quality-with-versioned-fixtures-and-benchmarks.md`
