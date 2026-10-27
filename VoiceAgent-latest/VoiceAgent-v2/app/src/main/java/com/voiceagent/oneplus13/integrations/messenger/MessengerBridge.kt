package com.voiceagent.oneplus13.integrations.messenger

import android.util.Log
import com.voiceagent.oneplus13.core.AgentEvent
import com.voiceagent.oneplus13.core.AgentEventBus
import com.voiceagent.oneplus13.integrations.messenger.models.MessengerPlatform
import com.voiceagent.oneplus13.integrations.messenger.models.Msg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Unifies Telegram/MAX/WhatsApp under one interface: publishes every inbound
 * message onto AgentEventBus (so AgentOrchestrator or the lecture module can react
 * to "read me my messages"), and routes outbound replies to the right client.
 */
class MessengerBridge internal constructor(
    private val telegram: TelegramClient,
    private val max: MaxClient,
    private val whatsApp: WhatsAppClient?,
    private val scope: CoroutineScope
) {
    constructor(telegram: TelegramClient, max: MaxClient, whatsApp: WhatsAppClient? = null) :
        this(telegram, max, whatsApp, CoroutineScope(SupervisorJob()))

    private var telegramOffset = 0L
    @Volatile private var polling = false

    private companion object {
        const val TAG = "VA_TELEGRAM"
        const val POLL_ERROR_DELAY_MS = 5_000L
    }

    @Synchronized fun startPolling() {
        if (polling) return
        polling = true
        Log.i(TAG, "polling=start")
        scope.launch { pollTelegramLoop() }
        scope.launch { pollMaxLoop() }
    }

    @Synchronized fun stopPolling() {
        polling = false
        scope.coroutineContext[Job]?.cancelChildren()
        Log.i(TAG, "polling=stop")
    }

    private suspend fun pollTelegramLoop() {
        while (polling) {
            // Сетевой сбой раньше не ловился: исключение из корутины без обработчика валило
            // приложение. Теперь — пауза и повтор (и без горячего цикла при мгновенных ошибках).
            try {
                val (newOffset, msgs) = telegram.pollUpdates(telegramOffset)
                currentCoroutineContext().ensureActive()
                msgs.forEach { publishInbound(it) }
                // Do not acknowledge a batch whose publication was cancelled by stopPolling.
                // Replaying a partially published batch on restart is safer than losing it.
                currentCoroutineContext().ensureActive()
                telegramOffset = newOffset
                Log.d(TAG, "poll=ok messages=${msgs.size}")
                delay(250) // guard even an upstream that returns immediate empty results
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramPollException) {
                val retryMs = maxOf(POLL_ERROR_DELAY_MS, e.retryAfterSeconds * 1000,
                    if (e.status in setOf(401, 403, 409)) 60_000L else 0L)
                val reason = when (e.status) {
                    401 -> "unauthorized"
                    403 -> "forbidden"
                    409 -> "webhook_or_other_poller_conflict"
                    429 -> "rate_limited"
                    else -> "http_error"
                }
                Log.w(TAG, "poll=failed status=${e.status} reason=$reason retry_ms=$retryMs")
                delay(retryMs)
            } catch (e: Exception) {
                // Exception messages/stack traces may contain a bot token or a request URL.
                Log.w(TAG, "poll=failed reason=transport_or_parse retry_ms=$POLL_ERROR_DELAY_MS")
                delay(POLL_ERROR_DELAY_MS)
            }
        }
    }

    private suspend fun pollMaxLoop() {
        while (polling) {
            try {
                max.pollUpdates().forEach { publishInbound(it) }
                delay(3000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(POLL_ERROR_DELAY_MS)
            }
        }
    }

    private suspend fun publishInbound(msg: Msg) {
        AgentEventBus.publish(
            AgentEvent.ActionRequested(
                action = "messenger_received",
                params = mapOf(
                    "platform" to msg.platform.name,
                    "from" to msg.senderName,
                    "chatId" to msg.chatId,
                    "text" to msg.text
                )
            )
        )
    }

    suspend fun send(platform: MessengerPlatform, chatId: String, text: String): Boolean = when (platform) {
        MessengerPlatform.TELEGRAM -> telegram.sendMessage(chatId, text)
        MessengerPlatform.MAX -> max.sendMessage(chatId, text)
        MessengerPlatform.WHATSAPP -> whatsApp?.sendMessage(chatId, text) ?: false
    }
}
