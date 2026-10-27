package com.voiceagent.oneplus13.integrations.messenger

import android.content.Context
import android.util.Log
import com.voiceagent.oneplus13.core.awaitPayload
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.voiceagent.oneplus13.BuildConfig
import com.voiceagent.oneplus13.core.security.SecretsStore
import com.voiceagent.oneplus13.integrations.messenger.models.MessengerPlatform
import com.voiceagent.oneplus13.integrations.messenger.models.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class TgSendResult(val ok: Boolean)

/**
 * Thin client for the official Telegram Bot API. Used both to send messages the
 * agent composes and, via long-polling in TelegramBotService, to receive them.
 */
class TelegramClient(context: Context? = null, botToken: String? = null) {

    private val botToken: String = botToken
        ?: context?.let { SecretsStore(it) }?.getOrDefault(SecretsStore.KEY_TELEGRAM_BOT_TOKEN, BuildConfig.TELEGRAM_BOT_TOKEN)
        ?: BuildConfig.TELEGRAM_BOT_TOKEN

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS) // long-poll friendly
        .build()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val base = "https://api.telegram.org/bot$botToken"

    init {
        Log.i("VA_TELEGRAM", "client=created token_configured=${this.botToken.isNotBlank()}")
    }

    suspend fun sendMessage(chatId: String, text: String) = withContext(Dispatchers.IO) {
        val json = org.json.JSONObject().put("chat_id", chatId).put("text", text).toString()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$base/sendMessage").post(body).build()
        http.newCall(request).awaitPayload().let {
            val sent = it.status in 200..299 && org.json.JSONObject(it.body).optBoolean("ok")
            Log.i("VA_TELEGRAM", "send status=${it.status} ok=$sent")
            sent
        }
    }

    /** Long-polls getUpdates and returns any new messages as platform-agnostic Msg objects. */
    suspend fun pollUpdates(offset: Long): Pair<Long, List<Msg>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base/getUpdates?timeout=30&offset=$offset")
            .get()
            .build()
        http.newCall(request).awaitPayload().let { resp ->
            if (resp.status !in 200..299) {
                val retryAfter = runCatching { org.json.JSONObject(resp.body)
                    .optJSONObject("parameters")?.optLong("retry_after", 0L) ?: 0L }.getOrDefault(0L)
                throw TelegramPollException(resp.status, retryAfter.coerceIn(0, 300))
            }
            val body = resp.body
            parseUpdates(body, offset)
        }
    }

    private fun parseUpdates(json: String, currentOffset: Long): Pair<Long, List<Msg>> {
        val root = org.json.JSONObject(json)
        if (!root.optBoolean("ok", false)) throw TelegramPollException(root.optInt("error_code", 500),
            (root.optJSONObject("parameters")?.optLong("retry_after", 0L) ?: 0L).coerceIn(0, 300))
        val results = root.getJSONArray("result")
        var maxUpdateId = currentOffset
        val msgs = mutableListOf<Msg>()
        for (i in 0 until results.length()) {
            val update = results.getJSONObject(i)
            maxUpdateId = maxOf(maxUpdateId, update.optLong("update_id", currentOffset) + 1)
            val message = update.optJSONObject("message") ?: continue
            val text = message.optString("text")
            if (text.isBlank()) continue
            val chat = message.optJSONObject("chat")
            val from = message.optJSONObject("from")
            msgs.add(
                Msg(
                    platform = MessengerPlatform.TELEGRAM,
                    chatId = chat?.optString("id").orEmpty(),
                    senderName = from?.optString("first_name").orEmpty(),
                    text = text
                )
            )
        }
        return maxUpdateId to msgs
    }

    private fun escapeJson(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

internal class TelegramPollException(val status: Int, val retryAfterSeconds: Long) : java.io.IOException("Telegram HTTP $status")
