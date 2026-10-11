package com.sleepguard.poc

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room storage row for one analyzed night (on-device only — no network, no cloud).
 *
 * Design: the canonical record is the full [NightRecord] serialized into [recordJson] — the SAME
 * JSON shape as the legacy `sleepguard_history.json` file, so the one-time import is a straight copy
 * and [NightRecord] stays the single source of truth. The remaining columns are a denormalized copy
 * of the summary fields (part of the table schema), queryable without decoding the blob.
 *
 * One row per night: [nightOf] (the morning's ISO date) is the primary key, which gives a clean
 * upsert (re-collecting a night replaces it, never duplicates).
 */
@Entity(tableName = "nights")
data class NightEntity(
    @PrimaryKey val nightOf: String,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val collectedAtMillis: Long,
    val restPattern: String,
    val confidence: String,
    val recordJson: String
)
