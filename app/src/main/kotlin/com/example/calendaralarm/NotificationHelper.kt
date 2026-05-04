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
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    fun buildAlarmNotification(context: Context, summary: String, startTime: String,
                                uid: String, location: String, meetingUrl: String?): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val base = uid.hashCode()

        val alarmActivityIntent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("summary", summary)
            putExtra("start_time", startTime)
            putExtra("uid", uid)
            putExtra("location", location)
            putExtra("meeting_url", meetingUrl)
        }

        val fullScreenPi = PendingIntent.getActivity(context, base + 2, alarmActivityIntent, flags)

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
                putExtra("location", location)
                putExtra("meeting_url", meetingUrl)
            }, flags)

        val contentText = buildString {
            append("Starts at $startTime")
            if (location.isNotEmpty()) append(" · $location")
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(summary)
            .setContentText(contentText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .addAction(0, "Dismiss", dismissPi)
            .addAction(0, "Snooze", snoozePi)

        if (meetingUrl != null) {
            val joinPi = PendingIntent.getBroadcast(context, base + 3,
                Intent(context, ActionReceiver::class.java).apply {
                    action = ActionReceiver.ACTION_JOIN
                    putExtra("meeting_url", meetingUrl)
                }, flags)
            val label = if (meetingUrl.contains("zoom.us", ignoreCase = true)) "Join Zoom" else "Join Teams"
            builder.addAction(0, label, joinPi)
        }

        return builder.build()
    }
}
