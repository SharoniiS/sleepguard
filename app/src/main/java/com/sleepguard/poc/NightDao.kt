package com.sleepguard.poc

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data-access object for the `nights` table. Synchronous for now (see [AppDatabase] note); the UI
 * track will add Flow-returning variants for reactive screens.
 */
@Dao
interface NightDao {

    /** Insert or replace one night (one row per nightOf → re-collecting overwrites, never duplicates). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: NightEntity)

    /** All rows (including the blob), oldest-first. Backs the drop-in [NightRepository.loadAll]. */
    @Query("SELECT * FROM nights ORDER BY nightOf")
    fun getAll(): List<NightEntity>
}
