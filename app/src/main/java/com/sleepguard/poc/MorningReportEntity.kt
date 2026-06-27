package com.sleepguard.poc

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The per-night USER layer (on-device): the morning self-report. Separate from [NightEntity] (the
 * analysis layer) and keyed by the same `nightOf`, so re-collecting / overwriting a night's analysis
 * never destroys what the user entered. Joined to [NightEntity] for display.
 *
 * Nullable Boolean = "not answered" (honest unknown), not false. v1 = questionnaire fields only; the
 * manual quiet-window time corrections ("ערוך זמנים") are a later phase and are intentionally absent.
 */
@Entity(tableName = "morning_reports")
data class MorningReportEntity(
    @PrimaryKey val nightOf: String,
    val nightmares: Boolean? = null,
    val medications: String? = null,
    val cannabis: Boolean? = null,
    val alcohol: Boolean? = null,
    val note: String? = null,
    val updatedAtMillis: Long
)
