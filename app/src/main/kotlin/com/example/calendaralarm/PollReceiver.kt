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
        if (sources.isEmpty()) {
            Log.w("CalAlarm", "PollReceiver: no ICS data available")
            AlarmScheduler.schedulePoll(context, prefs.pollIntervalMinutes * 60_000L)
            return
        }

        val maxCacheTs = sources.maxOf { it.cacheTimestamp }
        if (maxCacheTs <= prefs.lastAlarmScheduleTs) {
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
        val events = parsedPerSource.flatMap { it.second }

        val filters    = prefs.eventFilters
        val eventFlags = events.associateWith { FilterEngine.evaluate(it, filters) }

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
