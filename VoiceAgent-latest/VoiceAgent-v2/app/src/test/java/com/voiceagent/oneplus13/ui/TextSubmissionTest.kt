package com.voiceagent.oneplus13.ui

import org.junit.Assert.*
import org.junit.Test

class TextSubmissionTest {
    @Test fun disconnectedRouteKeepsDraftAndDoesNotSend() {
        var draft = "  вопрос  "; var calls = 0
        if (submitTextIfReady(draft, { false }) { calls++ }) draft = ""
        assertEquals("  вопрос  ", draft); assertEquals(0, calls)
    }
    @Test fun connectedRouteSendsOnceAndAllowsDraftClear() {
        var draft = "  вопрос  "; val sent = mutableListOf<String>()
        if (submitTextIfReady(draft, { true }, sent::add)) draft = ""
        assertEquals(listOf("вопрос"), sent); assertEquals("", draft)
    }
    @Test fun readinessIsCheckedAtSubmissionNotCompositionTime() {
        var ready = false; val sent = mutableListOf<String>()
        assertFalse(submitTextIfReady("вопрос", { ready }, sent::add))
        ready = true
        assertTrue(submitTextIfReady("вопрос", { ready }, sent::add)); assertEquals(1, sent.size)
    }
    @Test fun blankInputIsNotDispatched() {
        assertFalse(submitTextIfReady("  ", { true }) { error("must not send") })
    }
}
