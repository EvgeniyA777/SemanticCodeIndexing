---
title: "Repository Documentation Reorganization Summary"
doc_type: "implementation_note"
lifecycle: "completed"
agent_action: "historical_reference_only"
updated: "2026-08-02"
file_type: working-note
topic: repo-documentation-reorg-summary
created_at: 2026-04-03T00:00:00-0700
author: codex
language: ru
status: completed
---

# Working Note: Repo Documentation Reorg Summary

Ниже сохранен summary-текст по проведенной перестройке структуры документации.

## Summary

Корень теперь очищен до нормальных repo-entrypoint файлов.

Я убрал `.DS_Store`, вынес root-идеи в `ideas`, а пустые языковые файлы превратил в onboarding stubs в `docs/language-onboarding`.

Второй слой тоже выровнял:

- реальные plan-документы из `docs/` уехали в `plans/architecture`
- архивный implementation plan из `notes/` уехал в `plans/archive`
- добавил `plans/README.md`, чтобы было понятно, чем `plans/` отличается от `notes/` и `docs/`
- обновил ссылки в `README.md`, `docs/roadmap-status.md`, `MEMORY.md` и связанных заметках

Новая логика такая:

- `docs`: долговременная reference/API/onboarding документация
- `plans`: execution-oriented planning
- `notes`: dated working notes, reviews, logs
- `ideas`: concept-stage and source-idea material
- `adr`: decisions of record

Не трогал исторические текстовые упоминания старого пути `Semantic Code Indexing.md` внутри одной старой заметки, где это часть исторического описания.

Тесты не запускал, потому что менялась только файловая структура и markdown.
