package com.example.calendaralarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray

object NotificationHelper {
    const val CHANNEL_ALARM = "alarm"
    const val CHANNEL_REMINDER = "reminder"
    const val ALARM_NOTIF_ID = 42
    const val QUIET_HOURS_NOTIF_ID = 43

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Event Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Quiet Hours Reminder", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun showQuietHoursNotification(context: Context, count: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = "$count ${if (count == 1) "event" else "events"} scheduled during quiet hours"
        val notif = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Upcoming events")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(QUIET_HOURS_NOTIF_ID, notif)
    }

    fun showSilentAlarmNotification(context: Context, eventsJson: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(ALARM_NOTIF_ID, buildAlarmNotification(context, eventsJson, silent = true))
    }

    fun buildAlarmNotification(context: Context, eventsJson: String, silent: Boolean = false): Notification {
        val arr    = JSONArray(eventsJson)
        val count  = arr.length()
        val first  = arr.getJSONObject(0)

        val firstSummary = first.getString("summary")
        val startTime    = first.getString("start_time")
        val firstUid     = first.getString("uid")
        val firstLoc     = first.optString("location", "")
        val firstUrl     = first.optString("meeting_url", "").takeIf { it.isNotEmpty() }
        val maxStartTimeMs = AlarmScheduler.maxStartTimeMsFromJson(eventsJson)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val base  = firstUid.hashCode()

        val alarmActivityIntent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("events_json", eventsJson)
        }
        val fullScreenPi = PendingIntent.getActivity(context, base + 2, alarmActivityIntent, flags)

        val dismissPi = PendingIntent.getBroadcast(context, base,
            Intent(context, ActionReceiver::class.java).apply {
                action = ActionReceiver.ACTION_DISMISS
            }, flags)

        val snoozePi = PendingIntent.getBroadcast(context, base + 1,
            Intent(context, ActionReceiver::class.java).apply {
                action = ActionReceiver.ACTION_SNOOZE
                putExtra("events_json", eventsJson)
            }, flags)

        val snoozeUntilMs   = AlarmScheduler.computeSnoozeUntilMs(maxStartTimeMs, Prefs(context))
        val snoozeUntilTime = Instant.ofEpochMilli(snoozeUntilMs)
            .atZone(ZoneId.systemDefault()).toLocalDateTime()
        val snoozeLabel = "Snooze until ${snoozeUntilTime.format(timeFormatter(context))}"

        val title = if (count == 1) firstSummary else "$count events at $startTime"
        val contentText = if (count == 1) {
            buildString {
                append("Starts at $startTime")
                if (firstLoc.isNotEmpty()) append(" · $firstLoc")
            }
        } else {
            (0 until count).joinToString("\n") { arr.getJSONObject(it).getString("summary") }
        }

        val builder = NotificationCompat.Builder(
            context,
            if (silent) CHANNEL_REMINDER else CHANNEL_ALARM
        )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(fullScreenPi)
            .addAction(0, "Dismiss", dismissPi)
            .addAction(0, snoozeLabel, snoozePi)

        if (silent) {
            builder
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setSilent(true)
        } else {
            builder
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPi, true)
        }

        if (count > 1) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        }

        if (count == 1 && firstUrl != null) {
            val joinPi = PendingIntent.getBroadcast(context, base + 3,
                Intent(context, ActionReceiver::class.java).apply {
                    action = ActionReceiver.ACTION_JOIN
                    putExtra("meeting_url", firstUrl)
                }, flags)
            val label = if (firstUrl.contains("zoom.us", ignoreCase = true)) "Join Zoom" else "Join Teams"
            builder.addAction(0, label, joinPi)
        }

        return builder.build()
    }
}
