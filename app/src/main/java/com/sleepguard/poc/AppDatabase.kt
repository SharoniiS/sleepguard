package com.sleepguard.poc

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * On-device Room database — a single `nights` table. Fully local: no network, no cloud, no account.
 *
 * `exportSchema = false` for now (v1 has nothing to migrate from). Before bumping to version 2, turn
 * it on and set `room.schemaLocation` so the schema history is tracked and migrations can be tested.
 *
 * `allowMainThreadQueries()` is a TEMPORARY drop-in shim: the previous file-based store did
 * synchronous main-thread I/O and the data set is tiny (tens of nights). The UI track will move reads
 * to Flow / coroutines and remove this.
 */
@Database(entities = [NightEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun nightDao(): NightDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "sleepguard.db"
                ).allowMainThreadQueries().build().also { INSTANCE = it }
            }
    }
}
