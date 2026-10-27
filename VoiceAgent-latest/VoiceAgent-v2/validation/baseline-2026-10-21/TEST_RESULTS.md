# VoiceAgent-v2 — результаты проверок

Дата: 21 октября 2026, Europe/Moscow. Проверялись изменённые исходники из `VoiceAgent-v2_P0-first-fix.zip`.

## Итог реально выполненных проверок

| Проверка | Результат |
|---|---|
| Kotlin/Java debug compilation `app` и `core:ai-engines` | Успешно, в составе Gradle-сборки |
| `:app:testDebugUnitTest` | **45 passed**, 0 failed, 0 errors, 0 skipped |
| `:core:ai-engines:testDebugUnitTest` | **7 passed**, 0 failed, 0 errors, 0 skipped |
| `:app:assembleDebug` | **Успешно**; APK 28 293 246 байт, без Vosk-модели |
| `:app:lintDebug` | **Успешно**, 0 errors, 7 warnings, 2 informational findings |
| Backend `python -m unittest discover -s tests -v` | **14 passed**, 0 failed, 0 skipped |

Всего: **66 тестов прошли**. Это 52 JVM-теста и 14 backend-тестов, а не 66 Android instrumented-тестов. APK на телефон не устанавливался. Нет оснований утверждать, что полный голосовой цикл уже работает на реальном устройстве.

Итоговая Gradle-команда завершилась exit code 0, `BUILD SUCCESSFUL in 1m`, 100 задач: 17 executed, 83 up-to-date. Последняя проверка app-тестов выполнена заново после исправления queued-confirmation. Результаты core-тестов переиспользованы Gradle из предыдущего успешного выполнения на неизменённых core-исходниках; XML сохранены. Backend-проверка отдельно завершилась exit code 0, 14 tests, OK.

## Как запускалось

В машине подготовлены Temurin JDK 17.0.16+8, Gradle 8.9, Android SDK Platform 35 r02 и Build Tools 34.0.0. Использованы исходные Kotlin 2.0.21, AGP 8.7.3 и Vosk 0.3.45.

Gradle запускался из корня проекта через локальный вспомогательный `run_gradle.py`, который задаёт SDK/JDK/cache-каталоги, доступный HTTPS-прокси и системное доверенное хранилище сертификатов. Исполняемая команда:

```sh
bash /workspace/voiceagent-tools/gradle-8.9/bin/gradle \
  --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :core:ai-engines:testDebugUnitTest \
  :app:assembleDebug :app:lintDebug
```

Скачанные Gradle/JDK проверены SHA-256 из официальных источников; Android SDK — checksum из официального XML. TLS не отключался. Для воспроизведения на обычной машине с JDK 17/SDK 35 используйте включённый Wrapper той же версии:

```sh
bash ./gradlew :app:testDebugUnitTest :core:ai-engines:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Backend запускался с зависимостями из `backend/requirements.txt`, установленными в отдельное venv:

```sh
cd backend
/workspace/voiceagent-tools/backend-venv/bin/python -m unittest discover -s tests -v
```

Тесты задают синтетический worker-token/model ID/API-key, используют реальные ASGI routes FastAPI и `httpx.MockTransport` для upstream. Запросы в платные API не выполнялись. HTTP cancellation test использует локальный MockWebServer; остальные сетевые тесты перехватываются тестовым transport/interceptor.

## Наборы JVM-тестов

| Класс | Количество | Что проверяется |
|---|---:|---|
| AgentParsingTest | 7 | yes/no, Claude-prefix, grant-permission phrase, отдельная action envelope |
| JsonExtractionTest | 5 | Сохранённые проверки извлечения JSON для неисполняемых форматов |
| ActionSafetyTest | 8 | Примеры/Markdown/хвосты, внутреннее secret-action, schema/types, URL, consent source/request/TTL/single-use, queued yes до вопроса |
| ProjectReaderTest | 4 | Нормальное чтение, traversal, symlink, исключение секретов/служебных каталогов, размер |
| BackupCodecTest | 9 | Encrypt/decrypt, salt/nonce, неверный пароль, tamper, KDF bound, пароль, полная schema validation, legacy facts |
| WorkflowValidationTest | 8 | Линейный порядок, цикл, разрыв, битая ссылка, fork, дубли ID, пустой граф, изолированный цикл |
| SseEventParserTest | 4 | EOF без DONE, финальный DONE, ошибка upstream, предел ответа |
| AudioCaptureGateTest | 4 | Pause ждёт release, вложенные паузы, отмена/ошибка, смена lecture-owner и stale release |
| CancellableHttpTest | 3 | Реальная отмена Call, retry 503, отсутствие retry/утечки тела при 401 |

Существующий тест, принимавший action из поясняющего текста, изменён на безопасный контракт; отдельно добавлены отрицательные проверки прежнего поведения. Assert не отключались, тестовые ошибки не игнорировались.

## Backend-тесты

Проверены health, отклонение запуска без токена/со старым default, авторизация, отсутствие фиктивного успешного job, лимиты очереди/retention/body/params/stream-concurrency, обязательность model ID, нормальный SSE DONE, оборванный SSE EOF, повреждённый event и сокрытие upstream error body.

## Lint

Ошибок нет. Оставшиеся предупреждения:

- BatteryLife — запрос исключения из оптимизации батареи; необходима отдельная оценка перед Play Store-публикацией.
- ModifierParameter — порядок параметров одного Compose-компонента.
- ObsoleteSdkInt — четыре избыточные API/resource version-проверки.
- UnusedResources — один неиспользуемый ресурс.
- Два информационных замечания AutoboxingStateCreation.

Они не подавлялись baseline или `abortOnError=false`. Предшествующие ошибки MissingPermission и SuspiciousIndentation устранены; итоговый Lint запущен после последних изменений.

## Промежуточные препятствия и диагностика

1. Исходный Wrapper не мог разрешить services.gradle.org через прямое Java-соединение. Использован существующий системный прокси, скачан официальный Gradle с проверкой SHA-256.
2. Android/Gradle пытались писать в недоступный пользовательский каталог — служебные каталоги перенесены в `/workspace/voiceagent-tools`.
3. В исходном Gradle Kotlin DSL обнаружена ошибка обращения `java.util.UUID` — исправлена импортом UUID.
4. Установленная Java не содержала `jlink` — подготовлен полный JDK 17. Для него явно использовано доверенное системное хранилище CA; проверка TLS сохранена.
5. Первая комбинированная проверка не прошла Lint из-за POST_NOTIFICATIONS. Добавлены permission check и обработка SecurityException.
6. Последующая проверка Lint выявила форматирование изменяемого файла; после завершения правок выполнен финальный успешный прогон.
7. Kotlin daemon выводил сообщение об одной неудачной попытке старта, затем Gradle самостоятельно продолжил и завершил финальную сборку успешно. Это сообщение сохранено в логе и не засчитано как провал завершившейся компиляции.
8. Официальный хост Vosk-модели и DeepSeek docs возвращали HTTP 403 от прокси. Модель не скачана и не входит в APK/ZIP; актуальный авторизованный LLM contract не проверен.

## Что НЕ выполнялось

- Android instrumented tests, эмулятор, установка/запуск APK на телефоне, визуальная проверка Compose/overlay.
- Реальный микрофон, нативная Vosk-модель, акустическое самоэхо, TTS callback/lifecycle на устройстве, длительный memory profiling.
- Проверка FGS в фоне на Android 15+/OnePlus, root permissions, RemoteInput Telegram/WhatsApp, реальные notification races.
- Системный share sheet/FileProvider на другом приложении, Android Backup/restore, power-loss/process-kill/disk-full и атомарность импорта нескольких SharedPreferences.
- Авторизованные DeepSeek/Claude API-запросы, реальные Telegram/MAX/WhatsApp polling, OAuth Google-вход.
- Release-сборка/подпись релизным ключом, GitHub Actions workflow, публикация APK или backend deployment.

Эти пункты — **не проверены**, а не «пройдены» и не «пропущены тестовым runner». Подробные остаточные риски по VA-ID приведены в FIX_REPORT.md.

## Доказательства в архиве

`validation/gradle-validation.log`, `validation/backend-tests.log`, `validation/lint-results-debug.txt`, XML JUnit обоих модулей и `validation/source-sha256.txt`. Переименованный тестовый/исправленный проект не содержит сборочных cache, SDK/JDK, private signing key или реальных API-ключей.
