# Модель, построение и исполнение сценариев

Владелец: `workflow`.

## Основные интерфейсы

Workflow.executionOrder, WorkflowRunner, WorkflowStorage, WorkflowAiBuilder.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#workflow). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Линейный план валидируется до выполнения; исполнение в корутине вызывающего; отмена проверяется перед действием.

## Возможные ошибки

Циклы/разрывы/ветви, неуспешный шаг, отмена, LLM/IO.

## Тесты

- [ WorkflowCancellationTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/workflow/WorkflowCancellationTest.kt)
- [ WorkflowValidationTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/workflow/WorkflowValidationTest.kt)

## Правила расширения

Новые блоки требуют проверки параметров, отмены и правил подтверждения во всех entry points.
