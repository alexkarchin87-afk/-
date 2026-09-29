package com.voiceagent.oneplus13.lecture

import android.content.Context
import com.voiceagent.oneplus13.core.STTEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives STTEngine specifically for long-form lecture capture: continuously
 * appends recognized chunks to the active LectureSession via LectureChunkProcessor,
 * independent of the normal command-listening loop in VoiceAgentService.
 */
class LectureRecorder(context: Context) {

    private val stt = STTEngine(context)
    private val scope = CoroutineScope(Job())
    private var listening = false

    fun start(session: LectureSession, chunkProcessor: LectureChunkProcessor) {
        if (listening) return
        listening = true
        stt.ensureModel(
            onReady = {
                scope.launch {
                    stt.listen().collect { chunk ->
                        chunkProcessor.process(session, chunk)
                    }
                }
            },
            onError = { /* surfaced via AgentEventBus by the caller */ }
        )
    }

    fun stop() {
        listening = false
        stt.stop()
    }
}
