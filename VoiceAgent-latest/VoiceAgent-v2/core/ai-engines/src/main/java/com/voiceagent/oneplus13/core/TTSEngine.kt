package com.voiceagent.oneplus13.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin wrapper around Android's built-in TextToSpeech, exposed as a suspend function.
 *
 * - Язык (ru-RU) выставляется ПОСЛЕ инициализации движка: вызов setLanguage до onInit
 *   молча игнорируется, и озвучка шла бы на языке системы по умолчанию.
 * - Первая фраза не теряется, если движок ещё поднимается: speak() ждёт инициализацию.
 * - Вызовы сериализованы (Mutex): одновременные speak() не затирают listener друг друга.
 * - Отмена корутины останавливает речь; слишком длинный текст режется на куски.
 */
class TTSEngine(context: Context) {

    private val initResult = CompletableDeferred<Boolean>()
    private val speakLock = Mutex()
    @Volatile private var languageApplied = false
    @Volatile private var closed = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        initResult.complete(status == TextToSpeech.SUCCESS)
    }

    suspend fun speak(text: String) {
        check(!closed) { "TTS is closed" }
        if (text.isBlank()) return
        val ready = withTimeoutOrNull(INIT_TIMEOUT_MS) { initResult.await() } ?: false
        if (!ready) {
            throw IllegalStateException("TTS engine is not ready")
        }
        speakLock.withLock {
            check(!closed) { "TTS is closed" }
            AudioCaptureGate.withPaused {
                applyLanguageOnce()
                VoiceRuntime.during(VoiceRuntime.Phase.SPEAKING) {
                    for (chunk in splitForTts(text)) withTimeout(60_000L) { speakChunk(chunk) }
                }
            }
        }
    }

    private fun applyLanguageOnce() {
        if (languageApplied) return
        val result = tts.setLanguage(Locale("ru", "RU"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Нет русского голоса — говорим голосом по умолчанию, но не молчим.
            Log.w(TAG, "ru-RU voice unavailable (code=$result), using default language")
        }
        languageApplied = true
    }

    private suspend fun speakChunk(chunk: String) = suspendCancellableCoroutine<Unit> { cont ->
        val id = UUID.randomUUID().toString()
        val finished = AtomicBoolean(false)
        fun finish(utteranceId: String?, failed: Boolean = false) {
            if (utteranceId == id && finished.compareAndSet(false, true) && cont.isActive) {
                if (failed) cont.resumeWithException(IllegalStateException("TTS utterance failed"))
                else cont.resume(Unit)
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId, true)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, true)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, true)
        })
        cont.invokeOnCancellation { finished.set(true); tts.stop() }
        // Если speak() вернул ошибку, колбэки не придут никогда — не зависаем.
        if (!cont.isActive) return@suspendCancellableCoroutine
        if (tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            finish(id, true)
        }
    }

    /** speak() возвращает ERROR на тексте длиннее getMaxSpeechInputLength() (обычно 4000). */
    private fun splitForTts(text: String): List<String> {
        val max = minOf(TextToSpeech.getMaxSpeechInputLength(), MAX_CHUNK)
        if (text.length <= max) return listOf(text)
        val parts = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > max) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), max - 1)
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max - 1)
            if (cut < max / 2) cut = max - 1
            parts += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    fun shutdown() {
        if (closed) return
        closed = true
        initResult.complete(false)
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        const val TAG = "TTSEngine"
        const val INIT_TIMEOUT_MS = 5_000L
        const val MAX_CHUNK = 3_500
    }
}
