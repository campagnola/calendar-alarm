package com.example.calendaralarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

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

    fun computeSnoozeUntilMs(eventTimeMs: Long, prefs: Prefs): Long {
        val now   = System.currentTimeMillis()
        val minMs = prefs.snoozeMinutes * 60_000L
        return now + if (eventTimeMs > now) maxOf((eventTimeMs - now) * 3 / 4, minMs) else minMs
    }

    fun buildEventsJson(context: Context, events: List<CalendarEvent>): String {
        val arr = JSONArray()
        val fmt = timeFormatter(context)
        for (ev in events) {
            val startMs = ev.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            arr.put(JSONObject().apply {
                put("summary",    ev.summary)
                put("start_time", ev.start.format(fmt))
                put("start_time_ms", startMs)
                put("uid",        ev.uid)
                put("location",   ev.location)
                ev.meetingUrl?.let { put("meeting_url", it) }
                put("end_time_ms", ev.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            })
        }
        return arr.toString()
    }

    fun maxEndTimeMsFromJson(eventsJson: String): Long = runCatching {
        val arr = JSONArray(eventsJson)
        (0 until arr.length()).maxOf { arr.getJSONObject(it).getLong("end_time_ms") }
    }.getOrDefault(0L)

    fun maxStartTimeMsFromJson(eventsJson: String): Long = runCatching {
        val arr = JSONArray(eventsJson)
        (0 until arr.length()).maxOf { arr.getJSONObject(it).optLong("start_time_ms", 0L) }
    }.getOrDefault(0L)

    fun alarmRequestCode(event: CalendarEvent): Int {
        val startMs = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return (event.uid + startMs).hashCode()
    }

    fun groupAlarmRequestCode(events: List<CalendarEvent>): Int =
        events.map { alarmRequestCode(it) }.fold(0) { acc, c -> acc xor c }

    fun alarmKey(event: CalendarEvent): String {
        val startMs = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return "${event.uid}_$startMs"
    }

    fun scheduleEventAlarm(context: Context, event: CalendarEvent, triggerAtMs: Long) {
        scheduleAlarm(context, alarmRequestCode(event), buildEventsJson(context, listOf(event)), triggerAtMs)
    }

    fun scheduleGroupAlarm(context: Context, events: List<CalendarEvent>, triggerAtMs: Long) {
        scheduleAlarm(context, groupAlarmRequestCode(events), buildEventsJson(context, events), triggerAtMs)
    }

    private fun scheduleAlarm(context: Context, requestCode: Int, eventsJson: String, triggerAtMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, AlarmReceiver::class.java).putExtra("events_json", eventsJson),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (_: SecurityException) {}
    }

    fun scheduleSnooze(context: Context, triggerAtMs: Long, eventsJson: String) {
        val uid = runCatching { JSONArray(eventsJson).getJSONObject(0).getString("uid") }
                    .getOrDefault("snooze")
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, uid.hashCode() xor 0xABCD,
            Intent(context, AlarmReceiver::class.java).putExtra("events_json", eventsJson),
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

        // Collect alarms, grouping events that share the same trigger time
        val byTrigger = linkedMapOf<Long, MutableList<CalendarEvent>>()
        var quietCount = 0
        for (event in events) {
            val ef = flags[event] ?: FilterEngine.EventFlags()
            if (!ef.alarmEnabled) continue
            val startMs = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (startMs >= qhTriggerMs && startMs < qhEndMs) {
                quietCount++
                continue
            }
            val baseMs = if (ef.advanceRelativeToEnd)
                event.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            else startMs
            val advanceMs = (ef.advanceMinutes ?: prefs.advanceMinutes) * 60_000L
            val triggerMs = baseMs - advanceMs
            if (triggerMs > now) {
                byTrigger.getOrPut(triggerMs) { mutableListOf() }.add(event)
            }
        }

        for ((triggerMs, group) in byTrigger) {
            if (group.size == 1) {
                val event = group[0]
                Log.i("CalAlarm", "scheduleAll: '${event.summary}' in ${(triggerMs - now) / 1000}s")
                scheduleEventAlarm(context, event, triggerMs)
                newCodes.add(alarmRequestCode(event))
            } else {
                Log.i("CalAlarm", "scheduleAll: group of ${group.size} events in ${(triggerMs - now) / 1000}s")
                scheduleGroupAlarm(context, group, triggerMs)
                newCodes.add(groupAlarmRequestCode(group))
            }
        }

        if (quietCount > 0 && qhTriggerMs > now && qhTriggerMs != Long.MAX_VALUE) {
            Log.i("CalAlarm", "scheduleAll: quiet hours alarm for $quietCount events")
            scheduleQuietHoursAlarm(context, qhTriggerMs, quietCount)
        }

        return newCodes
    }
}
