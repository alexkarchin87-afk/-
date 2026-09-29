package com.voiceagent.oneplus13.lecture

import com.voiceagent.oneplus13.core.LLMClient

/** Sends a completed lecture's transcript to the LLM for a structured summary. */
class LectureSummarizer(private val llm: LLMClient) {

    private val prompt = """
        Ты помогаешь студенту. Тебе дан полный текст лекции (расшифровка речи).
        Сделай: 1) краткое резюме в 5-8 пунктах, 2) список ключевых терминов с
        однострочными определениями, 3) 3 вопроса для самопроверки. Пиши по-русски,
        структурированно, без вступлений.
    """.trimIndent()

    suspend fun summarize(session: LectureSession): String {
        val transcript = session.fullTranscript()
        if (transcript.isBlank()) return "Транскрипт пуст — нечего суммировать."
        val summary = llm.complete(prompt, emptyList(), transcript)
        session.summary = summary
        return summary
    }
}
