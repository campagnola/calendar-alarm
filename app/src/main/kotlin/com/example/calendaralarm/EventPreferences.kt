package com.example.calendaralarm

import android.content.Context
import org.json.JSONObject

/** Overrides [event]'s alarm for [scope] and resyncs alarms to apply it. */
fun setEventAlarm(context: Context, event: CalendarEvent, scope: EventPreference.Scope, enabled: Boolean) {
    Prefs(context).saveEventPreference(EventPreference.of(event, scope, enabled))
    AlarmScheduler.schedulePoll(context, 0L)
}

data class EventPreference(
    val key: String,
    val scope: Scope,
    val alarmEnabled: Boolean? = null,
    val lastSeenEndMs: Long = 0L
) {
    enum class Scope { EVENT, SERIES }

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("scope", scope.name)
        alarmEnabled?.let { put("alarmEnabled", it) }
        put("lastSeenEndMs", lastSeenEndMs)
    }

    companion object {
        fun fromJson(json: JSONObject): EventPreference = EventPreference(
            key = json.getString("key"),
            scope = Scope.valueOf(json.getString("scope")),
            alarmEnabled = if (json.has("alarmEnabled")) json.getBoolean("alarmEnabled") else null,
            lastSeenEndMs = json.optLong("lastSeenEndMs", 0L)
        )

        fun of(event: CalendarEvent, scope: Scope, alarmEnabled: Boolean) = EventPreference(
            key = when (scope) {
                Scope.EVENT  -> eventKey(event)
                Scope.SERIES -> seriesKey(event)
            },
            scope = scope,
            alarmEnabled = alarmEnabled,
            lastSeenEndMs = event.end.toEpochMilli()
        )

        fun eventKey(event: CalendarEvent): String {
            return listOf(event.sourceId, event.uid, event.start.toEpochMilli().toString()).joinToString("|")
        }

        fun seriesKey(event: CalendarEvent): String =
            listOf(event.sourceId, event.uid).joinToString("|")
    }
}
