package com.sleepguard.poc

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local, app-private persistence for [NightRecord]s (DEBUG / POC).
 *
 * Storage = a single JSON file in internal storage (`filesDir`). No external files, no
 * sharing/export, no network, no permissions. Data is sandboxed to the app and removed on
 * uninstall.
 *
 * POC note: reads/writes run synchronously on the calling thread. The data set is tiny, so
 * this is acceptable for the POC; production would move I/O off the main thread.
 *
 * The (de)serialization and the upsert merge are pure and unit-tested; only the file read/
 * write touches Android.
 */
class NightRepository(private val context: Context) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    fun loadAll(): List<NightRecord> {
        if (!file.exists()) return emptyList()
        return try {
            JSON.decodeFromString<List<NightRecord>>(file.readText())
        } catch (t: Throwable) {
            // Don't lose a corrupt file silently: set it aside, then start clean.
            runCatching { file.copyTo(File(context.filesDir, "$FILE_NAME.corrupt"), overwrite = true) }
            emptyList()
        }
    }

    fun upsert(record: NightRecord) = writeAll(upsertInto(loadAll(), record))

    fun clearAll() {
        if (file.exists()) file.delete()
    }

    private fun writeAll(records: List<NightRecord>) {
        file.writeText(JSON.encodeToString(records))
    }

    companion object {
        const val FILE_NAME = "sleepguard_history.json"

        private val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true   // forward-compatible across schema changes
        }

        /** Pure upsert: replace any record with the same id, then sort by night date. */
        fun upsertInto(existing: List<NightRecord>, record: NightRecord): List<NightRecord> =
            (existing.filter { it.recordId != record.recordId } + record).sortedBy { it.nightOf }
    }
}
