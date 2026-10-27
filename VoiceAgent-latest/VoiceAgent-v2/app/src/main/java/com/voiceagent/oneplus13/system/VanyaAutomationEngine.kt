package com.voiceagent.oneplus13.system

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.voiceagent.oneplus13.core.AgentEvent
import com.voiceagent.oneplus13.core.AgentEventBus
import com.voiceagent.oneplus13.core.STTEngine
import com.voiceagent.oneplus13.core.TTSEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Монолитное ядро: слово "Ваня" -> снимок экрана -> команда -> потоковый ответ DeepSeek -> озвучка.
 *
 * Конвейер одного цикла ([runCycle]):
 *  1. STT фаза 1: офлайн-ожидание ["ваня"]; событие WakeDetected в AgentEventBus.
 *  2. Ровно один снимок экрана в момент вызова (параллельно с записью команды).
 *  3. STT фаза 2: полнотекстовое распознавание, берём первую непустую фразу.
 *  4. POST {backendUrl}/chat/stream (SSE) с текстом + снимком (image_url, base64 JPEG).
 *  5. Чанки текста режутся на фразы и сразу уходят в TTSEngine, пока ответ ещё идёт.
 *
 * Не запускать одновременно с циклом прослушивания в VoiceAgentService: микрофон один.
 * [screen] может быть null или без выданного разрешения — тогда запрос уходит без снимка.
 */
class VanyaAutomationEngine(
    private val stt: STTEngine,
    private val tts: TTSEngine,
    private val screen: ScreenCaptureManager?,
    private val backendUrl: String,
    private val backendToken: String,
    private val scope: CoroutineScope,
    private val systemPrompt: String = DEFAULT_PROMPT,
    private val http: OkHttpClient = defaultHttp(),
) {
    private var loopJob: Job? = null
    private val history = ArrayDeque<Pair<String, String>>() // (role, text), без картинок

    val isRunning: Boolean get() = loopJob?.isActive == true

    fun start() {
        if (isRunning) return
        loopJob = scope.launch(Dispatchers.Default) {
            try {
                awaitModel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentEventBus.publish(AgentEvent.Error("Не удалось загрузить модель Vosk", e))
                return@launch
            }
            while (isActive) {
                try {
                    runCycle()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "cycle failed", e)
                    AgentEventBus.publish(AgentEvent.Error("Сбой цикла ассистента: ${e.message}", e))
                }
                delay(RESTART_DELAY_MS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        stt.stop()
    }

    // ------------------------------------------------------------- цикл

    private suspend fun runCycle() = coroutineScope {
        stt.awaitWakeWord()                                         // 1. слово-вызов
        AgentEventBus.publish(AgentEvent.WakeDetected)

        val shot = async(Dispatchers.Default) { captureScreenAsJpegBase64() } // 2. один снимок
        delay(MIC_HANDOFF_DELAY_MS)                                 // микрофон должен освободиться

        val command = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {       // 3. полнотекстовая команда
            stt.listen().first { it.isNotBlank() }
        }
        if (command.isNullOrBlank()) {
            shot.cancel()
            return@coroutineScope
        }
        AgentEventBus.publish(AgentEvent.Heard(command))
        answer(command, shot.await())
    }

    private suspend fun answer(command: String, imageBase64: String?) {
        AgentEventBus.publish(AgentEvent.ThinkingStarted(command))
        val reply = StringBuilder()
        try {
            val chunks = streamChat(buildMessages(command, imageBase64)).onEach { reply.append(it) }
            speakStream(chunks)                                     // 4+5. стрим -> озвучка
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "answer failed", e)
            AgentEventBus.publish(AgentEvent.Error("Не удалось получить ответ: ${e.message}", e))
            runCatching { tts.speak("Не удалось получить ответ.") }
            return
        }
        remember(command, reply.toString())
        AgentEventBus.publish(AgentEvent.Reply(reply.toString()))
    }

    // ------------------------------------------------------------- STT

    private suspend fun awaitModel() = suspendCancellableCoroutine<Unit> { c ->
        stt.ensureModel(
            onReady = { if (c.isActive) c.resume(Unit) },
            onError = { if (c.isActive) c.resumeWithException(it) }
        )
    }

    // ------------------------------------------------------------- экран

    /** Вызывается из фонового потока. Bitmap не recycle()-им: ScreenCaptureManager держит его как кэш. */
    private fun captureScreenAsJpegBase64(): String? {
        val manager = screen?.takeIf { it.isReady() } ?: return null
        val bitmap = manager.captureCurrentScreen() ?: return null
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    // ------------------------------------------------------------- запрос

    private fun buildMessages(command: String, imageBase64: String?): JSONArray {
        val messages = JSONArray().put(message("system", systemPrompt))
        history.forEach { (role, text) -> messages.put(message(role, text)) }
        val user = if (imageBase64 == null) {
            message("user", command)
        } else {
            JSONObject().put("role", "user").put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", command))
                    .put(
                        JSONObject().put("type", "image_url").put(
                            "image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                        )
                    )
            )
        }
        return messages.put(user)
    }

    private fun message(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    private fun remember(command: String, reply: String) {
        if (reply.isBlank()) return
        history.addLast("user" to command)
        history.addLast("assistant" to reply)
        while (history.size > MAX_HISTORY_ENTRIES) history.removeFirst()
    }

    /** SSE от backend/server.py (/chat/stream): строки `data: {"text": "..."}` и `data: [DONE]`. */
    private fun streamChat(messages: JSONArray): Flow<String> = callbackFlow {
        val body = JSONObject().put("messages", messages).put("temperature", 0.7)
            .toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${backendUrl.trimEnd('/')}/chat/stream")
            .header("Authorization", "Bearer $backendToken")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()
        val call = http.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) throw IOException("Бэкенд ответил ${response.code}")
                    val source = response.body?.source() ?: throw IOException("Пустой ответ бэкенда")
                    val parser = SseEventParser()
                    while (!parser.completed) {
                        val line = source.readUtf8Line() ?: break
                        val text = parser.line(line)
                        if (text != null && trySendBlocking(text).isFailure) return
                    }
                    parser.endOfInput()
                    close()
                } catch (e: Exception) {
                    close(e)
                } finally {
                    response.close()
                }
            }
        })
        awaitClose { call.cancel() }
    }

    // ------------------------------------------------------------- озвучка потока

    /**
     * Чанки копятся в буфере; готовая фраза уходит в очередь, отдельная корутина озвучивает
     * очередь по порядку. Чтение стрима не ждёт озвучку, озвучка не ждёт конец ответа.
     */
    private suspend fun speakStream(chunks: Flow<String>) = coroutineScope {
        val sentences = Channel<String>(16)
        val speaker = launch { for (s in sentences) tts.speak(s) }
        val buffer = StringBuilder()
        var first = true
        try {
            chunks.collect { piece ->
                buffer.append(piece)
                while (true) {
                    val cut = nextCut(buffer, first)
                    if (cut < 0) break
                    val sentence = clean(buffer.substring(0, cut))
                    buffer.delete(0, cut)
                    if (sentence.isNotBlank()) {
                        sentences.send(sentence)
                        first = false
                    }
                }
            }
            clean(buffer.toString()).takeIf { it.isNotBlank() }?.let { sentences.send(it) }
        } finally {
            sentences.close()
        }
        speaker.join()
    }

    /** Индекс конца первой готовой фразы или -1. Первая фраза режется раньше — минимальная задержка. */
    private fun nextCut(b: CharSequence, first: Boolean): Int {
        for (i in 0 until b.length - 1) {
            val c = b[i]
            // следующий символ обязателен: "3.5" и "т.д." не режем
            if (c == '\n' || (c in ".!?…" && b[i + 1].isWhitespace())) return i + 1
        }
        val limit = if (first) FIRST_PHRASE_CHARS else MAX_PHRASE_CHARS
        if (b.length >= limit) {
            val space = b.lastIndexOf(' ')
            if (space > limit / 2) return space + 1
        }
        return -1
    }

    private fun clean(text: String): String =
        text.replace(MARKDOWN, "").replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val TAG = "VanyaEngine"
        private const val COMMAND_TIMEOUT_MS = 8_000L
        private const val MIC_HANDOFF_DELAY_MS = 250L
        private const val RESTART_DELAY_MS = 1_000L
        private const val JPEG_QUALITY = 70
        private const val FIRST_PHRASE_CHARS = 60
        private const val MAX_PHRASE_CHARS = 200
        private const val MAX_HISTORY_ENTRIES = 12
        private val MARKDOWN = Regex("[*_#`>~|]+")

        private const val DEFAULT_PROMPT =
            "Ты голосовой ассистент Ваня. Отвечай по-русски, коротко и разговорно, без markdown, " +
                "списков и эмодзи — ответ будет озвучен. К сообщению может быть приложен снимок экрана " +
                "пользователя: используй его как контекст вопроса."

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS) // пауза между чанками (R1 думает долго)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
