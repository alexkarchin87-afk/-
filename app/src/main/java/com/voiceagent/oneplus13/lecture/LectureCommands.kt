package com.voiceagent.oneplus13.lecture

import android.content.Context
import com.voiceagent.oneplus13.core.LLMClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Voice-command surface for the lecture feature, called by AgentOrchestrator's
 * dispatch table (lecture_start / lecture_stop / lecture_summary).
 */
class LectureCommands(context: Context) {

    private val recorder = LectureRecorder(context)
    private val chunkProcessor = LectureChunkProcessor()
    private val storage = LectureStorage(context)
    private val summarizer = LectureSummarizer(LLMClient(context))
    private val scope = CoroutineScope(Job())

    private var current: LectureSession? = null

    fun start(title: String) {
        val session = LectureSession(title = title)
        current = session
        recorder.start(session, chunkProcessor)
    }

    fun stop() {
        val session = current ?: return
        recorder.stop()
        session.endedAtMillis = System.currentTimeMillis()
        scope.launch {
            summarizer.summarize(session)
            storage.save(session)
        }
        current = null
    }

    fun latestSummary(): String? = storage.latestSummary()
}
