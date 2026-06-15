package com.sleepguard.poc

/**
 * Data models for the SleepGuard POC.
 *
 * Privacy note: by design these models carry ONLY timing + event-type information.
 * They never hold message content, notification text, app names, URLs, location, or
 * any other private content. See README.md for the full privacy statement.
 */

/** The only screen/lock event types this POC reads. */
enum class ScreenEventType {
    SCREEN_INTERACTIVE,
    SCREEN_NON_INTERACTIVE,
    KEYGUARD_SHOWN,
    KEYGUARD_HIDDEN,
    UNKNOWN
}

/** A single raw usage event, reduced to just a timestamp and a type. */
data class RawScreenEvent(
    val timestampMillis: Long,
    val type: ScreenEventType
)

// ---------------------------------------------------------------------------
// SleepGuard MVP pattern analysis (NightPatternAnalyzer)
//
// All thresholds live in AnalysisConfig so they can be tuned without touching
// logic. The absolute time boundaries of the official window (yesterday 22:00 ->
// today 18:00, plus the 04:00/06:00/12:00 anchors) live in WindowAnchors, which
// is computed once per run from the device timezone and passed into the analyzer.
// Keeping anchors out of the analyzer keeps it a pure, timezone-free function.
// ---------------------------------------------------------------------------

/**
 * Tunable thresholds for the pattern analysis. Durations are in minutes unless
 * the name says otherwise. Defaults are the approved SleepGuard MVP values.
 */
data class AnalysisConfig(
    /** A session counts as "real use" if screen-on lasted at least this long... */
    val meaningfulMinScreenOnSeconds: Long = 60L,
    /** ...OR (when true) the device was unlocked (KEYGUARD_HIDDEN) during it. */
    val treatUnlockAsMeaningful: Boolean = true,

    val microQuietMinMinutes: Long = 20L,      // 20–90 min  -> MICRO_QUIET_BLOCK
    val shortSleepMinMinutes: Long = 90L,      // 90 min–4h  -> SHORT_SLEEP_LIKE_BLOCK
    val mainSleepMinMinutes: Long = 240L,      // >= 4h      -> MAIN_SLEEP_LIKE_BLOCK
    val delayedSleepMinMinutes: Long = 180L,   // >= 3h, starts 04:00–12:00 -> DELAYED_SLEEP_LIKE
    val recoveryMinMinutes: Long = 90L,        // >= 90 min, morning/daytime recovery

    /** preSleepPhoneTime = REAL_USE time in this many minutes before phoneDownTime. */
    val preSleepLookbackMinutes: Long = 120L,
    /** A REAL_USE interruption no longer than this, between two sleep-like blocks, is an awakening. */
    val awakeningMaxMinutes: Long = 30L,
    /** If the window opens with a quiet stretch at least this long, phone-down is unobservable. */
    val windowOpensInQuietMinMinutes: Long = 90L
)

/**
 * Absolute millisecond boundaries for one analysis run, in the device timezone.
 * Using absolute timestamps (not hour-of-day) removes all across-midnight ambiguity.
 */
data class WindowAnchors(
    val windowStart: Long,     // yesterday 22:00
    val morningEarliest: Long, // today 04:00  (DELAYED earliest start)
    val morningStart: Long,    // today 06:00  (MORNING_RECOVERY earliest start)
    val noon: Long,            // today 12:00  (DELAYED latest start / DAYTIME_RECOVERY start)
    val windowEnd: Long        // today 18:00
)

/** A screen-use episode that passed the "meaningful interaction" filter. */
data class MeaningfulInteraction(
    val startMillis: Long,
    val endMillis: Long,
    val durationMillis: Long
)

/** Classification of a single quiet (no-meaningful-use) stretch. */
enum class QuietBlockLabel {
    MAIN_SLEEP_LIKE_BLOCK,
    DELAYED_SLEEP_LIKE,
    SHORT_SLEEP_LIKE_BLOCK,
    MORNING_RECOVERY_SLEEP,
    DAYTIME_RECOVERY_SLEEP,
    MICRO_QUIET_BLOCK
}

/** A detected quiet stretch with its classification. */
data class QuietBlock(
    val startMillis: Long,
    val endMillis: Long,
    val durationMillis: Long,
    val label: QuietBlockLabel
) {
    /** Sleep-like = anything >= 90 min (i.e. not a MICRO_QUIET_BLOCK). */
    val isSleepLike: Boolean get() = label != QuietBlockLabel.MICRO_QUIET_BLOCK
}

/** Top-line verdict for the whole window (heuristic). */
enum class NightPattern {
    CONSOLIDATED_SLEEP_LIKE,
    FRAGMENTED_SLEEP_LIKE,
    DELAYED_SLEEP_LIKE,
    RECOVERY_SLEEP,
    ACTIVE_NIGHT
}

enum class Confidence { HIGH, MEDIUM, LOW }

/** Machine-readable caveats attached to a result. */
enum class AnalysisFlag {
    WINDOW_OPENS_IN_QUIET,
    NO_MORNING_INTERACTION,
    ACTIVE_NIGHT,
    DELAYED_PRIMARY_SLEEP,
    MULTIPLE_SLEEP_BLOCKS
}

// ---------------------------------------------------------------------------
// Phase 1: schedule-agnostic structural model (ADDITIVE — runs alongside the
// clock-based model above; classification uses rank/duration/structure only,
// never clock anchors). See SCHEDULE_AGNOSTIC_DESIGN.md.
// ---------------------------------------------------------------------------

/** Structural role of a quiet block, independent of clock time. */
enum class RestRole {
    PRIMARY_REST,    // the longest sleep-like block, whenever it occurs
    SECONDARY_REST,  // any other sleep-like block (a nap)
    BRIEF_QUIET      // 20–90 min gap (the old MICRO)
}

/** Schedule-agnostic verdict for the window. */
enum class RestPattern {
    CONSOLIDATED,   // one dominant rest period, <= 1 awakening
    FRAGMENTED,     // multiple rest periods and/or several awakenings
    MINIMAL_REST    // no sleep-like block anywhere in the window
}

/** A quiet block annotated with its structural role. Wraps QuietBlock (geometry unchanged). */
data class RestPeriod(
    val block: QuietBlock,
    val role: RestRole
)

/**
 * Full result of the MVP pattern analysis.
 *
 * Nullable Long fields represent UNKNOWN (e.g. phoneDownTime when the window
 * opened mid-sleep). The product must NEVER render these as "0" or fabricate a
 * value — show "Unknown" and defer to the morning self-report.
 */
data class NightPatternResult(
    val windowStart: Long,
    val windowEnd: Long,
    val interactions: List<MeaningfulInteraction>,
    val quietBlocks: List<QuietBlock>,
    val nightPattern: NightPattern,
    val phoneDownTime: Long?,                 // UNKNOWN -> null
    val longestQuietBlock: QuietBlock?,       // the primary sleep-like block
    val preSleepPhoneTimeMillis: Long?,       // UNKNOWN -> null
    val nighttimeAwakenings: List<Long>,      // start times; LOWER BOUND
    val confidence: Confidence,
    val flags: Set<AnalysisFlag>,
    // --- Phase 1 additive structural model (clock-agnostic; computed alongside the above) ---
    val restPattern: RestPattern,
    val restPeriods: List<RestPeriod>,        // one per quiet block, chronological order
    val primaryRest: RestPeriod?,             // the PRIMARY_REST period, or null
    val secondaryRests: List<RestPeriod>,     // SECONDARY_REST periods, duration descending
    val firstUseAfterPrimaryRest: Long?       // structural; UNKNOWN -> null
)
