package com.sleepguard.poc

import kotlin.math.max
import kotlin.math.min

/** Identifies which analyzer logic produced a stored record (for future re-analysis). */
const val ANALYZER_VERSION = "1.1.0"

/**
 * SleepGuard MVP analyzer.
 *
 * Turns raw screen/lock events from the official window (yesterday 18:00 ->
 * today 18:00) into a flexible "pattern around sleep" picture that tolerates
 * broken, late, inverted, and white-night sleep — without ever claiming the user
 * "slept". It only reports *possible sleep-like quiet periods* and device activity.
 *
 * Pure JVM logic: no Android dependencies, fully unit-testable. All timezone work
 * happens upstream and arrives as absolute timestamps in [WindowAnchors].
 *
 * Pipeline:
 *   1. Build REAL_USE interactions (the "meaningful interaction" filter, §3).
 *   2. Detect quiet blocks between them and label each by precedence (§4).
 *   3. Derive the five MVP answers + verdict + confidence + flags (§6–9).
 */
class NightPatternAnalyzer(private val config: AnalysisConfig = AnalysisConfig()) {

    /**
     * @param nowMillis the moment of collection. The analyzer NEVER infers activity or
     *   quiet periods after this point: the effective data end is min(windowEnd, now), so
     *   the future portion of the window (when collected before 18:00) is ignored. Defaults
     *   to windowEnd (full window) for tests that supply complete, past-only data.
     */
    fun analyze(
        rawEvents: List<RawScreenEvent>,
        anchors: WindowAnchors,
        nowMillis: Long = anchors.windowEnd
    ): NightPatternResult {
        // Cap the data end at the collection moment: nothing in the future is analyzed.
        val effectiveEnd = min(anchors.windowEnd, nowMillis)
        // Lock/unlock events are the real-use signal. A window with none at all (no lock screen)
        // falls back to the screen-on duration rule — otherwise the whole day would read as sleep.
        val lockSignalsPresent = rawEvents.any {
            it.type == ScreenEventType.KEYGUARD_SHOWN || it.type == ScreenEventType.KEYGUARD_HIDDEN
        }
        val interactions = buildRealUseInteractions(rawEvents, anchors, effectiveEnd, lockSignalsPresent)
        val quietBlocks = buildQuietBlocks(interactions, anchors, effectiveEnd)

        val sleepLike = quietBlocks.filter { it.isSleepLike }.sortedBy { it.startMillis }
        val primary = sleepLike.maxByOrNull { it.durationMillis }

        // --- Main sleep run: the sleep-like blocks connected to the primary block through
        //     interruptions. The activity between two sleep-like blocks (first touch -> quiet
        //     returns) is an interruption when it is <= awakeningMax, or <= longInterruptionMax
        //     AND the rest that resumes after it lasts >= longReturnMin. Otherwise it is the final
        //     wake (forward) / the start of the night (backward). Each crossed interruption is one
        //     nighttime awakening (lower bound). mainEnd anchors "first use after". ---
        val awakeningMaxMillis = config.awakeningMaxMinutes * MIN
        val longInterruptionMaxMillis = config.longInterruptionMaxMinutes * MIN
        val longReturnMinMillis = config.longReturnMinMinutes * MIN
        // restEnd[i]: end of the rest that resumes at block i — block i plus the blocks after it that
        // are joined by short interruptions (a brief check inside the returning quiet doesn't break it).
        val restEnd = LongArray(sleepLike.size)
        for (i in sleepLike.indices.reversed()) {
            restEnd[i] = if (i + 1 < sleepLike.size &&
                sleepLike[i + 1].startMillis - sleepLike[i].endMillis <= awakeningMaxMillis
            ) restEnd[i + 1] else sleepLike[i].endMillis
        }
        // Does the activity between block i-1 and block i count as an interruption (join them)?
        fun joinsPrevious(i: Int): Boolean {
            val active = sleepLike[i].startMillis - sleepLike[i - 1].endMillis
            if (active <= awakeningMaxMillis) return true
            return active <= longInterruptionMaxMillis &&
                restEnd[i] - sleepLike[i].startMillis >= longReturnMinMillis
        }
        var mainStart: Long? = null
        var mainEnd: Long? = null
        var mainRunSize = 0
        val awakenings = mutableListOf<Long>()
        var interruptionMillis = 0L
        var hasLongInterruption = false
        if (primary != null) {
            val idx = sleepLike.indexOf(primary)
            var lo = idx
            var hi = idx
            while (lo > 0 && joinsPrevious(lo)) lo--
            while (hi < sleepLike.size - 1 && joinsPrevious(hi + 1)) hi++
            mainStart = sleepLike[lo].startMillis
            mainEnd = sleepLike[hi].endMillis
            mainRunSize = hi - lo + 1
            for (j in (lo + 1)..hi) {
                awakenings.add(sleepLike[j - 1].endMillis)
                val active = sleepLike[j].startMillis - sleepLike[j - 1].endMillis
                interruptionMillis += active
                if (active > awakeningMaxMillis) hasLongInterruption = true
            }
        }

        // --- 0.3: the bridged MAIN REST EPISODE (primary + sleep-like blocks joined by
        //     interruptions) and the first use after it. The headline + History display these;
        //     primaryRest / firstUseAfterPrimaryRest (single longest block) stay unchanged.
        //     Its duration is NET: the interruptions inside it are subtracted. ---
        val mainRestEpisode: MainRestEpisode? =
            if (mainStart != null && mainEnd != null)
                MainRestEpisode(mainStart, mainEnd, (mainEnd - mainStart) - interruptionMillis)
            else null
        val firstUseAfterMainRest: Long? = mainEnd?.let { me ->
            interactions.filter { it.startMillis >= me }.minByOrNull { it.startMillis }?.startMillis
        }

        // --- phoneDownTime: end of the last REAL_USE before the primary block.
        //     Null (UNKNOWN) when the window opened mid-sleep (no prior use). ---
        val phoneDownTime: Long? = primary?.let { p ->
            interactions.filter { it.endMillis <= p.startMillis }.maxOfOrNull { it.endMillis }
        }

        // --- WINDOW_OPENS_IN_QUIET: the opening edge gap is itself sleep-like. ---
        val firstUseStart = interactions.minByOrNull { it.startMillis }?.startMillis ?: effectiveEnd
        val openingGap = firstUseStart - anchors.windowStart
        val windowOpensInQuiet = openingGap >= config.windowOpensInQuietMinMinutes * MIN

        // --- preSleepPhoneTime: REAL_USE time in the lookback before phone-down. ---
        val preSleepPhoneTime: Long? = phoneDownTime?.let { down ->
            val lookbackStart = max(down - config.preSleepLookbackMinutes * MIN, anchors.windowStart)
            interactions.sumOf { i ->
                val s = max(i.startMillis, lookbackStart)
                val e = min(i.endMillis, down)
                if (e > s) e - s else 0L
            }
        }

        // --- firstMorning: first REAL_USE after the main sleep run (or after 06:00 when
        //     there is no sleep run). Feeds confidence + the NO_MORNING_INTERACTION flag. ---
        val reference = mainEnd ?: anchors.morningStart
        val firstMorning = interactions.filter { it.startMillis >= reference }
            .minByOrNull { it.startMillis }?.startMillis

        // --- Verdict (§7) ---
        val nightPattern = if (primary == null) {
            NightPattern.ACTIVE_NIGHT
        } else when {
            primary.label == QuietBlockLabel.DELAYED_SLEEP_LIKE -> NightPattern.DELAYED_SLEEP_LIKE
            else -> {
                val overnightSleepLike = sleepLike.any { it.startMillis < anchors.morningStart }
                val recoveryPrimary = primary.label == QuietBlockLabel.MORNING_RECOVERY_SLEEP ||
                    primary.label == QuietBlockLabel.DAYTIME_RECOVERY_SLEEP
                when {
                    !overnightSleepLike && recoveryPrimary -> NightPattern.RECOVERY_SLEEP
                    awakenings.size >= 2 || sleepLike.size >= 2 -> NightPattern.FRAGMENTED_SLEEP_LIKE
                    else -> NightPattern.CONSOLIDATED_SLEEP_LIKE
                }
            }
        }

        // --- Flags ---
        val flags = mutableSetOf<AnalysisFlag>()
        if (windowOpensInQuiet) flags.add(AnalysisFlag.WINDOW_OPENS_IN_QUIET)
        if (firstMorning == null) flags.add(AnalysisFlag.NO_MORNING_INTERACTION)
        if (sleepLike.size >= 2) flags.add(AnalysisFlag.MULTIPLE_SLEEP_BLOCKS)
        if (nightPattern == NightPattern.ACTIVE_NIGHT) flags.add(AnalysisFlag.ACTIVE_NIGHT)
        if (primary?.label == QuietBlockLabel.DELAYED_SLEEP_LIKE) flags.add(AnalysisFlag.DELAYED_PRIMARY_SLEEP)
        val hasScreenEvents = rawEvents.any { it.type == ScreenEventType.SCREEN_INTERACTIVE }
        if (hasScreenEvents && !lockSignalsPresent) flags.add(AnalysisFlag.NO_LOCK_EVENTS)

        // --- Confidence (§8): measurement certainty of the sleep claim. ---
        val confidence = when {
            nightPattern == NightPattern.ACTIVE_NIGHT || windowOpensInQuiet || firstMorning == null ->
                Confidence.LOW
            primary?.label == QuietBlockLabel.MAIN_SLEEP_LIKE_BLOCK && awakenings.size <= 1 ->
                Confidence.HIGH
            else -> Confidence.MEDIUM
        }

        // --- Phase 1: schedule-agnostic structural model (additive; no clock anchors) ---
        // primary == the longest sleep-like block (rank-based, not duration-gated, not clock-based).
        val restPeriods = quietBlocks.map { block ->
            val role = when {
                block === primary -> RestRole.PRIMARY_REST
                block.isSleepLike -> RestRole.SECONDARY_REST
                else -> RestRole.BRIEF_QUIET
            }
            RestPeriod(block, role)
        }
        val primaryRest = restPeriods.firstOrNull { it.role == RestRole.PRIMARY_REST }
        val secondaryRests = restPeriods
            .filter { it.role == RestRole.SECONDARY_REST }
            .sortedByDescending { it.block.durationMillis }
        // 0.3: one main episode with 0–1 short bridged interruptions is CONSOLIDATED. FRAGMENTED when
        // there are 2+ awakenings inside the episode, a single LONG (> awakeningMax) interruption, OR
        // a sleep-like block that could not be bridged into the main run (a second rest period).
        val restPattern = when {
            sleepLike.isEmpty() -> RestPattern.MINIMAL_REST
            awakenings.size >= 2 -> RestPattern.FRAGMENTED
            hasLongInterruption -> RestPattern.FRAGMENTED
            sleepLike.size - mainRunSize >= 1 -> RestPattern.FRAGMENTED
            else -> RestPattern.CONSOLIDATED
        }
        val firstUseAfterPrimaryRest = primary?.let { p ->
            interactions.filter { it.startMillis >= p.endMillis }.minByOrNull { it.startMillis }?.startMillis
        }

        return NightPatternResult(
            windowStart = anchors.windowStart,
            windowEnd = anchors.windowEnd,
            interactions = interactions,
            quietBlocks = quietBlocks,
            nightPattern = nightPattern,
            phoneDownTime = phoneDownTime,
            longestQuietBlock = primary,
            preSleepPhoneTimeMillis = preSleepPhoneTime,
            nighttimeAwakenings = awakenings,
            confidence = confidence,
            flags = flags,
            restPattern = restPattern,
            restPeriods = restPeriods,
            primaryRest = primaryRest,
            secondaryRests = secondaryRests,
            firstUseAfterPrimaryRest = firstUseAfterPrimaryRest,
            mainRestEpisode = mainRestEpisode,
            firstUseAfterMainRest = firstUseAfterMainRest
        )
    }

    // -----------------------------------------------------------------------
    // Step 1 — meaningful-interaction filter
    // -----------------------------------------------------------------------

    private fun buildRealUseInteractions(
        rawEvents: List<RawScreenEvent>,
        anchors: WindowAnchors,
        effectiveEnd: Long,
        lockSignalsPresent: Boolean
    ): List<MeaningfulInteraction> {
        val events = rawEvents.sortedBy { it.timestampMillis }
        val keyguardHidden = events
            .filter { it.type == ScreenEventType.KEYGUARD_HIDDEN }
            .map { it.timestampMillis }
        val keyguardEvents = events.filter {
            it.type == ScreenEventType.KEYGUARD_SHOWN || it.type == ScreenEventType.KEYGUARD_HIDDEN
        }
        val screenOffs = events
            .filter { it.type == ScreenEventType.SCREEN_NON_INTERACTIVE }
            .map { it.timestampMillis }
        val carryOverMillis = config.unlockCarryOverMinutes * MIN

        // Was the phone still unlocked when the screen turned on at [t]? True when the last lock
        // event before [t] is an unlock (no KEYGUARD_SHOWN since), and the phone has not sat
        // unlocked-but-idle for longer than the carry-over limit (guards against a missed lock event).
        fun stillUnlockedAt(t: Long): Boolean {
            val last = keyguardEvents.lastOrNull { it.timestampMillis <= t } ?: return false
            if (last.type != ScreenEventType.KEYGUARD_HIDDEN) return false
            val lastOff = screenOffs.lastOrNull { it <= t } ?: Long.MIN_VALUE
            return t - max(last.timestampMillis, lastOff) <= carryOverMillis
        }

        // Pair SCREEN_INTERACTIVE -> next SCREEN_NON_INTERACTIVE into raw sessions, tracking HOW
        // each session closed. A SCREEN_INTERACTIVE that never sees its own SCREEN_NON_INTERACTIVE
        // (closed only by the NEXT SCREEN_INTERACTIVE, or still open at collection) has an UNKNOWN
        // on-duration — it must NOT be stretched to the next event / effectiveEnd, or a passive
        // blip (e.g. a notification-lit screen) becomes a fake multi-hour "use" that ends sleep (1a).
        val sessions = mutableListOf<RawSession>()
        var openStart: Long? = null
        for (e in events) {
            when (e.type) {
                ScreenEventType.SCREEN_INTERACTIVE -> {
                    val s = openStart
                    if (s != null) sessions.add(RawSession(s, s, SessionClose.MISSED_OFF)) // unknown end -> a point
                    openStart = e.timestampMillis
                }
                ScreenEventType.SCREEN_NON_INTERACTIVE -> {
                    val s = openStart
                    if (s != null) {
                        sessions.add(RawSession(s, e.timestampMillis, SessionClose.CLOSED_OFF))
                        openStart = null
                    }
                }
                else -> Unit // KEYGUARD_* / UNKNOWN not used for session boundaries
            }
        }
        openStart?.let { sessions.add(RawSession(it, it, SessionClose.OPEN_AT_END)) } // unknown end -> a point

        val minOnMillis = config.meaningfulMinScreenOnSeconds * 1000L
        val result = mutableListOf<MeaningfulInteraction>()
        for (s in sessions) {
            when (s.close) {
                // Properly closed session. Real use = the phone was unlocked while the screen was on
                // (an unlock during the session, or still unlocked from before). A lit but LOCKED
                // screen — alarm, incoming call, notification — is never real use, however long.
                // Fallback for phones with no lock events at all: the screen-on duration rule.
                SessionClose.CLOSED_OFF -> {
                    val start = max(s.start, anchors.windowStart)
                    val end = min(s.end, effectiveEnd)
                    val duration = end - start
                    if (duration <= 0L) continue
                    val isRealUse = if (lockSignalsPresent) {
                        keyguardHidden.any { it in s.start..s.end } || stillUnlockedAt(s.start)
                    } else {
                        duration >= minOnMillis
                    }
                    if (isRealUse) result.add(MeaningfulInteraction(start, end, duration))
                }
                // Unknown on-duration: a screen-on we never saw close cannot count as real use by
                // duration. Only a genuine unlock NEAR ITS START (or a phone still unlocked from
                // before) makes it meaningful — and the unlock search is bounded so a far-away
                // unlock (e.g. next morning) is NOT swallowed (1a).
                SessionClose.MISSED_OFF, SessionClose.OPEN_AT_END -> {
                    val point = s.start
                    if (point < anchors.windowStart || point > effectiveEnd) continue
                    val wasUnlocked = keyguardHidden.any { it in point..(point + UNLOCK_ASSOC_WINDOW) } ||
                        stillUnlockedAt(point)
                    if (wasUnlocked) {
                        result.add(MeaningfulInteraction(point, point, 0L)) // duration unknown -> a point
                    }
                    // Otherwise a passive unclosed blip: dropped, must NOT break a quiet period.
                }
            }
        }
        return result.sortedBy { it.startMillis }
    }

    // -----------------------------------------------------------------------
    // Step 2 — quiet-block detection + labelling
    // -----------------------------------------------------------------------

    private fun buildQuietBlocks(
        interactions: List<MeaningfulInteraction>,
        anchors: WindowAnchors,
        effectiveEnd: Long
    ): List<QuietBlock> {
        val blocks = mutableListOf<QuietBlock>()
        val microMillis = config.microQuietMinMinutes * MIN
        var prevEnd = anchors.windowStart
        for (i in interactions) {
            addGap(blocks, prevEnd, i.startMillis, anchors, microMillis)
            prevEnd = max(prevEnd, i.endMillis)
        }
        // Trailing gap ends at the collection moment, never in the future.
        addGap(blocks, prevEnd, effectiveEnd, anchors, microMillis)
        return blocks
    }

    private fun addGap(
        out: MutableList<QuietBlock>,
        start: Long,
        end: Long,
        anchors: WindowAnchors,
        microMillis: Long
    ) {
        val duration = end - start
        if (duration < microMillis) return
        out.add(QuietBlock(start, end, duration, labelFor(start, duration, anchors)))
    }

    /** Label precedence — FIRST match wins (spec §4). */
    private fun labelFor(start: Long, durationMillis: Long, anchors: WindowAnchors): QuietBlockLabel {
        val minutes = durationMillis / MIN

        // 1. DELAYED: >= 3h AND starts in [04:00, 12:00)  (delayedSleepLatestStart = 12:00)
        if (minutes >= config.delayedSleepMinMinutes &&
            start >= anchors.morningEarliest && start < anchors.noon
        ) return QuietBlockLabel.DELAYED_SLEEP_LIKE

        // 2. MORNING_RECOVERY: >= 90 min AND starts in [06:00, 12:00)
        if (minutes >= config.recoveryMinMinutes &&
            start >= anchors.morningStart && start < anchors.noon
        ) return QuietBlockLabel.MORNING_RECOVERY_SLEEP

        // 3. DAYTIME_RECOVERY: >= 90 min AND starts in [12:00, 18:00)
        if (minutes >= config.recoveryMinMinutes &&
            start >= anchors.noon && start < anchors.windowEnd
        ) return QuietBlockLabel.DAYTIME_RECOVERY_SLEEP

        // 4. MAIN: >= 4h
        if (minutes >= config.mainSleepMinMinutes) return QuietBlockLabel.MAIN_SLEEP_LIKE_BLOCK

        // 5. SHORT: >= 90 min
        if (minutes >= config.shortSleepMinMinutes) return QuietBlockLabel.SHORT_SLEEP_LIKE_BLOCK

        // 6. MICRO: >= 20 min (gaps below microQuietMinMinutes never reach here)
        return QuietBlockLabel.MICRO_QUIET_BLOCK
    }

    /** How a raw screen-on session ended — determines whether its on-duration is trustworthy. */
    private enum class SessionClose { CLOSED_OFF, MISSED_OFF, OPEN_AT_END }

    private class RawSession(val start: Long, val end: Long, val close: SessionClose)

    private companion object {
        const val MIN = 60_000L // one minute in millis
        /** Max gap between an unclosed Screen On and an Unlock for them to be treated as one event. */
        const val UNLOCK_ASSOC_WINDOW = 60_000L
    }
}
