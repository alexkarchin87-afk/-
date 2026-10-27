package com.voiceagent.oneplus13.system

import org.junit.Assert.*
import org.junit.Test

class DigestBatchTest {
    private fun record(id: Long, text: String) = RecordedNotification(id, NotificationEntry("", "", text, 0))
    @Test fun partialNotificationCanBeReassembledWithoutLostCharacters() {
        val record = record(1, "1234567890")
        val first = prepareDigestBatch(listOf(record), DigestProgress(), 6) { it.text }!!
        val second = prepareDigestBatch(listOf(record), first.next, 6) { it.text }!!
        assertEquals("1234567890", first.text + second.text)
        assertEquals(DigestProgress(completedThrough = 1), second.next)
    }
    @Test fun emojiAtBoundaryRemainsAWholeSurrogatePair() {
        val record = record(1, "12345😀tail")
        val first = prepareDigestBatch(listOf(record), DigestProgress(), 6) { it.text }!!
        val second = prepareDigestBatch(listOf(record), first.next, 6) { it.text }!!
        assertEquals("12345", first.text)
        assertEquals("😀tail", second.text)
        assertEquals(record.entry.text, first.text + second.text)
    }
    @Test fun evictedPartialEntryDoesNotSkipPrefixOfNewEntry() {
        val progress = DigestProgress(completedThrough = 1, partialSequence = 2, partialChars = 4)
        val next = prepareDigestBatch(listOf(record(3, "whole new message")), progress, 40) { it.text }!!
        assertEquals("whole new message", next.text)
        assertEquals(3L, next.next.completedThrough)
    }
    @Test fun exactBudgetDoesNotAcknowledgeTheNextNotification() {
        val records = listOf(record(1, "123456"), record(2, "next"))
        val first = prepareDigestBatch(records, DigestProgress(), 6) { it.text }!!
        assertEquals(1L, first.next.completedThrough)
        assertEquals("next", prepareDigestBatch(records, first.next, 6) { it.text }!!.text)
    }
}
