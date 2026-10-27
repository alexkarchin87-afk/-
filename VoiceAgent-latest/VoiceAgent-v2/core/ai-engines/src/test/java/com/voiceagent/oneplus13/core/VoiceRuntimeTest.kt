package com.voiceagent.oneplus13.core
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRuntimeTest {
    @Test fun processingSpeechThenIdleFollowRealCompletion()=runTest {
        assertEquals(VoiceRuntime.Phase.IDLE,VoiceRuntime.phase.value)
        VoiceRuntime.during(VoiceRuntime.Phase.PROCESSING) {
            assertEquals(VoiceRuntime.Phase.PROCESSING,VoiceRuntime.phase.value)
            VoiceRuntime.during(VoiceRuntime.Phase.SPEAKING){delay(100_000);assertEquals(VoiceRuntime.Phase.SPEAKING,VoiceRuntime.phase.value)}
            assertEquals(VoiceRuntime.Phase.PROCESSING,VoiceRuntime.phase.value)
        }
        assertEquals(VoiceRuntime.Phase.IDLE,VoiceRuntime.phase.value)
    }
    @Test fun cancellingOneOwnerDoesNotClearAnother()=runTest {
        val one=launch{VoiceRuntime.during(VoiceRuntime.Phase.LISTENING){awaitCancellation()}}
        val two=launch{VoiceRuntime.during(VoiceRuntime.Phase.PROCESSING){awaitCancellation()}}
        runCurrent();one.cancelAndJoin();assertEquals(VoiceRuntime.Phase.PROCESSING,VoiceRuntime.phase.value)
        two.cancelAndJoin();assertEquals(VoiceRuntime.Phase.IDLE,VoiceRuntime.phase.value)
    }
    @Test fun exceptionClearsPhase()=runTest {
        try{VoiceRuntime.during(VoiceRuntime.Phase.SPEAKING){error("synthetic")}}catch(_:IllegalStateException){}
        assertEquals(VoiceRuntime.Phase.IDLE,VoiceRuntime.phase.value)
    }
}
