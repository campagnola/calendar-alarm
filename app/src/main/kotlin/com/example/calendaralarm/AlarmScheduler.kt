package com.example.calendaralarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.LocalDateTime
import java.time.ZoneId

object AlarmScheduler {
    private const val POLL_REQUEST_CODE = 9001
    private const val QUIET_HOURS_REQUEST_CODE = 9002

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

    fun cancelAllEventAlarms(context: Context, codes: Set<Int>) {
        val am = context.getSystemService(AlarmManager::class.java)
        for (code in codes) {
            val pi = PendingIntent.getBroadcast(
                context, code,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: continue
            am.cancel(pi)
            pi.cancel()
        }
    }

    fun alarmRequestCode(event: CalendarEvent): Int {
        val startMs = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return (event.uid + startMs).hashCode()
    }

    fun alarmKey(event: CalendarEvent): String {
        val startMs = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return "${event.uid}_$startMs"
    }

    fun scheduleQuietHoursAlarm(context: Context, triggerAtMs: Long, eventCount: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, QUIET_HOURS_REQUEST_CODE,
            Intent(context, QuietHoursReceiver::class.java).putExtra("event_count", eventCount),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (_: SecurityException) {}
    }

    fun cancelQuietHoursAlarm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, QUIET_HOURS_REQUEST_CODE,
            Intent(context, QuietHoursReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    fun scheduleAll(context: Context, events: List<CalendarEvent>,
                    flags: Map<CalendarEvent, FilterEngine.EventFlags>,
                    prefs: Prefs): Set<Int> {
        cancelAllEventAlarms(context, prefs.activeAlarmCodes)
        cancelQuietHoursAlarm(context)

        val now = System.currentTimeMillis()
        val newCodes = mutableSetOf<Int>()

        val qhTriggerMs: Long
        val qhEndMs: Long
        if (prefs.quietHoursAlarmEnabled) {
            val nowDt = LocalDateTime.now()
            val startMin = prefs.quietHoursStartMinutes.toLong()
            val endMin   = prefs.quietHoursEndMinutes
            val todayStart = nowDt.toLocalDate().atStartOfDay().plusMinutes(startMin)
            val qhStartDt  = if (nowDt < todayStart) todayStart else todayStart.plusDays(1)
            val duration   = if (endMin > startMin.toInt()) (endMin - startMin.toInt()).toLong()
                             else (1440L - startMin + endMin)
            qhTriggerMs = qhStartDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            qhEndMs     = qhStartDt.plusMinutes(duration).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } else {
            qhTriggerMs = Long.MAX_VALUE
            qhEndMs     = Long.MAX_VALUE
        }

        var quietCount = 0
        for (event in events) {
            val ef = flags[event] ?: FilterEngine.EventFlags()
            if (!ef.alarmEnabled) continue
            val startMs = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (startMs >= qhTriggerMs && startMs < qhEndMs) {
                quietCount++
                continue
            }
            val advanceMs = (ef.advanceMinutes ?: prefs.advanceMinutes) * 60_000L
            val triggerMs = startMs - advanceMs
            if (triggerMs > now) {
                Log.i("CalAlarm", "scheduleAll: '${event.summary}' in ${(triggerMs - now) / 1000}s")
                scheduleEventAlarm(context, event, triggerMs)
                newCodes.add(alarmRequestCode(event))
            }
        }

        if (quietCount > 0 && qhTriggerMs > now && qhTriggerMs != Long.MAX_VALUE) {
            Log.i("CalAlarm", "scheduleAll: quiet hours alarm for $quietCount events")
            scheduleQuietHoursAlarm(context, qhTriggerMs, quietCount)
        }

        return newCodes
    }
}
