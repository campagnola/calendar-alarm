package com.example.calendaralarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_ALARM = "alarm"
    const val ALARM_NOTIF_ID = 42

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Event Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)   // vibration handled by AlarmService directly
                enableVibration(false)
            }
        )
    }

    fun buildAlarmNotification(context: Context, summary: String, startTime: String, uid: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val base = uid.hashCode()

        val dismissPi = PendingIntent.getBroadcast(context, base,
            Intent(context, ActionReceiver::class.java).apply {
                action = ActionReceiver.ACTION_DISMISS
            }, flags)

        val snoozePi = PendingIntent.getBroadcast(context, base + 1,
            Intent(context, ActionReceiver::class.java).apply {
                action = ActionReceiver.ACTION_SNOOZE
                putExtra("summary", summary)
                putExtra("start_time", startTime)
                putExtra("uid", uid)
            }, flags)

        val openPi = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java), flags)

        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(summary)
            .setContentText("Starts at $startTime")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openPi)
            .addAction(0, "Dismiss", dismissPi)
            .addAction(0, "Snooze 4 min", snoozePi)
            .build()
    }
}
