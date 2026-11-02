package com.voiceagent.oneplus13.notes

import com.voiceagent.oneplus13.core.AgentEvent
import com.voiceagent.oneplus13.core.AgentEventBus
import com.voiceagent.oneplus13.core.TTSEngine

/** Owns notes use cases and conversational topic selection, independent of routing.
 * The service's request guard must reject cancellation/closure before committing
 * an asynchronous result. It is injected without a dependency on the agent package.
 */
internal class NotesActions(
    private val notesBuilder: NotesBuilder,
    private val notesStorage: NotesStorage,
    private val tts: TTSEngine,
    private val ensureActiveRequest: suspend () -> Unit
) {
    private var lastTopic: String? = null

    /** "Собери конспект по..." — из присланных источников (текст лекции, слайды,
     *  учебник, описания схем) собирает один структурированный конспект и
     *  сохраняет его под именем темы, чтобы потом можно было дополнить. */
    suspend fun build(params: Map<String, String>) {
        val topic = params["topic"]?.takeIf { it.isNotBlank() } ?: "Конспект"
        val sources = params["sources"]
        if (sources.isNullOrBlank()) {
            tts.speak("Не вижу материалов для конспекта — пришли текст, слайды или фото учебника.")
            return
        }
        tts.speak("Собираю конспект, секунду.")
        val notes = notesBuilder.build(topic, listOf(sources))
        ensureActiveRequest()
        notesStorage.save(topic, notes)
        lastTopic = topic
        AgentEventBus.publish(AgentEvent.Reply(notes))
        tts.speak("Готово, конспект \"$topic\" сохранён. Можешь попросить дополнить его позже.")
    }

    /** "Дополни конспект..." — находит нужное место в уже собранном конспекте и
     *  вписывает туда новую информацию, не переписывая всё с нуля. */
    suspend fun extend(params: Map<String, String>) {
        val addition = params["addition"]
        if (addition.isNullOrBlank()) return
        val topic = params["topic"]?.takeIf { it.isNotBlank() } ?: lastTopic ?: notesStorage.latestTopic()
        if (topic == null) {
            tts.speak("Не помню, какой конспект дополнять — скажи его тему.")
            return
        }
        val existing = notesStorage.load(topic)
        if (existing == null) {
            tts.speak("Не нашёл конспект \"$topic\".")
            return
        }
        tts.speak("Дополняю конспект, секунду.")
        val updated = notesBuilder.extend(existing, addition)
        ensureActiveRequest()
        notesStorage.save(topic, updated)
        lastTopic = topic
        AgentEventBus.publish(AgentEvent.Reply(updated))
        tts.speak("Дополнил конспект \"$topic\".")
    }

}
