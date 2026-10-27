package com.voiceagent.oneplus13.lecture

import android.content.Context
import com.voiceagent.oneplus13.core.AgentEvent
import com.voiceagent.oneplus13.core.AgentEventBus
import com.voiceagent.oneplus13.core.LLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A transcript is persisted before any optional cloud processing; jobs belong to the service. */
class LectureCommands(context: Context, private val scope: CoroutineScope) {
    private val audioOwner = Any()
    private val recorder = LectureRecorder(context)
    private val processor = LectureChunkProcessor()
    private val storage = LectureStorage(context)
    private val summarizer = LectureSummarizer(LLMClient(context))
    private val recovery = LectureSummaryRecovery(storage.repository, summarizer::summarize)
    @Volatile private var current: LectureSession? = null
    private var recording: Job? = null
    val isActive: Boolean get() = current != null

    suspend fun start(title: String) {
        if (current != null) {
            AgentEventBus.publish(AgentEvent.Reply("Лекция уже записывается."))
            return
        }
        val session = LectureSession(title = title)
        withContext(Dispatchers.IO) { storage.save(session) }
        com.voiceagent.oneplus13.core.AudioCaptureGate.activateLecture(audioOwner)
        current = session
        recording = scope.launch(Dispatchers.IO) {
            try {
                recorder.record { chunk ->
                    if (chunk.trim().lowercase() in setOf("ваня заверши лекцию", "ваня останови лекцию")) {
                        AgentEventBus.publish(AgentEvent.ActionRequested("lecture_stop_requested", mapOf("session_id" to session.id)))
                    } else {
                        processor.process(session, chunk)
                        storage.save(session) // durable checkpoint on every final phrase
                    }
                }
            } catch (e: CancellationException) { session.interrupted = true; throw e }
            catch (e: Exception) {
                session.interrupted = true
                AgentEventBus.publish(AgentEvent.Error("Запись лекции прервалась. Сохранённые фразы доступны локально.", e))
            } finally {
                session.endedAtMillis = System.currentTimeMillis()
                val failure = try { storage.save(session); null } catch (e: Exception) { e }
                if (current === session) {
                    current = null
                    com.voiceagent.oneplus13.core.AudioCaptureGate.releaseLecture(audioOwner)
                }
                // Release the microphone before an event emission that can suspend/cancel.
                if (failure != null) AgentEventBus.publish(AgentEvent.Error(
                    "Не удалось сохранить последнюю часть лекции. Предыдущие сохранённые фразы не удалены.", failure))
            }
        }
        AgentEventBus.publish(AgentEvent.Reply("Записываю лекцию. Для остановки скажите: Ваня заверши лекцию."))
    }

    suspend fun stop(expectedSessionId: String? = null) {
        val session = current ?: return
        if (expectedSessionId != null && expectedSessionId != session.id) return
        recording?.cancelAndJoin() // no more chunks may race the final save
        recording = null
        current = null
        session.endedAtMillis = System.currentTimeMillis()
        session.interrupted = false
        withContext(Dispatchers.IO) { storage.save(session) }
        AgentEventBus.publish(AgentEvent.Reply("Транскрипт лекции сохранён."))
        summarizeSaved(session.id)
    }

    suspend fun retryLatestSummary() {
        if (current != null) {
            AgentEventBus.publish(AgentEvent.Reply("Сначала остановите запись лекции."))
            return
        }
        val session = withContext(Dispatchers.IO) { storage.latestSession() }
        if (session == null) {
            AgentEventBus.publish(AgentEvent.Reply("Пока нет сохранённых лекций."))
            return
        }
        summarizeSaved(session.id)
    }

    private fun summarizeSaved(id: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val message = when (recovery.run(id)) {
                    SummaryResult.SAVED -> "Резюме лекции сохранено."
                    SummaryResult.ALREADY_SAVED -> "Резюме этой лекции уже сохранено."
                    SummaryResult.EMPTY -> "Транскрипт пуст — нечего суммировать."
                }
                AgentEventBus.publish(AgentEvent.Reply(message))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AgentEventBus.publish(AgentEvent.Error("Резюме не сохранено. Транскрипт остаётся локально. Скажите: повтори резюме лекции.", e))
            }
        }
    }

    suspend fun latestSummary(): String = withContext(Dispatchers.IO) {
        val session = storage.latestSession() ?: return@withContext "Пока нет сохранённых лекций."
        session.summary?.takeIf { it.isNotBlank() } ?: if (session.id == current?.id) {
            "Лекция ещё записывается."
        } else {
            "Транскрипт сохранён${if (session.isActive || session.interrupted) " после прерывания записи" else ""}, но резюме ещё нет. Скажите: повтори резюме лекции."
        }
    }
    fun close() { recording?.cancel(); recorder.close(); com.voiceagent.oneplus13.core.AudioCaptureGate.releaseLecture(audioOwner) }
}
