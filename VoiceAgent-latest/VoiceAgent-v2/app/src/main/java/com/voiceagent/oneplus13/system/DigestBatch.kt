package com.voiceagent.oneplus13.system

internal data class DigestProgress(
    val completedThrough: Long = 0,
    val partialSequence: Long? = null,
    val partialChars: Int = 0
)

internal data class DigestBatch(val text: String, val next: DigestProgress)

/** A bounded request plus its proposed checkpoint; never consumes unsubmitted text. */
internal fun prepareDigestBatch(
    records: List<RecordedNotification>,
    progress: DigestProgress,
    maxChars: Int,
    format: (NotificationEntry) -> String
): DigestBatch? {
    require(maxChars >= 2)
    val text = StringBuilder()
    var next = progress
    for (record in records) {
        if (record.sequence <= progress.completedThrough) continue
        val formatted = format(record.entry)
        val start = if (record.sequence == progress.partialSequence)
            progress.partialChars.coerceIn(0, formatted.length) else 0
        val separator = if (text.isEmpty()) "" else "\n"
        val room = maxChars - text.length - separator.length
        if (room <= 0) break
        var end = start + minOf(room, formatted.length - start)
        // Do not split a surrogate pair when the budget ends in the middle of an emoji.
        if (end < formatted.length && end > start && formatted[end - 1].isHighSurrogate() &&
            formatted[end].isLowSurrogate()) end--
        if (end == start && start < formatted.length) break
        text.append(separator).append(formatted, start, end)
        next = if (end == formatted.length) DigestProgress(completedThrough = record.sequence)
        else DigestProgress(next.completedThrough, record.sequence, end)
        if (end < formatted.length) break
    }
    return if (text.isEmpty()) null else DigestBatch(text.toString(), next)
}
