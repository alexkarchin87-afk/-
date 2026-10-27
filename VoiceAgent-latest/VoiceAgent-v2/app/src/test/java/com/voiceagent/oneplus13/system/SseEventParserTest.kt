package com.voiceagent.oneplus13.system

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SseEventParserTest {
    @Test fun terminalRequiredEvenAfterText() {
        val parser = SseEventParser()
        assertEquals("hello", parser.line("data: {\"text\":\"hello\"}"))
        assertThrows(IOException::class.java) { parser.endOfInput() }
    }
    @Test fun doneTerminatesAndRejectsLaterText() {
        val parser = SseEventParser()
        parser.line("data: [DONE]"); parser.endOfInput()
        assertNull(parser.line("data: {\"text\":\"late\"}"))
    }
    @Test fun upstreamErrorFails() {
        assertThrows(IOException::class.java) { SseEventParser().line("data: {\"error\":\"network\"}") }
    }
    @Test fun boundsResponseSize() {
        assertThrows(IOException::class.java) { SseEventParser().line("data: " + org.json.JSONObject().put("text", "x".repeat(128 * 1024 + 1))) }
    }
}
