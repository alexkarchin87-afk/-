# Память, резервные копии и диагностика сбоев

Владелец: `persistence`.

## Основные интерфейсы

UserMemory, BackupManager, BackupCodec, FeatureRequests, CrashLogger.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#persistence). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Данные живут в файлах и encrypted preferences; импорт валидируется до commit.

## Возможные ошибки

Повреждённый импорт, неудачный commit, IO и Keystore.

## Тесты

- [ BackupCodecTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/core/BackupCodecTest.kt)
- [ SettingsImportTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/core/SettingsImportTest.kt)

## Правила расширения

Сохранять совместимость формата; не сбрасывать данные при ошибке ключа.
