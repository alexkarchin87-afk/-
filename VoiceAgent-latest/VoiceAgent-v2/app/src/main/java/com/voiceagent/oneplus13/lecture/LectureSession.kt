package com.voiceagent.oneplus13.lecture

import java.util.UUID

/** Persisted checkpoint for one lecture; interrupted recordings are recovered without restarting the mic. */
data class LectureSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val startedAtMillis: Long = System.currentTimeMillis(),
    var endedAtMillis: Long? = null,
    val chunks: MutableList<String> = mutableListOf(),
    var summary: String? = null,
    var interrupted: Boolean = false
) {
    val isActive: Boolean get() = endedAtMillis == null

    fun appendTranscriptChunk(text: String) {
        if (text.isNotBlank()) chunks.add(text)
    }

    fun fullTranscript(): String = chunks.joinToString(separator = " ")
}
