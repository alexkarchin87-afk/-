# Точки входа и настройки

Владелец: `entry`.

## Основные интерфейсы

MainActivity, VoiceAgentApp, SettingsActivity.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#entry). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Android создаёт Application/Activity; Activity привязывается к VoiceAgentService и освобождает binding.

## Возможные ошибки

Отказ разрешений, Android background limits, недоступный Keystore.

## Тесты

Автономных JVM-тестов нет; нужна проверка Android lifecycle/device.

## Правила расширения

Не переносить бизнес-логику в Activity; команды отправлять через binder.
