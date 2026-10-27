package com.voiceagent.oneplus13.system

import android.content.Context
import android.content.pm.PackageManager
import com.voiceagent.oneplus13.core.LLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class ChatsDigestTest {
    private lateinit var context: Context
    private lateinit var llm: LLMClient
    @Before fun setup() {
        // Fill the bounded buffer with ignored entries to isolate tests without a production reset API.
        repeat(200) { NotificationBuffer.record(NotificationEntry("fixture", "", "", 0)) }
        context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(pm)
        `when`(pm.getApplicationInfo(anyString(), anyInt())).thenThrow(PackageManager.NameNotFoundException())
        llm = mock(LLMClient::class.java)
        NotificationBuffer.record(NotificationEntry("fixture", "synthetic title", "synthetic message", 1))
    }
    @Test fun networkFailureDoesNotConsumeNotifications() = runTest {
        val inputs = mutableListOf<String>()
        doAnswer { inv ->
            inputs += inv.getArgument<String>(2)
            if (inputs.size == 1) throw java.io.IOException("synthetic offline")
            "summary"
        }.`when`(llm).complete(anyString(), anyList(), anyString(), anyBoolean())
        val digest = ChatsDigest(context, llm)
        try { digest.summarizeSinceLastDigest(); fail("Expected offline error") }
        catch (_: java.io.IOException) { }
        assertEquals("summary", digest.summarizeSinceLastDigest())
        assertEquals(2, inputs.size)
        assertEquals(inputs[0], inputs[1])
        assertTrue(inputs[1].contains("synthetic message"))
    }
    @Test fun cancellationDoesNotConsumeNotifications() = runTest {
        var calls = 0
        doAnswer {
            if (calls++ == 0) throw CancellationException("synthetic cancellation")
            "summary"
        }.`when`(llm).complete(anyString(), anyList(), anyString(), anyBoolean())
        val digest = ChatsDigest(context, llm)
        try { digest.summarizeSinceLastDigest(); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals("summary", digest.summarizeSinceLastDigest())
        assertEquals(2, calls)
    }
    @Test fun successfulSummaryAcknowledgesOnlyItsSnapshot() = runTest {
        var calls = 0
        doAnswer { inv ->
            calls++
            if (calls == 1) {
                NotificationBuffer.record(NotificationEntry("fixture", "", "arrived during request", 1))
            } else assertTrue(inv.getArgument<String>(2).contains("arrived during request"))
            "summary"
        }.`when`(llm).complete(anyString(), anyList(), anyString(), anyBoolean())
        val digest = ChatsDigest(context, llm)
        assertEquals("summary", digest.summarizeSinceLastDigest())
        assertEquals("summary", digest.summarizeSinceLastDigest())
        assertEquals("Новых уведомлений с прошлого раза нет.", digest.summarizeSinceLastDigest())
        assertEquals(2, calls)
    }

    @Test fun equalAndEarlierTimestampsAreStillNewNotifications() = runTest {
        val inputs = mutableListOf<String>()
        doAnswer { inv -> inputs += inv.getArgument<String>(2); "summary" }
            .`when`(llm).complete(anyString(), anyList(), anyString(), anyBoolean())
        val digest = ChatsDigest(context, llm)
        digest.summarizeSinceLastDigest(); inputs.clear()
        NotificationBuffer.record(NotificationEntry("fixture", "", "same timestamp", 1))
        NotificationBuffer.record(NotificationEntry("fixture", "", "clock moved back", 0))
        assertEquals("summary", digest.summarizeSinceLastDigest())
        assertTrue(inputs.single().contains("same timestamp"))
        assertTrue(inputs.single().contains("clock moved back"))
    }
    @Test fun longNotificationTailRemainsAvailableWithoutExceedingBudget() = runTest {
        val inputs = mutableListOf<String>()
        doAnswer { inv -> inputs += inv.getArgument<String>(2); "summary" }
            .`when`(llm).complete(anyString(), anyList(), anyString(), anyBoolean())
        val digest = ChatsDigest(context, llm)
        digest.summarizeSinceLastDigest(); inputs.clear()
        val text = "A".repeat(4500) + " tail"
        NotificationBuffer.record(NotificationEntry("fixture", "title", text, 2))
        assertEquals("summary", digest.summarizeSinceLastDigest())
        assertEquals("summary", digest.summarizeSinceLastDigest())
        assertTrue(inputs.all { it.length <= 4000 })
        assertEquals("fixture: title — " + text, inputs.joinToString(""))
        assertEquals("Новых уведомлений с прошлого раза нет.", digest.summarizeSinceLastDigest())
    }
    @Test fun failedContinuationRetriesTheSameUnconsumedText() = runTest {
        val inputs = mutableListOf<String>()
        var failNext = false
        doAnswer { inv ->
            inputs += inv.getArgument<String>(2)
            if (failNext) { failNext = false; throw java.io.IOException("synthetic offline") }
            "summary"
        }.`when`(llm).complete(anyString(), anyList(), anyString(), anyBoolean())
        val digest = ChatsDigest(context, llm)
        digest.summarizeSinceLastDigest(); inputs.clear()
        NotificationBuffer.record(NotificationEntry("fixture", "", "B".repeat(5000), 2))
        digest.summarizeSinceLastDigest()
        failNext = true
        try { digest.summarizeSinceLastDigest(); fail("Expected retryable error") }
        catch (_: java.io.IOException) { }
        assertEquals("summary", digest.summarizeSinceLastDigest())
        assertEquals(3, inputs.size)
        assertEquals(inputs[1], inputs[2])
    }
    @Test fun notificationsBeyondTheFirstInputBudgetAreNotAcknowledged() = runTest {
        val inputs = mutableListOf<String>()
        doAnswer { inv -> inputs += inv.getArgument<String>(2); "summary" }
            .`when`(llm).complete(anyString(), anyList(), anyString(), anyBoolean())
        val digest = ChatsDigest(context, llm)
        digest.summarizeSinceLastDigest(); inputs.clear()
        NotificationBuffer.record(NotificationEntry("fixture", "", "C".repeat(3990), 2))
        NotificationBuffer.record(NotificationEntry("fixture", "", "later notification", 2))
        digest.summarizeSinceLastDigest()
        assertEquals("summary", digest.summarizeSinceLastDigest())
        assertTrue(inputs.last().contains("later notification"))
        assertTrue(inputs.all { it.length <= 4000 })
    }
}
