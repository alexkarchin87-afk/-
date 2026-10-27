package com.voiceagent.oneplus13.agent

import org.junit.Assert.*
import org.junit.Test

class ActionSafetyTest {
    @org.junit.Test fun lectureRetryAcceptsNoArbitraryFileOrSession() {
        org.junit.Assert.assertTrue(ActionPolicy.valid("lecture_retry_summary", emptyMap()))
        org.junit.Assert.assertFalse(ActionPolicy.valid("lecture_retry_summary", mapOf("path" to "../file")))
        org.junit.Assert.assertFalse(ActionPolicy.valid("lecture_retry_summary", mapOf("session_id" to "other")))
    }

    private val action = """{"action":"open_app","params":{"package":"Spotify"}}"""
    @Test fun examplesAndCodeFencesAreNotCommands() {
        for (text in listOf("Пример: $action", "$action конец", "```json\n$action\n```", "$action $action"))
            assertNull(text, tryParseAction(text))
    }
    @Test fun internalSecretActionIsNotAProposal() {
        assertNull(tryParseAction("""{"action":"apply_captured_token","params":{"project":"test"}}"""))
    }
    @Test fun validatesKnownParamsAndTypes() {
        for (text in listOf(
            """{"action":"open_app","params":{"package":42}}""",
            """{"action":"open_app","params":{"package":"test","extra":"x"}}""",
            """{"action":"unknown","params":{}}""",
            """{"action":"open_app","params":{}}""",
            """{"action":"set_volume","params":{"percent":"101"}}"""
        )) assertNull(text, tryParseAction(text))
        assertEquals("open_app", tryParseAction(action)?.first)
    }
    @Test fun blocksNonWebUrls() {
        for (url in listOf("file:///data/x", "intent://x", "javascript:alert(1)", "https://user@host/x"))
            assertFalse(ActionPolicy.valid("open_url", mapOf("url" to url)))
        assertTrue(ActionPolicy.valid("open_url", mapOf("url" to "https://example.org")))
    }
    @Test fun affirmativeMustBeStandalone() {
        assertNull(classifyYesNo("да расскажи про погоду"))
        assertEquals(false, classifyYesNo("да, но не отправляй"))
    }
    @Test fun confirmationIsBoundToSourceAndRequestAndSingleUse() {
        val slot = ConfirmationSlot<String> { 10 }
        slot.offer("payload", 1, "voice", 100)
        assertNull(slot.take(2, "text"))
        assertNull(slot.take(3, "voice"))
        slot.offer("payload", 3, "voice", 100)
        assertEquals("payload", slot.take(4, "voice"))
        assertNull(slot.take(5, "voice"))
        slot.offer("payload", 5, "voice", 100)
        assertNull(slot.take(5, "voice"))
    }
    @Test fun expiredAndClearedConfirmationsCannotExecute() {
        var now = 0L
        val slot = ConfirmationSlot<String> { now }
        slot.offer("send", 1, "voice", 100)
        now = 101
        assertNull(slot.take(2, "voice"))
        slot.offer("send", 2, null, 100)
        slot.clear()
        assertNull(slot.take(3, "text"))
    }
    @Test fun queuedYesBeforeQuestionCannotAuthorizeFutureAction() {
        val slot = ConfirmationSlot<String> { 100 }
        slot.offer("send", 1, "text", 100)
        assertNull(slot.take(2, "text", receivedAt = 90))
        assertNull(slot.take(3, "text", receivedAt = 110))
    }
}
