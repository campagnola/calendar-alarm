package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("CalAlarm", "AlarmReceiver fired")
        val eventsJson = intent.getStringExtra("events_json")
        if (intent.getBooleanExtra("silent", false) && eventsJson != null) {
            NotificationHelper.showSilentAlarmNotification(context, eventsJson)
            return
        }
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            putExtra("events_json", eventsJson)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
