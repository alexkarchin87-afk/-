package com.voiceagent.oneplus13.lecture

import android.content.Context
import com.voiceagent.oneplus13.core.STTEngine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Coroutine ownership makes a late model callback harmless after stop/restart. */
class LectureRecorder(context: Context) {
    private val stt = STTEngine(context, lecture = true)
    suspend fun record(onChunk: suspend (String) -> Unit) {
        suspendCancellableCoroutine<Unit> { continuation ->
            stt.ensureModel(
                onReady = { if (continuation.isActive) continuation.resume(Unit) },
                onError = { if (continuation.isActive) continuation.resumeWithException(it) }
            )
        }
        stt.listen().collect { onChunk(it) }
    }
    fun close() = stt.close()
}
