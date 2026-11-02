# Альтернативный SSE-конвейер и захват экрана

Владелец: `automation`.

## Основные интерфейсы

VanyaAutomationEngine, SseEventParser, ScreenCaptureManager, ScreenCaptureService.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#automation). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Отдельный loopJob; screen projection требует системного согласия; не запускать одновременно с голосовым сервисом.

## Возможные ошибки

SSE оборван, backend недоступен, projection отозван, занят микрофон.

## Тесты

- [ SseEventParserTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/system/SseEventParserTest.kt)

## Правила расширения

Не объединять с основным маршрутом без тестов; закрывать HTTP/image/projection ресурсы.
