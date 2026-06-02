package com.example.calendaralarm

import org.json.JSONObject
import java.time.ZoneId

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
            val startMs = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            return listOf(event.sourceId, event.uid, startMs.toString()).joinToString("|")
        }

        fun seriesKey(event: CalendarEvent): String =
            listOf(event.sourceId, event.uid).joinToString("|")
    }
}
