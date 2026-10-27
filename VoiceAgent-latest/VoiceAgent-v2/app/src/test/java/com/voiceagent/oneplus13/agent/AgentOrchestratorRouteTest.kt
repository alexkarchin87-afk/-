package com.voiceagent.oneplus13.agent

import android.content.Context
import android.util.Log
import com.voiceagent.oneplus13.core.*
import com.voiceagent.oneplus13.core.security.SecretsStore
import com.voiceagent.oneplus13.lecture.LectureCommands
import com.voiceagent.oneplus13.system.SystemController
import com.voiceagent.oneplus13.workflow.WorkflowStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.mockito.Mockito.*
import org.mockito.MockedConstruction
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

/** Runs the real orchestrator; only platform/storage/LLM/TTS boundaries are mocked. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentOrchestratorRouteTest {
    private val resources=mutableListOf<AutoCloseable>()
    private lateinit var systems:MockedConstruction<SystemController>
    private lateinit var workflows:MockedConstruction<WorkflowStorage>
    private lateinit var llm:LLMClient
    private lateinit var tts:TTSEngine
    @Before fun setup() {
        resources += mockStatic(Log::class.java)
        systems=mockConstruction(SystemController::class.java) { mock,_ ->
            `when`(mock.openAppResult(anyString())).thenReturn(SystemController.AppOpenResult.REQUESTED)
        };resources+=systems
        resources+=mockConstruction(LectureCommands::class.java)
        resources+=mockConstruction(SecretsStore::class.java)
        resources+=mockConstruction(UserMemory::class.java){mock,_->`when`(mock.factsBlock()).thenReturn("")}
        resources+=mockConstruction(FeatureRequests::class.java)
        workflows=mockConstruction(WorkflowStorage::class.java);resources+=workflows
        llm=mock(LLMClient::class.java);tts=mock(TTSEngine::class.java)
    }
    @After fun cleanup(){resources.asReversed().forEach{it.close()}}
    private fun TestScope.agent()=AgentOrchestrator(mock(Context::class.java),llm,tts,backgroundScope)
    private suspend fun reply(text:String){`when`(llm.complete(anyString(),anyList(),anyString(),anyBoolean())).thenReturn(text)}

    @Test fun offlineLocalRequestRequiresConfirmationAndExecutesOnce()=runTest {
        reply("Обычный ответ")
        val agent=agent();agent.handleTranscript("открой Telegram","voice")
        verifyNoInteractions(llm);verify(systems.constructed().single(),never()).openAppResult(anyString())
        agent.handleTranscript("да","voice");agent.handleTranscript("да","voice")
        verify(systems.constructed().single(),times(1)).openAppResult("org.telegram.messenger")
        verify(tts).speak("Запрос на открытие передан Android. Проверьте, появилось ли приложение.")
        assertEquals(VoiceRuntime.Phase.IDLE,VoiceRuntime.phase.value)
    }
    @Test fun wrongSourceCannotConfirmVoiceAction()=runTest {
        reply("Ответ");val agent=agent();agent.handleTranscript("открой Telegram","voice");agent.handleTranscript("да","text")
        verify(systems.constructed().single(),never()).openAppResult(anyString())
    }
    @Test fun modelExampleIsSpokenWithoutExecutingOrProposing()=runTest {
        val text="Пример: {\"action\":\"open_app\",\"params\":{\"package\":\"org.telegram.messenger\"}}"
        reply(text);val agent=agent();agent.handleTranscript("дай пример","text")
        verify(tts).speak(text);verify(systems.constructed().single(),never()).openAppResult(anyString())
    }
    @Test fun failedLaunchIsReportedWithoutFalseSuccess()=runTest {
        val agent=agent();`when`(systems.constructed().single().openAppResult(anyString())).thenReturn(SystemController.AppOpenResult.NOT_FOUND)
        agent.handleTranscript("открой Telegram");agent.handleTranscript("да")
        verify(tts).speak("Не нашёл однозначно подходящее приложение. Проверьте, установлено ли оно.")
    }
    @Test fun offlineFailureDoesNotPoisonNextCommand()=runTest {
        doAnswer{throw java.io.IOException("synthetic offline")}.`when`(llm).complete(anyString(),anyList(),anyString(),anyBoolean())
        val agent=agent();agent.handleTranscript("расскажи историю")
        agent.handleTranscript("открой Telegram");agent.handleTranscript("да")
        verify(systems.constructed().single(),times(1)).openAppResult(anyString())
        assertEquals(VoiceRuntime.Phase.IDLE,VoiceRuntime.phase.value)
    }
    @Test fun lateReplyAfterCancellationCannotSpeakOrOfferAction()=runTest {
        var pending:Continuation<String>?=null
        doAnswer{inv->@Suppress("UNCHECKED_CAST") pending=inv.rawArguments.last() as Continuation<String>;COROUTINE_SUSPENDED}
            .`when`(llm).complete(anyString(),anyList(),anyString(),anyBoolean())
        val agent=agent();val task=launch{agent.handleTranscript("запрос")};runCurrent();assertNotNull(pending)
        task.cancel();pending!!.resume("""{"action":"open_app","params":{"package":"org.telegram.messenger"}}""")
        task.join();verifyNoInteractions(tts);verify(systems.constructed().single(),never()).openAppResult(anyString())
        assertEquals(VoiceRuntime.Phase.IDLE,VoiceRuntime.phase.value)
    }
    @Test fun closeRejectsLateReplyEvenFromUncooperativeTransport()=runTest {
        var pending:Continuation<String>?=null
        doAnswer{inv->@Suppress("UNCHECKED_CAST") pending=inv.rawArguments.last() as Continuation<String>;COROUTINE_SUSPENDED}
            .`when`(llm).complete(anyString(),anyList(),anyString(),anyBoolean())
        val agent=agent();val task=launch{agent.handleTranscript("запрос")};runCurrent();agent.close();pending!!.resume("late reply");task.join()
        verifyNoInteractions(tts);assertEquals(VoiceRuntime.Phase.IDLE,VoiceRuntime.phase.value)
    }
    @Test fun ttsFailureDoesNotLeavePendingConfirmation()=runTest {
        reply("Ответ");var count=0
        doAnswer{if(count++==0)throw IllegalStateException("synthetic TTS error");Unit}.`when`(tts).speak(anyString())
        val agent=agent();agent.handleTranscript("открой Telegram");agent.handleTranscript("да")
        verify(systems.constructed().single(),never()).openAppResult(anyString())
    }
    @Test fun operationTimeoutReturnsToListeningCallerAndAllowsNextCommand()=runTest {
        val timeout=try { withTimeout(1){delay(10)};error("expected timeout") }
            catch(e:TimeoutCancellationException){e}
        doAnswer{throw timeout}.`when`(llm).complete(anyString(),anyList(),anyString(),anyBoolean())
        val agent=agent();agent.handleTranscript("расскажи историю")
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(VoiceRuntime.Phase.IDLE,VoiceRuntime.phase.value)
        agent.handleTranscript("открой Telegram");agent.handleTranscript("да")
        verify(systems.constructed().single(),times(1)).openAppResult(anyString())
    }
    @Test fun validModelActionIsOnlyExecutedAfterFreshConfirmation()=runTest {
        reply("""{"action":"open_app","params":{"package":"org.telegram.messenger"}}""")
        val agent=agent();agent.handleTranscript("запусти мессенджер","voice")
        verify(systems.constructed().single(),never()).openAppResult(anyString())
        agent.handleTranscript("да","voice")
        verify(systems.constructed().single(),times(1)).openAppResult("org.telegram.messenger")
    }
    @Test fun configuredWorkflowRetainsPriorityOverLocalTelegramShortcut()=runTest {
        val agent=agent()
        `when`(workflows.constructed().single().findByTriggerIn("открой Telegram"))
            .thenReturn(com.voiceagent.oneplus13.workflow.Workflow(name="Мой сценарий",triggerPhrase="открой Telegram"))
        agent.handleTranscript("открой Telegram")
        verify(tts).speak("Подтвердите действие run_workflow (name: Мой сценарий). Скажите да или нет.")
        verify(systems.constructed().single(),never()).openAppResult(anyString());verifyNoInteractions(llm)
    }

    private suspend fun TestScope.checkLateAction(actionJson: String, lateReply: String, cancel: Boolean = false) {
        val stores = mockConstruction(com.voiceagent.oneplus13.devassist.ProjectStorage::class.java)
        val notes = mockConstruction(com.voiceagent.oneplus13.notes.NotesStorage::class.java)
        try {
            reply(actionJson)
            val agent = agent()
            `when`(systems.constructed().single().readScreen()).thenReturn(listOf("synthetic screen"))
            agent.handleTranscript("запрос")
            var pending: Continuation<String>? = null
            doAnswer { inv ->
                @Suppress("UNCHECKED_CAST")
                pending = inv.rawArguments.last() as Continuation<String>
                COROUTINE_SUSPENDED
            }.`when`(llm).complete(anyString(), anyList(), anyString(), anyBoolean())
            val task = launch { agent.handleTranscript("да") }
            runCurrent()
            assertNotNull("Confirmed action must reach its asynchronous operation", pending)
            clearInvocations(tts)
            if (cancel) task.cancel() else agent.close()
            pending!!.resume(lateReply)
            task.join()
            verifyNoInteractions(tts)
            assertFalse(mockingDetails(stores.constructed().single()).invocations.any { it.method.name == "save" })
            verify(notes.constructed().single(), never()).save(anyString(), anyString())
            assertEquals(VoiceRuntime.Phase.IDLE, VoiceRuntime.phase.value)
        } finally { notes.close(); stores.close() }
    }

    @Test fun closedAgentDropsLateScreenDescription() = runTest {
        checkLateAction("""{"action":"describe_screen","params":{}}""", "late screen answer")
    }
    @Test fun cancelledActionDropsLateScreenIdentification() = runTest {
        checkLateAction("""{"action":"identify_on_screen","params":{}}""", "late identification", cancel = true)
    }
    @Test fun closedAgentDoesNotSaveLateGeneratedProject() = runTest {
        checkLateAction("""{"action":"generate_code","params":{"spec":"hello"}}""",
            """{"project_name":"demo","files":[{"path":"main.py","content":"print('hello')"}],"run_instructions":"test"}""")
    }
    @Test fun closedAgentDoesNotSaveLateNotes() = runTest {
        checkLateAction("""{"action":"notes_build","params":{"topic":"test","sources":"synthetic"}}""", "late notes")
    }
    @Test fun closedAgentDoesNotSpeakLateTransportFailure() = runTest {
        var pending: Continuation<String>? = null
        doAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            pending = inv.rawArguments.last() as Continuation<String>
            COROUTINE_SUSPENDED
        }.`when`(llm).complete(anyString(), anyList(), anyString(), anyBoolean())
        val agent = agent()
        val task = launch { agent.handleTranscript("запрос") }
        runCurrent(); assertNotNull(pending)
        agent.close()
        pending!!.resumeWith(Result.failure(java.io.IOException("synthetic late failure")))
        task.join()
        verifyNoInteractions(tts)
        assertEquals(VoiceRuntime.Phase.IDLE, VoiceRuntime.phase.value)
    }
    @Test fun activeAgentStillSavesAndSpeaksNotes() = runTest {
        val notes = mockConstruction(com.voiceagent.oneplus13.notes.NotesStorage::class.java)
        try {
            reply("""{"action":"notes_build","params":{"topic":"test","sources":"synthetic"}}""")
            val agent = agent(); agent.handleTranscript("запрос")
            reply("completed notes"); agent.handleTranscript("да")
            verify(notes.constructed().single()).save("test", "completed notes")
            verify(tts).speak("Готово, конспект \"test\" сохранён. Можешь попросить дополнить его позже.")
        } finally { notes.close() }
    }
}
