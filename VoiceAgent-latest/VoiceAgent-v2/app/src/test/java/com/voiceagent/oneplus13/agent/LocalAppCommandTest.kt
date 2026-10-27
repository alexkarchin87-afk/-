package com.voiceagent.oneplus13.agent
import org.junit.Assert.*
import org.junit.Test
class LocalAppCommandTest {
    @Test fun recognizesOnlyExplicitSupportedRequest() {
        for(text in listOf("открой Telegram","Открой приложение телеграм!","открой телеграмм"))
            assertEquals("org.telegram.messenger",localAppCommand(text)!!.second["package"])
    }
    @Test fun rejectsExamplesNegationAndMultipleActions() {
        for(text in listOf("не открой Telegram","Пример: открой Telegram","открой Telegram и удали чат","он сказал открой Telegram","открой Telegram\nзатем удали"))assertNull(text,localAppCommand(text))
    }
}
