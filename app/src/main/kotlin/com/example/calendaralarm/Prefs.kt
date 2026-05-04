package com.example.calendaralarm

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("cal_alarm", Context.MODE_PRIVATE)

    var icsUrl: String
        get() = p.getString("ics_url", "") ?: ""
        set(v) = p.edit().putString("ics_url", v).apply()

    var advanceMinutes: Int
        get() = p.getInt("advance_min", 5)
        set(v) = p.edit().putInt("advance_min", v).apply()

    var pollIntervalMinutes: Int
        get() = p.getInt("poll_min", 15)
        set(v) = p.edit().putInt("poll_min", v).apply()

    var snoozeMinutes: Int
        get() = p.getInt("snooze_min", 4)
        set(v) = p.edit().putInt("snooze_min", v).apply()

    var lastPollTime: Long
        get() = p.getLong("last_poll", 0L)
        set(v) = p.edit().putLong("last_poll", v).apply()

    var icsEtag: String
        get() = p.getString("ics_etag", "") ?: ""
        set(v) = p.edit().putString("ics_etag", v).apply()

    var icsLastModified: String
        get() = p.getString("ics_last_modified", "") ?: ""
        set(v) = p.edit().putString("ics_last_modified", v).apply()

    fun clearIcsCache() {
        p.edit().remove("ics_etag").remove("ics_last_modified").apply()
    }

    private var scheduledAlarms: Set<String>
        get() = p.getStringSet("scheduled", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("scheduled", v).apply()

    fun isAlarmScheduled(key: String) = scheduledAlarms.contains(key)

    fun addScheduledAlarm(key: String) {
        scheduledAlarms = scheduledAlarms + key
    }

    fun retainScheduledAlarms(validKeys: Set<String>) {
        scheduledAlarms = scheduledAlarms.intersect(validKeys)
    }
}
