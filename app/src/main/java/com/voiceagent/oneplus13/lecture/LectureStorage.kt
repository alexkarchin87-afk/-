package com.voiceagent.oneplus13.lecture

import android.content.Context
import java.io.File

/** Persists lecture transcripts + summaries as plain text files under app-internal storage. */
class LectureStorage(private val context: Context) {

    private fun lecturesDir(): File =
        File(context.filesDir, "lectures").apply { mkdirs() }

    fun save(session: LectureSession) {
        val dir = lecturesDir()
        File(dir, "${session.id}_transcript.txt").writeText(session.fullTranscript())
        session.summary?.let { File(dir, "${session.id}_summary.txt").writeText(it) }
    }

    fun latestSummary(): String? {
        val dir = lecturesDir()
        val latest = dir.listFiles { f -> f.name.endsWith("_summary.txt") }
            ?.maxByOrNull { it.lastModified() }
        return latest?.readText()
    }

    fun listSessions(): List<File> =
        lecturesDir().listFiles { f -> f.name.endsWith("_transcript.txt") }?.toList().orEmpty()
}
