package com.voiceagent.oneplus13.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CancellableHttpTest {
    @Test fun cancellationCancelsTheActualCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient()
            val call = client.newCall(Request.Builder().url(server.url("/")).build())
            val task = launch(Dispatchers.IO) { call.awaitPayload() }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(2000) { task.cancelAndJoin() }
            assertTrue(call.isCanceled())
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun retryTransientThenSucceed() = runBlocking {
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            attempts++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (attempts < 3) 503 else 200).message("test")
                .body(ResponseBody.create(null, "answer")).build()
        }.build()
        assertEquals("answer", executeLlmRequest(client, Request.Builder().url("https://example.invalid").build(), 2) { it })
        assertEquals(3, attempts)
        client.dispatcher.executorService.shutdown()
    }
    @Test fun authenticationFailureDoesNotRetryOrExposeBody() = runBlocking {
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            attempts++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("test")
                .body(ResponseBody.create(null, "synthetic-private-response")).build()
        }.build()
        try {
            executeLlmRequest(client, Request.Builder().url("https://example.invalid").build(), 2) { it }
            fail("must fail")
        } catch (e: LlmHttpException) {
            assertEquals(401, e.status)
            assertFalse(e.message!!.contains("synthetic-private"))
        }
        assertEquals(1, attempts)
        client.dispatcher.executorService.shutdown()
    }
    @Test fun totalBudgetCancelsBlockedCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client=OkHttpClient();var timedOut=false
            val task=launch(Dispatchers.IO){
                try { executeLlmRequest(client,Request.Builder().url(server.url("/")).build(),2,timeoutMillis=1000){it} }
                catch(_:TimeoutCancellationException){timedOut=true}
            }
            assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))
            withTimeout(3000){task.join()};assertTrue(timedOut)
            withTimeout(2000){while(client.dispatcher.runningCallsCount()>0)delay(10)}
            assertEquals(0,client.dispatcher.runningCallsCount())
            client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
        }
    }
}
