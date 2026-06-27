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
 *
 * `exportSchema = false`; schema changes are handled with explicit [Migration]s (below). If schema
 * history tracking is wanted later, turn exportSchema on + set `room.schemaLocation`.
 *
 * `allowMainThreadQueries()` is a TEMPORARY drop-in shim (the data set is tiny); the UI track will
 * move reads to Flow / coroutines and remove it.
 */
@Database(
    entities = [NightEntity::class, MorningReportEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun nightDao(): NightDao
    abstract fun morningReportDao(): MorningReportDao

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

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "sleepguard.db"
                ).allowMainThreadQueries()
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
