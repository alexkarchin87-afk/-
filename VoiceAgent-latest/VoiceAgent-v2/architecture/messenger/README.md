# Telegram, MAX, WhatsApp и захват токенов

Владелец: `messenger`.

## Основные интерфейсы

MessengerBridge.startPolling/stopPolling/send, TelegramClient, TokenCapture, TelegramBotService.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#messenger). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Сервис владеет bridge; каждый запуск polling имеет дочерний job; offset продвигается после публикации всей порции.

## Возможные ошибки

401/403/409/429, transport, отмена; частичная публикация допускает повтор. MAX endpoint требует настройки; WhatsApp — ограниченная интеграция.

## Тесты

- [ MessengerBridgeTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/integrations/messenger/MessengerBridgeTest.kt)

## Правила расширения

Не включать автозапуск и /start ответы без отдельной функции; токен применять только после согласия.
