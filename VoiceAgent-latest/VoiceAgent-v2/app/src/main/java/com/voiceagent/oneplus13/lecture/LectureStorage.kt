package com.voiceagent.oneplus13.lecture

import android.content.Context
import android.util.Log
import java.io.File

/** Context adapter; repository/checkpoint logic is exercised against real files in JVM tests. */
class LectureStorage(context: Context) {
    internal val repository = LectureRepository(File(context.filesDir, "lectures"), mirrorFailure = {
        Log.w("LectureStorage", "Lecture checkpoint saved; text export mirror refresh failed")
    })
    fun save(session: LectureSession) = repository.save(session)
    fun latestSession(): LectureSession? = repository.latest()
    fun latestSummary(): String? = latestSession()?.summary
    fun listSessions(): List<File> = repository.transcriptFiles()
}
