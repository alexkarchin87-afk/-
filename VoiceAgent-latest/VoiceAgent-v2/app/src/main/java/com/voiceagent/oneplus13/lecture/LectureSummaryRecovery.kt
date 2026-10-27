package com.voiceagent.oneplus13.lecture

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class SummaryResult { SAVED, ALREADY_SAVED, EMPTY }

/** Retry reads the durable checkpoint, so it also works after service/process recreation.
 * No work is scheduled at construction/startup. Caller explicitly requests cloud processing.
 */
internal class LectureSummaryRecovery(
    private val repository: LectureRepository,
    private val summarize: suspend (LectureSession) -> String,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val lock = Mutex()

    suspend fun run(id: String): SummaryResult {
        lock.lock() // Queue different lectures; duplicates re-read and reuse the first result.
        try {
            val session = requireNotNull(repository.load(id)) { "Lecture not found" }
            if (!session.summary.isNullOrBlank()) return SummaryResult.ALREADY_SAVED
            if (session.fullTranscript().isBlank()) return SummaryResult.EMPTY
            if (session.isActive) {
                session.endedAtMillis = now()
                session.interrupted = true
                repository.save(session)
            }
            val summary = summarize(session)
            check(summary.isNotBlank()) { "Empty lecture summary" }
            currentCoroutineContext().ensureActive()
            session.summary = summary
            repository.save(session)
            return SummaryResult.SAVED
        } finally { lock.unlock() }
    }
}
