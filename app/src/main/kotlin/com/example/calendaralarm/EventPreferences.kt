package com.example.calendaralarm

import org.json.JSONObject

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

        fun eventKey(event: CalendarEvent): String {
            return listOf(event.sourceId, event.uid, event.start.toEpochMilli().toString()).joinToString("|")
        }

        fun seriesKey(event: CalendarEvent): String =
            listOf(event.sourceId, event.uid).joinToString("|")
    }
}
