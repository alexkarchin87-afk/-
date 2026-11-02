# Зашифрованные секреты и атомарные настройки

Владелец: `security`.

## Основные интерфейсы

SecretsStore, SettingsLock, SettingsCommitter.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#security). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Один authoritative preferences file; process lock вокруг изменений.

## Возможные ошибки

Keystore недоступен, commit false; rollback snapshot.

## Тесты

- [ SettingsCommitterTest.kt ](../../core/ai-engines/src/test/java/com/voiceagent/oneplus13/core/security/SettingsCommitterTest.kt)

## Правила расширения

Все записи настроек проводить через существующую транзакцию, без plaintext fallback.
