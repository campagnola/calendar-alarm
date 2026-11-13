package com.example.calendaralarm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/**
 * The only path that plans event alarms: fetches the events in the alarm window, applies
 * filters and preferences, and hands the plan to the [AlarmController]. Runs are serialized process-wide.
 */
object ScheduleSync {
    private const val WINDOW_HOURS = 25L

    private val mutex = Mutex()

    suspend fun run(context: Context, reason: String): Unit = mutex.withLock {
        val prefs = Prefs(context)
        val now   = Instant.ofEpochMilli(AppClock.now())
        val end   = now.plus(Duration.ofHours(WINDOW_HOURS))
        val today = AppClock.nowLocal().toLocalDate()
        fun List<CalendarEvent>.countToday(match: (CalendarEvent) -> Boolean = { true }) =
            count { it.localStart.toLocalDate() == today && match(it) }
        AppLog.i("ScheduleSync ($reason): ${prefs.icsSources.size} ICS source(s)")

        // Restores ringing/snoozed alarms even when the calendar cannot be fetched.
        val controller = AlarmController.from(context)
        controller.reconcile(reason)

        // Feeds sync in parallel so one unresponsive server doesn't delay the others.
        val feeds = coroutineScope {
            prefs.icsSources.map { url ->
                async(Dispatchers.IO) {
                    FeedSync.sync(context, url, now, end)?.also { events ->
                        prefs.setIcsLastFetchEventCount(prefs.urlId(url), events.countToday())
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (prefs.icsSources.isNotEmpty() && feeds.isEmpty()) {
            AppLog.w("ScheduleSync ($reason): no ICS data available, keeping existing alarms")
            return@withLock
        }
        val icsEvents = feeds.flatten()
        val localEvents = if (prefs.localCalendarEnabled && prefs.localCalendarIds.isNotEmpty()) {
            LocalCalendarReader.fetchEvents(context, prefs.localCalendarIds, now, end).also { events ->
                prefs.setLocalCalendarEventCount(events.countToday())
            }
        } else emptyList()
        val events = icsEvents + localEvents

        val (filters, _) = readEventSettings(prefs) ?: run {
            AppLog.w("ScheduleSync ($reason): settings unreadable, keeping existing alarms")
            return@withLock
        }
        prefs.pruneStaleEventPreferences(now)
        prefs.updateEventPreferenceLastSeen(events)
        val flags = FilterEngine.effectiveFlags(events, filters, prefs.eventPreferences)
        for (filter in filters) {
            prefs.setFilterMatchCount(filter.id, events.countToday { FilterEngine.matches(it, filter.criteria) })
        }

        controller.syncFromCalendar(reason, events) { handledKeys ->
            planAlarms(
                events = events,
                flags = flags,
                advanceMinutes = prefs.advanceMinutes,
                quietHoursAlarmEnabled = prefs.quietHoursAlarmEnabled,
                quietHoursStartMinutes = prefs.quietHoursStartMinutes,
                quietHoursEndMinutes = prefs.quietHoursEndMinutes,
                nowMs = AppClock.now(),
                handledAlarmKeys = handledKeys
            )
        }
        prefs.lastPollTime = System.currentTimeMillis()
        AppLog.i("ScheduleSync ($reason): done")
    }

    /**
     * The stored filters and event preferences, or null if they are unreadable. The outcome is
     * recorded as [Prefs.settingsProblem] for the main screen to show.
     */
    fun readEventSettings(prefs: Prefs): Pair<List<EventFilter>, List<EventPreference>>? =
        try {
            (prefs.eventFilters to prefs.eventPreferences).also { prefs.settingsProblem = null }
        } catch (e: CorruptSettingException) {
            AppLog.e("ScheduleSync: ${e.message}")
            prefs.settingsProblem = e.message
            null
        }
}
