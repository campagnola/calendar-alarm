package com.example.calendaralarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.ZoneId

object AlarmScheduler {
    private const val POLL_REQUEST_CODE = 9001

    fun schedulePoll(context: Context, delayMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, POLL_REQUEST_CODE,
            Intent(context, PollReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
        } catch (_: SecurityException) {}
    }

    fun scheduleEventAlarm(context: Context, event: CalendarEvent, triggerAtMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val requestCode = alarmRequestCode(event)
        val startTime = event.start.format(timeFormatter(context))
        val pi = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, AlarmReceiver::class.java).apply {
                putExtra("summary", event.summary)
                putExtra("start_time", startTime)
                putExtra("uid", event.uid)
                putExtra("location", event.location)
                putExtra("meeting_url", event.meetingUrl)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (_: SecurityException) {}
    }

    fun scheduleSnooze(context: Context, triggerAtMs: Long, summary: String, startTime: String,
                       uid: String, location: String, meetingUrl: String?) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, uid.hashCode() xor 0xABCD,
            Intent(context, AlarmReceiver::class.java).apply {
                putExtra("summary", summary)
                putExtra("start_time", startTime)
                putExtra("uid", uid)
                putExtra("location", location)
                putExtra("meeting_url", meetingUrl)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (_: SecurityException) {}
    }

    fun alarmRequestCode(event: CalendarEvent): Int {
        val startMs = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return (event.uid + startMs).hashCode()
    }

    fun alarmKey(event: CalendarEvent): String {
        val startMs = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return "${event.uid}_$startMs"
    }
}
