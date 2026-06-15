package com.sleepguard.poc

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Retrospectively reads screen on/off (and lock) events for a given time window
 * using [UsageStatsManager.queryEvents].
 *
 * This is intentionally a pull-on-demand collector: the user presses a button in the
 * morning and we query last night's window. There is NO background service and NO
 * real-time monitoring, which keeps the POC simple and within Android's background
 * execution limits.
 */
class ScreenEventsCollector(private val context: Context) {

    /**
     * Outcome of a collection pass.
     *
     * @param events            the screen/lock events we kept, sorted by timestamp.
     * @param rawEventCount     total number of events returned by queryEvents BEFORE
     *                          filtering (all event types, used for the debug view).
     * @param filteredEventCount number of events we kept (== events.size).
     */
    data class CollectionResult(
        val events: List<RawScreenEvent>,
        val rawEventCount: Int,
        val filteredEventCount: Int
    )

    /**
     * Queries usage events in [startMillis, endMillis) and returns only the
     * screen/lock events we care about, sorted by timestamp, alongside the raw
     * (pre-filter) and filtered counts.
     *
     * Only timestamps and event types are extracted — no app names or content.
     */
    fun collect(startMillis: Long, endMillis: Long): CollectionResult {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val usageEvents = usageStatsManager.queryEvents(startMillis, endMillis)
        val event = UsageEvents.Event()
        val results = mutableListOf<RawScreenEvent>()
        var rawEventCount = 0

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            rawEventCount++

            val mappedType = when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE ->
                    ScreenEventType.SCREEN_INTERACTIVE

                UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                    ScreenEventType.SCREEN_NON_INTERACTIVE

                UsageEvents.Event.KEYGUARD_SHOWN ->
                    ScreenEventType.KEYGUARD_SHOWN

                UsageEvents.Event.KEYGUARD_HIDDEN ->
                    ScreenEventType.KEYGUARD_HIDDEN

                else -> null
            }

            if (mappedType != null) {
                results.add(
                    RawScreenEvent(
                        timestampMillis = event.timeStamp,
                        type = mappedType
                    )
                )
            }
        }

        val sorted = results.sortedBy { it.timestampMillis }
        return CollectionResult(
            events = sorted,
            rawEventCount = rawEventCount,
            filteredEventCount = sorted.size
        )
    }
}
