package com.sleepguard.poc

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * State holder for the Compose product UI. Reuses the existing data/logic classes. Synchronous for
 * now (Room runs with allowMainThreadQueries; data set is tiny) — moves to coroutines later.
 *
 * NOTE: the collection logic below is ported from [MainActivity] (the View POC kept for debug). This
 * is intentional temporary duplication during the View→Compose move; once MainActivity is retired the
 * single copy lives here.
 */
class SleepViewModel(app: Application) : AndroidViewModel(app) {

    private val usageAccess = UsageAccessManager(app)
    private val collector = ScreenEventsCollector(app)
    private val config = AnalysisConfig()
    private val analyzer = NightPatternAnalyzer(config)
    private val repo = NightRepository(app)
    private val morningRepo = MorningReportRepository(app)
    private val medicationDao = AppDatabase.getInstance(app).medicationDao()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val backfillLookbackDays = 9

    var hasPermission by mutableStateOf(false)
        private set
    var nights by mutableStateOf<List<NightRecord>>(emptyList())
        private set
    var latestComplete by mutableStateOf<NightRecord?>(null)
        private set
    var savedMedications by mutableStateOf<List<String>>(emptyList())
        private set

    /** Re-check permission, auto-collect recent nights, and reload. Call on resume. */
    fun refresh() {
        hasPermission = usageAccess.hasUsageAccess()
        if (hasPermission) runCatching { collectBackfill() }
        nights = repo.loadAll().sortedByDescending { it.nightOf }
        latestComplete = repo.getLatestComplete()
        savedMedications = medicationDao.getAll()
    }

    /** Add (and remember) a medication name so it can be reused from the questionnaire dropdown. */
    fun addMedication(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        medicationDao.insert(MedicationEntity(n))
        savedMedications = medicationDao.getAll()
    }

    fun openUsageAccessSettings(activity: Activity): Boolean =
        usageAccess.openUsageAccessSettings(activity)

    /** The morning self-report for a night, or null if not filled. */
    fun getReport(nightOf: String): MorningReportEntity? = morningRepo.get(nightOf)

    /** Save (insert/replace) a night's morning self-report. */
    fun saveReport(report: MorningReportEntity) = morningRepo.save(report)

    // ---- collection (ported from MainActivity; see class note) ----

    private fun at(date: LocalDate, hour: Int): Long =
        date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    private fun anchorsForNight(morning: LocalDate) = WindowAnchors(
        windowStart = at(morning.minusDays(1), 22),
        morningEarliest = at(morning, 4),
        morningStart = at(morning, 6),
        noon = at(morning, 12),
        windowEnd = at(morning, 18)
    )

    private fun dayWindow(morning: LocalDate): Pair<Long, Long> =
        at(morning.minusDays(1), 18) to at(morning, 18)

    private fun isComplete(r: NightRecord): Boolean = r.collectedAtMillis >= r.windowEndMillis

    private fun collectBackfill() {
        val today = LocalDate.now(zone)
        val complete = repo.loadAll().filter { isComplete(it) }.map { it.nightOf }.toSet()
        for (k in 0 until backfillLookbackDays) {
            val morning = today.minusDays(k.toLong())
            val (start, end) = dayWindow(morning)
            val collection = runCatching { collector.collect(start, end) }.getOrNull() ?: continue
            if (collection.events.isEmpty()) continue
            if (morning.toString() in complete) continue
            val now = Instant.now().toEpochMilli()
            val result = analyzer.analyze(collection.events, anchorsForNight(morning), now)
            repo.upsert(
                NightRecordMapper.toRecord(
                    result, collection.events, collection.rawEventCount, morning.toString(), now, config
                )
            )
        }
    }
}
