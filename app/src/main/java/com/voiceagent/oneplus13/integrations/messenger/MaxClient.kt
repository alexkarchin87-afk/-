package com.voiceagent.oneplus13.integrations.messenger

import android.content.Context
import com.voiceagent.oneplus13.BuildConfig
import com.voiceagent.oneplus13.core.security.SecretsStore
import com.voiceagent.oneplus13.integrations.messenger.models.MessengerPlatform
import com.voiceagent.oneplus13.integrations.messenger.models.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the MAX messenger Bot API (community/service-token based, same shape
 * as VK's Bot API long-poll). Fill in the exact endpoint from your bot's dashboard —
 * the field names below follow the common {token, community_id} pattern; adjust
 * `base` if MAX documents a different host for your account.
 */
class MaxClient(
    context: Context? = null,
    serviceToken: String? = null,
    private val communityId: String = BuildConfig.MAX_COMMUNITY_ID,
    private val base: String = "https://api.max.ru/bot"
) {
    private val serviceToken: String = serviceToken
        ?: context?.let { SecretsStore(it) }?.getOrDefault(SecretsStore.KEY_MAX_SERVICE_TOKEN, BuildConfig.MAX_SERVICE_TOKEN)
        ?: BuildConfig.MAX_SERVICE_TOKEN
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    suspend fun sendMessage(chatId: String, text: String) = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("access_token", serviceToken)
            .add("community_id", communityId)
            .add("chat_id", chatId)
            .add("message", text)
            .build()
        val request = Request.Builder().url("$base/messages.send").post(formBody).build()
        http.newCall(request).execute().use { it.isSuccessful }
    }

    /** Polls the bot's long-poll server for new events. Requires calling messages.getLongPollServer
     *  once and caching the returned server/key/ts per MAX's docs — stubbed here as a single call
     *  you should adapt to your account's exact long-poll contract. */
    suspend fun pollUpdates(): List<Msg> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base/messages.getUpdates?access_token=$serviceToken&community_id=$communityId")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            parseUpdates(resp.body?.string().orEmpty())
        }
    }

    private fun parseUpdates(json: String): List<Msg> {
        return try {
            val root = JSONObject(json)
            val updates = root.optJSONArray("updates") ?: return emptyList()
            (0 until updates.length()).mapNotNull { i ->
                val u = updates.getJSONObject(i)
                val text = u.optString("text")
                if (text.isBlank()) return@mapNotNull null
                Msg(
                    platform = MessengerPlatform.MAX,
                    chatId = u.optString("chat_id"),
                    senderName = u.optString("sender_name"),
                    text = text
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
