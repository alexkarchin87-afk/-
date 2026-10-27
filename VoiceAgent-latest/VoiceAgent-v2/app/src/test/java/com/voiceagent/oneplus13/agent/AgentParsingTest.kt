package com.voiceagent.oneplus13.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentParsingTest {

    @Test fun yesIsRecognized() {
        assertEquals(true, classifyYesNo("да, давай"))
        assertEquals(true, classifyYesNo("Конечно"))
    }

    @Test fun noWinsOverYes() {
        // раньше: находилось "используй" -> true
        assertEquals(false, classifyYesNo("нет, не используй"))
        assertEquals(false, classifyYesNo("не надо"))
    }

    @Test fun wordsInsideOtherWordsDoNotCount() {
        // "никогда" содержит подстроку "да", но это не согласие
        assertNull(classifyYesNo("никогда"))
        assertNull(classifyYesNo("расскажи анекдот"))
    }

    @Test fun askClaudeQueryIsExtracted() {
        assertEquals("почему небо голубое", extractAskClaudeQuery("Спроси у Клода, почему небо голубое"))
        assertEquals("что такое Kotlin", extractAskClaudeQuery("спроси Клода: что такое Kotlin"))
        assertNull(extractAskClaudeQuery("открой ютуб"))
        assertNull(extractAskClaudeQuery("спроси Клода"))
    }

    @Test fun actionJsonIsParsed() {
        val r = tryParseAction("{\"action\": \"open_app\", \"params\": {\"package\": \"Spotify\"}}")
        assertEquals("open_app", r?.first)
        assertEquals(mapOf("package" to "Spotify"), r?.second)
    }

    @Test fun grantPermissionsPhrase() {
        assertEquals(true, isGrantPermissionsRequest("Выдай себе все разрешения"))
        assertEquals(true, isGrantPermissionsRequest("настрой права"))
        assertEquals(false, isGrantPermissionsRequest("открой настройки"))
    }

    @Test fun plainTextIsNotAnAction() {
        assertNull(tryParseAction("Привет! Чем помочь?"))
    }
}
