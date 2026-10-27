package com.voiceagent.oneplus13.notes

import android.content.Context
import java.io.File

/** Персистентное хранение конспектов — по одному .md файлу на тему, чтобы
 *  "дополни конспект" могло найти и переписать нужный файл, а не потерять его. */
class NotesStorage(private val context: Context) {

    private fun notesDir(): File = File(context.filesDir, "notes").apply { mkdirs() }
    private fun fileFor(topic: String): File = File(notesDir(), "${sanitize(topic)}.md")

    fun save(topic: String, markdown: String) {
        fileFor(topic).writeText(markdown)
    }

    fun load(topic: String): String? = fileFor(topic).takeIf { it.exists() }?.readText()

    fun latestTopic(): String? =
        notesDir().listFiles { f -> f.extension == "md" }
            ?.maxByOrNull { it.lastModified() }
            ?.nameWithoutExtension

    private fun sanitize(topic: String): String =
        topic.trim().lowercase().replace(Regex("[^a-zа-я0-9]+"), "_").take(60).ifBlank { "конспект" }
}
