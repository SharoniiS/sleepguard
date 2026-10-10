package com.sleepguard.poc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Realistic whole-night scenarios, in clock time, on the production window (18:00 -> 18:00).
 *
 * These are the night shapes reviewed with the product owner on 2026-10-10 (the 11 probe scenarios
 * plus the cases behind each approved rule). Each test pins the user-visible outcome: the shown
 * window, interruptions, consolidated/fragmented, net duration, and "use in the 2h before sleep".
 *
 * Every use is an unlocked 2-min session that locks again at screen-off (as a real phone does).
 */
class NightScenarioTest {

    private val M = 60_000L
    private val H = 60 * M
    private val analyzer = NightPatternAnalyzer()

    /** Clock time inside the night window: 18:00 = 0 ... 17:59 next day. */
    private fun at(h: Int, m: Int = 0): Long = ((if (h >= 18) h - 18 else h + 6) * 60L + m) * M

    /** Clock time in the 2h collection lead BEFORE the window (16:00–17:59 of the previous day). */
    private fun lead(h: Int, m: Int = 0): Long = ((h - 18) * 60L + m) * M

    private val anchors = WindowAnchors(
        windowStart = at(18), morningEarliest = at(4), morningStart = at(6), noon = at(12), windowEnd = 24 * H
    )

    private fun use(t: Long, durSeconds: Long = 120L): List<RawScreenEvent> = listOf(
        RawScreenEvent(t, ScreenEventType.SCREEN_INTERACTIVE),
        RawScreenEvent(t + 1_000L, ScreenEventType.KEYGUARD_HIDDEN),
        RawScreenEvent(t + durSeconds * 1_000L, ScreenEventType.SCREEN_NON_INTERACTIVE),
        RawScreenEvent(t + durSeconds * 1_000L, ScreenEventType.KEYGUARD_SHOWN)
    )

    /** Screen lit WITHOUT unlocking (alarm, incoming call, notification). */
    private fun lockedOn(t: Long, durSeconds: Long): List<RawScreenEvent> = listOf(
        RawScreenEvent(t, ScreenEventType.SCREEN_INTERACTIVE),
        RawScreenEvent(t + durSeconds * 1_000L, ScreenEventType.SCREEN_NON_INTERACTIVE)
    )

    /** Uses every [stepMin] minutes in [from, to). */
    private fun every(from: Long, to: Long, stepMin: Long, durSeconds: Long = 120L): List<RawScreenEvent> {
        val out = mutableListOf<RawScreenEvent>()
        var t = from
        while (t < to) { out += use(t, durSeconds); t += stepMin * M }
        return out
    }

    /** Ordinary evening: a use every 30 min from 18:00 until [until]. */
    private fun evening(until: Long) = every(at(18), until, 30)

    /** Ordinary day: a use every hour from [from] until 18:00 (gaps < 90 min, never rest). */
    private fun day(from: Long) = every(from, anchors.windowEnd, 60)

    private fun expect(
        events: List<RawScreenEvent>,
        start: Long, end: Long, interruptions: Int, pattern: RestPattern, netMinutes: Long,
        preSleepMinutes: Long? = null
    ) {
        val r = analyzer.analyze(events, anchors)
        val ep = r.mainRestEpisode!!
        assertEquals("window start", start, ep.startMillis)
        assertEquals("window end", end, ep.endMillis)
        assertEquals("interruptions", interruptions, r.nighttimeAwakenings.size)
        assertEquals("pattern", pattern, r.restPattern)
        assertEquals("net duration", netMinutes * M, ep.durationMillis)
        assertEquals("first use after", end, r.firstUseAfterMainRest)
        if (preSleepMinutes != null) {
            assertEquals("phone-down = window start", start, r.phoneDownTime)
            assertEquals("use in 2h before sleep", preSleepMinutes * M, r.preSleepPhoneTimeMillis)
        }
    }

    // ---- The 11 reviewed scenarios ----------------------------------------------------------

    @Test // 1. Check at 03:00; the second half is the longer block. Was: "use before" = 2 min.
    fun s01_checkAt3_secondHalfLonger() = expect(
        evening(at(21, 30)) + every(at(21, 30), at(23, 21), 10) + use(at(3)) + day(at(7, 30)),
        start = at(23, 22), end = at(7, 30), interruptions = 1, pattern = RestPattern.CONSOLIDATED,
        netMinutes = 8 * 60 + 6, preSleepMinutes = 24
    )

    @Test // 2. Heavy use 20:00–22:44. Was: "use before" = 36 min (cut at 22:00).
    fun s02_heavyEveningUse() = expect(
        evening(at(20)) + every(at(20), at(22, 41), 5, 240) + day(at(7)),
        start = at(22, 44), end = at(7), interruptions = 0, pattern = RestPattern.CONSOLIDATED,
        netMinutes = 8 * 60 + 16, preSleepMinutes = 96
    )

    @Test // 3. No phone 22:00–23:40 (TV), a check, sleep. Kept by decision: the quiet joins the night.
    fun s03_tvEveningThenCheckThenSleep() = expect(
        evening(at(22, 1)) + use(at(23, 40)) + day(at(7)),
        start = at(22, 2), end = at(7), interruptions = 1, pattern = RestPattern.CONSOLIDATED,
        netMinutes = 8 * 60 + 56, preSleepMinutes = 8
    )

    @Test // 4. Falls asleep 21:15. Was: window from 22:00, "use before" unknown.
    fun s04_fallsAsleepBefore22() = expect(
        evening(at(21, 1)) + use(at(21, 13)) + day(at(6)),
        start = at(21, 15), end = at(6), interruptions = 0, pattern = RestPattern.CONSOLIDATED,
        netMinutes = 8 * 60 + 45, preSleepMinutes = 10
    )

    @Test // 5. Awake 42 min with the phone at 03:00. Was: 23:02–03:00, no interruptions.
    fun s05_awake42MinAt3() = expect(
        evening(at(23, 1)) + every(at(3), at(3, 41), 10) + day(at(7)),
        start = at(23, 2), end = at(7), interruptions = 1, pattern = RestPattern.CONSOLIDATED,
        netMinutes = 7 * 60 + 16
    )

    @Test // 6. Two checks, 03:00 and 03:35. Was: 23:02–03:00, no interruptions.
    fun s06_twoChecks35MinApart() = expect(
        evening(at(23, 1)) + use(at(3)) + use(at(3, 35)) + day(at(7, 30)),
        start = at(23, 2), end = at(7, 30), interruptions = 1, pattern = RestPattern.CONSOLIDATED,
        netMinutes = 7 * 60 + 51
    )

    @Test // 7. Alarm rings 3 min at 06:30 (locked); real wake 08:00. Was: woke at 06:30.
    fun s07_alarmRingingLocked() = expect(
        evening(at(23, 1)) + lockedOn(at(6, 30), 180) + day(at(8)),
        start = at(23, 2), end = at(8), interruptions = 0, pattern = RestPattern.CONSOLIDATED,
        netMinutes = 8 * 60 + 58
    )

    @Test // 8. Two halves of ~3.5h with a check at 03:00.
    fun s08_twoHalves() = expect(
        evening(at(23)) + use(at(23, 20)) + use(at(3)) + day(at(6, 35)),
        start = at(23, 22), end = at(6, 35), interruptions = 1, pattern = RestPattern.CONSOLIDATED,
        netMinutes = 7 * 60 + 11, preSleepMinutes = 8
    )

    @Test // 9. Short night 00:22–06:00, 82 min of morning use, 6.5h workday without the phone.
          //    Approved consequence: the workday joins the night as one long interruption.
    fun s09_shortNightThenPhoneFreeWorkday() = expect(
        every(at(18), at(0, 21), 20) + every(at(6), at(7, 21), 20) + every(at(14), anchors.windowEnd, 30),
        start = at(0, 22), end = at(14), interruptions = 1, pattern = RestPattern.FRAGMENTED,
        netMinutes = 12 * 60 + 16, preSleepMinutes = 12
    )

    @Test // 10. Normal night with three brief checks (01:00, 03:00, 05:00).
    fun s10_threeBriefChecks() = expect(
        evening(at(23, 1)) + use(at(1)) + use(at(3)) + use(at(5)) + day(at(7)),
        start = at(23, 2), end = at(7), interruptions = 3, pattern = RestPattern.FRAGMENTED,
        netMinutes = 7 * 60 + 52
    )

    @Test // 11. Wakes 07:00, quick check, 2h without the phone. Kept by decision: sleep until 09:00.
    fun s11_morningCheckThenTwoHoursWithoutPhone() = expect(
        evening(at(23, 1)) + use(at(7)) + day(at(9)),
        start = at(23, 2), end = at(9), interruptions = 1, pattern = RestPattern.CONSOLIDATED,
        netMinutes = 9 * 60 + 56
    )

    // ---- The approved rules, case by case ---------------------------------------------------

    @Test // Awake 82 min at 01:30, then 5h of sleep -> long interruption, the night continues.
    fun r01_insomniaThenLongSleep_isOneNight() = expect(
        evening(at(23, 1)) + every(at(1, 30), at(2, 51), 10) + day(at(8)),
        start = at(23, 2), end = at(8), interruptions = 1, pattern = RestPattern.FRAGMENTED,
        netMinutes = 7 * 60 + 36
    )

    @Test // Awake almost 4h (> 3h cap) -> two separate sleeps; the longer one is shown.
    fun r02_whiteNightLongerThanCap_splits() = expect(
        evening(at(23, 1)) + every(at(1), at(4, 51), 10) + day(at(10)),
        start = at(4, 52), end = at(10), interruptions = 0, pattern = RestPattern.FRAGMENTED,
        netMinutes = 5 * 60 + 8
    )

    @Test // Wakes 07:00 and uses the phone for 1.5h, day continues -> the night ends at 07:00.
    fun r03_realMorningWake() = expect(
        evening(at(23, 1)) + every(at(7), at(8, 31), 10) + day(at(9)),
        start = at(23, 2), end = at(7), interruptions = 0, pattern = RestPattern.CONSOLIDATED,
        netMinutes = 7 * 60 + 58
    )

    @Test // Awake 5h in the morning, then 3h without the phone at midday -> NOT joined (cap).
    fun r04_middayQuietAfterLongMorning_isNotJoined() = expect(
        evening(at(23, 1)) + every(at(7), at(12, 1), 60) + every(at(15, 2), anchors.windowEnd, 60),
        start = at(23, 2), end = at(7), interruptions = 0, pattern = RestPattern.FRAGMENTED,
        netMinutes = 7 * 60 + 58
    )

    @Test // Approved consequence: phone-free 19:00–21:00, 42 min of use, then sleep -> one night from 19:02.
    fun r05_eveningQuietJoinsTheNight() = expect(
        evening(at(19, 1)) + every(at(21), at(21, 41), 10) + day(at(6)),
        start = at(19, 2), end = at(6), interruptions = 1, pattern = RestPattern.CONSOLIDATED,
        netMinutes = 10 * 60 + 16, preSleepMinutes = 6
    )

    @Test // Falls asleep 18:32; "use before" reaches into 16:00–18:00 (the collection lead).
    fun r06_sleepRightAfter18_lookbackUsesTheLead() = expect(
        every(lead(16, 30), at(18), 30) + every(at(18), at(18, 31), 30) + day(at(5)),
        start = at(18, 32), end = at(5), interruptions = 0, pattern = RestPattern.CONSOLIDATED,
        netMinutes = 10 * 60 + 28, preSleepMinutes = 8
    )

    @Test // An incoming call ringing 45s at 02:00 (not unlocked) does not interrupt the night.
    fun r07_incomingCallLocked_isNotAnInterruption() = expect(
        evening(at(23, 1)) + lockedOn(at(2), 45) + day(at(7)),
        start = at(23, 2), end = at(7), interruptions = 0, pattern = RestPattern.CONSOLIDATED,
        netMinutes = 7 * 60 + 58
    )
}
