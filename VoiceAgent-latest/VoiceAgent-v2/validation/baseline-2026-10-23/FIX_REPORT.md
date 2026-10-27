# VoiceAgent-v2 — основной голосовой маршрут и подготовка OnePlus 13

Дата: 23 октября 2026, Europe/Moscow. Основа — ранее выданный `VoiceAgent-v2-RELIABILITY.zip`, SHA-256 `2b1db02976c051449c46556f942ca996321613c3410f399e844232fb5fef66d3`. Изменения внесены в существующие исходники; прежние исправления импорта/лекций сохранены.

**Состояние:** исходники исправлены и доступны для сборки/проверок. Полная работоспособность на OnePlus 13 не доказана. Vosk-модель отсутствует: официальный download endpoint блокируется HTTP 403; без добавления модели и пересборки голосовой маршрут не стартует. Телефон, Android instrumented-тесты и реальные LLM API не использовались.

## Реальная структура и маршрут

`MainActivity` запрашивает RECORD_AUDIO/POST_NOTIFICATIONS → `VoiceAgentService` запускает microphone foreground service → `STTEngine` (Vosk) → `AgentOrchestrator.handleTranscript` → локальная команда/существующий workflow/LLM → одноразовое подтверждение → `SystemController` → Android `startActivity` → явный ответ о результате запроса. LLM-действия проходят существующие `tryParseAction`, `ActionPolicy`, `ConfirmationSlot`.

`AudioCaptureGate` уже существует и остаётся владельцем исключения конкуренции микрофона; новые `VoiceRuntime`/`WakeCommand` — небольшие средства отображения реальных фаз и выделения обращения, не замена архитектуры. Отдельного `SessionGate` нет. `VanyaAutomationEngine` есть, но мест создания/вызова в проекте не найдено; его альтернативный маршрут использует HTTP/SSE, не WebSocket. `AccessibilityAgent` обслуживает нажатия/жесты; для открытия Activity он не обязателен. Main-route WebSocket отсутствует.

## Подтверждённые проблемы: файл → метод → причина → последствия → исправление → проверка

| ID / приоритет / статус | Файл и метод | Причина и последствия | Изменение | Проверка и предел |
|---|---|---|---|---|
| VR-00 / P0 блокер теста / CONFIRMED, осталось | `STTEngine.ensureModel`, assets | В архиве нет модели; без неё Vosk не начнёт распознавание. Официальная загрузка заблокирована прокси. | Добавлено точное сообщение об отсутствии/сбое модели и безопасная метка диагностики. Модель не подменена сторонней. | Проверены assets и повторный curl: exit 22, HTTP 403 в ошибке. Голосовой тест блокирован до добавления модели. |
| VR-01 / P1 / CONFIRMED | `STTEngine.awaitWakeWord`; `VoiceAgentService.listenOnce` | Узкая грамматика обнаруживала только имя, а остаток фразы не возвращался. После освобождения первого recognizer и паузы создавался второй; продолжение непрерывной команды могло теряться. | Основной сервис получает полную финальную фразу через `awaitWakeCommand`, отделяет обращение и использует сохранённый хвост. При одном имени продолжает слушать команду. Старый интерфейс `awaitWakeWord` сохранён. | 3 JVM-теста выделения команды, компиляция. Акустика и реальный Vosk не проверены; не заявляется, что все реальные фразы уже распознаются. |
| VR-02 / P1 / CONFIRMED | `AgentOrchestrator.handleTranscript`; `TTSEngine.speak`; `VoiceAgentService.startListeningLoop` | TimeoutCancellationException является CancellationException. Собственный тайм-аут TTS/LLM мог выйти из обработчика как отмена и завершить цикл слушания. | Собственный timeout отдельно очищает pending-состояние и возвращает ошибку вызывающему циклу. Отмена родительского job/закрытие по-прежнему прекращают обработку. | Реальный orchestrator с подставным timeout: caller остаётся активным, следующая команда выполняется. Настоящие Android TTS callbacks не тестировались. |
| VR-03 / P1 / CONFIRMED | `AgentOrchestrator.dispatch(open_app/tap_text)`; `SystemController.openApp/resolveByLabel` | Boolean результата игнорировался; отсутствующее приложение/отказ оставались без ответа. Частичный поиск выбирал первый пакет даже при неоднозначности. | Сохранён Boolean API; добавлен подробный результат REQUESTED/NOT_FOUND/DENIED, обработка SecurityException/ActivityNotFoundException, однозначный выбор label. Orchestrator сообщает исход; tap тоже сообщает false. | JVM реального SystemController с mock Android: FLAG_ACTIVITY_NEW_TASK, один вызов, отказ разрешения. Pure resolver: неоднозначность/дубли. Orchestrator: отрицательный исход. Реальное появление Activity не проверено. |
| VR-04 / P1 / CONFIRMED | `AgentOrchestrator.handleTranscriptLocked` | Обычная команда открытия Telegram без workflow зависела от сети, ключа и model ID. При отключённой сети до Android-действия не доходила. | Узкая локальная обработка точного «открой [приложение] Telegram/телеграм/телеграмм», только из пользовательского transcript. То же подтверждение, тот же Android API. Пользовательские workflow сохраняют прежний приоритет; остальной LLM-маршрут сохранён. | Тесты реального orchestrator: LLM не вызывается, без «да» действия нет, лишнее «да» не повторяет запуск, другой источник не подтверждает. Примеры/отрицание/добавочные команды не принимаются local parser. |
| VR-05 / P2 / CONFIRMED | `AgentOrchestrator.close/handleTranscript/proposeAction/dispatch` | `close()` закрывал лекцию, но сам объект продолжал принимать запросы. После некооперативного позднего ответа не было явной проверки закрытия перед history/предложением действия. Основной сервис уже отменял scope, но одного close было недостаточно. | Флаг closed и проверка активности после LLM, перед предложением/фиксацией подтверждения и dispatch; отмена освобождает pending state. | Два теста с настоящим orchestrator и вручную возобновлённым suspend transport после cancel/close: ни речи, ни выполнения. Это не симуляция полного Android process death. |
| VR-06 / P2 / CONFIRMED | `ChatComponents.rememberAgentPhase`; STT/TTS/orchestrator | UI сбрасывал SPEAKING через 6 с и THINKING через 90 с независимо от продолжающейся работы. | Фазы основаны на времени жизни actual coroutine операций. Вложенные владельцы не очищают состояние друг друга; отмена/ошибка освобождают фазу. | 3 JVM-теста фаз, включая речь дольше прежнего таймера и отмену одного владельца. Compose на экране не проверялся. |
| VR-07 / P2 / CONFIRMED | `LLMClient.http`; `CancellableHttp.executeLlmRequest` | Read timeout не ограничивал полную длительность вызова с медленно поступающим телом и повторами. | OkHttp callTimeout 200 с и coroutine budget 210 с на executeLlmRequest со всеми его retries. Реальный Call отменяется. | MockWebServer без ответа: budget истекает, running call завершается. Прежние cancel/503-retry/401 tests сохранены. Это предел одной HTTP-операции, не всей сложной цепочки нескольких LLM-вызовов. |

Все пути в таблице относительны стандартным Kotlin-каталогам `app/src/main/java/com/voiceagent/oneplus13/` либо `core/ai-engines/src/main/java/com/voiceagent/oneplus13/core/`. Полные пути реально изменённых файлов перечислены в `CHANGED_FILES.txt`; применимый unified diff относительно RELIABILITY — `VOICE_ROUTE.patch`.

## Проверенное сохранённое поведение

- Строгая отдельная JSON action-envelope; пример JSON внутри обычного ответа не исполняется. Валидная envelope сама по себе только предлагает действие.
- Подтверждение связано с источником/сроком/запросом и расходуется один раз. Эти прежние тесты продолжают проходить.
- Микрофон освобождается через callbackFlow cleanup до обработки команды; gate блокирует захват на время обработки/TTS. Vosk join/shutdown/Recognizer.close не заменялись.
- Service stop отменяет общий job и закрывает orchestrator/STT/TTS. Отдельных Interrupt/Reset API/кнопок не было и не добавлено; остановка и повторный запуск сохраняются.
- Сохранены импорты, лекции, генерация кода, workflows, мессенджеры, экспорт. Добавлены только testImplementation Mockito/coroutines-test, runtime-технологии не заменялись. Ни пользовательские исходники, ни секреты внешним API не передавались.

## LIKELY и UNKNOWN — без утверждений об исправлении

- **LIKELY:** Android/OxygenOS ограничит запуск Activity из фона/на заблокированном экране. Успешный возврат startActivity не доказывает открытое окно; поэтому ответ говорит о переданном запросе. Тестировать сначала с открытым экраном Вани.
- **UNKNOWN:** фактические AudioRecord/Vosk/TTS ресурсы, драйвер микрофона, акустическое эхо, русский TTS, OnePlus battery policy, несколько последовательных service start/stop на телефоне.
- **UNKNOWN:** качество и расход батареи полнословарного ожидания обращения. Это осознанный минимальный способ сохранить непрерывную фразу; измерения на устройстве нужны.
- **UNKNOWN:** живые DeepSeek/Claude model IDs и ответы авторизованного аккаунта. Никаких оплачиваемых вызовов не было.
- **CONFIRMED ограничение:** голосовое прерывание поверх TTS не реализовано при half-duplex. Проверяется кнопкой остановки; полноценный barge-in потребовал бы отдельной работы с эхоподавлением.
- **CONFIRMED вне основного маршрута:** VanyaAutomationEngine не подключён к сервису, SessionGate/WebSocket не найдены. Альтернативный SSE-маршрут не выдаётся за испытанный основной pipeline. Его stop/start, screenshot/TTS на телефоне не проверены.
- Нативные микрофонные разрешения проверяются в MainActivity/сервисе; foreground microphone type и manifest queries уже были. UI automation требует включённого AccessibilityService; расширенная проверка фактического успеха жестов, root-действий и иных интеграций в этот небольшой этап не входит.

## Материалы

`TEST_RESULTS.md` — фактические команды, результаты и пределы; `ONEPLUS13_TEST.md` — пошаговый сценарий; `CHANGED_FILES.txt` — полный список; `VOICE_ROUTE.patch` — изменения; `validation/voice-route/` — логи/JUnit/Lint/сведения APK. Предыдущие отчёты сохранены как исторические, а не повторная проверка всех VA-01–VA-22.

## Реально изменённые исходники и тесты

```text
app/build.gradle.kts
app/src/main/java/com/voiceagent/oneplus13/agent/AgentOrchestrator.kt
app/src/main/java/com/voiceagent/oneplus13/agent/LocalAppCommand.kt
app/src/main/java/com/voiceagent/oneplus13/core/VoiceAgentService.kt
app/src/main/java/com/voiceagent/oneplus13/system/AppLabelResolver.kt
app/src/main/java/com/voiceagent/oneplus13/system/SystemController.kt
app/src/main/java/com/voiceagent/oneplus13/ui/ChatComponents.kt
app/src/test/java/com/voiceagent/oneplus13/agent/AgentOrchestratorRouteTest.kt
app/src/test/java/com/voiceagent/oneplus13/agent/LocalAppCommandTest.kt
app/src/test/java/com/voiceagent/oneplus13/system/AppLabelResolverTest.kt
app/src/test/java/com/voiceagent/oneplus13/system/SystemControllerLaunchTest.kt
core/ai-engines/src/main/java/com/voiceagent/oneplus13/core/CancellableHttp.kt
core/ai-engines/src/main/java/com/voiceagent/oneplus13/core/LLMClient.kt
core/ai-engines/src/main/java/com/voiceagent/oneplus13/core/STTEngine.kt
core/ai-engines/src/main/java/com/voiceagent/oneplus13/core/TTSEngine.kt
core/ai-engines/src/main/java/com/voiceagent/oneplus13/core/VoiceRuntime.kt
core/ai-engines/src/main/java/com/voiceagent/oneplus13/core/WakeCommand.kt
core/ai-engines/src/test/java/com/voiceagent/oneplus13/core/CancellableHttpTest.kt
core/ai-engines/src/test/java/com/voiceagent/oneplus13/core/VoiceRuntimeTest.kt
core/ai-engines/src/test/java/com/voiceagent/oneplus13/core/WakeCommandTest.kt
```

Дополнительно сформированы текущие отчёты, diff и проверочные артефакты; они не меняют runtime.
