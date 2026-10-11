package com.sleepguard.poc

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.locks.ReentrantLock

/**
 * A rolling snapshot of phone activity for the window [startMillis, endMillis] — the Home
 * "last 24 hours" view. Purely descriptive raw events (timestamp + type); NOT analyzed and NEVER
 * persisted, so it can't collide with a stored night record.
 */
data class Last24hActivity(
    val startMillis: Long,
    val endMillis: Long,
    val events: List<StoredEvent>
)

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
    var last24h by mutableStateOf<Last24hActivity?>(null)
        private set

    /** One refresh at a time: on app open the splash and onResume both ask for one. */
    private val refreshLock = ReentrantLock()

    /**
     * Re-check permission, auto-collect recent nights, and reload. Call on resume. If a refresh is
     * already running, wait for it to finish instead of collecting the same data a second time.
     */
    fun refresh() {
        val waited = !refreshLock.tryLock()
        if (waited) refreshLock.lock()
        try {
            // After waiting, the running refresh's results are already loaded — unless it started
            // before Usage Access was granted (e.g. just back from Settings): then it collected
            // nothing, so collect now.
            if (!waited || (!hasPermission && usageAccess.hasUsageAccess())) doRefresh()
        } finally {
            refreshLock.unlock()
        }
    }

    private fun doRefresh() {
        hasPermission = usageAccess.hasUsageAccess()
        if (hasPermission) runCatching { collectBackfill() }
        val sorted = repo.loadAll().sortedByDescending { it.nightOf }
        nights = sorted
        latestComplete = pickHeadline(sorted)
        savedMedications = medicationDao.getAll()
        last24h = if (hasPermission) runCatching { collectLast24h() }.getOrNull() else null
    }

    /** Add (and remember) a medication name so it can be reused from the questionnaire dropdown. */
    fun addMedication(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        medicationDao.insert(MedicationEntity(n))
        savedMedications = medicationDao.getAll()
    }

    /** Refresh off the main thread (e.g. from onResume after returning from Settings). */
    fun refreshInBackground() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { refresh() } }
    }

    fun openUsageAccessSettings(activity: Activity): Boolean =
        usageAccess.openUsageAccessSettings(activity)

    /** The morning self-report for a night, or null if not filled. */
    fun getReport(nightOf: String): MorningReportEntity? = morningRepo.get(nightOf)

    /** Save (insert/replace) a night's morning self-report. */
    fun saveReport(report: MorningReportEntity) = morningRepo.save(report)

    private fun hasRest(r: NightRecord) = r.mainRestEpisode != null || r.primaryRest != null

    /** Newest night for the headline. Skip it only if it's TODAY's and has no detected rest yet
     *  (a premature mid-collection); then fall back to the newest night that has a rest window. */
    private fun pickHeadline(sortedDesc: List<NightRecord>): NightRecord? {
        val newest = sortedDesc.firstOrNull() ?: return null
        val today = LocalDate.now(zone).toString()
        val partial = newest.nightOf == today && !hasRest(newest)
        return if (partial) sortedDesc.firstOrNull { hasRest(it) } ?: newest else newest
    }

    // ---- collection (ported from MainActivity; see class note) ----

    /** Collect the rolling last-24h window [now-24h, now] on demand. Independent of the nightly
     *  backfill and NOT persisted — feeds the Home "last 24 hours" card. */
    private fun collectLast24h(): Last24hActivity {
        val now = Instant.now().toEpochMilli()
        val start = now - 24L * 60 * 60 * 1000
        val collection = collector.collect(start, now)
        return Last24hActivity(
            startMillis = start,
            endMillis = now,
            events = collection.events.map { StoredEvent(it.timestampMillis, it.type.name) }
        )
    }

    /** (Re)analyze the recent nights that need it — see [BackfillPolicy.needsAnalysis]. */
    private fun collectBackfill() {
        val today = LocalDate.now(zone)
        val stored = repo.loadAll().associateBy { it.nightOf }
        for (k in 0 until backfillLookbackDays) {
            val morning = today.minusDays(k.toLong())
            val existing = stored[morning.toString()]
            if (!BackfillPolicy.needsAnalysis(existing)) continue
            val anchors = NightWindow.anchors(morning, zone)
            val (start, end) = NightWindow.collectionRange(morning, zone, config.preSleepLookbackMinutes)
            val collection = runCatching { collector.collect(start, end) }.getOrNull() ?: continue
            // Retention guard: re-analyze the stored events if the fresh read came back thinner.
            val keepStored = existing?.takeIf { BackfillPolicy.preferStoredEvents(collection.events, it, anchors) }
            val events = keepStored?.let { BackfillPolicy.toRawEvents(it.events) } ?: collection.events
            if (events.isEmpty()) continue
            val rawEventCount = keepStored?.rawEventCount ?: collection.rawEventCount
            val now = Instant.now().toEpochMilli()
            val result = analyzer.analyze(events, anchors, now)
            repo.upsert(
                NightRecordMapper.toRecord(result, events, rawEventCount, morning.toString(), now, config)
            )
        }
    }
}
