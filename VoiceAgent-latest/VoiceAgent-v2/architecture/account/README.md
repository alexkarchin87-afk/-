# Локальный Google account gate

Владелец: `account`.

## Основные интерфейсы

AccountManager, AccountGateScreen.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#account). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Activity sign-in; локальная привязка пользователя; это не блокировка фонового сервиса.

## Возможные ошибки

OAuth package/SHA-1 не настроены, отмена входа.

## Тесты

Автономных JVM-тестов нет; нужна проверка Android lifecycle/device.

## Правила расширения

Сохранять подпись APK для обновления и корректной Google OAuth настройки.
