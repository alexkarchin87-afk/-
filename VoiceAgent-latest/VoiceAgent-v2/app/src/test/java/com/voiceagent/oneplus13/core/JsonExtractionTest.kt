package com.voiceagent.oneplus13.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonExtractionTest {

    @Test fun fencedJsonIsUnwrapped() {
        assertEquals("{\"a\":1}", extractJsonObject("```json\n{\"a\":1}\n```"))
    }

    @Test fun trailingTextIsCut() {
        assertEquals("{\"a\":1}", extractJsonObject("{\"a\":1} Готово!"))
    }

    @Test fun bracesInsideStringsAreIgnored() {
        val json = "{\"code\": \"fun x() { return }\", \"n\": 2}"
        assertEquals(json, extractJsonObject("Вот проект: $json — всё."))
    }

    @Test fun escapedQuotesAreHandled() {
        val json = "{\"s\": \"он сказал \\\"}\\\" и ушёл\"}"
        assertEquals(json, extractJsonObject(json))
    }

    @Test fun noJsonGivesNull() {
        assertNull(extractJsonObject("просто текст"))
    }
}
