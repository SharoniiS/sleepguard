package com.sleepguard.poc

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.sleepguard.poc.databinding.ActivityMainBinding
import com.sleepguard.poc.databinding.ItemNightBinding
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
 * analysis is unaffected. Saved data auto-loads on launch. No background work.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val usageAccessManager by lazy { UsageAccessManager(this) }
    private val collector by lazy { ScreenEventsCollector(this) }
    private val analysisConfig = AnalysisConfig()
    private val patternAnalyzer by lazy { NightPatternAnalyzer(analysisConfig) }
    private val repository by lazy { NightRepository(this) }

    /** How many days back "Backfill" probes. Configurable. */
    private val backfillLookbackDays = 10

    /** A quiet period at least this long counts as estimated sleep. Configurable (currently 4h). */
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
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        renderFromStorage()              // auto-load saved data on launch / return from Settings
        binding.debugText.text = baselineDebug()
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
     * Probes the last [backfillLookbackDays] nights and saves each one that still has data.
     * Android prunes raw usage events after a few days, so this is "available" history, not a true
     * maximum. Empty nights are skipped (never overwrite a good record with an empty one).
     */
    private fun collectBackfill() {
        if (!ensurePermission()) return

        val today = LocalDate.now(zone)
        var saved = 0
        var earliest: Long? = null

        for (k in 0 until backfillLookbackDays) {
            val morning = today.minusDays(k.toLong())
            val (start, end) = dayWindow(morning)
            val collection = try {
                collector.collect(start, end)
            } catch (t: Throwable) {
                continue
            }
            if (collection.events.isEmpty()) continue

            val now = Instant.now().toEpochMilli()
            val result = patternAnalyzer.analyze(collection.events, anchorsForNight(morning), now)
            repository.upsert(
                NightRecordMapper.toRecord(
                    result, collection.events, collection.rawEventCount, morning.toString(), now, analysisConfig
                )
            )
            saved++
            collection.events.minByOrNull { it.timestampMillis }?.let { e ->
                earliest = if (earliest == null) e.timestampMillis else minOf(earliest!!, e.timestampMillis)
            }
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
        renderLastNight(records.maxByOrNull { it.nightOf })
        renderEstimatedSleep(records)
        renderNightList(records)
        renderHistory(records)
    }

    /** Top-of-History box: long inactivities (>= 4h) per night = estimated sleep. */
    private fun renderEstimatedSleep(records: List<NightRecord>) {
        if (records.isEmpty()) {
            binding.estimatedSleepText.text = getString(R.string.estimated_sleep_empty)
            return
        }
        binding.estimatedSleepText.text = records.sortedByDescending { it.nightOf }.joinToString("\n") { r ->
            val blocks = InteractionHistory.longInactivities(r, estimatedSleepMinMillis)
            if (blocks.isEmpty()) {
                "${r.nightOf}: none > 4h"
            } else {
                "${r.nightOf}: " + blocks.joinToString("; ") {
                    "${fmt(it.startMillis)}–${fmt(it.endMillis)} (${durHuman(it.durationMillis)})"
                }
            }
        }
    }

    private fun renderLastNight(record: NightRecord?) {
        binding.lastNightText.text =
            if (record == null) getString(R.string.last_night_empty) else formatNightSummary(record)
    }

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
