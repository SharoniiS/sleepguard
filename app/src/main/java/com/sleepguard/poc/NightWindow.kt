package com.sleepguard.poc

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The time window of one night, in the device timezone. Pure JVM (no Android) so it is unit-testable,
 * and the single copy shared by the product UI ([SleepViewModel]) and the debug screen ([MainActivity]).
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

    /** The range of events to read for that night: the analysis window itself. */
    fun collectionRange(morning: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val a = anchors(morning, zone)
        return a.windowStart to a.windowEnd
    }
}
