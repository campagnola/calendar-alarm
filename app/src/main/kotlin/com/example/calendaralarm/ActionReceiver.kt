package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.NotificationManager

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DISMISS = "com.example.calendaralarm.DISMISS"
        const val ACTION_SNOOZE  = "com.example.calendaralarm.SNOOZE"
        const val ACTION_JOIN    = "com.example.calendaralarm.JOIN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        context.stopService(Intent(context, AlarmService::class.java))
        context.getSystemService(NotificationManager::class.java)
            .cancel(NotificationHelper.ALARM_NOTIF_ID)

        when (intent.action) {
            ACTION_SNOOZE -> {
                val prefs      = Prefs(context)
                val eventsJson = intent.getStringExtra("events_json") ?: return
                val maxStartMs = AlarmScheduler.maxStartTimeMsFromJson(eventsJson)
                AlarmScheduler.scheduleSnooze(
                    context,
                    AlarmScheduler.computeSnoozeUntilMs(maxStartMs, prefs),
                    eventsJson
                )
            }
            ACTION_JOIN -> {
                val url = intent.getStringExtra("meeting_url") ?: return
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }
}
