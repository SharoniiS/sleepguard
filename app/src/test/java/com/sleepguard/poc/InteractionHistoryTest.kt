package com.sleepguard.poc

import org.junit.Assert.assertEquals
import org.junit.Test

class InteractionHistoryTest {

    private val H = 3_600_000L

    private fun rec(nightOf: String, events: List<StoredEvent>): NightRecord = NightRecord(
        schemaVersion = STORAGE_SCHEMA_VERSION,
        recordId = nightOf,
        nightOf = nightOf,
        analyzerVersion = ANALYZER_VERSION,
        windowStartMillis = 0L,
        windowEndMillis = 0L,
        collectedAtMillis = 0L,
        restPattern = "CONSOLIDATED",
        confidence = "HIGH",
        primaryRest = null,
        secondaryRests = emptyList(),
        phoneDownMillis = null,
        firstUseAfterPrimaryRestMillis = null,
        preSleepPhoneTimeMillis = null,
        awakenings = emptyList(),
        flags = emptyList(),
        rawEventCount = 0,
        filteredEventCount = events.size,
        config = StoredConfig(60, true, 20, 90, 240, 180, 90, 120, 30, 90),
        events = events
    )

    @Test
    fun flatten_dedupsBoundaryEventsAndSorts() {
        val a = rec(
            "2026-06-13",
            listOf(StoredEvent(300, "SCREEN_INTERACTIVE"), StoredEvent(100, "SCREEN_INTERACTIVE"))
        )
        val b = rec(
            "2026-06-14",
            listOf(StoredEvent(300, "SCREEN_INTERACTIVE"), StoredEvent(200, "KEYGUARD_HIDDEN"))
        )

        val all = InteractionHistory.flatten(listOf(a, b))

        assertEquals(listOf(100L, 200L, 300L), all.map { it.timestampMillis })
        assertEquals(3, all.size) // the shared 300/SCREEN_INTERACTIVE is de-duplicated
    }

    @Test
    fun flatten_emptyIsEmpty() {
        assertEquals(0, InteractionHistory.flatten(emptyList()).size)
    }

    @Test
    fun longInactivities_keepsOnlyBlocksOverThreshold() {
        val night = rec("2026-06-14", emptyList()).copy(
            primaryRest = StoredBlock(0L, 5 * H, 5 * H, "PRIMARY_REST", "MAIN_SLEEP_LIKE_BLOCK"),
            secondaryRests = listOf(
                StoredBlock(6 * H, 8 * H, 2 * H, "SECONDARY_REST", "SHORT_SLEEP_LIKE_BLOCK")
            )
        )

        val result = InteractionHistory.longInactivities(night, 4 * H)

        assertEquals(1, result.size)              // the 2h secondary is below 4h
        assertEquals(5 * H, result[0].durationMillis)
    }

    @Test
    fun longInactivities_noneWhenNoPrimary() {
        val night = rec("2026-06-14", emptyList()) // primaryRest = null
        assertEquals(0, InteractionHistory.longInactivities(night, 4 * H).size)
    }
}
