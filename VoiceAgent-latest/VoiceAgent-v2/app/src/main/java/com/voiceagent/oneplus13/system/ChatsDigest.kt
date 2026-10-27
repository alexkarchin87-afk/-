package com.voiceagent.oneplus13.system

import android.content.Context
import android.content.pm.PackageManager
import com.voiceagent.oneplus13.core.LLMClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Всеядный штурман уведомлений" — на "что нового?"/"прочитай уведомления"
 * берёт всё, что накопилось в [NotificationBuffer] с прошлого раза, и просит
 * LLM выдать 1-2 короткие фразы понятным языком: суть переписки/письма/лобби
 * в игре и есть ли что-то, требующее внимания пользователя. Флуд — не пересказывает.
 */
class ChatsDigest(private val context: Context, private val llm: LLMClient) {

    private val digestLock = Mutex()
    private var progress = DigestProgress()

    private val prompt = """
        Тебе дан список уведомлений из шторки телефона подростка (Telegram,
        WhatsApp, Gmail, GitHub, игры вроде Standoff 2/Minecraft и т.п.).
        Кратко перескажи суть в 1-2 коротких фразах на понятном подростку языке:
        о чём договорились в переписке, какой баг завели на GitHub, какое пришло
        письмо, зовут ли в лобби в игре. Отдельно отметь, если есть что-то,
        адресованное лично пользователю или требующее его действия. Весь флуд
        игнорируй. Если ничего важного нет, ответь ровно: "Там просто флудят,
        ничего важного".
    """.trimIndent()

    suspend fun summarizeSinceLastDigest(): String = digestLock.withLock {
        val records = NotificationBuffer.recordsAfter(progress.completedThrough)
        val batch = prepareDigestBatch(records, progress, MAX_DIGEST_INPUT_CHARS) { e ->
            "${friendlyAppName(e.packageName)}: ${e.title} — ${e.text}"
        } ?: return@withLock "Новых уведомлений с прошлого раза нет."

        val summary = llm.complete(prompt, emptyList(), batch.text)
        currentCoroutineContext().ensureActive()
        // Commit only the characters included in this successful request. The rest
        // stays available for a later user request; failures leave progress unchanged.
        progress = batch.next
        summary
    }

    private fun friendlyAppName(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    companion object {
        private const val MAX_DIGEST_INPUT_CHARS = 4000
    }
}
