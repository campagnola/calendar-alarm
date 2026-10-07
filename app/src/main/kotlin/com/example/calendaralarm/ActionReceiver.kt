package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification actions; each names the alarm instance it acts on. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DISMISS              = "com.example.calendaralarm.DISMISS"
        const val ACTION_SNOOZE               = "com.example.calendaralarm.SNOOZE"
        const val ACTION_NOTIFICATION_DELETED = "com.example.calendaralarm.NOTIFICATION_DELETED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = alarmIdOf(intent, "ActionReceiver") ?: return
        AppLog.i("ActionReceiver: ${intent.action} $id")
        val controller = AlarmController.from(context)
        when (intent.action) {
            ACTION_DISMISS              -> controller.dismiss(id)
            ACTION_SNOOZE               -> controller.snooze(id)
            ACTION_NOTIFICATION_DELETED -> controller.onNotificationDeleted(id)
            else -> error("ActionReceiver: unexpected action ${intent.action}")
        }
    }
}
