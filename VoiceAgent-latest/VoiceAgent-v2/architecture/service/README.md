# Жизненный цикл голосового сервиса

Владелец: `service`.

## Основные интерфейсы

VoiceAgentService.LocalBinder.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#service). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

SupervisorJob принадлежит сервису; onDestroy отменяет job, закрывает агент/STT/TTS и overlay.

## Возможные ошибки

Нет RECORD_AUDIO, foreground restrictions, ошибка загрузки Vosk.

## Тесты

Автономных JVM-тестов нет; нужна проверка Android lifecycle/device.

## Правила расширения

Новые ресурсы явно закрывать при onDestroy; не создавать независимые бесхозные scope.
