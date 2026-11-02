package com.voiceagent.oneplus13.integrations.messenger

import android.util.Log
import com.voiceagent.oneplus13.core.AgentEventBus
import com.voiceagent.oneplus13.integrations.messenger.models.MessengerPlatform
import com.voiceagent.oneplus13.integrations.messenger.models.Msg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MessengerBridgeTest {
    private lateinit var logs: MockedStatic<Log>
    private lateinit var telegram: TelegramClient
    private lateinit var max: MaxClient

    @Before fun setup() {
        logs = mockStatic(Log::class.java)
        telegram = mock(TelegramClient::class.java)
        max = mock(MaxClient::class.java)
    }

    @After fun cleanup() { logs.close() }

    private fun TestScope.bridge() = MessengerBridge(
        telegram, max, null,
        CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
    )

    @Test fun oldTelegramGenerationCannotPublishOrAdvanceNewOffset() = runTest {
        val offsets = mutableListOf<Long>()
        val events = mutableListOf<com.voiceagent.oneplus13.core.AgentEvent>()
        var pending: kotlin.coroutines.Continuation<Pair<Long, List<Msg>>>? = null
        doAnswer { invocation ->
            offsets += invocation.getArgument<Long>(0)
            if (offsets.size == 1) {
                @Suppress("UNCHECKED_CAST")
                pending = invocation.rawArguments.last() as kotlin.coroutines.Continuation<Pair<Long, List<Msg>>>
                kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            } else 7L to emptyList<Msg>()
        }.`when`(telegram).pollUpdates(anyLong())
        `when`(max.pollUpdates()).thenReturn(emptyList())
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            AgentEventBus.events.collect { events += it }
        }
        val bridge = bridge()
        try {
            bridge.startPolling(); runCurrent()
            bridge.stopPolling(); bridge.startPolling(); runCurrent()
            pending!!.resumeWith(Result.success(99L to listOf(Msg(MessengerPlatform.TELEGRAM, "old", "old", "stale"))))
            runCurrent(); advanceTimeBy(250); runCurrent()
            assertEquals(listOf(0L, 0L, 7L), offsets)
            assertEquals(emptyList<com.voiceagent.oneplus13.core.AgentEvent>(), events)
        } finally { bridge.stopPolling(); collector.cancel() }
    }

    @Test fun oldMaxGenerationCannotPublishAfterRestart() = runTest {
        var pending: kotlin.coroutines.Continuation<List<Msg>>? = null
        var count = 0
        val events = mutableListOf<com.voiceagent.oneplus13.core.AgentEvent>()
        doAnswer { invocation ->
            if (count++ == 0) {
                @Suppress("UNCHECKED_CAST")
                pending = invocation.rawArguments.last() as kotlin.coroutines.Continuation<List<Msg>>
                kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            } else emptyList<Msg>()
        }.`when`(max).pollUpdates()
        `when`(telegram.pollUpdates(anyLong())).thenReturn(0L to emptyList())
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            AgentEventBus.events.collect { events += it }
        }
        val bridge = bridge()
        try {
            bridge.startPolling(); runCurrent()
            bridge.stopPolling(); bridge.startPolling(); runCurrent()
            pending!!.resumeWith(Result.success(listOf(Msg(MessengerPlatform.MAX, "old", "old", "stale"))))
            runCurrent()
            assertEquals(emptyList<com.voiceagent.oneplus13.core.AgentEvent>(), events)
        } finally { bridge.stopPolling(); collector.cancel() }
    }

    @Test fun stoppingBridgePreservesUnrelatedParentScopeWork() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val unrelated = owner.launch { awaitCancellation() }
        val bridge = MessengerBridge(telegram, max, null, owner)
        bridge.startPolling()
        bridge.stopPolling()
        org.junit.Assert.assertTrue("Bridge must cancel only its own polling jobs", unrelated.isActive)
        unrelated.cancel()
    }

    @Test fun restartDoesNotAcknowledgeBatchWhosePublicationWasCancelled() = runTest {
        val offsets = mutableListOf<Long>()
        val batch = List(40) { Msg(MessengerPlatform.TELEGRAM, "chat", "sender", "message $it") }
        doAnswer { invocation ->
            offsets += invocation.getArgument<Long>(0)
            40L to batch
        }.`when`(telegram).pollUpdates(anyLong())
        `when`(max.pollUpdates()).thenReturn(emptyList())

        // A slow subscriber fills the event bus buffer and suspends batch publication.
        var delivered = 0
        val subscriber = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            AgentEventBus.events.collect {
                delivered++
                awaitCancellation()
            }
        }
        val bridge = bridge()
        try {
            bridge.startPolling()
            runCurrent()
            assertEquals(1, delivered)
            assertEquals(listOf(0L), offsets)

            bridge.stopPolling()
            subscriber.cancel()
            runCurrent()
            bridge.startPolling()
            runCurrent()
            assertEquals(listOf(0L, 0L), offsets)
        } finally {
            bridge.stopPolling()
            subscriber.cancel()
        }
    }

    @Test fun successfulBatchAdvancesOffsetAndRepeatedStartKeepsOnePoller() = runTest {
        val offsets = mutableListOf<Long>()
        doAnswer { invocation ->
            offsets += invocation.getArgument<Long>(0)
            8L to emptyList<Msg>()
        }.`when`(telegram).pollUpdates(anyLong())
        `when`(max.pollUpdates()).thenReturn(emptyList())
        val bridge = bridge()
        try {
            bridge.startPolling()
            bridge.startPolling()
            runCurrent()
            assertEquals(listOf(0L), offsets)
            advanceTimeBy(250)
            runCurrent()
            assertEquals(listOf(0L, 8L), offsets)
            bridge.stopPolling()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(listOf(0L, 8L), offsets)
        } finally { bridge.stopPolling() }
    }

    @Test fun conflictRetriesAfterOneMinuteAndLogsOnlySafeMetadata() = runTest {
        doAnswer { throw TelegramPollException(409, 0) }.`when`(telegram).pollUpdates(anyLong())
        `when`(max.pollUpdates()).thenReturn(emptyList())
        val bridge = bridge()
        try {
            bridge.startPolling()
            runCurrent()
            verify(telegram, times(1)).pollUpdates(0L)
            logs.verify {
                Log.w("VA_TELEGRAM", "poll=failed status=409 reason=webhook_or_other_poller_conflict retry_ms=60000")
            }
            advanceTimeBy(59_999)
            runCurrent()
            verify(telegram, times(1)).pollUpdates(0L)
            advanceTimeBy(1)
            runCurrent()
            verify(telegram, times(2)).pollUpdates(0L)
        } finally { bridge.stopPolling() }
    }

    @Test fun transportFailureDoesNotLogExceptionContainingCredentials() = runTest {
        doAnswer { throw IOException("https://api.telegram.org/botSYNTHETIC/getUpdates") }.`when`(telegram).pollUpdates(anyLong())
        `when`(max.pollUpdates()).thenReturn(emptyList())
        val bridge = bridge()
        try {
            bridge.startPolling()
            runCurrent()
            logs.verify { Log.i("VA_TELEGRAM", "polling=start") }
            logs.verify { Log.w("VA_TELEGRAM", "poll=failed reason=transport_or_parse retry_ms=5000") }
            logs.verifyNoMoreInteractions()
            advanceTimeBy(4_999)
            runCurrent()
            verify(telegram, times(1)).pollUpdates(0L)
            advanceTimeBy(1)
            runCurrent()
            verify(telegram, times(2)).pollUpdates(0L)
        } finally { bridge.stopPolling() }
    }
    @Test fun startMessageIsAnInboundEventNotAnAutomaticBotReply() = runTest {
        val events = mutableListOf<com.voiceagent.oneplus13.core.AgentEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            AgentEventBus.events.collect { events += it }
        }
        `when`(telegram.pollUpdates(anyLong())).thenReturn(1L to listOf(Msg(MessengerPlatform.TELEGRAM, "chat", "sender", "/start")))
        `when`(max.pollUpdates()).thenReturn(emptyList())
        val bridge = bridge()
        try {
            bridge.startPolling(); runCurrent()
            assertEquals("messenger_received", (events.single() as com.voiceagent.oneplus13.core.AgentEvent.ActionRequested).action)
            assertEquals(0, mockingDetails(telegram).invocations.count { it.method.name == "sendMessage" })
        } finally { bridge.stopPolling(); collector.cancel() }
    }
}
