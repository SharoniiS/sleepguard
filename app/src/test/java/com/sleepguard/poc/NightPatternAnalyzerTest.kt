package com.sleepguard.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for NightPatternAnalyzer. Run with `./gradlew test` (no device).
 *
 * Time scale: windowStart = 0 represents 22:00. Every value is an offset in
 * milliseconds from 22:00, so H = one hour:
 *   T_04 = 6H, T_06 = 8H, T_12 = 14H, windowEnd (T_18) = 20H.
 *
 * A "real use" is synthesised as SCREEN_INTERACTIVE + KEYGUARD_HIDDEN +
 * SCREEN_NON_INTERACTIVE so it always passes the meaningful-interaction filter.
 */
class NightPatternAnalyzerTest {

    private val H = 3_600_000L
    private val analyzer = NightPatternAnalyzer()
    private val anchors = WindowAnchors(
        windowStart = 0L,
        morningEarliest = 6 * H,  // 04:00
        morningStart = 8 * H,     // 06:00
        noon = 14 * H,            // 12:00
        windowEnd = 20 * H        // 18:00
    )

    /** A 120-second unlocked use starting at [start]. */
    private fun use(start: Long, durSeconds: Long = 120L): List<RawScreenEvent> = listOf(
        RawScreenEvent(start, ScreenEventType.SCREEN_INTERACTIVE),
        RawScreenEvent(start + 1_000L, ScreenEventType.KEYGUARD_HIDDEN),
        RawScreenEvent(start + durSeconds * 1_000L, ScreenEventType.SCREEN_NON_INTERACTIVE)
    )

    /** Active-day filler: short uses every [stepMin] minutes, so gaps stay < 90 min (MICRO). */
    private fun fill(fromMs: Long, toMs: Long, stepMin: Long = 80L): List<RawScreenEvent> {
        val out = mutableListOf<RawScreenEvent>()
        var t = fromMs
        while (t < toMs) {
            out += use(t)
            t += stepMin * 60_000L
        }
        return out
    }

    /** Label of the quiet block that spans [gapStart, gapEnd], isolated from other gaps. */
    private fun labelOfGap(gapStart: Long, gapEnd: Long): QuietBlockLabel {
        val events = use(gapStart - 120_000L) + use(gapEnd)
        val result = analyzer.analyze(events, anchors)
        return result.quietBlocks.first { it.startMillis == gapStart }.label
    }

    // ---- Label precedence (spec §4) -------------------------------------

    @Test
    fun label_main_whenLongBlockStartsBeforeMorning() {
        // 7h block starting 23:30 (1.5H)
        assertEquals(QuietBlockLabel.MAIN_SLEEP_LIKE_BLOCK, labelOfGap(3 * H / 2, 3 * H / 2 + 7 * H))
    }

    @Test
    fun label_delayed_whenLongBlockStartsAfter4amBeforeNoon() {
        // 5h block starting 07:00 (9H)  -> DELAYED beats MORNING_RECOVERY and MAIN
        assertEquals(QuietBlockLabel.DELAYED_SLEEP_LIKE, labelOfGap(9 * H, 14 * H))
        // user's example 07:00–11:00 (4h) -> DELAYED
        assertEquals(QuietBlockLabel.DELAYED_SLEEP_LIKE, labelOfGap(9 * H, 13 * H))
    }

    @Test
    fun label_morningRecovery_whenShortMorningBlock() {
        // 2h block starting 08:00 (10H)
        assertEquals(QuietBlockLabel.MORNING_RECOVERY_SLEEP, labelOfGap(10 * H, 12 * H))
    }

    @Test
    fun label_daytimeRecovery_evenWhenLong_afterNoon() {
        // refinement #1: 3.5h block starting 14:00 (16H) -> DAYTIME_RECOVERY, not DELAYED
        assertEquals(QuietBlockLabel.DAYTIME_RECOVERY_SLEEP, labelOfGap(16 * H, 16 * H + 7 * H / 2))
        // 2h block starting 14:00
        assertEquals(QuietBlockLabel.DAYTIME_RECOVERY_SLEEP, labelOfGap(16 * H, 18 * H))
    }

    @Test
    fun label_short_whenOvernightTwoHourBlock() {
        // 2h block starting 03:00 (5H) -> SHORT (before 06:00, < 4h)
        assertEquals(QuietBlockLabel.SHORT_SLEEP_LIKE_BLOCK, labelOfGap(5 * H, 7 * H))
    }

    @Test
    fun label_micro_whenUnderNinetyMinutes() {
        // 1h block starting 03:00 (5H)
        assertEquals(QuietBlockLabel.MICRO_QUIET_BLOCK, labelOfGap(5 * H, 6 * H))
    }

    // ---- Meaningful-interaction filter (spec §3, req #5) -----------------

    @Test
    fun shortGlowDoesNotBreakQuietPeriod() {
        // A 30s screen-on with NO unlock between two real uses must be ignored.
        val glow = listOf(
            RawScreenEvent(5 * H, ScreenEventType.SCREEN_INTERACTIVE),
            RawScreenEvent(5 * H + 30_000L, ScreenEventType.SCREEN_NON_INTERACTIVE)
        )
        val events = use(10 * 60_000L) + glow + use(9 * H)
        val result = analyzer.analyze(events, anchors)

        assertEquals(2, result.interactions.size) // glow filtered out
        // One uninterrupted sleep-like block across the glow's timestamp.
        assertTrue(result.quietBlocks.any { it.startMillis <= 5 * H && it.endMillis >= 5 * H && it.isSleepLike })
    }

    // ---- 1.1.0: only an UNLOCKED phone is real use ----------------------

    /** A screen-on of [durSeconds] with NO unlock (alarm / incoming call / notification). */
    private fun locked(start: Long, durSeconds: Long): List<RawScreenEvent> = listOf(
        RawScreenEvent(start, ScreenEventType.SCREEN_INTERACTIVE),
        RawScreenEvent(start + durSeconds * 1_000L, ScreenEventType.SCREEN_NON_INTERACTIVE)
    )

    @Test
    fun longLockedScreenOn_isNotRealUse() {
        // A 5-minute lit-but-locked screen at 03:00 (e.g. a ringing call) must not break sleep.
        val events = use(10 * 60_000L) + locked(5 * H, 300L) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertFalse(result.interactions.any { it.startMillis == 5 * H })
        assertTrue(result.quietBlocks.any { it.startMillis <= 5 * H && it.endMillis >= 5 * H && it.isSleepLike })
        assertTrue(result.nighttimeAwakenings.isEmpty())
    }

    @Test
    fun alarmRingingLocked_doesNotEndSleep() {
        // Alarm at 06:30 rings 3 min with no unlock; the real wake (unlock) is 08:00.
        val alarm = 8 * H + 30 * 60_000L
        val events = use(10 * 60_000L) + locked(alarm, 180L) + fill(10 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(10 * H, result.mainRestEpisode!!.endMillis)
        assertEquals(10 * H, result.firstUseAfterMainRest)
    }

    @Test
    fun screenOnWhilePhoneStillUnlocked_isRealUse() {
        // Unlocked use at 03:00 (screen off 03:02, no lock event: "lock after X min" setting),
        // screen back on at 03:05 for 20 min WITHOUT a new unlock -> the phone was still unlocked.
        val again = 5 * H + 5 * 60_000L
        val events = use(10 * 60_000L) + use(5 * H) + locked(again, 1200L) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertTrue(result.interactions.any { it.startMillis == again })
    }

    @Test
    fun lockEventInBetween_nextScreenOnIsLocked() {
        // Same as above, but the phone locked (KEYGUARD_SHOWN) when the screen went off at 03:02.
        val again = 5 * H + 5 * 60_000L
        val events = use(10 * 60_000L) + use(5 * H) +
            listOf(RawScreenEvent(5 * H + 120_000L, ScreenEventType.KEYGUARD_SHOWN)) +
            locked(again, 1200L) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertFalse(result.interactions.any { it.startMillis == again })
    }

    @Test
    fun stillUnlockedCarryOver_expiresAfterLimit() {
        // No lock event recorded, but the screen comes back 45 min after the last use (> 30 min
        // carry-over) with no unlock -> treated as locked (a lock event was probably missed).
        val later = 5 * H + 47 * 60_000L
        val events = use(10 * 60_000L) + use(5 * H) + locked(later, 300L) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertFalse(result.interactions.any { it.startMillis == later })
    }

    @Test
    fun unlockLoggedJustBeforeScreenOn_stillCounts() {
        // Fingerprint/face unlock can be logged a few ms BEFORE the screen-on event.
        val t = 5 * H
        val events = use(10 * 60_000L) +
            listOf(RawScreenEvent(t - 5L, ScreenEventType.KEYGUARD_HIDDEN)) +
            locked(t, 120L) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertTrue(result.interactions.any { it.startMillis == t })
    }

    @Test
    fun noLockEventsAtAll_fallsBackToDurationRule() {
        // A phone with no lock screen logs no lock/unlock events: then a screen-on >= 60s counts
        // (configurable), a shorter one does not, and the record is flagged.
        val events = locked(10 * 60_000L, 120L) + locked(5 * H, 30L) + locked(9 * H, 120L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(2, result.interactions.size) // the 30s glow at 03:00 is dropped
        assertTrue(result.flags.contains(AnalysisFlag.NO_LOCK_EVENTS))
        // The fallback threshold is configurable: at 10s the 30s glow counts too.
        val lowered = NightPatternAnalyzer(AnalysisConfig(meaningfulMinScreenOnSeconds = 10L))
            .analyze(events, anchors)
        assertEquals(3, lowered.interactions.size)
    }

    @Test
    fun lockEventsPresent_noNoLockFlag() {
        val result = analyzer.analyze(use(10 * 60_000L) + fill(9 * H, 20 * H, 80L), anchors)
        assertFalse(result.flags.contains(AnalysisFlag.NO_LOCK_EVENTS))
    }

    // ---- WINDOW_OPENS_IN_QUIET (req #4) ---------------------------------

    @Test
    fun windowOpensInQuiet_phoneDownAndPreSleepUnknown() {
        // No use until 08:00 (10H); the window opens mid-sleep.
        val events = fill(10 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertTrue(result.flags.contains(AnalysisFlag.WINDOW_OPENS_IN_QUIET))
        assertNull(result.phoneDownTime)
        assertNull(result.preSleepPhoneTimeMillis)
        assertEquals(Confidence.LOW, result.confidence)
    }

    // ---- Awakenings + main sleep window (spec §6) -----------------------

    @Test
    fun briefNightUseCountsAsOneAwakening() {
        // Use early, sleep to 03:00, brief unlock, sleep to 07:00, then active day.
        val events = use(10 * 60_000L) + use(5 * H) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(1, result.nighttimeAwakenings.size)
        assertEquals(5 * H, result.nighttimeAwakenings.first()) // interruption at 03:00
        assertTrue(result.flags.contains(AnalysisFlag.MULTIPLE_SLEEP_BLOCKS))
        assertEquals(NightPattern.FRAGMENTED_SLEEP_LIKE, result.nightPattern)
    }

    // ---- Verdicts -------------------------------------------------------

    @Test
    fun consolidatedNight_highConfidence() {
        val events = use(10 * 60_000L) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(NightPattern.CONSOLIDATED_SLEEP_LIKE, result.nightPattern)
        assertEquals(QuietBlockLabel.MAIN_SLEEP_LIKE_BLOCK, result.primaryRest?.block?.label)
        assertEquals(Confidence.HIGH, result.confidence)
        assertTrue(result.nighttimeAwakenings.isEmpty())
        assertFalse(result.flags.contains(AnalysisFlag.WINDOW_OPENS_IN_QUIET))
    }

    @Test
    fun activeNight_whenNoSleepLikeBlock() {
        // Use every 60 min all window long -> every gap is MICRO, none >= 90 min.
        val events = fill(0L, 20 * H, 60L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(NightPattern.ACTIVE_NIGHT, result.nightPattern)
        assertNull(result.primaryRest)
        assertEquals(Confidence.LOW, result.confidence)
        assertTrue(result.flags.contains(AnalysisFlag.ACTIVE_NIGHT))
    }

    @Test
    fun multipleShortBlocks_areFragmentedNotActive() {
        // refinement #2: two separated SHORT blocks -> FRAGMENTED, never ACTIVE_NIGHT.
        val events =
            use(10 * 60_000L) +                 // anchor near window start
            use(2 * H) + use(2 * H + 40 * 60_000L) + // 40-min active gap between the two blocks
            use(5 * H) +                        // ends block 2
            fill(9 * H, 20 * H, 80L)            // active day
        val result = analyzer.analyze(events, anchors)

        val shortCount = result.quietBlocks.count { it.label == QuietBlockLabel.SHORT_SLEEP_LIKE_BLOCK }
        assertTrue("expected >= 2 SHORT blocks, got $shortCount", shortCount >= 2)
        assertEquals(NightPattern.FRAGMENTED_SLEEP_LIKE, result.nightPattern)
        assertFalse(result.flags.contains(AnalysisFlag.ACTIVE_NIGHT))
    }

    @Test
    fun whiteNightThenMorningQuiet_isRecoverySleep() {
        // Active 22:00–08:00, then a 2h quiet morning block (08:00–10:00), then active day.
        val events = fill(0L, 10 * H, 60L) + use(10 * H) + fill(12 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(NightPattern.RECOVERY_SLEEP, result.nightPattern)
        assertEquals(QuietBlockLabel.MORNING_RECOVERY_SLEEP, result.primaryRest?.block?.label)
    }

    @Test
    fun delayedPrimary_yieldsDelayedVerdict() {
        // Active until 07:00, then a 4h sleep block 07:00–11:00, then active day.
        val events = fill(0L, 9 * H, 60L) + use(13 * H) + fill(13 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(QuietBlockLabel.DELAYED_SLEEP_LIKE, result.primaryRest?.block?.label)
        assertEquals(NightPattern.DELAYED_SLEEP_LIKE, result.nightPattern)
        assertTrue(result.flags.contains(AnalysisFlag.DELAYED_PRIMARY_SLEEP))
    }

    // ---- preSleepPhoneTime is the last 2h only (req #6) ------------------

    @Test
    fun preSleepPhoneTimeCountsOnlyLastTwoHours() {
        // Uses at 22:00, 23:30, 00:30 (gaps < 90 min), phone-down 00:32. The 22:00 use is more
        // than 2h before phone-down -> outside the lookback; the other two (2 min each) count.
        val events = use(0L) + use(90 * 60_000L) + use(150 * 60_000L) + fill(11 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(152 * 60_000L, result.phoneDownTime)
        assertEquals(4 * 60_000L, result.preSleepPhoneTimeMillis)
    }

    @Test
    fun phoneDown_isBeforeTheShownWindow_notBeforeTheLongestBlock() {
        // Evening uses until 22:22, sleep, a 2-min check at 03:00, sleep until 08:00. The second
        // half is the longest block, but the shown window starts at 22:22 — so does phone-down.
        val events = use(0L) + use(10 * 60_000L) + use(20 * 60_000L) + use(5 * H) + fill(10 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(5 * H + 2 * 60_000L, result.primaryRest!!.block.startMillis) // longest = 2nd half
        assertEquals(22 * 60_000L, result.mainRestEpisode!!.startMillis)
        assertEquals(22 * 60_000L, result.phoneDownTime)
        assertEquals(6 * 60_000L, result.preSleepPhoneTimeMillis) // the three evening uses
    }

    @Test
    fun preSleepLookback_reachesBeforeWindowOpening() {
        // Uses BEFORE the window opens (the collection lead) count toward the 2h before phone-down,
        // but never become part of the night.
        val events = use(-90 * 60_000L) + use(-60 * 60_000L) + use(-30 * 60_000L) +
            use(10 * 60_000L) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertTrue(result.interactions.all { it.startMillis >= 0L }) // nothing before the opening
        assertEquals(12 * 60_000L, result.phoneDownTime)
        assertEquals(8 * 60_000L, result.preSleepPhoneTimeMillis) // 4 uses x 2 min
    }

    @Test
    fun useStraddlingWindowOpening_isClippedForTheNight() {
        // An unlocked use from 5 min before the opening to 3 min after it.
        val straddle = listOf(
            RawScreenEvent(-5 * 60_000L, ScreenEventType.SCREEN_INTERACTIVE),
            RawScreenEvent(-5 * 60_000L + 1_000L, ScreenEventType.KEYGUARD_HIDDEN),
            RawScreenEvent(3 * 60_000L, ScreenEventType.SCREEN_NON_INTERACTIVE)
        )
        val result = analyzer.analyze(straddle + fill(9 * H, 20 * H, 80L), anchors)

        val first = result.interactions.first()
        assertEquals(0L, first.startMillis)
        assertEquals(3 * 60_000L, first.endMillis)
        assertEquals(3 * 60_000L, result.phoneDownTime)
        assertEquals(8 * 60_000L, result.preSleepPhoneTimeMillis) // the whole use, before + after
    }

    @Test
    fun useOnlyBeforeWindow_phoneDownStillUnknown() {
        // Last use before the opening; the night starts at the opening -> phone-down is UNKNOWN.
        val events = use(-30 * 60_000L) + fill(10 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(0L, result.mainRestEpisode!!.startMillis)
        assertNull(result.phoneDownTime)
        assertNull(result.preSleepPhoneTimeMillis)
    }

    // ---- Future-window cap (nowMillis) ----------------------------------

    @Test
    fun doesNotInferQuietOrRecoveryAfterNow() {
        // Collected at 13:00 (now = 15H). Last real use at noon (14H), then the phone is idle.
        // Without the cap, the gap to 18:00 would become a phantom DAYTIME_RECOVERY nap.
        val now = 15 * H
        val events = use(10 * 60_000L) + use(9 * H) + use(14 * H)
        val result = analyzer.analyze(events, anchors, now)

        assertTrue("no quiet block may extend past now", result.quietBlocks.all { it.endMillis <= now })
        assertFalse(
            "no phantom afternoon recovery in the future",
            result.quietBlocks.any { it.label == QuietBlockLabel.DAYTIME_RECOVERY_SLEEP }
        )
    }

    // ---- Phase 1: schedule-agnostic structural model (additive) ---------

    @Test
    fun restPattern_minimal_whenNoSleepLikeBlock() {
        val result = analyzer.analyze(fill(0L, 20 * H, 60L), anchors)

        assertEquals(RestPattern.MINIMAL_REST, result.restPattern)
        assertNull(result.primaryRest)
        assertTrue(result.restPeriods.all { it.role == RestRole.BRIEF_QUIET })
    }

    @Test
    fun restPattern_consolidated_singleCleanBlock() {
        val result = analyzer.analyze(use(10 * 60_000L) + fill(9 * H, 20 * H, 80L), anchors)

        assertEquals(RestPattern.CONSOLIDATED, result.restPattern)
        assertEquals(RestRole.PRIMARY_REST, result.primaryRest?.role)
        assertTrue(result.secondaryRests.isEmpty())
    }

    @Test
    fun restPattern_fragmented_multipleRestBlocks() {
        val events = use(10 * 60_000L) +
            use(2 * H) + use(2 * H + 40 * 60_000L) + use(5 * H) +
            fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(RestPattern.FRAGMENTED, result.restPattern)
        assertEquals(1, result.restPeriods.count { it.role == RestRole.PRIMARY_REST })
        assertTrue(result.secondaryRests.isNotEmpty())
    }

    @Test
    fun oneShortAwakeningStillConsolidated() {
        // 0.3: one brief unlocked check (03:00), quiet returns -> bridged -> CONSOLIDATED.
        // (This input was previously asserted FRAGMENTED; that assertion encoded the 0.3 bug.)
        val events = use(10 * 60_000L) + use(5 * H) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(RestPattern.CONSOLIDATED, result.restPattern)
        assertEquals(1, result.nighttimeAwakenings.size)
    }

    @Test
    fun primaryRest_isLongest_regardlessOfClock() {
        // Active until ~13:00, then the longest rest is in the afternoon.
        // Structurally it is the PRIMARY_REST, even though the old clock model
        // labels the very same block DAYTIME_RECOVERY_SLEEP. Both coexist.
        val events = fill(0L, 16 * H, 70L) + use(19 * H + H / 2)
        val result = analyzer.analyze(events, anchors)

        assertEquals(RestRole.PRIMARY_REST, result.primaryRest?.role)
        assertEquals(QuietBlockLabel.DAYTIME_RECOVERY_SLEEP, result.primaryRest?.block?.label)
    }

    @Test
    fun secondaryRests_rankedByDurationDescending() {
        val events = use(10 * 60_000L) +
            use(2 * H) + use(2 * H + 40 * 60_000L) + use(5 * H) +
            fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        val durations = result.secondaryRests.map { it.block.durationMillis }
        assertTrue(durations.size >= 2)
        assertEquals(durations.sortedDescending(), durations)
    }

    @Test
    fun firstUseAfterPrimaryRest_isStructural() {
        val events = use(10 * 60_000L) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        val primaryEnd = result.primaryRest!!.block.endMillis
        val expected = result.interactions
            .filter { it.startMillis >= primaryEnd }
            .minByOrNull { it.startMillis }!!.startMillis
        assertEquals(expected, result.firstUseAfterPrimaryRest)
    }

    @Test
    fun primaryRest_isTheLongestSleepLikeBlock() {
        val result = analyzer.analyze(use(10 * 60_000L) + use(5 * H) + fill(9 * H, 20 * H, 80L), anchors)

        val longest = result.quietBlocks.filter { it.isSleepLike }.maxByOrNull { it.durationMillis }
        assertEquals(longest, result.primaryRest?.block)
    }

    @Test
    fun everyQuietBlockHasARole() {
        val events = use(10 * 60_000L) + use(5 * H) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(result.quietBlocks.size, result.restPeriods.size)
        result.quietBlocks.zip(result.restPeriods).forEach { (block, period) ->
            assertEquals(block, period.block)
            if (block.label == QuietBlockLabel.MICRO_QUIET_BLOCK) {
                assertEquals(RestRole.BRIEF_QUIET, period.role)
            }
        }
    }

    // ---- 0.3: bridged main rest episode ---------------------------------

    @Test
    fun shortUnlockedUseFollowedByQuiet_bridgesPrimaryRest() {
        // Sleep, a brief unlocked check at 03:00, back to sleep until 07:00.
        val events = use(10 * 60_000L) + use(5 * H) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        val episode = result.mainRestEpisode!!
        // The episode extends past the brief check to the real morning, beyond the primary block.
        assertEquals(9 * H, episode.endMillis)
        assertTrue(episode.endMillis > result.primaryRest!!.block.endMillis)
    }

    @Test
    fun firstUseAfterPrimaryRest_isFinalMorningUseNotShortInterruption() {
        val events = use(10 * 60_000L) + use(5 * H) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        // The bridged field points at the real morning; the primary-block field still sees the check.
        assertEquals(9 * H, result.firstUseAfterMainRest)
        assertEquals(5 * H, result.firstUseAfterPrimaryRest)
    }

    // ---- 1.1.0: interruption vs final wake ------------------------------

    private val M = 60_000L

    /** Unlocked uses every [stepMin] minutes in [from, to): continuous phone activity. */
    private fun activeFrom(from: Long, to: Long, stepMin: Long = 15L): List<RawScreenEvent> {
        val out = mutableListOf<RawScreenEvent>()
        var t = from
        while (t < to) { out += use(t); t += stepMin * M }
        return out
    }

    @Test
    fun fortyMinuteWake_isAnInterruption() {
        // A ~42-min active wake (01:00–01:42) between two sleep blocks is <= 60 min -> joined.
        // (Before 1.1.0 the limit was 30 min and this night was split.)
        val events = use(10 * M) + use(3 * H) + use(3 * H + 40 * M) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        val episode = result.mainRestEpisode!!
        assertEquals(12 * M, episode.startMillis)
        assertEquals(9 * H, episode.endMillis)
        assertEquals(1, result.nighttimeAwakenings.size)
        assertEquals(RestPattern.CONSOLIDATED, result.restPattern)
        // Net duration: the 42-min interruption is subtracted from the window.
        assertEquals((9 * H - 12 * M) - 42 * M, episode.durationMillis)
    }

    @Test
    fun longWakeFollowedByLongQuiet_isLongInterruption() {
        // Sleep 22:12–01:00, awake with the phone 01:00–02:17 (77 min), sleep 02:17–07:00 (4h43m).
        val events = use(10 * M) + activeFrom(3 * H, 4 * H + 30 * M) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        val episode = result.mainRestEpisode!!
        assertEquals(12 * M, episode.startMillis)
        assertEquals(9 * H, episode.endMillis)
        assertEquals(listOf(3 * H), result.nighttimeAwakenings)
        assertEquals(RestPattern.FRAGMENTED, result.restPattern) // one LONG interruption
        assertEquals((9 * H - 12 * M) - 77 * M, episode.durationMillis)
    }

    @Test
    fun longWakeWithoutLongQuietAfter_isFinalWake() {
        // Sleep 22:12–04:00, awake 04:00–05:17 (77 min), then only 1h43m of quiet -> final wake 04:00.
        val events = use(10 * M) + activeFrom(6 * H, 7 * H + 30 * M) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(6 * H, result.mainRestEpisode!!.endMillis)
        assertEquals(6 * H, result.firstUseAfterMainRest)
        assertTrue(result.nighttimeAwakenings.isEmpty())
        assertEquals(RestPattern.FRAGMENTED, result.restPattern) // the later quiet is a separate rest
    }

    @Test
    fun wakeLongerThanCap_isNeverAnInterruption() {
        // Sleep 22:12–00:00, awake with the phone 00:00–03:47 (> 3h), sleep 03:47–10:00.
        val events = use(10 * M) + activeFrom(2 * H, 5 * H + 46 * M) + fill(12 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        val episode = result.mainRestEpisode!!
        assertEquals(5 * H + 47 * M, episode.startMillis) // the earlier block is NOT joined
        assertTrue(result.nighttimeAwakenings.isEmpty())
    }

    @Test
    fun briefCheckInsideReturningQuiet_stillCountsAsLongReturn() {
        // Sleep 22:12–01:00, awake 01:00–02:17 (77 min), quiet 02:17–04:00, a 2-min check at 04:00,
        // quiet 04:02–07:00. The returning quiet is 4h43m in total (the check doesn't break it).
        val events = use(10 * M) + activeFrom(3 * H, 4 * H + 30 * M) + use(6 * H) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        val episode = result.mainRestEpisode!!
        assertEquals(12 * M, episode.startMillis)
        assertEquals(9 * H, episode.endMillis)
        assertEquals(2, result.nighttimeAwakenings.size)
    }

    @Test
    fun multipleSignificantAwakeningsCanBeFragmented() {
        // Two brief bridged interruptions (01:00, 04:00) -> 2 awakenings in one run -> FRAGMENTED.
        val events = use(10 * 60_000L) + use(3 * H) + use(6 * H) + fill(9 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        assertEquals(2, result.nighttimeAwakenings.size)
        assertEquals(RestPattern.FRAGMENTED, result.restPattern)
    }

    // ---- 1a: unclosed / open Screen On must not fabricate REAL_USE ------

    @Test
    fun unclosedScreenOn_doesNotEndSleep() {
        // 22:10 phone-down use, then a bare Screen On at 02:13 (no Screen Off, no unlock),
        // then a real unlocked use at 10:00. The 02:13 blip must NOT split the sleep.
        val blip = 4 * H + 13 * 60_000L // 02:13
        val events =
            use(10 * 60_000L) +
            listOf(RawScreenEvent(blip, ScreenEventType.SCREEN_INTERACTIVE)) +
            use(12 * H) +                 // 10:00 morning use
            fill(13 * H, 20 * H, 80L)
        val result = analyzer.analyze(events, anchors)

        // The blip never becomes an interaction.
        assertFalse(result.interactions.any { it.startMillis == blip })
        // One uninterrupted sleep-like block spans the blip's timestamp.
        assertTrue(result.quietBlocks.any { it.startMillis <= blip && it.endMillis >= blip && it.isSleepLike })
        // Wake anchor is the real morning use, not the blip.
        assertEquals(12 * H, result.firstUseAfterPrimaryRest)
        assertTrue(result.nighttimeAwakenings.isEmpty())
    }

    @Test
    fun openScreenOnAtCollection_isNotMultiHourUse() {
        // Collected at 13:00 (now = 15H). A bare Screen On at 12:00 (14H), never closed, no unlock.
        // Old code stretched it to now (1h "use"); it must instead be dropped.
        val now = 15 * H
        val blip = 14 * H
        val events = use(10 * 60_000L) + use(9 * H) +
            listOf(RawScreenEvent(blip, ScreenEventType.SCREEN_INTERACTIVE))
        val result = analyzer.analyze(events, anchors, now)

        assertFalse(result.interactions.any { it.startMillis == blip })
        // The quiet after the 07:00 use is one block across the blip, not split at 12:00.
        assertTrue(result.quietBlocks.any { it.startMillis <= blip && it.endMillis >= blip && it.isSleepLike })
    }

    @Test
    fun farUnlockNotSwallowedByUnclosedScreenOn() {
        // A bare Screen On at 03:00 (no Off, no nearby unlock); the next unlock belongs to the
        // 07:00 use, hours later. The far unlock must NOT promote the 03:00 blip to REAL_USE.
        val blip = 5 * H // 03:00
        val events = use(10 * 60_000L) +
            listOf(RawScreenEvent(blip, ScreenEventType.SCREEN_INTERACTIVE)) +
            fill(9 * H, 20 * H, 80L)      // active day from 07:00; its first unlock is far from the blip
        val result = analyzer.analyze(events, anchors)

        assertFalse(result.interactions.any { it.startMillis == blip })
        assertTrue(result.quietBlocks.any { it.startMillis <= blip && it.endMillis >= blip && it.isSleepLike })
        assertTrue(result.nighttimeAwakenings.isEmpty())
    }

    @Test
    fun quickUnlockAfterMissedOff_stillCounts() {
        // Regression guard: a genuine quick unlock right after an unclosed Screen On (within the
        // association window) is still REAL_USE — we only drop unclosed blips with NO nearby unlock.
        val t = 5 * H // 03:00
        val events = use(10 * 60_000L) +
            listOf(
                RawScreenEvent(t, ScreenEventType.SCREEN_INTERACTIVE),
                RawScreenEvent(t + 1_000L, ScreenEventType.KEYGUARD_HIDDEN)
            ) +
            fill(9 * H, 20 * H, 80L)      // active day from 07:00 (first use closes the night)
        val result = analyzer.analyze(events, anchors)

        assertTrue(result.interactions.any { it.startMillis == t })
        // It sits between two sleep-like blocks -> counted as one awakening.
        assertEquals(1, result.nighttimeAwakenings.size)
    }

    // ---- Empty input ----------------------------------------------------

    @Test
    fun emptyInput_isUnknownAndLowConfidence() {
        val result = analyzer.analyze(emptyList(), anchors)

        assertTrue(result.interactions.isEmpty())
        assertNull(result.phoneDownTime)
        assertTrue(result.flags.contains(AnalysisFlag.WINDOW_OPENS_IN_QUIET))
        assertTrue(result.flags.contains(AnalysisFlag.NO_MORNING_INTERACTION))
        assertEquals(Confidence.LOW, result.confidence)
    }
}
