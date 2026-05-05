package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class QuietHoursReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val count = intent.getIntExtra("event_count", 0)
        if (count > 0) NotificationHelper.showQuietHoursNotification(context, count)
    }
}
