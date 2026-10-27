package com.voiceagent.oneplus13.lecture

/**
 * Cleans and appends raw STT output to a session. Kept separate from LectureRecorder
 * so filtering rules (drop filler words, merge sentence fragments) can evolve without
 * touching the audio pipeline.
 */
class LectureChunkProcessor {

    private val fillerWords = setOf("э-э", "ну", "как бы", "типа")

    fun process(session: LectureSession, rawChunk: String) {
        val cleaned = clean(rawChunk)
        if (cleaned.isNotBlank()) session.appendTranscriptChunk(cleaned)
    }

    private fun clean(text: String): String {
        var result = text.trim()
        fillerWords.forEach { filler ->
            result = result.replace(Regex("(?i)\\b$filler\\b"), "")
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }
}
