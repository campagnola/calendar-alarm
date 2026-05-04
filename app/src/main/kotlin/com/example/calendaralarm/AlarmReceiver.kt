package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("CalAlarm", "AlarmReceiver fired for '${intent.getStringExtra("summary")}'")
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            putExtra("summary",     intent.getStringExtra("summary"))
            putExtra("start_time", intent.getStringExtra("start_time"))
            putExtra("uid",        intent.getStringExtra("uid"))
            putExtra("location",   intent.getStringExtra("location"))
            putExtra("meeting_url", intent.getStringExtra("meeting_url"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
