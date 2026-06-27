package com.sleepguard.poc

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room storage row for one analyzed night (on-device only — no network, no cloud).
 *
 * Design: the canonical record is the full [NightRecord] serialized into [recordJson] — the SAME
 * JSON shape as the legacy `sleepguard_history.json` file, so the one-time import is a straight copy
 * and [NightRecord] stays the single source of truth. The remaining columns are a denormalized index
 * of the queryable / sortable summary fields, so list and Week screens can read them via
 * [NightSummary] WITHOUT decoding the (potentially large) blob.
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

/**
 * Lightweight projection for list / Week screens — summary columns only, never the heavy blob (and
 * therefore never the raw events). Populated by [NightDao.getAllSummaries].
 */
data class NightSummary(
    val nightOf: String,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val collectedAtMillis: Long,
    val restPattern: String,
    val confidence: String
)
