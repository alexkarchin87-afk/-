# VoiceAgent-v2: архитектура и надёжность

Дата: 2 ноября 2026. База: `dd3afe2abb38ef669d1dfeaef72f58e06daee366`. Ветка: `feature/voiceagent-defense-hardening-2026-11-02`. Исторические FIX_REPORT/TEST_RESULTS сохранены; текущие результаты относятся к этому проходу.

## CONFIRMED — инвентаризация

- Реальных Gradle-модулей два: `:app` → `:core:ai-engines`. Обратной зависимости и цикла Gradle нет. Новые Gradle-модули не создавались: app содержит связанные Android-компоненты без готовых независимых контрактов; массовое выделение перед защитой увеличило бы риск.
- В индексе 96 production Kotlin-файлов, 17 package-имён и 20 явно назначенных владельцев. Package `com.voiceagent.oneplus13.core` разделён между app и ai-engines; карта показывает реальный модуль и путь, а не выводит модуль из package.
- Полный индекс классов/top-level функций, файлов, зависимостей и тестов — [ARCHITECTURE_MAP.md](ARCHITECTURE_MAP.md). Назначение задаётся вручную в [реестре](architecture/components.json). READMEs каждого владельца содержат интерфейсы, lifecycle, ошибки, тесты и правила расширения.
- Manifest entry points: VoiceAgentApp; MainActivity, SettingsActivity, WorkflowEditorActivity; VoiceAgentService, AccessibilityAgent, NotificationReader, TelegramBotService, ScreenCaptureService; BootReceiver; AndroidX FileProvider. Все локальные реализации проверяются генератором.
- STT/TTS/LLM и encrypted settings живут в ai-engines. App владеет агентом, UI/overlay, лекциями, конспектами, workflow, генерацией проектов, уведомлениями/root/Android API, Telegram/MAX/WhatsApp, памятью и резервными копиями. Manifest, source declarations и тесты индексируются целиком.
- Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, compile/target SDK 35 сохранены. Основные зависимости: Compose BOM 2024.11.00, coroutines 1.9.0, Vosk 0.3.45, OkHttp 4.12.0, Moshi 1.15.1, Retrofit 2.11.0, Security Crypto 1.1.0-alpha06, Google Auth 21.2.0. Секреты не добавлялись.

## CONFIRMED — состояния и связи

`AgentState`, `SessionId`, `SessionToken`, `ConnectionGeneration` как отдельные типы в текущих исходниках отсутствуют. Существующие механизмы:

- `VoiceRuntime.Phase` — StateFlow реальных lifetime LISTENING/PROCESSING/SPEAKING, с отдельными owner-ключами для конкурентных операций; это не durable state машины задач. UI имеет собственное отображение AgentPhase.
- `AgentOrchestrator.inputLock` сериализует ввод/внешние события. `requestId` относится к свежести подтверждения, а не является идентификатором LLM-транспорта. `ensureRequestActive` проверяет отмену корутины и закрытие агента перед применением асинхронных результатов.
- `ConfirmationSlot` связывает согласие с payload, источником, TTL и моментом получения следующего ввода. Root grant доступен только через прямую фразу пользователя и согласие. ActionPolicy ограничивает действия из LLM; root shell остаётся фиксированным allowlist.
- LectureSession использует строковый UUID. Событие остановки содержит `session_id`; чужой ID не останавливает новую запись. LectureSummaryRecovery и ChatsDigest имеют Mutex; transcript и digest cursor изменяются после соответствующих проверок.
- STT закрывает recorder/recognizer в завершении Flow; AudioCaptureGate ждёт освобождения captureLock. TTS сериализует speak и останавливает речь при отмене. HTTP Call отменяется вместе с корутиной; response закрывается перед resume. Сервис отменяет свой SupervisorJob при уничтожении.

Это анализ кода и JVM-проверки, не доказательство отсутствия всех гонок или работы native ресурсов на Android. Main-thread lifecycle сервиса остаётся важным предположением.

## FIXED — исходный код

1. `MessengerBridge.stopPolling()` отменял всех детей внедрённого scope, включая чужую работу. Теперь каждый запуск получает собственный дочерний SupervisorJob. Stop отменяет только его; restart создаёт нового владельца. Старый ответ Telegram не публикуется и не сдвигает offset нового запуска; MAX также проверяет отмену до публикации. Offset по-прежнему подтверждается после всей порции, поэтому частично доставленная порция может повториться.
2. `WorkflowRunner` мог выполнить синхронное системное действие после отмены, если callback отменил корутину без suspension. Теперь ensureActive вызывается перед Started, после callback, после блока и перед Finished. Отмена не превращается в Failed и не продолжает план.
3. Сценарии конспектов выделены в `notes/NotesActions.kt`: build/extend, lastTopic и выбор сохранённой темы. AgentOrchestrator маршрутизирует к владельцу; callback актуальности не создаёт обратной зависимости notes → agent. Проверка после LLM и до сохранения/Reply/TTS сохранена. Публичный API агента не менялся.
4. `.gitignore` исключает build-каталоги вложенных Gradle-модулей. Генератор, явные границы, карта и CI добавлены в реальный проект. Это 20 архитектурных областей, не 20 новых Gradle-модулей.

AgentOrchestrator уменьшен с 851 до 807 строк. Генерация проектов, Android handlers и workflow ещё требуют постепенного выделения; полностью разделённым оркестратор не объявляется.

## TESTED — текущие результаты

- До исправления production-кода: 116 app-тестов, ровно два новых failure (`stoppingBridgePreservesUnrelatedParentScopeWork`, `cancellationAtStepBoundaryPreventsSystemAction`); остальные 114 app и 27 core прошли. Исторические 141 тест подтверждены текущим прогоном. Это regression baseline с добавленными тестами, не чистая копия без новых тестов.
- После первого исправления: целевой прогон MessengerBridgeTest + WorkflowCancellationTest успешен. После выделения notes: все 17 AgentOrchestratorRouteTest успешны.
- Финальный полный прогон: **151 JVM-тест: app 124, core 27; 0 failures/errors/skipped**. В том числе поздние ответы/ошибки закрытого агента, защита подтверждений, отмена и позднее дополнение конспекта, два поколения polling, сохранение offset и чужих задач scope.
- `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:core:ai-engines:testDebugUnitTest`, `:app:lintDebug`: BUILD SUCCESSFUL. Lint: 0 ошибок, 9 предупреждений, 5 information. Предупреждения не подавлялись.
- Генератор `--check` и **12 Python-тестов** прошли. Mutation-проверки: отсутствующие source/test/README, мёртвая Markdown-ссылка, двойной владелец, новый файл без владельца, forbidden import/FQN, обратная зависимость модулей, цикл Gradle, отсутствующая manifest class и устаревшая карта; комментарии/строки не создают фиктивную зависимость.

Среда: Linux, JDK 17.0.16, Gradle 8.9, SDK 35. Команды запускались установленным Gradle той же версии через локальный launcher, который выбирает JDK/SDK/cache и доверенный session proxy. Проверки безопасности и verifyVoskModel не отключались. Эквивалентные переносимые команды — [DEMO_GUIDE_RU.md](DEMO_GUIDE_RU.md). Точные логи, XML Lint и сводка suite — [validation/architecture-2026-11-02](validation/architecture-2026-11-02/).

## BLOCKED

`:app:assembleDebug` завершился BUILD FAILED на `:app:verifyVoskModel`: отсутствуют `uuid`, `am/final.mdl`, `conf/mfcc.conf`, `graph/FST`. Лог: [assemble.log](validation/architecture-2026-11-02/assemble.log). Реальная модель Vosk в assets отсутствует; фиктивные файлы и обход проверки не применялись. Новый APK не получен.

Физический OnePlus 13, native Vosk, микрофон/TTS, UI/IME, root, Google OAuth, Telegram/MAX/WhatsApp и сетевые LLM на устройстве не проверялись. Успешный JVM mock не выдаётся за реальную интеграцию. PowerShell-тесты установки модели в этом проходе не запускались.

## REMAINING — риски и следующий порядок

- **P0:** установить настоящую Vosk-модель, получить APK со стабильной подписью и выполнить device checklist. Без этого production-ready статус не подтверждён.
- **P1:** внутри app остаётся сильно связанная группа `agent, entry, messenger, service, system, workflow, workflow-ui`. Часть связей — composition/service entry points, часть — реальная связанность. Gradle-цикла нет. Реестр фиксирует разрешённые существующие направления и запрещает остальные; это не завершённая Clean Architecture.
- **P1:** подтверждение workflow в агенте связывается с именем, а план загружается при исполнении. Для защиты от изменения плана между вопросом и согласием нужно отдельное immutable подтверждённое snapshot/revision. В редакторе «Запустить сейчас» является прямым пользовательским запуском, а не голосовым двухшаговым подтверждением; все entry points требуют дальнейшего унифицированного review.
- **P1:** `SystemController.downloadApk` использует blocking URL.openStream без явных сетевых timeout/close-on-cancel. Отмена `withContext` препятствует последующему installer prompt, но немедленное освобождение заблокированного IO не доказано.
- **P2:** оставшиеся handlers агента (code, screen, root, workflow) выделять по одному с characterization tests. SettingsActivity (499 строк), MainScreen (636), WorkflowEditorActivity (478) остаются крупными. BackupManager зависит от UiPreferences: кандидат для явного settings contract без зависимости persistence → UI.
- **P2:** WorkflowEditorActivity создаёт TTSEngine без явного shutdown; нужна отдельная проверка lifecycle и освобождения ресурса. Session IDs для всех process-wide событий отсутствуют; долговечная очередь/восстановление всего агента после смерти процесса не реализованы.
- **P2:** NotificationBuffer ограничен 200 in-memory записями; потеря при смерти процесса/вытеснении не решена. NotesStorage использует writeText; атомарность при аварийном завершении записи не заявляется. Partial polling publication допускает повтор событий.
- **P2:** TelegramBotService не имеет запуска из текущего UI; `/start` responder отсутствует. MAX содержит конфигурируемую/неподтверждённую интеграцию, WhatsApp ограничен текущей реализацией. Альтернативный VanyaAutomationEngine не имеет production caller и не должен конкурировать за микрофон с VoiceAgentService. Ничего из этого не удалено и не представлено как новая рабочая функция.
- **P3:** расширить лексический архитектурный анализ до compiler tooling при появлении dynamic Gradle/DI; текущее ограничение описано в architecture/README.md. Новая CI-проверка анализирует архитектуру, а не выполняет Android tests без SDK.

## Коммиты и сохранность

Реальные implementation-коммиты GitHub:

| SHA | Изменение |
|---|---|
| be7fab53e4e82f859154ddeb1733fa40a9c8db4f | Два воспроизводящих regression-теста |
| 3c06c418f8172da45def8235fbb1ccc9bc38d7b7 | Владение polling и отмена workflow |
| c2a8ebe2895505a5d125f299649b8881576784d5 | Выделение NotesActions и шесть тестов |
| 5c4dc0ab8612739aa779579f5ff797c600f2ac0a | Реестр, генератор, карта, READMEs, CI |
| f7ef88127e0d1ee385aa44f72eab16d2e53c7a9b | Два теста поздних ответов polling |

Финальный коммит документации/доказательств виден в истории PR. Каждый этап — отдельный обратимый коммит; тестовый первый коммит намеренно красный и исправлен следующим. Итоговая ветка проверена целиком.

Полный список изменённых файлов — [CHANGED_FILES_ARCHITECTURE.txt](CHANGED_FILES_ARCHITECTURE.txt). Исходные файлы не удалялись. VanyaBridge/Windows-Portable не менялись. Старый handoff ZIP, SHA256SUMS и исторические отчёты относятся к прежней поставке; их содержимое не переписывалось. PR не сливается автоматически.
