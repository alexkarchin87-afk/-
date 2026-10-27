package com.voiceagent.oneplus13.agent

/** A confirmation is consumed by the next input even if expired, unrelated or from another source. */
internal class ConfirmationSlot<T>(private val now: () -> Long = System::nanoTime) {
    private data class Entry<T>(val payload: T, val requestId: Long, val source: String?, val offeredAt: Long, val deadline: Long)
    private var pending: Entry<T>? = null
    val hasPending: Boolean get() = pending != null
    fun offer(payload: T, requestId: Long, source: String?, ttlNanos: Long) {
        val offeredAt = now()
        pending = Entry(payload, requestId, source, offeredAt, offeredAt + ttlNanos)
    }
    fun take(requestId: Long, source: String, receivedAt: Long = now()): T? {
        val entry = pending ?: return null
        pending = null
        return entry.payload.takeIf {
            requestId > entry.requestId && receivedAt >= entry.offeredAt && now() <= entry.deadline && (entry.source == null || entry.source == source)
        }
    }
    fun clear() { pending = null }
}
