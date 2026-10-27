# Fix pass (модуль :core:ai-engines + исправления)

## Модуль
- `:core:ai-engines` (LLMClient, STTEngine, TTSEngine, SecretsStore), подключён через `implementation(project(":core:ai-engines"))`.

## Исправленные баги
- LLMClient: несуществующий id модели `claude-sonnet-5` -> `claude-sonnet-5-5`; readTimeout 60s -> 180s (R1/Claude отвечают дольше минуты); `generateAdapter=false` (KSP в проекте нет, работает рефлексия).
- TTSEngine: язык ru-RU ставился до инициализации движка (игнорировался); первая фраза терялась; зависание при ошибке speak(); нет отмены; лимит длины текста.
- STTEngine: двойной shutdown() Vosk (stop() + отмена Flow) -> возможный SIGSEGV; ensureModel кэширует модель.
- VoiceAgentService: нет RECORD_AUDIO -> краш startForeground на Android 14+; цикл STT без обработки ошибок валил приложение; оверлей работал не в главном потоке (пузырь не показывался/не скрывался).
- MainActivity: нигде не запрашивались RECORD_AUDIO / POST_NOTIFICATIONS.
- BootReceiver: запуск микрофонного FGS из BOOT_COMPLETED запрещён на Android 14+ (краш).
- MessengerBridge: сетевые сбои в polling валили приложение / горячий цикл.
- LectureRecorder: необработанное исключение STT; флаг `listening` залипал.
- Manifest: убран `uses-permission BIND_ACCESSIBILITY_SERVICE` (защищённое разрешение, lint-ошибка).
- CI: Gradle 8.7 -> 8.9 (требование AGP 8.7), SDK platform 35.

## Известные ограничения (не исправлялись)
- Нет папки `app/src/main/assets/model-ru-small` (модель Vosk) — см. README.
- LectureRecorder и VoiceAgentService используют разные экземпляры STTEngine (два микрофонных потока одновременно).
- Проект не собирался в среде разработки (нет Android SDK) — первая сборка покажет остаточные ошибки.

## Wake word + streaming
- STTEngine.awaitWakeWord(): офлайн-ожидание "ваня" (Recognizer с грамматикой), затем полнотекстовый listen(); VoiceAgentService.listenOnce() — двухфазный цикл.
- backend/server.py: POST /chat/stream (SSE, DeepSeek stream=true); модель через DEEPSEEK_MODEL; httpx добавлен в requirements.

## Screen capture
- system/ScreenCaptureManager.kt (+ ScreenCaptureService, mediaProjection FGS): requestPermission(), captureCurrentScreen(): Bitmap? (фоновый поток), stop(). Манифест: FOREGROUND_SERVICE_MEDIA_PROJECTION + service.
- AgentOrchestrator: добавлен import system.RootActions (класс уже существовал)
- AgentOrchestrator: handleNowPlaying -> suspend (вызывал tts.speak из обычной функции); catch в handleCreateWorkflow логирует ошибку и пробрасывает CancellationException.
- WorkflowRunner.run: onStep теперь suspend (внутри колбэка вызывался suspend AgentEventBus.publish).

## VanyaAutomationEngine
- system/VanyaAutomationEngine.kt: wake word -> один снимок экрана -> команда -> SSE-стрим /chat/stream -> озвучка по фразам.
- backend: /chat/stream принимает content-части (text + image_url); при картинке модель = DEEPSEEK_VISION_MODEL.
