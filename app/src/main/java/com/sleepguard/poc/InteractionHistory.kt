package com.sleepguard.poc

/** Pure helpers for the cross-night "all phone interactions" log. JVM-testable. */
object InteractionHistory {

    /**
     * Every stored event across all saved nights, de-duplicated by (timestamp, type) — adjacent
     * day windows can share a boundary event — and sorted ascending by time.
     */
    fun flatten(records: List<NightRecord>): List<StoredEvent> =
        records.flatMap { it.events }
            .distinctBy { it.timestampMillis to it.type }
            .sortedBy { it.timestampMillis }

    /**
     * "Estimated sleep" for one night = its long inactivities: rest blocks (the primary plus
     * any secondary) whose duration is at least [minDurationMillis]. These are already
     * glow-filtered quiet periods, so a stray nighttime notification won't shorten them.
     * Sorted by start time.
     */
    fun longInactivities(record: NightRecord, minDurationMillis: Long): List<StoredBlock> =
        (listOfNotNull(record.primaryRest) + record.secondaryRests)
            .filter { it.durationMillis >= minDurationMillis }
            .sortedBy { it.startMillis }
}
