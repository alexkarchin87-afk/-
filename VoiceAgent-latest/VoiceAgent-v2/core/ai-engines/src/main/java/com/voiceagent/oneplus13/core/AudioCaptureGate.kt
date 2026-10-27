package com.voiceagent.oneplus13.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide microphone ownership. Pausing waits for the current recorder's cleanup. */
object AudioCaptureGate {
    internal val captureLock = Mutex()
    private val stateLock = Any()
    private var pauseCount = 0
    private val _allowed = MutableStateFlow(true)
    internal val allowed = _allowed.asStateFlow()
    internal val lectureActive = MutableStateFlow(false)
    private var lectureOwner: Any? = null
    fun activateLecture(owner: Any) = synchronized(stateLock) {
        check(lectureOwner == null || lectureOwner === owner) { "Another lecture owns the microphone" }
        lectureOwner = owner
        lectureActive.value = true
    }
    fun releaseLecture(owner: Any) = synchronized(stateLock) {
        if (lectureOwner === owner) { lectureOwner = null; lectureActive.value = false }
    }
    internal fun canCapture(lecture: Boolean) = kotlinx.coroutines.flow.combine(allowed, lectureActive) { available, recording ->
        available && recording == lecture
    }

    suspend fun <T> withPaused(block: suspend () -> T): T {
        synchronized(stateLock) { pauseCount++; _allowed.value = false }
        try {
            captureLock.withLock { /* await AudioRecord/Recognizer release */ }
            return block()
        } finally {
            synchronized(stateLock) { pauseCount--; _allowed.value = pauseCount == 0 }
        }
    }
}
