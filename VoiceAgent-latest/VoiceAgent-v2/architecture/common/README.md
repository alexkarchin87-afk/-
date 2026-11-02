# Разбор JSON ответов

Владелец: `common`.

## Основные интерфейсы

extractJsonObject.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#common). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Чистая функция без ресурсов.

## Возможные ошибки

Неполный JSON и текст вокруг объекта.

## Тесты

- [ JsonExtractionTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/core/JsonExtractionTest.kt)

## Правила расширения

Отделять извлечение JSON от доверия к действию; валидировать ActionPolicy.
