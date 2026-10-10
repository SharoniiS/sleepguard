package com.sleepguard.poc

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * On-device Room database. Fully local: no network, no cloud, no account.
 *
 * Tables:
 *  - `nights`           — the analysis layer ([NightEntity]).
 *  - `morning_reports`  — the per-night user self-report layer ([MorningReportEntity]).
 *  - `medications`      — the user's reusable medication names ([MedicationEntity]).
 *
 * `exportSchema = true`: Room writes each version's schema JSON to `app/schemas/` (path set by the
 * `room.schemaLocation` KSP arg in app/build.gradle). Commit those files — v3 is the shape that ships
 * to real users, and future [Migration]s (below) must be reviewed and tested against it.
 *
 * `allowMainThreadQueries()` is a TEMPORARY drop-in shim (the data set is tiny); the UI track will
 * move reads to Flow / coroutines and remove it.
 */
@Database(
    entities = [NightEntity::class, MorningReportEntity::class, MedicationEntity::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun nightDao(): NightDao
    abstract fun morningReportDao(): MorningReportDao
    abstract fun medicationDao(): MedicationDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /** v1 → v2: add the `morning_reports` table (the per-night user self-report layer). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `morning_reports` (" +
                        "`nightOf` TEXT NOT NULL, " +
                        "`nightmares` INTEGER, " +
                        "`medications` TEXT, " +
                        "`cannabis` INTEGER, " +
                        "`alcohol` INTEGER, " +
                        "`note` TEXT, " +
                        "`updatedAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`nightOf`))"
                )
            }
        }

        /** v2 → v3: add the `medications` table (the user's reusable medication names). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `medications` (" +
                        "`name` TEXT NOT NULL, PRIMARY KEY(`name`))"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "sleepguard.db"
                ).allowMainThreadQueries()
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
