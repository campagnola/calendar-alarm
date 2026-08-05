package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLog.init(context)

        val eventsJson = intent.getStringExtra("events_json")
        val intendedMs = intent.getLongExtra("trigger_ms", 0L)
        val delayMs    = if (intendedMs > 0L) System.currentTimeMillis() - intendedMs else -1L
        val delayStr   = if (delayMs >= 0) " delay=${delayMs}ms" else ""
        val silentTag  = if (intent.getBooleanExtra("silent", false)) " [silent]" else ""
        AppLog.i("AlarmReceiver fired$delayStr$silentTag")

        if (eventsJson != null) {
            markRaised(context, eventsJson)
        }

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

    private fun markRaised(context: Context, eventsJson: String) {
        val prefs = Prefs(context)
        try {
            val arr = JSONArray(eventsJson)
            val keys = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val key = "${obj.getString("uid")}_${obj.getLong("start_time_ms")}_${obj.getLong("end_time_ms")}"
                keys.add(key)
                prefs.markAlarmRaised(key)
            }
            AppLog.i("AlarmReceiver: marked raised: $keys")
        } catch (e: Exception) {
            AppLog.w("AlarmReceiver: failed to mark raised: ${e.message}")
        }
    }
}
