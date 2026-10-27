package com.voiceagent.oneplus13.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide event bus. Every component (STT, agent orchestrator, lecture module,
 * messenger bridge) publishes here instead of holding direct references to each other,
 * so the UI and the foreground service can both observe the same stream.
 */
sealed class AgentEvent {
    data class Heard(val text: String) : AgentEvent()
    data class ThinkingStarted(val prompt: String) : AgentEvent()
    data class Reply(val text: String) : AgentEvent()
    data class ActionRequested(val action: String, val params: Map<String, String>) : AgentEvent()
    data class Error(val message: String, val cause: Throwable? = null) : AgentEvent()
    /** Сгенерированный проект упакован в .zip и лежит по [zipPath] (уже и в
     *  Загрузках — см. ProjectStorage). UI показывает это как вложение в чате
     *  с кнопками "Открыть"/"Поделиться", сервис — как отдельное уведомление. */
    data class ProjectReady(val projectName: String, val zipPath: String) : AgentEvent()
    /** Услышано будильное слово ("Ваня") — сигнал показать пузырь/панель,
     *  даже если сама фраза после него ещё не разобрана. */
    object WakeDetected : AgentEvent()
}

object AgentEventBus {
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 32)
    val events = _events.asSharedFlow()

    private val _lastStatus = MutableStateFlow("ожидание")
    val lastStatus = _lastStatus.asStateFlow()

    suspend fun publish(event: AgentEvent) {
        _lastStatus.value = when (event) {
            is AgentEvent.Heard -> "услышал: ${event.text}"
            is AgentEvent.ThinkingStarted -> "думаю…"
            is AgentEvent.Reply -> "ответ: ${event.text}"
            is AgentEvent.ActionRequested -> "действие: ${event.action}"
            is AgentEvent.Error -> "ошибка: ${event.message}"
            is AgentEvent.ProjectReady -> "проект готов: ${event.projectName}.zip"
            is AgentEvent.WakeDetected -> "слушаю…"
        }
        _events.emit(event)
    }
}
