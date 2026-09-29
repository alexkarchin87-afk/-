package com.voiceagent.oneplus13.system

/** Одна запись из шторки уведомлений: пакет-источник, заголовок, текст, время. */
data class NotificationEntry(
    val packageName: String,
    val title: String,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Кольцевой буфер последних уведомлений — процесс-wide, наполняется из
 * [NotificationReader]. Нужен, чтобы на "что нового?"/"прочитай уведомления"
 * агент мог ответить по факту увиденного в шторке, а не выдумывать ответ:
 * до этого буфера каждое уведомление публиковалось в AgentEventBus по
 * отдельности и тут же терялось, если никто не слушал именно в этот момент.
 */
object NotificationBuffer {
    private const val MAX_ENTRIES = 200
    private val entries = ArrayDeque<NotificationEntry>()

    @Synchronized
    fun record(entry: NotificationEntry) {
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun entriesSince(timestampMillis: Long): List<NotificationEntry> =
        entries.filter { it.timestampMillis > timestampMillis }
}
