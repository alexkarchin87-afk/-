package com.voiceagent.oneplus13.system

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.voiceagent.oneplus13.core.AgentEvent
import com.voiceagent.oneplus13.core.AgentEventBus
import com.voiceagent.oneplus13.core.security.SecretsStore
import com.voiceagent.oneplus13.integrations.messenger.TokenCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Surfaces incoming notifications (e.g. new Telegram/WhatsApp messages) to the
 * agent so it can be asked "what did I just get from Vika?" or read messages aloud.
 * Also feeds Telegram notifications into TokenCapture, so a fresh @BotFather token
 * can be picked up automatically for the last project AgentOrchestrator generated.
 *
 * "Ответь [Имя] [Текст]" without opening the messenger works by re-using the
 * SAME quick-reply action the messenger itself already put on its notification
 * (the same mechanism Wear OS / Android Auto use) — VoiceAgent never talks to
 * Telegram/WhatsApp servers directly and never needs their API keys for this.
 *
 * Requires the user to grant Notification Access once in system settings.
 */
class NotificationReader : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob())
    private var tokenCapture: TokenCapture? = null

    /** Последнее уведомление с доступным быстрым ответом на отправителя (ключ —
     *  заголовок уведомления в нижнем регистре, обычно это имя собеседника). */
    private val replyableBySender = java.util.concurrent.ConcurrentHashMap<String, StatusBarNotification>()

    override fun onCreate() {
        super.onCreate()
        tokenCapture = runCatching { TokenCapture(SecretsStore(applicationContext)) }.getOrNull()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        replyableBySender.clear()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        if (findReplyAction(sbn) != null) {
            replyableBySender[sbn.key] = sbn
        } else { replyableBySender.remove(sbn.key) }

        scope.launch {
            val handledAsToken = try {
                val capture = tokenCapture ?: TokenCapture(SecretsStore(applicationContext)).also { tokenCapture = it }
                capture.onNotification(
                    packageName = sbn.packageName,
                    title = title,
                    text = text,
                    forProjectName = PendingProject.awaitingTokenFor
                )
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { return@launch } // Do not treat a token as ordinary notification text after a storage error.
            if (handledAsToken) return@launch

            NotificationBuffer.record(NotificationEntry(sbn.packageName, title, text))

            AgentEventBus.publish(
                AgentEvent.ActionRequested(
                    action = "notification_received",
                    params = mapOf(
                        "package" to sbn.packageName,
                        "title" to title,
                        "text" to text
                    )
                )
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        replyableBySender.entries.removeAll { it.value.key == sbn.key }
    }

    /** "Ответь Вике привет" — находит уведомление от отправителя, чьё имя
     *  содержит [senderNameQuery], и отправляет [text] через собственное
     *  действие "быстрый ответ" этого уведомления (RemoteInput), в точности
     *  как это делает системная плашка "Ответить" в шторке. Ничего не
     *  открывает на экране. Возвращает false, если такого уведомления с
     *  быстрым ответом сейчас нет (например, уже прочитано/смахнуто).
     */
    data class ReplyTarget(val key: String, val postTime: Long, val label: String)

    fun resolveReplyTarget(senderName: String): ReplyTarget? {
        val query = senderName.trim()
        if (query.isBlank()) return null
        val matches = replyableBySender.values.filter {
            it.notification.extras.getCharSequence("android.title")?.toString()?.trim()
                ?.equals(query, ignoreCase = true) == true &&
                System.currentTimeMillis() - it.postTime in 0..MAX_REPLY_AGE_MS
        }
        val sbn = matches.singleOrNull() ?: return null // never pick an arbitrary namesake/chat
        return ReplyTarget(sbn.key, sbn.postTime, "$query (${sbn.packageName})")
    }

    /** Called only with the key/version shown in the confirmed proposal; a changed notification fails closed. */
    fun replyToKey(key: String, postTime: Long, text: String): Boolean {
        if (text.isBlank()) return false
        val sbn = replyableBySender[key] ?: return false
        if (sbn.postTime != postTime || System.currentTimeMillis() - postTime !in 0..MAX_REPLY_AGE_MS) return false
        val action = findReplyAction(sbn) ?: return false
        val inputs = action.remoteInputs?.filter { it.allowFreeFormInput }?.toTypedArray() ?: return false
        if (inputs.isEmpty() || !replyableBySender.remove(key, sbn)) return false
        val intent = Intent()
        val bundle = android.os.Bundle()
        inputs.forEach { bundle.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, bundle)
        return try { action.actionIntent.send(applicationContext, 0, intent); true }
        catch (_: PendingIntent.CanceledException) { false }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        replyableBySender.clear()
        super.onListenerDisconnected()
    }

    private fun findReplyAction(sbn: StatusBarNotification) =
        sbn.notification.actions?.firstOrNull { it.remoteInputs?.any { ri -> ri.allowFreeFormInput } == true }

    companion object {
        private const val MAX_REPLY_AGE_MS = 10 * 60 * 1000L
        /** Живой экземпляр запущенного слушателя — так же, как AccessibilityAgent.instance,
         *  чтобы AgentOrchestrator мог дёрнуть replyTo() без биндинга сервиса. */
        @Volatile var instance: NotificationReader? = null
    }
}

/** Tiny process-wide handoff: AgentOrchestrator sets this right after it generates a
 *  project that needs a bot token, so NotificationReader knows which project a
 *  freshly-seen BotFather token belongs to. Cleared once the token is consumed. */
object PendingProject {
    @Volatile var awaitingTokenFor: String? = null
}
