package com.sleepguard.poc

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sleepguard.poc.databinding.ActivityMainBinding
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Single debug screen for the SleepGuard Android POC.
 *
 * One primary action ("Collect Last Night") and three sections:
 *   - Last Night  : the analysis of the most recent saved night.
 *   - History     : saved nights (newest first), with a toggle to show ALL phone
 *                   interactions across history (full date + time).
 *   - Debug       : small area with Backfill / Clear and technical counts.
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

    private var showAllLogs = false

    private val zone: ZoneId by lazy { ZoneId.systemDefault() }
    private val timeFormatter by lazy { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    private val dateTimeFormatter by lazy { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        binding.showAllLogsButton.setOnClickListener {
            showAllLogs = !showAllLogs
            renderHistory(repository.loadAll())
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

    // -----------------------------------------------------------------------
    // Windows
    // -----------------------------------------------------------------------

    /** Sleep-analysis anchors for the night whose morning is [morning]: (m-1) 22:00 -> m 18:00. */
    private fun anchorsForNight(morning: LocalDate): WindowAnchors {
        fun at(date: LocalDate, hour: Int): Long =
            date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()
        return WindowAnchors(
            windowStart = at(morning.minusDays(1), 22),
            morningEarliest = at(morning, 4),
            morningStart = at(morning, 6),
            noon = at(morning, 12),
            windowEnd = at(morning, 18)
        )
    }

    /**
     * Full-day capture window for the night whose morning is [morning]: (m-1) 18:00 -> m 18:00.
     * 24h and contiguous across days, so the interaction log misses nothing. Sleep analysis still
     * runs on the 22:00->18:00 slice (the analyzer ignores events outside its anchors).
     */
    private fun dayWindow(morning: LocalDate): Pair<Long, Long> {
        fun at(date: LocalDate, hour: Int): Long =
            date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()
        return at(morning.minusDays(1), 18) to at(morning, 18)
    }

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
        if (record == null) {
            binding.lastNightText.text = getString(R.string.last_night_empty)
            return
        }
        binding.lastNightText.text = buildString {
            appendLine("Night of ${record.nightOf}")
            appendLine("Pattern: ${humanRestPattern(record.restPattern)}")
            val pr = record.primaryRest
            if (pr == null) {
                appendLine("No long sleep-like period found.")
            } else {
                appendLine("Main sleep-like period: ${fmt(pr.startMillis)}–${fmt(pr.endMillis)} (${durHuman(pr.durationMillis)})")
            }
            appendLine("Phone put down: ${fmt(record.phoneDownMillis)}")
            appendLine("First use after: ${fmt(record.firstUseAfterPrimaryRestMillis)}")
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
    }

    private fun renderHistory(records: List<NightRecord>) {
        binding.showAllLogsButton.text =
            getString(if (showAllLogs) R.string.button_hide_all_logs else R.string.button_show_all_logs)

        if (records.isEmpty()) {
            binding.historyText.text = getString(R.string.history_empty)
            return
        }

        val summaries = records.sortedByDescending { it.nightOf }.joinToString("\n") { r ->
            val rest = r.primaryRest?.let { "${fmt(it.startMillis)}–${fmt(it.endMillis)}" } ?: "no primary rest"
            "- ${r.nightOf}  ${humanRestPattern(r.restPattern)}  $rest  ${r.confidence}  (${r.events.size} events)"
        }

        binding.historyText.text = if (!showAllLogs) {
            summaries
        } else {
            val all = InteractionHistory.flatten(records)
            buildString {
                appendLine(summaries)
                appendLine()
                appendLine("All phone interactions (${all.size} events, newest first):")
                append(all.asReversed().joinToString("\n") { e ->
                    "${dateTimeFormatter.format(Instant.ofEpochMilli(e.timestampMillis))}  ${humanEventType(e.type)}"
                })
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
