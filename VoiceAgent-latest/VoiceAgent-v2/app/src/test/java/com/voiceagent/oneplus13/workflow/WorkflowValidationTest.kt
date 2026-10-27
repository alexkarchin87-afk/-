package com.voiceagent.oneplus13.workflow

import org.junit.Assert.*
import org.junit.Test

class WorkflowValidationTest {
    private fun graph(ids: List<String>, vararg edges: Pair<String, String>) = Workflow(name = "test",
        blocks = ids.map { WorkflowBlock(id = it, type = BlockType.SPEAK) }.toMutableList(),
        connections = edges.map { WorkflowConnection(it.first, it.second) }.toMutableList())
    private fun invalid(workflow: Workflow) { assertThrows(IllegalArgumentException::class.java) { workflow.executionOrder() } }
    @Test fun linearGraphPreservesOrder() { assertEquals(listOf("a", "b", "c"), graph(listOf("c", "b", "a"), "a" to "b", "b" to "c").executionOrder().map { it.id }) }
    @Test fun cycleIsFailure() { invalid(graph(listOf("a", "b"), "a" to "b", "b" to "a")) }
    @Test fun disconnectedBlocksAreFailure() { invalid(graph(listOf("a", "b"))) }
    @Test fun brokenEdgeIsFailure() { invalid(graph(listOf("a"), "a" to "missing")) }
    @Test fun forkIsFailure() { invalid(graph(listOf("a", "b", "c"), "a" to "b", "a" to "c")) }
    @Test fun duplicateIdsAreFailure() { invalid(graph(listOf("a", "a"))) }
    @Test fun emptyGraphIsFailure() { invalid(graph(emptyList())) }
    @Test fun disconnectedCycleIsFailure() { invalid(graph(listOf("a", "b", "c"), "b" to "c", "c" to "b")) }
}
