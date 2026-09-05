---
title: "Lua Language Lane Proposal And Implementation Sketch"
doc_type: "implementation_note"
lifecycle: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
file_type: working-note
topic: lua-language-lane
created_at: 2026-03-18T11:30:00-0700
author: codex
language: ru
status: completed
---

# Working Note: Lua Language Lane Proposal and Implementation Sketch

Ниже сохранен почти дословно исходный рабочий текст про то, как добавлять Lua в индексатор. Эта заметка нужна как dated implementation sketch; каноническое архитектурное решение фиксируется отдельным ADR.

Я бы делал Lua в 2 этапа: сначала `regex/adapter MVP`, потом при необходимости `tree-sitter`. Архитектурно это сюда ложится нормально: индекс и retrieval уже в основном язык-агностичны, если новый парсер возвращает тот же IR через `semantic_ir.clj`. Основные точки встройки сейчас видны в `runtime/language_activation.clj`, `runtime/adapters.clj`, `runtime/index.clj` и тестах вроде `runtime_test.clj`.

## Как делать

1. Добавить `lua` в список поддерживаемых языков в `language_activation.clj`. После этого `supported_languages`, `allow_languages`, `prewarm_languages`, `language_refresh_required` и ответы MCP/HTTP начнут учитывать Lua автоматически.
2. Научить discovery видеть `.lua` через `adapters.clj`. Этого достаточно, чтобы `source-files`, `detected_languages` и фильтрация по active languages заработали без правок в index/retrieval.
3. Добавить модуль `runtime/languages/lua.clj` по образцу Python/Elixir и реализовать `parse-lua-file` в `adapters.clj`. Для MVP хватит regex-парсера.
4. В диспетчере `adapters.clj` добавить ветку `"lua"`.
5. Обновить тесты, где список языков зашит буквально: `project_context_test.clj`, `runtime_http_test.clj`, `mcp_server_test.clj`, `runtime_test.clj`.

## Что именно парсить в MVP

- `require("foo.bar")` как import/module dependency.
- `function foo(...)`, `local function foo(...)` как top-level functions.
- `function M.foo(...)` и `M.foo = function(...)` как module-qualified functions.
- `function M:foo(...)` как method; я бы нормализовал символ в `module#foo`, а вызовы `obj:foo()` писал в call tokens без `:`-формы, иначе текущие generic-хелперы хуже их матчят.
- Фоллбек, как и у других языков, оставлять через `fallback-unit`, чтобы индекс не падал на экзотическом Lua-коде.

## Почему не tree-sitter сразу

- Сейчас tree-sitter реально интегрирован только глубже для `clojure/java/typescript`; это видно по grammar-path/env support в `adapters.clj` и тестам в `runtime_test.clj`.
- Для Lua regex-MVP даст быстрый выигрыш: discovery, repo_map, resolve_context по path/module/symbol и базовые caller edges.
- Tree-sitter как этап 2 можно добавить потом: `:lua_engine`, `SCI_TREE_SITTER_LUA_GRAMMAR_PATH`, `:tree_sitter_grammars {:lua ...}` и parity-тесты.

Отдельная деталь: manifest hints сейчас работают по фиксированным путям, не по glob, так что `*.rockspec` автоматически не подхватится без небольшой доработки `language_activation.clj`. Для первого шага это не блокер, потому что extension-based detection уже достаточно.

Если цель быстро получить рабочую поддержку, я бы делал именно `regex MVP + тесты`.
