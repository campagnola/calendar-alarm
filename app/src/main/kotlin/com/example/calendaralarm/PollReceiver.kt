package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

class PollReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { poll(context) } finally { result.finish() }
        }
    }

    internal suspend fun poll(context: Context) {
        try {
            ScheduleSync.run(context, "poll")
        } finally {
            AlarmScheduler.schedulePoll(context, Prefs(context).pollIntervalMinutes * 60_000L)
        }
    }
}
