package com.sleepguard.poc

/**
 * Pure mapping from analyzer output ([NightPatternResult]) to the storable [NightRecord] DTO.
 * No Android dependencies — JVM-testable.
 */
object NightRecordMapper {

    fun toRecord(
        result: NightPatternResult,
        events: List<RawScreenEvent>,
        rawEventCount: Int,
        nightOf: String,
        collectedAtMillis: Long,
        config: AnalysisConfig
    ): NightRecord = NightRecord(
        schemaVersion = STORAGE_SCHEMA_VERSION,
        recordId = nightOf,                 // one record per night (see NightRecord docs)
        nightOf = nightOf,
        analyzerVersion = ANALYZER_VERSION,
        windowStartMillis = result.windowStart,
        windowEndMillis = result.windowEnd,
        collectedAtMillis = collectedAtMillis,
        restPattern = result.restPattern.name,
        confidence = result.confidence.name,
        primaryRest = result.primaryRest?.let { toBlock(it) },
        secondaryRests = result.secondaryRests.map { toBlock(it) },
        phoneDownMillis = result.phoneDownTime,
        firstUseAfterPrimaryRestMillis = result.firstUseAfterPrimaryRest,
        preSleepPhoneTimeMillis = result.preSleepPhoneTimeMillis,
        awakenings = result.nighttimeAwakenings,
        flags = result.flags.map { it.name },
        rawEventCount = rawEventCount,
        filteredEventCount = events.size,
        config = toStoredConfig(config),
        events = events.map { StoredEvent(it.timestampMillis, it.type.name) },
        mainRestEpisode = result.mainRestEpisode?.let {
            StoredMainEpisode(it.startMillis, it.endMillis, it.durationMillis)
        },
        firstUseAfterMainRestMillis = result.firstUseAfterMainRest
    )

    private fun toBlock(period: RestPeriod): StoredBlock = StoredBlock(
        startMillis = period.block.startMillis,
        endMillis = period.block.endMillis,
        durationMillis = period.block.durationMillis,
        role = period.role.name,
        label = period.block.label.name
    )

    private fun toStoredConfig(c: AnalysisConfig): StoredConfig = StoredConfig(
        meaningfulMinScreenOnSeconds = c.meaningfulMinScreenOnSeconds,
        treatUnlockAsMeaningful = c.treatUnlockAsMeaningful,
        microQuietMinMinutes = c.microQuietMinMinutes,
        shortSleepMinMinutes = c.shortSleepMinMinutes,
        mainSleepMinMinutes = c.mainSleepMinMinutes,
        delayedSleepMinMinutes = c.delayedSleepMinMinutes,
        recoveryMinMinutes = c.recoveryMinMinutes,
        preSleepLookbackMinutes = c.preSleepLookbackMinutes,
        awakeningMaxMinutes = c.awakeningMaxMinutes,
        windowOpensInQuietMinMinutes = c.windowOpensInQuietMinMinutes
    )
}
