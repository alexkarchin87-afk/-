package com.voiceagent.oneplus13.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Actual operation lifetimes, not UI timers. Nested/concurrent owners cannot clear another operation. */
object VoiceRuntime {
    enum class Phase { IDLE, LISTENING, PROCESSING, SPEAKING }
    private val lock = Any()
    private val active = mutableMapOf<Any, Phase>()
    private val mutablePhase = MutableStateFlow(Phase.IDLE)
    val phase = mutablePhase.asStateFlow()
    suspend fun <T> during(phase: Phase, block: suspend () -> T): T {
        val owner = Any()
        synchronized(lock) { active[owner] = phase; update() }
        try { return block() }
        finally { synchronized(lock) { active.remove(owner); update() } }
    }
    private fun update() { mutablePhase.value = active.values.maxByOrNull { it.ordinal } ?: Phase.IDLE }
}
