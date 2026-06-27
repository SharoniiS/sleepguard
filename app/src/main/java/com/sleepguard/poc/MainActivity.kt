package com.sleepguard.poc

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.sleepguard.poc.databinding.ActivityMainBinding
import com.sleepguard.poc.databinding.ItemNightBinding
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Single screen for the SleepGuard Android POC.
 *
 * One primary action ("Collect Last Night") plus:
 *   - Last Night : the analysis of the most recent saved night.
 *   - History    : a compact per-night "estimated sleep" list (always visible). A "Show night
 *                  details" button reveals one expandable card per saved night — each opens a
 *                  Last Night-style summary and, on demand, that night's raw events. A "Debug
 *                  information" button reveals the technical section (per-night summary lines,
 *                  Backfill / Clear, technical counts), which is collapsed by default.
 *
 * Sleep is analyzed on the 22:00->18:00 window, but events are CAPTURED for a full 24h day
 * (so the log misses nothing). The analyzer ignores events outside its window, so the sleep
 * analysis is unaffected. On every open (when Usage Access is granted) Backfill probes the last
 * backfillLookbackDays nights but stores only nights NOT already saved (capture-once): a night is
 * captured once when fresh and then frozen, so a later retention-degraded re-read can't overwrite a
 * good record. Clear wipes local storage for a fresh reseed. No background work.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val usageAccessManager by lazy { UsageAccessManager(this) }
    private val collector by lazy { ScreenEventsCollector(this) }
    private val analysisConfig = AnalysisConfig()
    private val patternAnalyzer by lazy { NightPatternAnalyzer(analysisConfig) }
    private val repository by lazy { NightRepository(this) }

    /** Pretty JSON for the debug "Export backup" action. */
    private val exportJson by lazy { Json { prettyPrint = true; encodeDefaults = true } }

    /** How many days back "Backfill" probes. 9, not 10: Android trims raw usage events at a ~9–10
     *  day rolling edge, so the 10th day is prone to retention-degradation (it comes back as a
     *  partial "15h" night). 9 keeps the oldest probed night safely inside the retention window. */
    private val backfillLookbackDays = 9

    /** Preferred minimum for an "estimated sleep" window in the History list (currently 4h). If a
     *  night has no window this long, the list falls back to the single longest detected window. */
    private val estimatedSleepMinMillis = 4L * 60L * 60L * 1000L

    private var showNightDetails = false
    private var showDebug = false

    private val zone: ZoneId by lazy { ZoneId.systemDefault() }
    private val timeFormatter by lazy { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    private val dateTimeFormatter by lazy { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        binding.privacyText.text = getString(R.string.privacy_message)

        binding.openSettingsButton.setOnClickListener {
            val launched = usageAccessManager.openUsageAccessSettings(this)
            if (!launched) {
                Toast.makeText(this, R.string.error_no_settings_screen, Toast.LENGTH_LONG).show()
            }
        }
        binding.collectLastNightButton.setOnClickListener { collectLastNight() }
        binding.backfillButton.setOnClickListener { collectBackfill() }
        binding.clearButton.setOnClickListener { clearStored() }
        binding.nightDetailsButton.setOnClickListener {
            showNightDetails = !showNightDetails
            binding.nightListContainer.visibility = if (showNightDetails) View.VISIBLE else View.GONE
            binding.nightDetailsButton.text = getString(
                if (showNightDetails) R.string.button_hide_night_details else R.string.button_show_night_details
            )
        }
        binding.debugToggleButton.setOnClickListener {
            showDebug = !showDebug
            binding.debugSection.visibility = if (showDebug) View.VISIBLE else View.GONE
            binding.debugToggleButton.text =
                getString(if (showDebug) R.string.button_hide_debug else R.string.button_show_debug)
        }
        binding.exportJsonButton.setOnClickListener { exportBackupJson() }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        // Auto-collect on every open (no explicit button press needed) via collectBackfill(). It
        // captures only nights NOT already stored (capture-once) and skips empty ones, so a later
        // retention-degraded re-read never overwrites a good record. Only when Usage Access is
        // granted; otherwise just show what's already saved (no error spam on each open).
        // collectBackfill() re-renders from storage + refreshes the debug text itself.
        if (usageAccessManager.hasUsageAccess()) {
            // Defensive: never let an auto-collect failure trap the user in a crash-on-open loop —
            // fall back to showing whatever is already saved.
            try {
                collectBackfill()
            } catch (t: Throwable) {
                renderFromStorage()
                binding.debugText.text = "Auto-collect failed on open: ${t.message}\n" + baselineDebug()
            }
        } else {
            renderFromStorage()
            binding.debugText.text = baselineDebug()
        }
    }

    private fun refreshPermissionState() {
        val granted = usageAccessManager.hasUsageAccess()
        binding.usageAccessStatus.text = getString(
            if (granted) R.string.status_granted else R.string.status_missing
        )
        binding.openSettingsButton.visibility = if (granted) View.GONE else View.VISIBLE
        binding.collectLastNightButton.isEnabled = granted
        binding.backfillButton.isEnabled = granted
        // Show-logs and Clear act on local storage only — always enabled.
    }

    /**
     * Edge-to-edge compatibility (targetSdk 35+ draws content behind the system bars). Pads the
     * scrollable content by the dynamic system-bar + display-cutout insets via the reusable
     * [applySystemBarInsetsPadding] helper, and keeps the bar icons readable against the app
     * background in both light and dark mode. No layout redesign and no data/logic change.
     */
    private fun applySystemBarInsets() {
        binding.contentRoot.applySystemBarInsetsPadding()

        val lightBackground = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = lightBackground
            isAppearanceLightNavigationBars = lightBackground
        }
    }

    // -----------------------------------------------------------------------
    // Windows
    // -----------------------------------------------------------------------

    /** Absolute epoch millis for [date] at [hour]:00 in the device timezone. */
    private fun at(date: LocalDate, hour: Int): Long =
        date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    /** Sleep-analysis anchors for the night whose morning is [morning]: (m-1) 22:00 -> m 18:00. */
    private fun anchorsForNight(morning: LocalDate): WindowAnchors =
        WindowAnchors(
            windowStart = at(morning.minusDays(1), 22),
            morningEarliest = at(morning, 4),
            morningStart = at(morning, 6),
            noon = at(morning, 12),
            windowEnd = at(morning, 18)
        )

    /**
     * Full-day capture window for the night whose morning is [morning]: (m-1) 18:00 -> m 18:00.
     * 24h and contiguous across days, so the interaction log misses nothing. Sleep analysis still
     * runs on the 22:00->18:00 slice (the analyzer ignores events outside its anchors).
     */
    private fun dayWindow(morning: LocalDate): Pair<Long, Long> =
        at(morning.minusDays(1), 18) to at(morning, 18)

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private fun collectLastNight() {
        if (!ensurePermission()) return

        val morning = LocalDate.now(zone)
        val (start, end) = dayWindow(morning)
        val collection = try {
            collector.collect(start, end)
        } catch (t: Throwable) {
            binding.collectionStatus.text = getString(R.string.error_query_failed)
            return
        }

        val now = Instant.now().toEpochMilli()
        val result = patternAnalyzer.analyze(collection.events, anchorsForNight(morning), now)
        repository.upsert(
            NightRecordMapper.toRecord(
                result, collection.events, collection.rawEventCount, morning.toString(), now, analysisConfig
            )
        )

        binding.collectionStatus.text =
            if (collection.events.isEmpty()) getString(R.string.error_no_events)
            else getString(R.string.status_success)
        renderFromStorage()
        binding.debugText.text = buildString {
            appendLine("Timezone: ${zone.id}")
            appendLine("Window: ${dateTimeFormatter.format(Instant.ofEpochMilli(start))} -> ${dateTimeFormatter.format(Instant.ofEpochMilli(end))}")
            appendLine("Raw events: ${collection.rawEventCount}   Filtered: ${collection.filteredEventCount}")
            append("Saved nights: ${repository.loadAll().size}")
        }
    }

    /**
     * Probes the last [backfillLookbackDays] nights. Saves a night only if it is NOT already stored
     * (capture-once): the first fresh capture is frozen, so a later retention-degraded re-read never
     * overwrites a good record. Empty nights are skipped too. Every night is still probed for the
     * retention-cutoff readout. Android prunes raw usage events after ~9-10 days, so this is
     * "available" history, not a true maximum. Clear resets so a reseed re-collects fresh.
     */
    private fun collectBackfill() {
        if (!ensurePermission()) return

        val today = LocalDate.now(zone)
        var saved = 0
        var earliest: Long? = null

        // Capture-once: a COMPLETE saved night is frozen. We still probe every night for the
        // retention-cutoff readout, but never overwrite a good (complete) record with a later
        // (possibly retention-degraded) re-read. Clear resets this so a reseed re-collects fresh.
        //
        // KNOWN EDGE CASE (premature freeze): if the app is opened in the MIDDLE of a night,
        // BEFORE the user has slept, that night is captured with only the pre-sleep evening events
        // (the analyzer's future-window cap limits analysis to "now"), and capture-once then freezes
        // it — so the real sleep, which happens later, is never re-read and the night stays
        // "active / no quiet period" forever. Workaround: "Clear Stored Data" wipes the frozen
        // records; a later re-collect (after the night ends) captures them complete.
        // Fix (implemented): freeze only COMPLETE nights; re-capture an INCOMPLETE one
        // (collectedAt < windowEnd, see [isCompleteNight]) so a premature mid-night capture
        // self-heals once the night ends — no manual "Clear Stored Data" needed. A complete night
        // stays frozen, so the retention-degradation protection for good records is preserved.
        val completeNights = repository.loadAll()
            .filter { isCompleteNight(it) }
            .map { it.nightOf }
            .toSet()

        for (k in 0 until backfillLookbackDays) {
            val morning = today.minusDays(k.toLong())
            val (start, end) = dayWindow(morning)
            val collection = try {
                collector.collect(start, end)
            } catch (t: Throwable) {
                continue
            }
            if (collection.events.isEmpty()) continue

            collection.events.minByOrNull { it.timestampMillis }?.let { e ->
                earliest = if (earliest == null) e.timestampMillis else minOf(earliest!!, e.timestampMillis)
            }

            if (morning.toString() in completeNights) continue   // already captured COMPLETE -> frozen

            val now = Instant.now().toEpochMilli()
            val result = patternAnalyzer.analyze(collection.events, anchorsForNight(morning), now)
            repository.upsert(
                NightRecordMapper.toRecord(
                    result, collection.events, collection.rawEventCount, morning.toString(), now, analysisConfig
                )
            )
            saved++
        }

        val cutoff = earliest?.let { dateTimeFormatter.format(Instant.ofEpochMilli(it)) } ?: "none"
        binding.collectionStatus.text = getString(R.string.status_success)
        renderFromStorage()
        binding.debugText.text = buildString {
            appendLine("Timezone: ${zone.id}")
            appendLine("Backfill: saved/updated $saved nights (probed $backfillLookbackDays days).")
            appendLine("Earliest event available (retention cutoff): $cutoff")
            append("Saved nights: ${repository.loadAll().size}")
        }
    }

    private fun clearStored() {
        repository.clearAll()
        binding.collectionStatus.text = ""
        renderFromStorage()
        binding.debugText.text = baselineDebug()
    }

    /**
     * DEBUG/POC: exports all saved nights as a JSON backup and opens the system share sheet (email /
     * Drive / Keep). Serializes the on-device [NightRecord] list verbatim — the same local-storage
     * format — so it is a true on-device backup, decoupled from any cloud. Shares a FILE (content
     * URI), NOT clipboard / EXTRA_TEXT, so it never hits the Binder transaction-size limit — large
     * exports no longer crash. Pure on-device — no network, no INTERNET permission; the user chooses
     * where (if anywhere) the file goes.
     */
    private fun exportBackupJson() {
        val records = repository.loadAll().sortedByDescending { it.nightOf }
        if (records.isEmpty()) {
            Toast.makeText(this, "No saved nights to export", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val text = exportJson.encodeToString(records)

            val file = File(cacheDir, "sleepguard_backup.json").apply { writeText(text) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SleepGuard backup (${records.size} nights)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.button_export_json)))
            Toast.makeText(this, "Sharing ${records.size} nights as a JSON file", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Export failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun ensurePermission(): Boolean {
        if (usageAccessManager.hasUsageAccess()) return true
        refreshPermissionState()
        binding.collectionStatus.text = getString(R.string.error_no_permission)
        return false
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    private fun renderFromStorage() {
        val records = repository.loadAll()
        renderLastNight(records)
        renderEstimatedSleep(records)
        renderNightList(records)
        renderHistory(records)
    }

    /** Top-of-History box: per night, the >=4h quiet window(s); if none, the single longest detected
     *  window (any length) so a real short sleep shows instead of "none". */
    private fun renderEstimatedSleep(records: List<NightRecord>) {
        if (records.isEmpty()) {
            binding.estimatedSleepText.text = getString(R.string.estimated_sleep_empty)
            return
        }
        binding.estimatedSleepText.text = records.sortedByDescending { it.nightOf }.joinToString("\n") { r ->
            val blocks = InteractionHistory.longInactivities(r, estimatedSleepMinMillis)
            if (blocks.isNotEmpty()) {
                "${r.nightOf}: " + blocks.joinToString("; ") {
                    "${fmt(it.startMillis)}–${fmt(it.endMillis)} (${durHuman(it.durationMillis)})"
                }
            } else {
                // New rule: when no >=4h window exists, show the single longest detected rest window
                // (even if <4h) — e.g. a real 3h16m sleep — instead of "none". The detail already
                // shows it; this keeps the History list consistent with it.
                val longest = InteractionHistory.longInactivities(r, 0L).maxByOrNull { it.durationMillis }
                if (longest != null)
                    "${r.nightOf}: ${fmt(longest.startMillis)}–${fmt(longest.endMillis)} (${durHuman(longest.durationMillis)})"
                else
                    "${r.nightOf}: none"
            }
        }
    }

    /**
     * Headline "Last Night" = the most recent night whose analysis window had fully elapsed at
     * capture time (a "complete" night, see [isCompleteNight]). If the single most recent night is
     * still in progress (a pre-sleep mid-night capture), it is NOT used as the headline; instead we
     * show the last completed night plus a small note. Presentation-only: the in-progress night is
     * untouched and still appears in the History list and per-night cards. No analysis/storage change.
     */
    private fun renderLastNight(records: List<NightRecord>) {
        if (records.isEmpty()) {
            binding.lastNightNote.visibility = View.GONE
            binding.lastNightText.text = getString(R.string.last_night_empty)
            return
        }
        val mostRecent = records.maxByOrNull { it.nightOf }!!
        val headline = records.filter { isCompleteNight(it) }.maxByOrNull { it.nightOf } ?: mostRecent
        val inProgress = headline.nightOf != mostRecent.nightOf
        binding.lastNightNote.visibility = if (inProgress) View.VISIBLE else View.GONE
        if (inProgress) binding.lastNightNote.text = getString(R.string.last_night_in_progress)
        binding.lastNightText.text = formatNightSummary(headline)
    }

    /**
     * A saved night is "complete" when its analysis window had already ended at capture time
     * (collectedAt >= windowEnd) — i.e. it was not a premature mid-night capture. Display-only helper
     * for [renderLastNight]; never affects analysis, storage, or any other view.
     */
    private fun isCompleteNight(r: NightRecord): Boolean = r.collectedAtMillis >= r.windowEndMillis

    /**
     * Readable "Last Night-style" summary for a single saved night, built only from stored
     * [NightRecord] fields (no recompute). Shared by the Last Night card and every per-night
     * card so they stay identical and so future per-night insights can reuse one formatter.
     */
    private fun formatNightSummary(record: NightRecord): String = buildString {
        appendLine("Night of ${record.nightOf}")
        appendLine("Pattern: ${humanRestPattern(record.restPattern)}")
        val rest = displaySleep(record)
        if (rest == null) {
            appendLine("No long sleep-like period found.")
        } else {
            appendLine("Main sleep-like period: ${fmt(rest.first)}–${fmt(rest.second)} (${durHuman(rest.third)})")
        }
        appendLine("Phone put down: ${fmt(record.phoneDownMillis)}")
        appendLine("First use after: ${fmt(displayFirstUseAfter(record))}")
        appendLine(
            "Pre-sleep phone use (2h): " +
                (record.preSleepPhoneTimeMillis?.let { durHuman(it) } ?: "Unknown")
        )
        appendLine(
            "Possible awakenings: ${record.awakenings.size}" +
                if (record.awakenings.isEmpty()) "" else " (" + record.awakenings.joinToString { fmt(it) } + ")"
        )
        append("Confidence: ${record.confidence}")
    }

    /**
     * Builds one readable card per saved night (newest first). The header shows only date +
     * estimated quiet-period duration; the short, user-friendly summary is always visible (no
     * toggle). Only "Show raw events for this night" expands, and it lazily builds + toggles
     * ONLY that night's stored raw events — nights are never merged into one global dump.
     * (Per-night structure so future insights/trends can reuse [NightRecord]; not built here.)
     */
    private fun renderNightList(records: List<NightRecord>) {
        val container = binding.nightListContainer
        container.removeAllViews()
        for (record in records.sortedByDescending { it.nightOf }) {
            val card = ItemNightBinding.inflate(layoutInflater, container, false)
            val estimate = displaySleep(record)
                ?.let { "${durHuman(it.third)} estimated" } ?: "no main quiet period"
            card.nightHeaderText.text = "${record.nightOf} · $estimate"
            card.nightSummaryText.text = formatNightCardBody(record)

            var rawBuilt = false
            card.rawEventsToggleButton.setOnClickListener {
                val show = card.rawEventsText.visibility != View.VISIBLE
                if (show && !rawBuilt) {
                    card.rawEventsText.text = formatRawEvents(record.events)   // lazy: only on open
                    rawBuilt = true
                }
                card.rawEventsText.visibility = if (show) View.VISIBLE else View.GONE
                card.rawEventsToggleButton.text = getString(
                    if (show) R.string.button_hide_raw_events else R.string.button_show_raw_events
                )
            }

            container.addView(card.root)
        }
    }

    /**
     * User-friendly per-night summary for a night card. Observational only — describes phone
     * activity and the quiet period, never sleep quality or any clinical interpretation. Built
     * from stored [NightRecord] fields (no recompute). Date + estimated duration live in the
     * card header, so they are not repeated here.
     */
    private fun formatNightCardBody(record: NightRecord): String = buildString {
        val rest = displaySleep(record)
        appendLine(
            "Main quiet / sleep-like period: " +
                if (rest == null) "none found" else "${fmt(rest.first)}–${fmt(rest.second)}"
        )
        appendLine("Phone put down: ${fmt(record.phoneDownMillis)}")
        appendLine("First use after: ${fmt(displayFirstUseAfter(record))}")
        appendLine(
            "Pre-sleep phone use in the last 2 hours: " +
                (record.preSleepPhoneTimeMillis?.let { durHuman(it) } ?: "Unknown")
        )
        appendLine(
            "Possible interruptions / awakenings: ${record.awakenings.size}" +
                if (record.awakenings.isEmpty()) "" else " (" + record.awakenings.joinToString { fmt(it) } + ")"
        )
        appendLine("Activity pattern: ${friendlyPattern(record.restPattern)}")
        append("Data reliability: ${friendlyReliability(record.confidence)}")
    }

    /** Plain, observational wording for the structural pattern (no clinical framing). */
    private fun friendlyPattern(name: String): String = when (name) {
        "CONSOLIDATED" -> "steady"
        "FRAGMENTED" -> "interrupted"
        "MINIMAL_REST" -> "mostly active"
        else -> name.lowercase()
    }

    /** Measurement certainty of the data, phrased as data availability rather than confidence. */
    private fun friendlyReliability(name: String): String = when (name) {
        "HIGH" -> "high"
        "MEDIUM" -> "medium"
        "LOW" -> "low"
        else -> name.lowercase()
    }

    /** One night's stored raw events (timestamp + type), newest first. Built lazily on demand. */
    private fun formatRawEvents(events: List<StoredEvent>): String = buildString {
        appendLine("Raw events (${events.size}, newest first):")
        append(events.asReversed().joinToString("\n") { e ->
            "${dateTimeFormatter.format(Instant.ofEpochMilli(e.timestampMillis))}  ${humanEventType(e.type)}"
        })
    }

    /** Per-night summary lines shown inside the Debug section. */
    private fun renderHistory(records: List<NightRecord>) {
        binding.historyText.text =
            if (records.isEmpty()) {
                getString(R.string.history_empty)
            } else {
                records.sortedByDescending { it.nightOf }.joinToString("\n") { r ->
                    val rest = displaySleep(r)?.let { "${fmt(it.first)}–${fmt(it.second)}" } ?: "no primary rest"
                    "- ${r.nightOf}  ${humanRestPattern(r.restPattern)}  $rest  ${r.confidence}  (${r.events.size} events)"
                }
            }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun baselineDebug(): String =
        "Timezone: ${zone.id}\nSaved nights: ${repository.loadAll().size}"

    private fun fmt(ms: Long?): String =
        if (ms == null) "Unknown" else timeFormatter.format(Instant.ofEpochMilli(ms))

    private fun durHuman(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /**
     * 0.3: the sleep span to display — the bridged [NightRecord.mainRestEpisode] if present, else
     * the single longest [NightRecord.primaryRest] block (pre-v2 records / fallback). (start, end, dur)
     */
    private fun displaySleep(r: NightRecord): Triple<Long, Long, Long>? {
        r.mainRestEpisode?.let { return Triple(it.startMillis, it.endMillis, it.durationMillis) }
        r.primaryRest?.let { return Triple(it.startMillis, it.endMillis, it.durationMillis) }
        return null
    }

    /** 0.3: first use after the displayed sleep span (the bridged episode's, or the primary block's). */
    private fun displayFirstUseAfter(r: NightRecord): Long? =
        if (r.mainRestEpisode != null) r.firstUseAfterMainRestMillis else r.firstUseAfterPrimaryRestMillis

    private fun humanRestPattern(name: String): String = when (name) {
        "CONSOLIDATED" -> "Consolidated"
        "FRAGMENTED" -> "Fragmented"
        "MINIMAL_REST" -> "Minimal rest / active"
        else -> name
    }

    private fun humanEventType(name: String): String = when (name) {
        "SCREEN_INTERACTIVE" -> "Screen On"
        "SCREEN_NON_INTERACTIVE" -> "Screen Off"
        "KEYGUARD_HIDDEN" -> "Unlocked"
        "KEYGUARD_SHOWN" -> "Locked"
        else -> name
    }

}
