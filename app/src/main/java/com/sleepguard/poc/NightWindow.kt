package com.sleepguard.poc

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The time window of one night, in the device timezone. Pure JVM (no Android) so it is unit-testable;
 * used by [SleepViewModel].
 *
 * A night is analyzed from 18:00 of the previous day to 18:00 of its morning: consecutive nights tile
 * with no gap, and a user who falls asleep before 22:00 is still seen from the moment the phone was
 * put down.
 */
object NightWindow {

    private fun at(date: LocalDate, hour: Int, zone: ZoneId): Long =
        date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    /** Analysis anchors for the night whose morning is [morning]: (m-1) 18:00 -> m 18:00. */
    fun anchors(morning: LocalDate, zone: ZoneId): WindowAnchors = WindowAnchors(
        windowStart = at(morning.minusDays(1), 18, zone),
        morningEarliest = at(morning, 4, zone),
        morningStart = at(morning, 6, zone),
        noon = at(morning, 12, zone),
        windowEnd = at(morning, 18, zone)
    )

    /**
     * The range of events to read for that night: the analysis window plus a lead of [leadMinutes]
     * before its opening. The lead is used ONLY for "phone use in the 2 hours before sleep" (someone
     * who puts the phone down at 19:00 needs 17:00–19:00); it never becomes part of the night.
     */
    fun collectionRange(morning: LocalDate, zone: ZoneId, leadMinutes: Long): Pair<Long, Long> {
        val a = anchors(morning, zone)
        return (a.windowStart - leadMinutes * 60_000L) to a.windowEnd
    }
}
