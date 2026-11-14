package com.example.calendaralarm

import android.content.Context
import org.json.JSONObject

/** Overrides [event]'s alarm for [scope] and resyncs alarms to apply it. */
fun setEventAlarm(context: Context, event: CalendarEvent, scope: EventPreference.Scope, enabled: Boolean) =
    updateEventPreference(context, event, scope) { it.copy(alarmEnabled = enabled) }

/** Overrides [event]'s advance warning in minutes before start (null restores filter/default) and resyncs alarms. */
fun setEventAdvance(context: Context, event: CalendarEvent, minutes: Int?) =
    updateEventPreference(context, event, EventPreference.Scope.EVENT) { it.copy(advanceMinutes = minutes) }

private fun updateEventPreference(
    context: Context, event: CalendarEvent, scope: EventPreference.Scope,
    change: (EventPreference) -> EventPreference
) {
    Prefs(context).updateEventPreference(event, scope, change)
    AlarmScheduler.schedulePoll(context, 0L)
}

data class EventPreference(
    val key: String,
    val scope: Scope,
    val alarmEnabled: Boolean? = null,
    val advanceMinutes: Int? = null,
    val lastSeenEndMs: Long = 0L
) {
    enum class Scope { EVENT, SERIES }

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("scope", scope.name)
        alarmEnabled?.let { put("alarmEnabled", it) }
        advanceMinutes?.let { put("advanceMinutes", it) }
        put("lastSeenEndMs", lastSeenEndMs)
    }

    companion object {
        fun fromJson(json: JSONObject): EventPreference = EventPreference(
            key = json.getString("key"),
            scope = Scope.valueOf(json.getString("scope")),
            alarmEnabled = if (json.has("alarmEnabled")) json.getBoolean("alarmEnabled") else null,
            advanceMinutes = if (json.has("advanceMinutes")) json.getInt("advanceMinutes") else null,
            lastSeenEndMs = json.optLong("lastSeenEndMs", 0L)
        )

        fun key(event: CalendarEvent, scope: Scope): String = when (scope) {
            Scope.EVENT  -> eventKey(event)
            Scope.SERIES -> seriesKey(event)
        }

        fun eventKey(event: CalendarEvent): String {
            return listOf(event.sourceId, event.uid, event.start.toEpochMilli().toString()).joinToString("|")
        }

        fun seriesKey(event: CalendarEvent): String =
            listOf(event.sourceId, event.uid).joinToString("|")
    }
}
