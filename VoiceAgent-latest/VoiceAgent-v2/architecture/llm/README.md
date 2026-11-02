# LLM и отменяемый HTTP

Владелец: `llm`.

## Основные интерфейсы

LLMClient.complete/askClaudeDirect, Call.awaitPayload.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#llm). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

HTTP Call отменяется с корутиной; response закрывается до resume.

## Возможные ошибки

401/429, transport, тайм-аут, некорректный ответ модели.

## Тесты

- [ CancellableHttpTest.kt ](../../core/ai-engines/src/test/java/com/voiceagent/oneplus13/core/CancellableHttpTest.kt)

## Правила расширения

Не публиковать секреты в логах; отмена должна распространяться наружу.
