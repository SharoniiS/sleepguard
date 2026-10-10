package com.sleepguard.poc

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Data-access for the per-night morning self-report (`morning_reports` table). */
@Dao
interface MorningReportDao {

    /** Insert or replace a night's report (one row per nightOf). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(report: MorningReportEntity)

    /** A night's report, or null if the user hasn't filled it. */
    @Query("SELECT * FROM morning_reports WHERE nightOf = :nightOf")
    fun getByNight(nightOf: String): MorningReportEntity?
}
