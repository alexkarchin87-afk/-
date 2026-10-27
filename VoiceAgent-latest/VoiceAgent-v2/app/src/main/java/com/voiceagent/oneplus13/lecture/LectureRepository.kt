package com.voiceagent.oneplus13.lecture

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One atomic JSON checkpoint is authoritative; txt files remain compatible export mirrors.
 * The rename must be atomic: an unsupported filesystem fails without replacing the old checkpoint.
 * Synchronization covers repository instances within this app process.
 */
internal class LectureRepository(
    private val directory: File,
    private val write: (File, String) -> Unit = ::writeLectureAtomic,
    private val mirrorFailure: () -> Unit = {}
) {
    companion object { private val lock = Any() }

    fun save(session: LectureSession) = synchronized(lock) {
        val file = sessionFile(session.id)
        val json = JSONObject().put("version", 1).put("id", session.id).put("title", session.title)
            .put("started_at", session.startedAtMillis).put("ended_at", session.endedAtMillis ?: JSONObject.NULL)
            .put("chunks", JSONArray(session.chunks)).put("summary", session.summary ?: JSONObject.NULL)
            .put("interrupted", session.interrupted)
        write(file, json.toString())
        // A mirror failure must not report that a successfully committed checkpoint was lost.
        try {
            write(checkedFile("${session.id}_transcript.txt"), session.fullTranscript())
            session.summary?.let { write(checkedFile("${session.id}_summary.txt"), it) }
        } catch (_: Exception) { mirrorFailure() }
    }

    fun load(id: String): LectureSession? = synchronized(lock) {
        val file = sessionFile(id)
        if (!file.exists()) return@synchronized loadLegacy(id)
        val json = JSONObject(file.readText())
        require(json.getInt("version") == 1 && json.getString("id") == id) { "Invalid lecture checkpoint" }
        val chunks = json.getJSONArray("chunks")
        LectureSession(
            id = id, title = json.getString("title"), startedAtMillis = json.getLong("started_at"),
            endedAtMillis = if (json.isNull("ended_at")) null else json.getLong("ended_at"),
            chunks = (0 until chunks.length()).map { chunks.getString(it) }.toMutableList(),
            summary = if (json.isNull("summary")) null else json.getString("summary"),
            interrupted = json.optBoolean("interrupted", false)
        )
    }

    /** Order by recording time, never by the completion time of an older cloud request. */
    fun latest(): LectureSession? = synchronized(lock) {
        ensureDirectory()
        val ids = directory.listFiles().orEmpty().mapNotNull { file ->
            when {
                file.name.endsWith(".session.json") -> file.name.removeSuffix(".session.json")
                file.name.endsWith("_transcript.txt.bak") -> file.name.removeSuffix("_transcript.txt.bak")
                file.name.endsWith("_transcript.txt") -> file.name.removeSuffix("_transcript.txt")
                else -> null
            }
        }.distinct()
        // Corruption is surfaced, not silently hidden by returning an unrelated older lecture.
        ids.mapNotNull(::load).maxWithOrNull(compareBy<LectureSession> { it.startedAtMillis }.thenBy { it.id })
    }

    fun transcriptFiles(): List<File> = synchronized(lock) {
        ensureDirectory()
        directory.listFiles { f -> f.name.endsWith("_transcript.txt") }.orEmpty().toList()
    }

    private fun loadLegacy(id: String): LectureSession? {
        val transcript = legacyFile("${id}_transcript.txt") ?: return null
        val summary = legacyFile("${id}_summary.txt")?.readText()
        return LectureSession(id = id, title = "Сохранённая лекция", startedAtMillis = transcript.lastModified(),
            endedAtMillis = transcript.lastModified(), chunks = mutableListOf(transcript.readText()), summary = summary)
    }

    private fun legacyFile(name: String): File? {
        // Android AtomicFile's old .bak holds the last committed document after an interrupted write.
        val backup = checkedFile(name + ".bak")
        return if (backup.isFile) backup else checkedFile(name).takeIf { it.isFile }
    }

    private fun sessionFile(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid lecture id" }
        ensureDirectory()
        return checkedFile("$id.session.json")
    }

    private fun checkedFile(name: String): File {
        val file = File(directory, name)
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "Lecture path escapes storage" }
        return file
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Lecture storage is unavailable" }
    }
}

internal fun writeLectureAtomic(file: File, text: String) {
    val temp = File.createTempFile(".lecture-", ".tmp", file.parentFile)
    try {
        FileOutputStream(temp).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temp.delete() }
}
