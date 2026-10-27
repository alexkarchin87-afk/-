# VoiceAgent-v2 — фактические проверки голосового маршрута

23 октября 2026, Europe/Moscow. Это результаты среды сборки, не OnePlus 13.

| Проверка | Результат |
|---|---|
| Kotlin debug compilation `app`/`core:ai-engines` | PASS в составе Gradle-сборки |
| `:app:testDebugUnitTest` | 80 passed, 0 failures/errors/skipped |
| `:core:ai-engines:testDebugUnitTest` | 22 passed, 0 failures/errors/skipped |
| `:app:assembleDebug` | PASS |
| `:app:lintDebug` | 0 errors, 9 warnings, 2 information |
| Backend unittest | 14 passed, OK, exit 0 |
| Android / OnePlus 13 / emulator | НЕ ВЫПОЛНЯЛОСЬ |

Всего 116 тестов: 102 JVM и 14 backend. Добавлены 24 теста относительно предыдущей RELIABILITY-версии. Ни один тест не объявлен Android instrumented-тестом.

## Фактические команды

Из корня проекта через подготовленный launcher:

```sh
python /workspace/voiceagent-tools/run_gradle.py :app:testDebugUnitTest :core:ai-engines:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Launcher запускает существующую Gradle 8.9 конфигурацию командой:

```sh
bash /workspace/voiceagent-tools/gradle-8.9/bin/gradle --no-daemon --max-workers=2 :app:testDebugUnitTest :core:ai-engines:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Заданы JDK/SDK/cache и системный HTTPS proxy/CA. Использованы прежние Temurin JDK 17.0.16+8, Platform 35 r02, Build Tools 34.0.0, Kotlin 2.0.21, AGP 8.7.3. На обычной машине с JDK 17/SDK 35 эквивалентно `./gradlew :app:testDebugUnitTest :core:ai-engines:testDebugUnitTest :app:assembleDebug :app:lintDebug`.

Backend (рабочий каталог `backend`):

```sh
/workspace/voiceagent-tools/backend-venv/bin/python -m unittest discover -s tests -v
```

Итоговый Gradle: exit 0, `BUILD SUCCESSFUL in 58s`, 100 задач (16 executed, 84 up-to-date). App-тесты выполнены после последней правки приоритета workflow. Core-тесты выполнены после изменений core; финальный запуск использовал UP-TO-DATE для неизменённых core-исходников. Backend выполнен заново: 14 тестов за 1.277 с.

## Что именно доказывают новые тесты

- `AgentOrchestratorRouteTest` (11): работает реальный orchestrator, mock только Android/storage/LLM/TTS. Без сети local Telegram не вызывает LLM; подтверждение обязательно и одноразово; чужой источник не подтверждает; обычный ответ с примером JSON не исполняется; валидная action-envelope ждёт подтверждения; ошибка запуска сообщается; сбой сети/timeout не блокирует следующую команду; поздний ответ после cancel/close не произносится и не исполняется; TTS-ошибка не оставляет подтверждения; workflow сохраняет приоритет.
- `SystemControllerLaunchTest` (2): реальный facade, mock Android Context/PackageManager/Intent; один `startActivity`, обязательный NEW_TASK, явный DENIED при SecurityException. Не проверяет появление окна на телефоне.
- `AppLabelResolverTest` (2): точное совпадение, отказ неоднозначному частичному, несколько activity одного пакета, пустой/неизвестный запрос.
- `LocalAppCommandTest` (2): точные поддержанные фразы, отказ примерам/отрицанию/составной инструкции.
- `WakeCommandTest` (3): весь хвост непрерывной фразы сохранён, имя без команды отличимо от чужой речи, регистр/пробелы. Это parser-тесты, а не микрофонные испытания.
- `VoiceRuntimeTest` (3): вложенные фазы, длинная речь, отмена владельца и исключение возвращают корректное состояние.
- `CancellableHttpTest` (+1): реальный локальный MockWebServer не отвечает; общий budget истекает, настоящий OkHttp Call прекращается. Старые проверки cancel/retry/401 сохранены.

Mockito 5.14.2 и coroutines-test 1.9.0 добавлены только в testImplementation app. Runtime зависимости не заменялись. Существующие AudioCaptureGate, import/lecture, schema/confirmation, workflow, HTTP/SSE и backend тесты также прошли.

## Все JVM-наборы

| Класс | Passed |
|---|---:|
| ActionSafetyTest | 9 |
| AgentOrchestratorRouteTest | 11 |
| AgentParsingTest | 7 |
| LocalAppCommandTest | 2 |
| BackupCodecTest | 9 |
| JsonExtractionTest | 5 |
| SettingsImportTest | 5 |
| ProjectReaderTest | 4 |
| LectureRepositoryTest | 7 |
| LectureSummaryRecoveryTest | 5 |
| AppLabelResolverTest | 2 |
| SseEventParserTest | 4 |
| SystemControllerLaunchTest | 2 |
| WorkflowValidationTest | 8 |
| AudioCaptureGateTest | 4 |
| CancellableHttpTest | 4 |
| VoiceRuntimeTest | 3 |
| WakeCommandTest | 3 |
| SettingsCommitterTest | 8 |

## APK и логи

- Исходный output: `app/build/outputs/apk/debug/app-debug.apk`.
- Выдаваемый файл: `VoiceAgent-v2-OnePlus13-debug.apk`, 28292230 байт.
- SHA-256: `495ced1b5046e5195f754a448ffc1f6a3576f6ddb28e0e75ea7a84503db23820`.
- APK не содержит русскую Vosk-модель и не устанавливался на Android. Секретные runtime ключи не добавлялись; signing keystore не выдаётся.
- В ZIP: `APK/VoiceAgent-v2-OnePlus13-debug.apk`. В проекте `validation/voice-route/` содержит JUnit XML, Lint TXT/XML, Gradle/backend логи и build evidence.

## Промежуточные результаты и ограничения

Первый запуск выявил ошибку тестовой синхронизации: существующий HTTP retry test использовал виртуальное время runTest одновременно с настоящими потоками OkHttp. Добавленный общий deadline перематывался раньше реального callback. Два таких HTTP-теста переведены на runBlocking, assertions сохранены; последующие прогоны успешны. После этого отдельно проверены исправление timeout-vs-cancel и сохранение приоритета workflow; логи каждого прогона приложены. Ошибки тестов не игнорировались.

Lint: прежние 7 предупреждений (BatteryLife, ModifierParameter, четыре ObsoleteSdkInt, UnusedResources) и два UseTomlInstead о test dependencies, два info AutoboxingStateCreation. Никаких suppress/baseline для скрытия новых результатов не добавлено. Есть прежнее предупреждение об отсутствии Platform-Tools; для compile/unit/assemble/lint оно не блокирующее. ADB в среде не найден, устройство и эмулятор не использовались.

Повторная загрузка официальной Vosk-модели: curl exit 22, HTTP 403 в ошибке прокси, модель не получена. Выполнялся только публичный GET модели; исходники/ключи наружу не отправлялись. Реальные cloud LLM API, OAuth, Telegram API, root, Accessibility gestures, runtime permissions, Android lifecycle/TTS/AudioRecord и фактическое открытие Telegram не проверялись. Для их проверки используйте `ONEPLUS13_TEST.md`. BUILD SUCCESSFUL не доказывает голосовую работоспособность на устройстве.
