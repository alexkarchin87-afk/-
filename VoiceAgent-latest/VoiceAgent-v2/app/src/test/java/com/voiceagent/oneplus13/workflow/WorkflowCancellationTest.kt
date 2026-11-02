package com.voiceagent.oneplus13.workflow

import com.voiceagent.oneplus13.core.TTSEngine
import com.voiceagent.oneplus13.system.SystemController
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.*

class WorkflowCancellationTest {
    @Test fun cancellationAtStepBoundaryPreventsSystemAction() = runTest {
        val system = mock(SystemController::class.java)
        val runner = WorkflowRunner(system, mock(TTSEngine::class.java))
        val block = WorkflowBlock(type = BlockType.OPEN_APP, params = mutableMapOf("package" to "test.app"))
        val task = launch {
            runner.run(Workflow(name = "test", blocks = mutableListOf(block))) {
                if (it is WorkflowRunner.StepResult.Started) currentCoroutineContext().cancel()
            }
        }
        task.join()
        verifyNoInteractions(system)
    }
}
