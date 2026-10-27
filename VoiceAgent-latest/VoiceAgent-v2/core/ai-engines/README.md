# :core:ai-engines

Изолированный Android-library модуль с ИИ-движками.

| Файл | Назначение |
|------|-----------|
| `LLMClient.kt` (+ `ChatMessage`) | DeepSeek / Claude API |
| `STTEngine.kt` | Распознавание речи (Vosk) |
| `TTSEngine.kt` | Синтез речи (Android TTS) |
| `security/SecretsStore.kt` | Шифрованное хранилище ключей |

- Kotlin-пакет: `com.voiceagent.oneplus13.core` (как раньше — импорты в `:app` не менялись).
- Gradle namespace: `com.voiceagent.oneplus13.core.aiengines`.
- `DEEPSEEK_API_KEY` берётся из `local.properties` в собственный `BuildConfig` модуля.
- `AgentEventBus` остаётся в `:app` — движки его не используют. Модуль НЕ должен зависеть от `:app`.
- Подключение: `implementation(project(":core:ai-engines"))` в `app/build.gradle.kts`.
