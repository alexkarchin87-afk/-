# Маршрутизация и подтверждение действий

Владелец: `agent`.

## Основные интерфейсы

AgentOrchestrator.handleTranscript/close, ActionPolicy, ConfirmationSlot.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#agent). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Один оркестратор на сервис; Mutex сериализует ввод и внешние события; close инвалидирует поздние ответы.

## Возможные ошибки

Отмена, тайм-аут LLM, неверный JSON, истёкшее или чужое подтверждение.

## Тесты

- [ ActionSafetyTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/agent/ActionSafetyTest.kt)
- [ AgentOrchestratorRouteTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/agent/AgentOrchestratorRouteTest.kt)
- [ AgentParsingTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/agent/AgentParsingTest.kt)
- [ LocalAppCommandTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/agent/LocalAppCommandTest.kt)

## Правила расширения

Новые действия сначала добавить в ActionPolicy и тесты подтверждения; реализацию помещать во владельца feature.
