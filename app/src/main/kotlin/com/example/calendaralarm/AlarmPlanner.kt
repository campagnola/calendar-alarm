package com.example.calendaralarm

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class PlannedAlarm(
    val events: List<CalendarEvent>,
    val triggerMs: Long,
    val silent: Boolean,
    val isQuietHoursAlarm: Boolean = false
)

private data class TriggerKey(val triggerMs: Long, val silent: Boolean)

fun planAlarms(
    events: List<CalendarEvent>,
    flags: Map<CalendarEvent, FilterEngine.EventFlags>,
    advanceMinutes: Int,
    quietHoursAlarmEnabled: Boolean,
    quietHoursStartMinutes: Int,
    quietHoursEndMinutes: Int,
    nowMs: Long,
    handledAlarmKeys: Set<String> = emptySet()
): List<PlannedAlarm> {
    val defaultQuietHoursMode = if (quietHoursAlarmEnabled)
        FilterAction.QuietHoursMode.EARLY else FilterAction.QuietHoursMode.SILENT

    val byTrigger = linkedMapOf<TriggerKey, MutableList<CalendarEvent>>()
    val earlyByTrigger = linkedMapOf<Long, MutableList<CalendarEvent>>()

    for (event in events) {
        val ef = flags[event] ?: FilterEngine.EventFlags()
        if (!ef.alarmEnabled) continue

        val alarmEvent = AlarmEvent.of(event)
        val startMs = alarmEvent.startMs
        val endMs   = alarmEvent.endMs

        if (alarmEvent.key in handledAlarmKeys) {
            AppLog.i("planAlarms: skip '${event.summary}' (alarm already raised)")
            continue
        }

        // Skip events that have completely ended — too late to be useful.
        if (endMs <= nowMs) {
            AppLog.i("planAlarms: skip '${event.summary}' (event ended)")
            continue
        }

        val baseMs = if (ef.advanceRelativeToEnd) endMs else startMs
        val advanceMs = ef.advanceMinutesOr(advanceMinutes) * 60_000L
        val triggerMs = baseMs - advanceMs

        var silent = false
        val quietMode = ef.quietHoursMode ?: defaultQuietHoursMode

        // Check both event start and computed trigger time against quiet hours.
        // Quiet hours based on event start is the common case (event occurs during quiet hours).
        // Quiet hours based on trigger time handles events that start just after quiet hours end
        // but whose advance time pushes the alarm back into quiet hours.
        val startInQuietHours = isInQuietHours(event.localStart, quietHoursStartMinutes, quietHoursEndMinutes)
        val triggerLdt = LocalDateTime.ofInstant(Instant.ofEpochMilli(triggerMs), ZoneId.systemDefault())
        val triggerInQuietHours = !startInQuietHours &&
            isInQuietHours(triggerLdt, quietHoursStartMinutes, quietHoursEndMinutes)

        if (startInQuietHours || triggerInQuietHours) {
            val refLdt = if (startInQuietHours) event.localStart else triggerLdt
            when (quietMode) {
                FilterAction.QuietHoursMode.SILENT -> silent = true
                FilterAction.QuietHoursMode.EARLY -> {
                    val quietStartMs = quietHoursStartFor(refLdt, quietHoursStartMinutes, quietHoursEndMinutes)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    if (quietStartMs > nowMs) {
                        earlyByTrigger.getOrPut(quietStartMs) { mutableListOf() }.add(event)
                        continue
                    }
                    silent = true
                }
                FilterAction.QuietHoursMode.ALARM -> Unit
            }
        }

        // If the intended trigger is in the past but the event hasn't ended, fire immediately
        // rather than dropping the alarm. This covers a trigger that is due but not yet delivered
        // when a schedule sync runs.
        val effectiveTriggerMs = if (triggerMs > nowMs) {
            triggerMs
        } else {
            AppLog.i("planAlarms: scheduling '${event.summary}' late (trigger was ${(nowMs - triggerMs) / 1000}s ago)")
            nowMs + 2_000L
        }
        byTrigger.getOrPut(TriggerKey(effectiveTriggerMs, silent)) { mutableListOf() }.add(event)
    }

    val result = mutableListOf<PlannedAlarm>()
    for ((ms, group) in earlyByTrigger) {
        result.add(PlannedAlarm(group, ms, silent = false, isQuietHoursAlarm = true))
    }
    for ((key, group) in byTrigger) {
        result.add(PlannedAlarm(group, key.triggerMs, key.silent))
    }
    return result
}

internal fun isInQuietHours(dt: LocalDateTime, startMin: Int, endMin: Int): Boolean {
    val minute = dt.hour * 60 + dt.minute
    return if (endMin > startMin) {
        minute >= startMin && minute < endMin
    } else {
        minute >= startMin || minute < endMin
    }
}

internal fun quietHoursStartFor(dt: LocalDateTime, startMin: Int, endMin: Int): LocalDateTime {
    val dayStart = dt.toLocalDate().atStartOfDay()
    val minute = dt.hour * 60 + dt.minute
    return if (endMin > startMin || minute >= startMin) {
        dayStart.plusMinutes(startMin.toLong())
    } else {
        dayStart.minusDays(1).plusMinutes(startMin.toLong())
    }
}
