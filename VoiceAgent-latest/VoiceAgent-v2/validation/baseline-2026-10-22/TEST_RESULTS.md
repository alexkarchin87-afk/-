# VoiceAgent-v2 — результаты проверок надёжности

Дата: 22 октября 2026 (UTC). Проверены реальные изменения исходников в `/workspace/voiceagent-fix/VoiceAgent-v2`. Старые результаты от 21 октября сохранены отдельно в `validation/baseline-2026-10-21/`; ниже приведены текущие результаты.

## Итог

| Проверка | Фактический результат |
|---|---|
| `:app:testDebugUnitTest` | **63 passed**, 0 failed/errors/skipped |
| `:core:ai-engines:testDebugUnitTest` | **15 passed**, 0 failed/errors/skipped |
| Kotlin/Java debug compilation | Успешно в составе сборки |
| `:app:assembleDebug` | **Успешно**, APK 28,292,850 байт, без Vosk-модели |
| `:app:lintDebug` | **0 errors**, 7 warnings, 2 information |
| Backend unittest | **14 passed**, OK |

Всего **92 успешных теста: 78 JVM + 14 backend**. Добавлены 26 тестов по сравнению с прошлым этапом (66). Это не Android instrumented-тесты.

Финальный Gradle-процесс завершился кодом 0: `BUILD SUCCESSFUL in 1m 2s`, 100 задач, 17 executed / 83 up-to-date. App-тесты пересобраны и выполнены после последнего изменения Kotlin-кода. Core-тесты выполнены во втором прогоне после изменения миграции; финальный прогон использовал эти результаты UP-TO-DATE при неизменных core-исходниках. Первый и второй прогоны также завершились успешно; их логи приложены. Backend заново выполнен отдельно: 14 тестов за 0.922 с.

## Новые регрессионные сценарии

- `SettingsCommitterTest` (8): единый commit всех категорий, false после изменения in-memory cache, исключение записи, отказ rollback, пустой набор, атомарный маркер миграции, повтор после неудачи, запрет повторной миграции/понижения неизвестной схемы. Используется подставной backend чтения/commit; это проверка алгоритма, не Android EncryptedSharedPreferences/Keystore.
- `SettingsImportTest` (5): единый write set из секретов/памяти/UI, сохранение отсутствующих полей, отсутствие понижения P1 при дубле, удаление только временных P3 при превышении лимита, legacy/Unicode.
- `LectureRepositoryTest` (7): запись/чтение реальных файлов и Unicode после создания нового экземпляра; незавершённый временный файл и исключение writer; отказ TXT-зеркала при успешном checkpoint; порядок по времени записи; старые TXT/AtomicFile `.bak`; явная ошибка повреждённого JSON без удаления; traversal/symlink. Сбой записи инъецируется, настоящий диск не заполнялся, Android-процесс не уничтожался.
- `LectureSummaryRecoveryTest` (5): сетевое исключение и повтор новым экземпляром, очередь одинаковых запросов с одним успешным вызовом, отмена и повтор, отсутствие вызова для пустого текста и отказ пустого ответа, сохранение двух разных лекций из очереди. LLM заменена тестовой suspend-функцией; платных запросов нет.
- Дополнительный `ActionSafetyTest` (1): разрешённый retry без параметров, отказ подставленным path/session ID.

## Все JVM-наборы

| Класс | Успешно |
|---|---:|
| ActionSafetyTest | 9 |
| AgentParsingTest | 7 |
| BackupCodecTest | 9 |
| JsonExtractionTest | 5 |
| SettingsImportTest | 5 |
| ProjectReaderTest | 4 |
| LectureRepositoryTest | 7 |
| LectureSummaryRecoveryTest | 5 |
| SseEventParserTest | 4 |
| WorkflowValidationTest | 8 |
| AudioCaptureGateTest | 4 |
| CancellableHttpTest | 3 |
| SettingsCommitterTest | 8 |

## Команды и среда

Использованы подготовленные ранее Temurin JDK 17.0.16+8, Gradle 8.9, Android SDK Platform 35 r02, Build Tools 34.0.0; AGP 8.7.3, Kotlin 2.0.21, Vosk 0.3.45 проекта не менялись. Локальный launcher задаёт SDK/JDK, рабочие cache-каталоги и системный HTTPS proxy/CA; TLS не отключался.

```sh
python /workspace/voiceagent-tools/run_gradle.py   :app:testDebugUnitTest :core:ai-engines:testDebugUnitTest   :app:assembleDebug :app:lintDebug
cd /workspace/voiceagent-fix/VoiceAgent-v2/backend
/workspace/voiceagent-tools/backend-venv/bin/python -m unittest discover -s tests -v
```

На обычной машине с JDK 17/SDK 35 эквивалентная команда из корня проекта:

```sh
./gradlew :app:testDebugUnitTest :core:ai-engines:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Backend-тесты проверяют FastAPI routes с синтетическими токенами и mock transport. JVM HTTP cancellation использует локальный MockWebServer. Внешние платные API не вызывались.

В логах есть предупреждение об отсутствующем Android Platform-Tools/его лицензии; Gradle unit tests/assemble/lint это не заблокировало. ADB/emulator не использовались. Семь Lint warnings остались прежними: BatteryLife, ModifierParameter, четыре ObsoleteSdkInt и UnusedResources; два info — AutoboxingStateCreation. Проверки не подавлялись, ошибки/тесты не игнорировались.

## Не выполнено и границы доказательств

- Установка и запуск APK, Android instrumented/UI tests, настоящий Keystore/EncryptedSharedPreferences, Android Backup/перенос между устройствами.
- Физический disk-full, power loss и убийство Android-процесса между write/fsync/rename либо при commit/rollback импорта. JVM тест нового экземпляра и инъекции исключений не доказывают эти сценарии на Android.
- Настоящие Vosk/микрофон/TTS, lifecycle/overlay/OnePlus restrictions, визуальное обновление полей после импорта, экран ошибки открытия настроек.
- Реальные DeepSeek/Claude контракты и тарифицируемые запросы, OAuth/мессенджеры, release signing, CI/deployment.

Русская Vosk-модель отсутствует с прошлого этапа (официальный download-host был заблокирован прокси). Для голосовых испытаний её нужно загрузить по README и пересобрать. Требуются собственные ключи и поддерживаемые model IDs. Повтор резюме сохраняет предыдущий текст, но не обещает восстановить ещё не завершённый STT-фрагмент или данные после удаления приложения. Потерянный Keystore-ключ кодом не восстанавливается. TXT и несекретные Android Backup-зеркала не включены в одну транзакцию с основными файлами.

## Доказательства

В `validation/`: финальный и два промежуточных Gradle-лога, backend-лог, Lint TXT/XML, JUnit XML обоих модулей, сведения о собранном APK, список изменённых файлов, SHA-256 исходников. ZIP содержит полный исходный проект и оба текущих отчёта также в корне архива. Build outputs, SDK/JDK, caches, local.properties, реальные ключи и signing keystore в ZIP не включены; APK за проверенное приложение на телефоне не выдаётся.
