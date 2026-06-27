package com.sleepguard.poc

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pure mapping between the domain [NightRecord] and the Room [NightEntity].
 *
 * The whole record is serialized into [NightEntity.recordJson] (the same JSON shape as the legacy
 * file), and the queryable summary fields are copied into their own columns. Pure JVM logic (no
 * Android, no I/O) → fully unit-testable, and [NightRecord] stays untouched.
 */
object NightEntityMapper {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun toEntity(r: NightRecord): NightEntity = NightEntity(
        nightOf = r.nightOf,
        windowStartMillis = r.windowStartMillis,
        windowEndMillis = r.windowEndMillis,
        collectedAtMillis = r.collectedAtMillis,
        restPattern = r.restPattern,
        confidence = r.confidence,
        recordJson = json.encodeToString(r)
    )

    fun toRecord(e: NightEntity): NightRecord = json.decodeFromString(e.recordJson)
}
