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
internal data class RecordedNotification(val sequence: Long, val entry: NotificationEntry)

object NotificationBuffer {
    private const val MAX_ENTRIES = 200
    private val entries = ArrayDeque<RecordedNotification>()
    private var sequence = 0L

    @Synchronized
    fun record(entry: NotificationEntry) {
        entries.addLast(RecordedNotification(++sequence, entry))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun entriesSince(timestampMillis: Long): List<NotificationEntry> =
        entries.map { it.entry }.filter { it.timestampMillis > timestampMillis }

    /** Arrival order is independent of equal timestamps and wall-clock changes. */
    @Synchronized
    internal fun recordsAfter(completedSequence: Long): List<RecordedNotification> =
        entries.filter { it.sequence > completedSequence }
}
