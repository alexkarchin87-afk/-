package com.voiceagent.oneplus13.core

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.atomic.AtomicBoolean

/** A model belongs to this engine; AudioRecord and Recognizer belong to one collected flow. */
class STTEngine(private val context: Context, private val lecture: Boolean = false) {
    private val stateLock = Any()
    private var model: Model? = null
    private var closed = false
    private var loading = false
    private val waiters = mutableListOf<Pair<(Model) -> Unit, (Throwable) -> Unit>>()
    private var releaseCurrent: (() -> Unit)? = null

    fun ensureModel(modelAssetPath: String = VoskModelFiles.ASSET_PATH, onReady: (Model) -> Unit, onError: (Throwable) -> Unit) {
        synchronized(stateLock) {
            if (closed) { onError(IllegalStateException("STT engine is closed")); return }
            model?.let { onReady(it); return }
            waiters += onReady to onError
            if (loading) return
            loading = true
        }
        VoskModelLoader.load(context.applicationContext, modelAssetPath, { loaded ->
            val callbacks = synchronized(stateLock) {
                loading = false
                if (closed) { loaded.close(); waiters.clear(); return@load }
                model = loaded
                waiters.toList().also { waiters.clear() }
            }
            callbacks.forEach { it.first(loaded) }
        }, { error ->
            val callbacks = synchronized(stateLock) {
                loading = false
                waiters.toList().also { waiters.clear() }
            }
            callbacks.forEach { it.second(error) }
        })
    }

    suspend fun awaitWakeWord(wakeWord: String = "ваня") {
        capture(org.json.JSONArray(listOf(wakeWord, "[unk]")).toString(), true)
            .first { it.split(Regex("\\s+")).any { word -> word.equals(wakeWord, ignoreCase = true) } }
    }

    /** Full final recognition avoids throwing away the command spoken together with the wake word. */
    suspend fun awaitWakeCommand(): String = commandAfterWake(
        capture(null, false).first { commandAfterWake(it) != null }
    ).orEmpty()

    fun listen(): Flow<String> = flow {
        VoiceRuntime.during(VoiceRuntime.Phase.LISTENING) { emitAll(capture(null, false)) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun capture(grammar: String?, partials: Boolean): Flow<String> =
        AudioCaptureGate.canCapture(lecture).flatMapLatest { allowed ->
            if (!allowed) emptyFlow() else flow {
                AudioCaptureGate.captureLock.withLock {
                    if (AudioCaptureGate.allowed.value && AudioCaptureGate.lectureActive.value == lecture) emitAll(recognize(grammar, partials))
                }
            }
        }

    private fun recognize(grammar: String?, partials: Boolean): Flow<String> = callbackFlow {
        val released = AtomicBoolean(false)
        lateinit var service: SpeechService
        lateinit var recognizer: Recognizer
        val release: () -> Unit = {
            if (released.compareAndSet(false, true)) {
                // Vosk 0.3.45 shutdown() releases AudioRecord only. Close Recognizer AFTER join.
                try { service.stop() } finally {
                    try { service.shutdown() } finally { recognizer.close() }
                }
            }
        }
        synchronized(stateLock) {
            check(!closed) { "STT engine is closed" }
            val current = checkNotNull(model) { "Call ensureModel() first" }
            recognizer = if (grammar == null) Recognizer(current, 16000f) else Recognizer(current, 16000f, grammar)
            try { service = SpeechService(recognizer, 16000f) }
            catch (e: Exception) { recognizer.close(); throw e }
            releaseCurrent = { close(); release() }
            fun publish(json: String?, key: String) {
                if (released.get()) return
                val text = runCatching { JSONObject(json.orEmpty()).optString(key) }.getOrDefault("")
                if (text.isNotBlank()) trySend(text)
            }
            try {
                val started = service.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) { if (partials) publish(hypothesis, "partial") }
                    override fun onResult(hypothesis: String?) = publish(hypothesis, "text")
                    override fun onFinalResult(hypothesis: String?) = publish(hypothesis, "text")
                    override fun onError(exception: Exception?) { close(exception ?: IllegalStateException("Vosk error")) }
                    override fun onTimeout() { close() }
                })
                if (!started) close(IllegalStateException("Микрофон занят"))
            } catch (e: Exception) { close(e) }
        }
        awaitClose {
            synchronized(stateLock) { release(); releaseCurrent = null }
        }
    }

    fun stop() { synchronized(stateLock) { releaseCurrent?.invoke(); releaseCurrent = null } }
    fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            stop()
            model?.close()
            model = null
            waiters.clear()
        }
    }
}
