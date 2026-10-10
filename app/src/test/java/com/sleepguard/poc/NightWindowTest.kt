package com.sleepguard.poc

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** JVM tests for the night window boundaries (shared by the product UI and the debug screen). */
class NightWindowTest {

    private val zone = ZoneId.of("Asia/Jerusalem")
    private val morning = LocalDate.of(2026, 10, 10)

    private fun millis(y: Int, mo: Int, d: Int, h: Int): Long =
        LocalDateTime.of(y, mo, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun nightStartsAt18PreviousDay_andEndsAt18() {
        val a = NightWindow.anchors(morning, zone)

        assertEquals(millis(2026, 10, 9, 18), a.windowStart)
        assertEquals(millis(2026, 10, 10, 4), a.morningEarliest)
        assertEquals(millis(2026, 10, 10, 6), a.morningStart)
        assertEquals(millis(2026, 10, 10, 12), a.noon)
        assertEquals(millis(2026, 10, 10, 18), a.windowEnd)
    }

    @Test
    fun collectionStartsTwoHoursBeforeTheNight() {
        val (start, end) = NightWindow.collectionRange(morning, zone, leadMinutes = 120L)

        assertEquals(millis(2026, 10, 9, 16), start)
        assertEquals(millis(2026, 10, 10, 18), end)
    }

    @Test
    fun consecutiveNightsTileWithNoGap() {
        val today = NightWindow.anchors(morning, zone)
        val yesterday = NightWindow.anchors(morning.minusDays(1), zone)

        assertEquals(yesterday.windowEnd, today.windowStart)
    }
}
