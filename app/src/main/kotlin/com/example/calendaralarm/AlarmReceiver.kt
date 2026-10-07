package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Delivers the AlarmManager alarms of an [AlarmInstance] to the [AlarmController]. */
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TRIGGER      = "com.example.calendaralarm.TRIGGER"
        const val ACTION_RING_TIMEOUT = "com.example.calendaralarm.RING_TIMEOUT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = alarmIdOf(intent, "AlarmReceiver") ?: return
        AppLog.i("AlarmReceiver: ${intent.action} $id")
        val controller = AlarmController.from(context)
        when (intent.action) {
            ACTION_TRIGGER      -> controller.onTrigger(id)
            ACTION_RING_TIMEOUT -> controller.ringTimeout(id)
            else -> error("AlarmReceiver: unexpected action ${intent.action}")
        }
    }
}
