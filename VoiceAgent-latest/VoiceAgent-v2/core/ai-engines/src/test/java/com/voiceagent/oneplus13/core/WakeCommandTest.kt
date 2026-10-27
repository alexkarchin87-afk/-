package com.voiceagent.oneplus13.core
import org.junit.Assert.*
import org.junit.Test
class WakeCommandTest {
    @Test fun continuousPhrasePreservesWholeCommand() { assertEquals("открой Telegram",commandAfterWake("Ваня, открой Telegram")) }
    @Test fun wakeAloneAndWakeWithPauseAreDistinctFromUnrelatedSpeech() {
        assertEquals("",commandAfterWake("эй Ваня!"));assertNull(commandAfterWake("Иваня открой"));assertNull(commandAfterWake("скажи Ваня открой"))
    }
    @Test fun caseAndWhitespaceDoNotLoseTail() { assertEquals("открой телеграм",commandAfterWake("  ЭЙ ВАНЯ: открой телеграм  ")) }
}
