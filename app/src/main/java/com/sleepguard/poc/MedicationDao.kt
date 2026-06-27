package com.sleepguard.poc

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Data-access for the user's saved medication names (`medications` table). */
@Dao
interface MedicationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(med: MedicationEntity)

    @Query("SELECT name FROM medications ORDER BY name")
    fun getAll(): List<String>
}
