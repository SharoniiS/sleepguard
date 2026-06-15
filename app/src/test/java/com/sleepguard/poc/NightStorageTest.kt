package com.sleepguard.poc

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the local-storage layer: the pure mapper, JSON (de)serialization, and
 * the pure upsert merge. File I/O in NightRepository is a thin Android wrapper and is not
 * unit-tested here.
 */
class NightStorageTest {

    private val H = 3_600_000L
    private val anchors = WindowAnchors(
        windowStart = 0L, morningEarliest = 6 * H, morningStart = 8 * H, noon = 14 * H, windowEnd = 20 * H
    )
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun use(start: Long): List<RawScreenEvent> = listOf(
        RawScreenEvent(start, ScreenEventType.SCREEN_INTERACTIVE),
        RawScreenEvent(start + 1_000L, ScreenEventType.KEYGUARD_HIDDEN),
        RawScreenEvent(start + 120_000L, ScreenEventType.SCREEN_NON_INTERACTIVE)
    )

    private fun sampleRecord(nightOf: String, collectedAt: Long = 0L): NightRecord {
        val events = use(10 * 60_000L) + use(9 * H)
        val result = NightPatternAnalyzer().analyze(events, anchors)
        return NightRecordMapper.toRecord(
            result = result,
            events = events,
            rawEventCount = 99,
            nightOf = nightOf,
            collectedAtMillis = collectedAt,
            config = AnalysisConfig()
        )
    }

    @Test
    fun mapper_mapsKeyFields() {
        val events = use(10 * 60_000L) + use(9 * H)
        val result = NightPatternAnalyzer().analyze(events, anchors)
        val record = NightRecordMapper.toRecord(result, events, 99, "2026-06-14", 123L, AnalysisConfig())

        assertEquals(STORAGE_SCHEMA_VERSION, record.schemaVersion)
        assertEquals("2026-06-14", record.recordId)   // recordId == nightOf
        assertEquals("2026-06-14", record.nightOf)
        assertEquals(ANALYZER_VERSION, record.analyzerVersion)
        assertEquals(result.restPattern.name, record.restPattern)
        assertEquals(result.confidence.name, record.confidence)
        assertEquals(99, record.rawEventCount)
        assertEquals(events.size, record.filteredEventCount)
        assertEquals(AnalysisConfig().mainSleepMinMinutes, record.config.mainSleepMinMinutes)
    }

    @Test
    fun mapper_storesRawEventsForDebug() {
        val record = sampleRecord("2026-06-14")
        assertTrue(record.events.isNotEmpty())
        assertEquals(ScreenEventType.SCREEN_INTERACTIVE.name, record.events.first().type)
    }

    @Test
    fun serialization_roundTrip() {
        val record = sampleRecord("2026-06-14")
        val decoded: NightRecord = json.decodeFromString(json.encodeToString(record))
        assertEquals(record, decoded)
    }

    @Test
    fun upsert_replacesByRecordId() {
        val a = sampleRecord("2026-06-12")
        val b = sampleRecord("2026-06-14", collectedAt = 1L)
        val bUpdated = sampleRecord("2026-06-14", collectedAt = 2L) // same id, newer content

        var list = NightRepository.upsertInto(emptyList(), a)
        list = NightRepository.upsertInto(list, b)
        assertEquals(2, list.size)

        list = NightRepository.upsertInto(list, bUpdated)
        assertEquals(2, list.size) // replaced, not duplicated
        assertEquals(2L, list.first { it.recordId == "2026-06-14" }.collectedAtMillis)
    }

    @Test
    fun upsert_sortsByNightDate() {
        var list = NightRepository.upsertInto(emptyList(), sampleRecord("2026-06-14"))
        list = NightRepository.upsertInto(list, sampleRecord("2026-06-10"))
        list = NightRepository.upsertInto(list, sampleRecord("2026-06-12"))

        assertEquals(listOf("2026-06-10", "2026-06-12", "2026-06-14"), list.map { it.nightOf })
    }
}
