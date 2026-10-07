package com.example.calendaralarm

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.ZoneId

/** Identifies one occurrence of an event. */
object AlarmKey {
    fun of(uid: String, startMs: Long, endMs: Long): String = "${uid}_${startMs}_${endMs}"

    private val FORMAT = Regex(".+_\\d+_\\d+")

    /** False for keys written by older app versions, which used other formats. */
    fun isCurrentFormat(key: String) = FORMAT.matches(key)

    /** Inverse of [of]: (uid, startMs, endMs). The uid may itself contain '_'. */
    fun parse(key: String): Triple<String, Long, Long> {
        val rest = key.substringBeforeLast('_')
        return Triple(rest.substringBeforeLast('_'), rest.substringAfterLast('_').toLong(),
                      key.substringAfterLast('_').toLong())
    }
}

/** The part of a calendar event an alarm needs to present it. */
data class AlarmEvent(
    val uid: String,
    val summary: String,
    val startMs: Long,
    val endMs: Long,
    val location: String = "",
    val meetingUrl: String? = null
) {
    val key: String get() = AlarmKey.of(uid, startMs, endMs)

    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("summary", summary)
        put("startMs", startMs)
        put("endMs", endMs)
        put("location", location)
        meetingUrl?.let { put("meetingUrl", it) }
    }

    companion object {
        fun of(event: CalendarEvent) = AlarmEvent(
            uid        = event.uid,
            summary    = event.summary,
            startMs    = event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            endMs      = event.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            location   = event.location,
            meetingUrl = event.meetingUrl
        )

        fun fromJson(obj: JSONObject) = AlarmEvent(
            uid        = obj.getString("uid"),
            summary    = obj.getString("summary"),
            startMs    = obj.getLong("startMs"),
            endMs      = obj.getLong("endMs"),
            location   = obj.getString("location"),
            meetingUrl = if (obj.has("meetingUrl")) obj.getString("meetingUrl") else null
        )
    }
}

/** One alarm for a group of events, from planning until it is dismissed or missed. */
data class AlarmInstance(
    val id: String,
    val events: List<AlarmEvent>,
    val kind: Kind,
    val state: State,
    val triggerMs: Long,   // when it is due: the planned time, or the snooze-until time
    val updatedMs: Long    // when it entered its current state; the ring timeout counts from it
) {
    enum class Kind { RING, SILENT }
    enum class State {
        SCHEDULED, RINGING, SNOOZED, DISMISSED, MISSED;
        val isTerminal: Boolean get() = this == DISMISSED || this == MISSED
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
        put("kind", kind.name)
        put("state", state.name)
        put("triggerMs", triggerMs)
        put("updatedMs", updatedMs)
    }

    companion object {
        /** Stable identity of the alarm for exactly these event occurrences. */
        fun idFor(events: List<AlarmEvent>): String =
            MessageDigest.getInstance("SHA-1")
                .digest(events.map { it.key }.sorted().joinToString("\n").toByteArray())
                .joinToString("") { "%02x".format(it) }.take(12)

        fun scheduled(planned: PlannedAlarm, nowMs: Long): AlarmInstance {
            val events = planned.events.map(AlarmEvent::of)
            return AlarmInstance(
                id        = idFor(events),
                events    = events,
                kind      = if (planned.silent) Kind.SILENT else Kind.RING,
                state     = State.SCHEDULED,
                triggerMs = planned.triggerMs,
                updatedMs = nowMs
            )
        }

        /** A dismissed placeholder for an event whose alarm was raised before AlarmStore existed. */
        fun legacyDismissed(key: String, nowMs: Long): AlarmInstance {
            val (uid, startMs, endMs) = AlarmKey.parse(key)
            val events = listOf(AlarmEvent(uid, summary = "", startMs = startMs, endMs = endMs))
            return AlarmInstance(idFor(events), events, Kind.RING, State.DISMISSED, startMs, nowMs)
        }

        fun fromJson(obj: JSONObject): AlarmInstance {
            val events = obj.getJSONArray("events")
            return AlarmInstance(
                id        = obj.getString("id"),
                events    = (0 until events.length()).map { AlarmEvent.fromJson(events.getJSONObject(it)) },
                kind      = Kind.valueOf(obj.getString("kind")),
                state     = State.valueOf(obj.getString("state")),
                triggerMs = obj.getLong("triggerMs"),
                updatedMs = obj.getLong("updatedMs")
            )
        }
    }
}
