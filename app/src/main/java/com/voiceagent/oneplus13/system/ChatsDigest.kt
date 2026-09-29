package com.voiceagent.oneplus13.system

import android.content.Context
import android.content.pm.PackageManager
import com.voiceagent.oneplus13.core.LLMClient

/**
 * "Всеядный штурман уведомлений" — на "что нового?"/"прочитай уведомления"
 * берёт всё, что накопилось в [NotificationBuffer] с прошлого раза, и просит
 * LLM выдать 1-2 короткие фразы понятным языком: суть переписки/письма/лобби
 * в игре и есть ли что-то, требующее внимания пользователя. Флуд — не пересказывает.
 */
class ChatsDigest(private val context: Context, private val llm: LLMClient) {

    private var lastDigestAtMillis: Long = 0L

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

    suspend fun summarizeSinceLastDigest(): String {
        val entries = NotificationBuffer.entriesSince(lastDigestAtMillis)
        lastDigestAtMillis = System.currentTimeMillis()
        if (entries.isEmpty()) return "Новых уведомлений с прошлого раза нет."

        val formatted = entries.joinToString("\n") { e ->
            "${friendlyAppName(e.packageName)}: ${e.title} — ${e.text}"
        }.take(MAX_DIGEST_INPUT_CHARS)

        return llm.complete(prompt, emptyList(), formatted)
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
