package com.voiceagent.oneplus13.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpPayload(val status: Int, val body: String)

/** Read and close the response before resuming; cancellation also interrupts a blocked body read. */
suspend fun Call.awaitPayload(): HttpPayload = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val payload = response.use { HttpPayload(it.code, it.body?.string().orEmpty()) }
                if (continuation.isActive) continuation.resume(payload)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}

internal class LlmHttpException(val status: Int) : IllegalStateException(when (status) {
    400, 404, 422 -> "LLM: проверьте model ID и параметры API (HTTP $status)"
    401, 403 -> "LLM: ключ не принят или нет доступа (HTTP $status)"
    429 -> "LLM: превышен лимит запросов (HTTP 429)"
    else -> "LLM: HTTP $status"
})

internal suspend fun executeLlmRequest(
    http: OkHttpClient, request: Request, maxRetries: Int, timeoutMillis: Long = 210_000L, extract: (String) -> String?
): String = withTimeout(timeoutMillis) {
    require(maxRetries in 0..5)
    repeat(maxRetries + 1) { attempt ->
        try {
            val payload = http.newCall(request).awaitPayload()
            if (payload.status in 200..299) {
                return@withTimeout extract(payload.body)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Empty LLM response")
            }
            if ((payload.status != 429 && payload.status !in 500..599) || attempt == maxRetries)
                throw LlmHttpException(payload.status)
        } catch (e: IOException) {
            if (attempt == maxRetries) throw e
        }
        delay(500L * (attempt + 1))
    }
    error("Unreachable")
}
