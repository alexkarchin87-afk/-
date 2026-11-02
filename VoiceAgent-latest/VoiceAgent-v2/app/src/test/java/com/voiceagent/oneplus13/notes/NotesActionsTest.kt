package com.voiceagent.oneplus13.notes

import com.voiceagent.oneplus13.core.TTSEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

@OptIn(ExperimentalCoroutinesApi::class)
class NotesActionsTest {
    private val builder = mock(NotesBuilder::class.java)
    private val storage = mock(NotesStorage::class.java)
    private val tts = mock(TTSEngine::class.java)
    private var closed = false
    private val actions = NotesActions(builder, storage, tts) {
        currentCoroutineContext().ensureActive()
        if (closed) throw CancellationException("closed")
    }

    @Test fun buildThenExtendUsesLastTopicAndPreservesCompleteMarkdown() = runTest {
        `when`(builder.build("physics", listOf("source"))).thenReturn("first notes")
        actions.build(mapOf("topic" to "physics", "sources" to "source"))
        verify(storage).save("physics", "first notes")
        `when`(storage.load("physics")).thenReturn("first notes")
        `when`(builder.extend("first notes", "more")).thenReturn("complete updated notes")
        actions.extend(mapOf("addition" to "more"))
        verify(storage).save("physics", "complete updated notes")
        verify(storage, never()).latestTopic()
    }

    @Test fun freshOwnerFallsBackToPersistedLatestTopic() = runTest {
        `when`(storage.latestTopic()).thenReturn("stored")
        `when`(storage.load("stored")).thenReturn("existing")
        `when`(builder.extend("existing", "more")).thenReturn("updated")
        actions.extend(mapOf("addition" to "more"))
        verify(storage).save("stored", "updated")
    }

    @Test fun absentSourcesDoNotCallLlmOrStorage() = runTest {
        actions.build(mapOf("topic" to "empty"))
        verifyNoInteractions(builder, storage)
    }

    @Test fun failedBuildDoesNotChangeLastTopic() = runTest {
        doAnswer { throw java.io.IOException("offline") }.`when`(builder).build(anyString(), anyList())
        try { actions.build(mapOf("topic" to "failed", "sources" to "input")); fail() }
        catch (_: java.io.IOException) { }
        actions.extend(mapOf("addition" to "more"))
        verify(storage).latestTopic()
        verify(storage, never()).save(anyString(), anyString())
    }

    private suspend fun TestScope.lateExtension(cancel: Boolean) {
        `when`(storage.latestTopic()).thenReturn("saved")
        `when`(storage.load("saved")).thenReturn("old")
        var pending: Continuation<String>? = null
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            pending = invocation.rawArguments.last() as Continuation<String>
            COROUTINE_SUSPENDED
        }.`when`(builder).extend(anyString(), anyString())
        val task = launch { actions.extend(mapOf("addition" to "more")) }
        runCurrent(); assertNotNull(pending)
        clearInvocations(tts)
        if (cancel) task.cancel() else closed = true
        pending!!.resume("stale update")
        task.join()
        verify(storage, never()).save(anyString(), anyString())
        verifyNoInteractions(tts)
    }

    @Test fun cancelledExtensionCannotOverwriteSavedNotes() = runTest { lateExtension(cancel = true) }
    @Test fun closedOwnerCannotCommitLateExtension() = runTest { lateExtension(cancel = false) }
}
