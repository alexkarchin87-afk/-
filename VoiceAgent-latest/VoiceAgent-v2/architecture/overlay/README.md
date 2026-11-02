# Системное плавающее окно

Владелец: `overlay`.

## Основные интерфейсы

OverlayBubbleController, OverlayPosition, OverlayLifecycleOwner.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#overlay). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Окно принадлежит VoiceAgentService; UI scope Main; hide снимает окно/подписки.

## Возможные ошибки

Нет overlay permission, IME, недоступный WindowManager.

## Тесты

- [ OverlayPositionTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/overlay/OverlayPositionTest.kt)

## Правила расширения

Сохранять lifecycle owners, геометрию и единственное владение окном.
