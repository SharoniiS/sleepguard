package com.sleepguard.poc

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local, app-private persistence for [NightRecord]s, backed by Room (on-device only — no network).
 *
 * The public API is intentionally unchanged from the previous file-based implementation, so callers
 * (e.g. MainActivity) are untouched. Internally each night is one Room row ([NightEntity]); the
 * canonical record is stored as JSON in that row and the summary columns index it for queries.
 *
 * POC note: calls run synchronously on the calling thread (Room is built with
 * `allowMainThreadQueries()`), matching the previous file store. The UI track will move reads to
 * Flow / coroutines.
 */
class NightRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).nightDao()

    init {
        importLegacyFileIfPresent(context)
    }

    /** All saved nights, oldest-first (callers sort for display). */
    fun loadAll(): List<NightRecord> = dao.getAll().map { NightEntityMapper.toRecord(it) }

    /** Insert or replace a night (one row per nightOf — no duplicates). */
    fun upsert(record: NightRecord) = dao.upsert(NightEntityMapper.toEntity(record))

    fun clearAll() = dao.clearAll()

    /**
     * One-time migration from the pre-Room store: if `sleepguard_history.json` exists, import every
     * night into Room (upsert, so re-running is harmless) and rename the file to ".imported" so it
     * never re-imports and the original is kept as a safety copy. On any failure the file is left
     * untouched — better to retry next launch than to lose nights.
     */
    private fun importLegacyFileIfPresent(context: Context) {
        val legacy = File(context.filesDir, LEGACY_FILE_NAME)
        if (!legacy.exists()) return
        try {
            val records = LEGACY_JSON.decodeFromString<List<NightRecord>>(legacy.readText())
            records.forEach { dao.upsert(NightEntityMapper.toEntity(it)) }
            legacy.renameTo(File(context.filesDir, "$LEGACY_FILE_NAME.imported"))
        } catch (_: Throwable) {
            // Leave the legacy file in place so the import can be retried; never drop data.
        }
    }

    companion object {
        /** The pre-Room single-file store; kept only for the one-time import above. */
        const val LEGACY_FILE_NAME = "sleepguard_history.json"

        private val LEGACY_JSON = Json { ignoreUnknownKeys = true }
    }
}
