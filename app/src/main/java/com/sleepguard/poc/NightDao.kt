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

    /** A single night's full row, or null. For the detail screen. */
    @Query("SELECT * FROM nights WHERE nightOf = :nightOf")
    fun getByNight(nightOf: String): NightEntity?

    /** Summary projection (no blob → never loads raw events) for list / Week screens. */
    @Query(
        "SELECT nightOf, windowStartMillis, windowEndMillis, collectedAtMillis, restPattern, confidence " +
            "FROM nights ORDER BY nightOf"
    )
    fun getAllSummaries(): List<NightSummary>

    @Query("DELETE FROM nights")
    fun clearAll()
}
