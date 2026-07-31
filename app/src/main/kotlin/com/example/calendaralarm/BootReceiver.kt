package com.example.calendaralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AppLog.init(context)
            AppLog.i("BootReceiver: scheduling immediate poll")
            AlarmScheduler.schedulePoll(context, 0L)
        }
    }
}
