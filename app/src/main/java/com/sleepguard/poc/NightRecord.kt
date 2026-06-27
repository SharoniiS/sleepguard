package com.sleepguard.poc

import kotlinx.serialization.Serializable

/**
 * Local-storage data model (DEBUG / POC).
 *
 * These are dedicated, serializable DTOs — intentionally decoupled from the analyzer's
 * runtime models (`NightPatternResult`, `QuietBlock`, ...). That decoupling lets the
 * analysis logic keep evolving (e.g. the schedule-agnostic migration) without breaking
 * already-saved files. Enum values are stored as their string `.name` for the same reason.
 *
 * PRIVACY / SCOPE:
 *  - Stored only in app-private internal storage. No external files, no sharing, no network.
 *  - Contains ONLY timestamps + event types + derived summary. Never content, app/package/
 *    class names, URLs, input, location, etc.
 *  - [NightRecord.events] (the filtered raw events) is kept for DEBUGGING/TESTING only.
 *    The app is on-device only and never uploads them; the user-initiated backup export shares
 *    the whole record (events included) as a local file the user chooses where to send.
 */

/**
 * Bump when the on-disk shape of [NightRecord] changes (enables future migrations).
 * v2 (0.3): added nullable [NightRecord.mainRestEpisode] + [NightRecord.firstUseAfterMainRestMillis].
 * Non-destructive: both fields default to null, so v1 records still deserialize, and
 * [NightRepository.loadAll] does not gate on this version (nothing is dropped).
 */
const val STORAGE_SCHEMA_VERSION = 2

@Serializable
data class NightRecord(
    val schemaVersion: Int,           // == STORAGE_SCHEMA_VERSION at write time
    /**
     * Stable identity for a saved night. We use `nightOf` (the morning's ISO date) rather
     * than a window-millis pair: it guarantees exactly one record per night (clean upsert,
     * no duplicate-night clutter) and is stable across re-collection and minor timezone /
     * window-time shifts.
     */
    val recordId: String,
    val nightOf: String,              // ISO local date of the window END (the morning), "2026-06-14"
    val analyzerVersion: String,      // which analyzer logic produced this (see ANALYZER_VERSION)
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val collectedAtMillis: Long,
    val restPattern: String,          // RestPattern.name
    val confidence: String,           // Confidence.name
    val primaryRest: StoredBlock?,
    val secondaryRests: List<StoredBlock>,
    val phoneDownMillis: Long?,
    val firstUseAfterPrimaryRestMillis: Long?,
    val preSleepPhoneTimeMillis: Long?,
    val awakenings: List<Long>,
    val flags: List<String>,          // AnalysisFlag.name list
    val rawEventCount: Int,
    val filteredEventCount: Int,
    val config: StoredConfig,         // analyzer config snapshot used for this record
    /** DEBUG/POC ONLY — filtered raw events (timestamp + type, no content). On-device only. */
    val events: List<StoredEvent>,
    // --- 0.3 (additive; nullable WITH defaults so pre-v2 records still deserialize) ---
    val mainRestEpisode: StoredMainEpisode? = null,
    val firstUseAfterMainRestMillis: Long? = null
)

@Serializable
data class StoredBlock(
    val startMillis: Long,
    val endMillis: Long,
    val durationMillis: Long,
    val role: String,                 // RestRole.name
    val label: String                 // QuietBlockLabel.name (both models coexist for now)
)

/** 0.3: the bridged main sleep episode (primary block extended across short interruptions). */
@Serializable
data class StoredMainEpisode(
    val startMillis: Long,
    val endMillis: Long,
    val durationMillis: Long
)

/** A filtered raw event reduced to timestamp + type name. Debug/testing only. */
@Serializable
data class StoredEvent(
    val timestampMillis: Long,
    val type: String                  // ScreenEventType.name, e.g. "SCREEN_INTERACTIVE"
)

/** Snapshot of the [AnalysisConfig] thresholds used to produce a record. */
@Serializable
data class StoredConfig(
    val meaningfulMinScreenOnSeconds: Long,
    val treatUnlockAsMeaningful: Boolean,
    val microQuietMinMinutes: Long,
    val shortSleepMinMinutes: Long,
    val mainSleepMinMinutes: Long,
    val delayedSleepMinMinutes: Long,
    val recoveryMinMinutes: Long,
    val preSleepLookbackMinutes: Long,
    val awakeningMaxMinutes: Long,
    val windowOpensInQuietMinMinutes: Long
)
