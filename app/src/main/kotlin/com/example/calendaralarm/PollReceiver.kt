package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.*
import java.time.LocalDateTime

class PollReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { poll(context) } finally { result.finish() }
        }
    }

    private suspend fun poll(context: Context) {
        val prefs = Prefs(context)
        Log.i("CalAlarm", "PollReceiver: polling ${prefs.icsSources.size} source(s)")

        val sources = IcsFetcher.fetchAll(context, forceDownload = true)
        val hasLocalCalendars = prefs.localCalendarEnabled && prefs.localCalendarIds.isNotEmpty()
        if (sources.isEmpty() && !hasLocalCalendars) {
            Log.w("CalAlarm", "PollReceiver: no ICS data available")
            AlarmScheduler.schedulePoll(context, prefs.pollIntervalMinutes * 60_000L)
            return
        }

        val maxCacheTs = sources.maxOfOrNull { it.cacheTimestamp } ?: System.currentTimeMillis()
        if (!hasLocalCalendars && maxCacheTs <= prefs.lastAlarmScheduleTs) {
            Log.i("CalAlarm", "PollReceiver: all caches unchanged, skipping reschedule")
            AlarmScheduler.schedulePoll(context, prefs.pollIntervalMinutes * 60_000L)
            return
        }

        val nowDt = LocalDateTime.now()
        val today = nowDt.toLocalDate()

        val parsedPerSource = sources.map { source ->
            val id = prefs.urlId(source.url)
            source to IcsParser.parse(source.text, nowDt, nowDt.plusHours(25), id)
        }
        for ((source, sourceEvents) in parsedPerSource) {
            prefs.setIcsLastFetchEventCount(
                prefs.urlId(source.url),
                sourceEvents.count { it.start.toLocalDate() == today }
            )
        }
        val localEvents = if (prefs.localCalendarEnabled && prefs.localCalendarIds.isNotEmpty()) {
            val end = nowDt.plusHours(25)
            LocalCalendarReader.fetchEvents(context, prefs.localCalendarIds, nowDt, end).also { evs ->
                prefs.setLocalCalendarEventCount(evs.count { it.start.toLocalDate() == today })
            }
        } else emptyList()
        val events = parsedPerSource.flatMap { it.second } + localEvents

        val filters    = prefs.eventFilters
        prefs.pruneStaleEventPreferences(nowDt)
        prefs.updateEventPreferenceLastSeen(events)
        val eventPrefs = prefs.eventPreferences.associateBy { it.key }
        val eventFlags = events.associateWith { event ->
            var flags = FilterEngine.evaluate(event, filters)
            if (event.isSeries) {
                eventPrefs[EventPreference.seriesKey(event)]?.alarmEnabled?.let {
                    flags = flags.copy(alarmEnabled = it)
                }
            }
            eventPrefs[EventPreference.eventKey(event)]?.alarmEnabled?.let {
                flags = flags.copy(alarmEnabled = it)
            }
            flags
        }

        for (filter in filters) {
            prefs.setFilterMatchCount(filter.id,
                events.count { it.start.toLocalDate() == today
                             && FilterEngine.matches(it, filter.criteria) })
        }

        prefs.activeAlarmCodes    = AlarmScheduler.scheduleAll(context, events, eventFlags, prefs)
        prefs.lastAlarmScheduleTs = maxCacheTs
        prefs.lastPollTime        = System.currentTimeMillis()

        AlarmScheduler.schedulePoll(context, prefs.pollIntervalMinutes * 60_000L)
    }
}
