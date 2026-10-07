package com.example.calendaralarm

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The only path that plans event alarms: fetches the events in the alarm window, applies
 * filters and preferences, and hands the plan to the [AlarmController]. Runs are serialized process-wide.
 */
object ScheduleSync {
    private const val WINDOW_HOURS = 25L

    private val mutex = Mutex()

    suspend fun run(context: Context, reason: String): Unit = mutex.withLock {
        val prefs = Prefs(context)
        val now   = AppClock.nowLocal()
        val end   = now.plusHours(WINDOW_HOURS)
        val today = now.toLocalDate()
        fun List<CalendarEvent>.countToday(match: (CalendarEvent) -> Boolean = { true }) =
            count { it.start.toLocalDate() == today && match(it) }
        AppLog.i("ScheduleSync ($reason): ${prefs.icsSources.size} ICS source(s)")

        // Restores ringing/snoozed alarms even when the calendar cannot be fetched.
        val controller = AlarmController.from(context)
        controller.reconcile(reason)

        val sources = IcsFetcher.fetchAll(context, forceDownload = true)
        if (prefs.icsSources.isNotEmpty() && sources.isEmpty()) {
            AppLog.w("ScheduleSync ($reason): no ICS data available, keeping existing alarms")
            return@withLock
        }

        val icsEvents = sources.flatMap { source ->
            val id = prefs.urlId(source.url)
            IcsParser.parse(source.text, now, end, id).also { events ->
                prefs.setIcsLastFetchEventCount(id, events.countToday())
            }
        }
        val localEvents = if (prefs.localCalendarEnabled && prefs.localCalendarIds.isNotEmpty()) {
            LocalCalendarReader.fetchEvents(context, prefs.localCalendarIds, now, end).also { events ->
                prefs.setLocalCalendarEventCount(events.countToday())
            }
        } else emptyList()
        val events = icsEvents + localEvents

        val filters = prefs.eventFilters
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
}
