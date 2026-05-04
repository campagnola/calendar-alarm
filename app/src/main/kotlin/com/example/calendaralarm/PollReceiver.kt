package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.ZoneId

class PollReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                poll(context)
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun poll(context: Context) {
        val prefs = Prefs(context)

        Log.i("CalAlarm", "PollReceiver: polling")
        val ics = IcsFetcher.fetch(context, forceDownload = true) ?: run {
            Log.w("CalAlarm", "PollReceiver: no ICS available (no cache, no network)")
            AlarmScheduler.schedulePoll(context, prefs.pollIntervalMinutes * 60_000L)
            return
        }

        if (ics.cacheTimestamp <= prefs.lastAlarmScheduleTs) {
            Log.i("CalAlarm", "PollReceiver: cache unchanged, skipping reschedule")
            AlarmScheduler.schedulePoll(context, prefs.pollIntervalMinutes * 60_000L)
            return
        }

        val nowDt = LocalDateTime.now()
        val events = IcsParser.parse(ics.text, nowDt, nowDt.plusHours(25))

        AlarmScheduler.cancelAllEventAlarms(context, prefs.activeAlarmCodes)

        val advanceMs = prefs.advanceMinutes * 60_000L
        val now = System.currentTimeMillis()
        val newCodes = mutableSetOf<Int>()
        for (event in events) {
            val startMs   = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val triggerMs = startMs - advanceMs
            if (triggerMs > now) {
                Log.i("CalAlarm", "PollReceiver: scheduling '${event.summary}' in ${(triggerMs - now) / 1000}s")
                AlarmScheduler.scheduleEventAlarm(context, event, triggerMs)
                newCodes.add(AlarmScheduler.alarmRequestCode(event))
            }
        }
        prefs.activeAlarmCodes = newCodes
        prefs.lastAlarmScheduleTs = ics.cacheTimestamp
        prefs.lastPollTime = System.currentTimeMillis()
        AlarmScheduler.schedulePoll(context, prefs.pollIntervalMinutes * 60_000L)
    }
}
