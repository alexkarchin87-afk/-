# Микрофон, STT, TTS и состояния аудио

Владелец: `voice`.

## Основные интерфейсы

STTEngine, TTSEngine, AudioCaptureGate, VoiceRuntime, VoskModelFiles.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#voice). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Модель принадлежит engine; recognizer/AudioRecord — collect Flow; TTS shutdown и STT close обязательны.

## Возможные ошибки

Микрофон занят, native Vosk, assets отсутствуют, TTS init, отмена.

## Тесты

- [ AudioCaptureGateTest.kt ](../../core/ai-engines/src/test/java/com/voiceagent/oneplus13/core/AudioCaptureGateTest.kt)
- [ VoiceRuntimeTest.kt ](../../core/ai-engines/src/test/java/com/voiceagent/oneplus13/core/VoiceRuntimeTest.kt)
- [ VoskModelFilesTest.kt ](../../core/ai-engines/src/test/java/com/voiceagent/oneplus13/core/VoskModelFilesTest.kt)
- [ WakeCommandTest.kt ](../../core/ai-engines/src/test/java/com/voiceagent/oneplus13/core/WakeCommandTest.kt)

## Правила расширения

Сохранять captureLock и finally cleanup; не обходить verifyVoskModel.
