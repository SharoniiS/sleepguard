package com.sleepguard.poc

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A medication name the user added once and can reuse from the questionnaire dropdown. On-device
 * only; the name is the key so the same medication is never stored twice.
 */
@Entity(tableName = "medications")
data class MedicationEntity(
    @PrimaryKey val name: String
)
