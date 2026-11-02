# Конспекты: сборка, дополнение и хранение

Владелец: `notes`.

## Основные интерфейсы

NotesActions.build/extend, NotesBuilder, NotesStorage.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#notes). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

NotesActions живёт вместе с агентом; lastTopic локален; после LLM проверяется актуальность перед сохранением.

## Возможные ошибки

Пустые источники, тема не найдена, поздний ответ, IO.

## Тесты

- [ AgentOrchestratorRouteTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/agent/AgentOrchestratorRouteTest.kt)

- [NotesActionsTest.kt](../../app/src/test/java/com/voiceagent/oneplus13/notes/NotesActionsTest.kt)

## Правила расширения

Не зависеть от agent/UI/system; новые сценарии дополнять через feature и cancellation guard.
