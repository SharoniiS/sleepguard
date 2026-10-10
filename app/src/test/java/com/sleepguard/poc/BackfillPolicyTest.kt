package com.sleepguard.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the backfill decisions: which recent nights get (re)analyzed, and from which events. */
class BackfillPolicyTest {

    private val H = 3_600_000L
    private val anchors = WindowAnchors(0L, 10 * H, 12 * H, 18 * H, 24 * H)

    private fun rec(
        analyzerVersion: String = ANALYZER_VERSION,
        collectedAt: Long = 25 * H,
        events: List<StoredEvent> = emptyList()
    ): NightRecord = NightRecord(
        schemaVersion = STORAGE_SCHEMA_VERSION,
        recordId = "2026-10-10",
        nightOf = "2026-10-10",
        analyzerVersion = analyzerVersion,
        windowStartMillis = 0L,
        windowEndMillis = 24 * H,
        collectedAtMillis = collectedAt,
        restPattern = "CONSOLIDATED",
        confidence = "HIGH",
        primaryRest = null,
        secondaryRests = emptyList(),
        phoneDownMillis = null,
        firstUseAfterPrimaryRestMillis = null,
        preSleepPhoneTimeMillis = null,
        awakenings = emptyList(),
        flags = emptyList(),
        rawEventCount = events.size,
        filteredEventCount = events.size,
        config = StoredConfig(60, true, 20, 90, 240, 180, 90, 120, 30, 90),
        events = events
    )

    private fun stored(vararg times: Long) = times.map { StoredEvent(it, "SCREEN_INTERACTIVE") }
    private fun raw(vararg times: Long) = times.map { RawScreenEvent(it, ScreenEventType.SCREEN_INTERACTIVE) }

    @Test
    fun newNight_isAnalyzed() = assertTrue(BackfillPolicy.needsAnalysis(null))

    @Test
    fun completeNightFromCurrentAnalyzer_staysFrozen() =
        assertFalse(BackfillPolicy.needsAnalysis(rec()))

    @Test
    fun completeNightFromOlderAnalyzer_isReanalyzed() =
        assertTrue(BackfillPolicy.needsAnalysis(rec(analyzerVersion = "1.0.0-phase1")))

    @Test
    fun incompleteNight_isReanalyzed() =
        assertTrue(BackfillPolicy.needsAnalysis(rec(collectedAt = 10 * H)))

    @Test
    fun thinnerFreshRead_keepsStoredEvents() {
        // Retention-trimmed: the fresh read lost the evening; the stored record still has it.
        val existing = rec(events = stored(1 * H, 2 * H, 3 * H, 12 * H))
        assertTrue(BackfillPolicy.preferStoredEvents(raw(12 * H), existing, anchors))
    }

    @Test
    fun fullFreshRead_isUsed() {
        // The fresh read has as many in-window events (plus the lead before the window).
        val existing = rec(events = stored(1 * H, 12 * H))
        assertFalse(BackfillPolicy.preferStoredEvents(raw(-H, 1 * H, 12 * H), existing, anchors))
    }

    @Test
    fun leadBeforeWindow_isNotCountedAsCoverage() {
        // Extra events BEFORE the window must not make a trimmed fresh read look complete.
        val existing = rec(events = stored(1 * H, 2 * H))
        assertTrue(BackfillPolicy.preferStoredEvents(raw(-2 * H, -H, 1 * H), existing, anchors))
    }

    @Test
    fun storedEventsConvertBack_unknownTypeBecomesUnknown() {
        val raw = BackfillPolicy.toRawEvents(
            listOf(StoredEvent(1L, "KEYGUARD_HIDDEN"), StoredEvent(2L, "SOMETHING_NEW"))
        )
        assertEquals(ScreenEventType.KEYGUARD_HIDDEN, raw[0].type)
        assertEquals(ScreenEventType.UNKNOWN, raw[1].type)
    }
}
