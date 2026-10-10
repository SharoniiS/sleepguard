package com.sleepguard.poc

/**
 * Pure decisions for the on-open backfill of recent nights (JVM-testable; used by [SleepViewModel]).
 */
object BackfillPolicy {

    /** The night's window had fully passed when it was collected — no more data can arrive. */
    fun isComplete(r: NightRecord): Boolean = r.collectedAtMillis >= r.windowEndMillis

    /**
     * Analyze (or re-analyze) a night when it is not stored yet, is still incomplete, or was produced
     * by an older analyzer version. So every night still inside the system's ~9-day event retention is
     * recalculated ONCE with the current logic; after that a complete night stays frozen.
     */
    fun needsAnalysis(existing: NightRecord?): Boolean =
        existing == null || !isComplete(existing) || existing.analyzerVersion != ANALYZER_VERSION

    /**
     * Retention guard for a re-analysis: Android trims old raw events at a rolling ~9–10 day edge, so
     * a fresh read of an old night can come back partial. When the fresh read has FEWER events inside
     * the night's window than the stored record, re-analyze the stored events instead.
     */
    fun preferStoredEvents(fresh: List<RawScreenEvent>, existing: NightRecord, anchors: WindowAnchors): Boolean {
        fun inWindow(t: Long) = t >= anchors.windowStart && t < anchors.windowEnd
        val freshCount = fresh.count { inWindow(it.timestampMillis) }
        val storedCount = existing.events.count { inWindow(it.timestampMillis) }
        return storedCount > freshCount
    }

    /** Stored events back to analyzer input (an unrecognized type name becomes UNKNOWN). */
    fun toRawEvents(stored: List<StoredEvent>): List<RawScreenEvent> = stored.map {
        val type = runCatching { ScreenEventType.valueOf(it.type) }.getOrDefault(ScreenEventType.UNKNOWN)
        RawScreenEvent(it.timestampMillis, type)
    }
}
