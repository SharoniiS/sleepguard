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
     * "Estimated sleep" for one night = its long inactivities (>= [minDurationMillis]). These are
     * already glow-filtered quiet periods, so a stray nighttime notification won't shorten them.
     * Sorted by start time.
     *
     * 0.3: when a bridged [NightRecord.mainRestEpisode] is present, it IS the main inactivity (it
     * spans brief awakenings), so it replaces the primary block plus any secondary rests that fall
     * within it; only secondary rests OUTSIDE the episode remain separate. Pre-v2 records have no
     * episode -> fall back to the single primary block + secondaries (unchanged behavior).
     */
    fun longInactivities(record: NightRecord, minDurationMillis: Long): List<StoredBlock> {
        val main = record.mainRestEpisode
        val blocks: List<StoredBlock> = if (main != null) {
            val mainBlock = StoredBlock(
                main.startMillis, main.endMillis, main.durationMillis,
                "PRIMARY_REST", "MAIN_SLEEP_LIKE_BLOCK"
            )
            val outside = record.secondaryRests.filter {
                it.endMillis <= main.startMillis || it.startMillis >= main.endMillis
            }
            listOf(mainBlock) + outside
        } else {
            listOfNotNull(record.primaryRest) + record.secondaryRests
        }
        return blocks.filter { it.durationMillis >= minDurationMillis }.sortedBy { it.startMillis }
    }
}
