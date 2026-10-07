package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores alarms after events that clear AlarmManager or shift wall-clock times. */
class BootReceiver : BroadcastReceiver() {
    private val actions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in actions) {
            AppLog.w("BootReceiver: ignoring ${intent.action}")
            return
        }
        AppLog.i("BootReceiver: ${intent.action}, reconciling alarms and polling now")
        AlarmController.from(context).reconcile(intent.action!!.substringAfterLast('.'))
        AlarmScheduler.schedulePoll(context, 0L)
    }
}
