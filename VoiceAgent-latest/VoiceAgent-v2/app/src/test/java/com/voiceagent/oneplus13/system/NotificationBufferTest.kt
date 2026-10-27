package com.voiceagent.oneplus13.system

import org.junit.Assert.*
import org.junit.Test

class NotificationBufferTest {
    @Test fun legacyTimestampQueryKeepsItsContract() {
        val boundary = Long.MAX_VALUE - 10
        val excluded = NotificationEntry("fixture", "", "at boundary", boundary)
        val included = NotificationEntry("fixture", "", "after boundary", boundary + 1)
        NotificationBuffer.record(excluded)
        NotificationBuffer.record(included)
        assertEquals(listOf(included), NotificationBuffer.entriesSince(boundary))
    }
    @Test fun newCursorTracksArrivalOrderDespiteEqualOrEarlierClock() {
        val previous = NotificationBuffer.recordsAfter(0).lastOrNull()?.sequence ?: 0L
        val entries = listOf(
            NotificationEntry("fixture", "", "first", 10),
            NotificationEntry("fixture", "", "same time", 10),
            NotificationEntry("fixture", "", "clock back", 0)
        )
        entries.forEach(NotificationBuffer::record)
        val records = NotificationBuffer.recordsAfter(previous)
        assertEquals(entries, records.map { it.entry })
        assertTrue(records.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
        assertEquals(listOf(entries.last()), NotificationBuffer.recordsAfter(records[1].sequence).map { it.entry })
    }
}
