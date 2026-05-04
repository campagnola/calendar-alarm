package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DISMISS = "com.example.calendaralarm.DISMISS"
        const val ACTION_SNOOZE = "com.example.calendaralarm.SNOOZE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        context.stopService(Intent(context, AlarmService::class.java))

        if (intent.action == ACTION_SNOOZE) {
            val prefs = Prefs(context)
            AlarmScheduler.scheduleSnooze(
                context,
                System.currentTimeMillis() + prefs.snoozeMinutes * 60_000L,
                intent.getStringExtra("summary") ?: "Event",
                intent.getStringExtra("start_time") ?: "",
                intent.getStringExtra("uid") ?: ""
            )
        }
    }
}
