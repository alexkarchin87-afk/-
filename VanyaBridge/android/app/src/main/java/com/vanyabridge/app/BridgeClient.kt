package com.vanyabridge.app

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BridgeClient(address: String, fingerprint: String, private val token: String = "") {
    private val base = ServerIdentity.address(address)
    private val pin = ServerIdentity.normalizeFingerprint(fingerprint)
    private val trust = object : X509TrustManager {
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("Client trust unused")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try { require(chain.isNotEmpty()); ServerIdentity.verify(chain[0], pin) }
            catch (error: Exception) { throw CertificateException("Pinned certificate rejected", error) }
        }
    }
    private val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    private val client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
        // Exact out-of-band certificate identity replaces CA/hostname trust, never trust-all.
        .hostnameVerifier { _, session ->
            runCatching { ServerIdentity.verify(session.peerCertificates[0] as X509Certificate, pin); true }.getOrDefault(false)
        }.followRedirects(false).followSslRedirects(false).connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()

    private fun request(path: String): Request.Builder = Request.Builder().url(base + path).apply {
        if (token.isNotEmpty()) header("Authorization", "Bearer $token")
    }

    suspend fun post(path: String, body: JSONObject): JSONObject = execute(request(path)
        .post(body.toString().toRequestBody("application/json".toMediaType())).build())

    suspend fun get(path: String): JSONObject = execute(request(path).build())

    private suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Нет соединения с ПК или не совпадает сертификат"))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) throw IOException("ПК отклонил запрос: HTTP ${it.code}")
                        val text = it.body?.string() ?: throw IOException("Пустой ответ ПК")
                        if (continuation.isActive) continuation.resume(JSONObject(text))
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    fun events(onSnapshot: (JSONObject) -> Unit, onClosed: () -> Unit): WebSocket =
        client.newWebSocket(request("/events").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.onSuccess(onSnapshot)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed()
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onClosed()
        })

    fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}
