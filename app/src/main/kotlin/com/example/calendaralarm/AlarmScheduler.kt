package com.example.calendaralarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

object AlarmScheduler {
    private const val POLL_REQUEST_CODE = 9001
    private const val QUIET_HOURS_REQUEST_CODE = 9002

    fun schedulePoll(context: Context, delayMs: Long) {
        AppLog.init(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, POLL_REQUEST_CODE,
            Intent(context, PollReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        AppLog.i("schedulePoll: next poll in ${delayMs / 1000}s")
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
        } catch (_: SecurityException) {
            AppLog.w("schedulePoll: SecurityException")
        }
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
        val endMs   = event.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return "${event.uid}_${startMs}_${endMs}"
    }

    fun scheduleEventAlarm(context: Context, event: CalendarEvent, triggerAtMs: Long) {
        scheduleAlarm(context, alarmRequestCode(event), buildEventsJson(context, listOf(event)), triggerAtMs)
    }

    fun scheduleGroupAlarm(
        context: Context,
        events: List<CalendarEvent>,
        triggerAtMs: Long,
        silent: Boolean = false
    ) {
        scheduleAlarm(context, groupAlarmRequestCode(events), buildEventsJson(context, events), triggerAtMs, silent)
    }

    private fun scheduleAlarm(
        context: Context,
        requestCode: Int,
        eventsJson: String,
        triggerAtMs: Long,
        silent: Boolean = false
    ) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, AlarmReceiver::class.java)
                .putExtra("events_json", eventsJson)
                .putExtra("silent", silent)
                .putExtra("trigger_ms", triggerAtMs),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        AppLog.i("scheduleAlarm: code=$requestCode triggerAt=${Instant.ofEpochMilli(triggerAtMs)}${if (silent) " [silent]" else ""}")
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (_: SecurityException) {
            AppLog.w("scheduleAlarm: SecurityException setting alarm code=$requestCode")
        }
    }

    fun scheduleSnooze(context: Context, triggerAtMs: Long, eventsJson: String) {
        val uid = runCatching { JSONArray(eventsJson).getJSONObject(0).getString("uid") }
                    .getOrDefault("snooze")
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, uid.hashCode() xor 0xABCD,
            Intent(context, AlarmReceiver::class.java)
                .putExtra("events_json", eventsJson)
                .putExtra("trigger_ms", triggerAtMs),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        AppLog.i("scheduleSnooze: in ${(triggerAtMs - System.currentTimeMillis()) / 1000}s")
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (_: SecurityException) {
            AppLog.w("scheduleSnooze: SecurityException")
        }
    }

    fun cancelAllEventAlarms(context: Context, codes: Set<Int>) {
        val am = context.getSystemService(AlarmManager::class.java)
        var cancelled = 0
        for (code in codes) {
            val pi = PendingIntent.getBroadcast(
                context, code,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: continue
            am.cancel(pi)
            pi.cancel()
            cancelled++
        }
        AppLog.i("cancelAllEventAlarms: cancelled $cancelled / ${codes.size} pending intents")
    }

    fun scheduleQuietHoursAlarm(context: Context, triggerAtMs: Long, events: List<CalendarEvent>) {
        AppLog.i("scheduleQuietHoursAlarm: ${events.size} events in ${(triggerAtMs - System.currentTimeMillis()) / 1000}s")
        scheduleAlarm(context, QUIET_HOURS_REQUEST_CODE, buildEventsJson(context, events), triggerAtMs)
    }

    fun cancelQuietHoursAlarm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val alarmPi = PendingIntent.getBroadcast(
            context, QUIET_HOURS_REQUEST_CODE,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (alarmPi != null) {
            am.cancel(alarmPi)
            alarmPi.cancel()
            AppLog.i("cancelQuietHoursAlarm: cancelled")
        }

        val legacyPi = PendingIntent.getBroadcast(
            context, QUIET_HOURS_REQUEST_CODE,
            Intent(context, QuietHoursReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        am.cancel(legacyPi)
        legacyPi.cancel()
    }

    fun scheduleAll(context: Context, events: List<CalendarEvent>,
                    flags: Map<CalendarEvent, FilterEngine.EventFlags>,
                    prefs: Prefs): Set<Int> {
        AppLog.init(context)
        val raisedKeys = prefs.raisedAlarmKeys
        AppLog.i("scheduleAll: ${events.size} events, ${raisedKeys.size} keys already raised")

        cancelAllEventAlarms(context, prefs.activeAlarmCodes)
        cancelQuietHoursAlarm(context)

        val now = System.currentTimeMillis()
        val newCodes = mutableSetOf<Int>()

        val planned = planAlarms(
            events = events,
            flags = flags,
            advanceMinutes = prefs.advanceMinutes,
            quietHoursAlarmEnabled = prefs.quietHoursAlarmEnabled,
            quietHoursStartMinutes = prefs.quietHoursStartMinutes,
            quietHoursEndMinutes = prefs.quietHoursEndMinutes,
            nowMs = now,
            raisedAlarmKeys = raisedKeys
        )

        for (alarm in planned) {
            val inSec = (alarm.triggerMs - now) / 1000
            when {
                alarm.isQuietHoursAlarm -> {
                    AppLog.i("scheduleAll: quiet hours alarm for ${alarm.events.size} events in ${inSec}s")
                    scheduleQuietHoursAlarm(context, alarm.triggerMs, alarm.events)
                    newCodes.add(QUIET_HOURS_REQUEST_CODE)
                }
                alarm.events.size == 1 -> {
                    val event = alarm.events[0]
                    val lateTag = if (inSec < 5) " [LATE]" else ""
                    AppLog.i("scheduleAll: '${event.summary}' in ${inSec}s$lateTag")
                    scheduleGroupAlarm(context, alarm.events, alarm.triggerMs, alarm.silent)
                    newCodes.add(alarmRequestCode(event))
                }
                else -> {
                    val lateTag = if (inSec < 5) " [LATE]" else ""
                    AppLog.i("scheduleAll: group of ${alarm.events.size} events in ${inSec}s$lateTag")
                    scheduleGroupAlarm(context, alarm.events, alarm.triggerMs, alarm.silent)
                    newCodes.add(groupAlarmRequestCode(alarm.events))
                }
            }
        }

        AppLog.i("scheduleAll: registered ${newCodes.size} alarms")
        return newCodes
    }
}
