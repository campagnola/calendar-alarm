package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DISMISS = "com.example.calendaralarm.DISMISS"
        const val ACTION_SNOOZE  = "com.example.calendaralarm.SNOOZE"
        const val ACTION_JOIN    = "com.example.calendaralarm.JOIN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        context.stopService(Intent(context, AlarmService::class.java))

        when (intent.action) {
            ACTION_SNOOZE -> {
                val prefs = Prefs(context)
                AlarmScheduler.scheduleSnooze(
                    context,
                    System.currentTimeMillis() + prefs.snoozeMinutes * 60_000L,
                    intent.getStringExtra("summary") ?: "Event",
                    intent.getStringExtra("start_time") ?: "",
                    intent.getStringExtra("uid") ?: "",
                    intent.getStringExtra("location") ?: "",
                    intent.getStringExtra("meeting_url")
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
