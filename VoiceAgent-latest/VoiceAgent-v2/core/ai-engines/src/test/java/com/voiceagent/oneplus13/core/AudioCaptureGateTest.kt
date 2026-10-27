package com.voiceagent.oneplus13.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioCaptureGateTest {
    @Test fun pauseWaitsForRecorderRelease() = runTest {
        AudioCaptureGate.captureLock.lock()
        var speaking = false
        val task = launch { AudioCaptureGate.withPaused { speaking = true } }
        runCurrent()
        assertFalse(AudioCaptureGate.allowed.value)
        assertFalse(speaking)
        AudioCaptureGate.captureLock.unlock()
        task.join()
        assertTrue(speaking)
        assertTrue(AudioCaptureGate.allowed.value)
    }
    @Test fun nestedSpeechCannotResumeCaptureEarly() = runTest {
        AudioCaptureGate.withPaused {
            assertFalse(AudioCaptureGate.allowed.value)
            AudioCaptureGate.withPaused { assertFalse(AudioCaptureGate.allowed.value) }
            assertFalse(AudioCaptureGate.allowed.value)
        }
        assertTrue(AudioCaptureGate.allowed.value)
    }
    @Test fun cancellationAndFailureRestoreCapture() = runTest {
        val task = launch { AudioCaptureGate.withPaused { awaitCancellation() } }
        runCurrent()
        assertFalse(AudioCaptureGate.allowed.value)
        task.cancelAndJoin()
        assertTrue(AudioCaptureGate.allowed.value)
        try { AudioCaptureGate.withPaused { error("test") } } catch (_: IllegalStateException) {}
        assertTrue(AudioCaptureGate.allowed.value)
    }
    @Test fun lectureOwnershipSuspendsWakeAndIgnoresStaleRelease() = runTest {
        val old = Any(); val current = Any()
        AudioCaptureGate.activateLecture(old)
        assertFalse(AudioCaptureGate.canCapture(false).first())
        assertTrue(AudioCaptureGate.canCapture(true).first())
        AudioCaptureGate.releaseLecture(old)
        AudioCaptureGate.activateLecture(current)
        AudioCaptureGate.releaseLecture(old)
        assertTrue(AudioCaptureGate.lectureActive.value)
        AudioCaptureGate.releaseLecture(current)
        assertTrue(AudioCaptureGate.canCapture(false).first())
        assertFalse(AudioCaptureGate.canCapture(true).first())
    }
}
