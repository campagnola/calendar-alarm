package com.example.calendaralarm

import android.content.Context
import java.security.MessageDigest

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("cal_alarm", Context.MODE_PRIVATE)

    // ── Calendar sources ──────────────────────────────────────────────────────

    var icsSources: List<String>
        get() {
            val stored = p.getString("ics_sources", null)
            if (stored != null) return stored.split('\n').filter { it.isNotEmpty() }
            // Migrate from the legacy single-URL field.
            val legacy = p.getString("ics_url", "") ?: ""
            return if (legacy.isNotEmpty()) listOf(legacy) else emptyList()
        }
        set(v) = p.edit().putString("ics_sources", v.joinToString("\n")).apply()

    // ── Alarm / poll config ───────────────────────────────────────────────────

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

    var lastAlarmScheduleTs: Long
        get() = p.getLong("last_alarm_schedule_ts", 0L)
        set(v) = p.edit().putLong("last_alarm_schedule_ts", v).apply()

    var activeAlarmCodes: Set<Int>
        get() = (p.getStringSet("active_alarm_codes", emptySet()) ?: emptySet())
                    .mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = p.edit().putStringSet("active_alarm_codes", v.map { it.toString() }.toSet()).apply()

    // ── Per-URL metadata (keyed by urlId = first 8 hex chars of MD5(url)) ────

    fun urlId(url: String): String =
        MessageDigest.getInstance("MD5").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)

    fun icsEtag(id: String): String             = p.getString("etag_$id", "") ?: ""
    fun setIcsEtag(id: String, v: String)       = p.edit().putString("etag_$id", v).apply()

    fun icsLastModified(id: String): String     = p.getString("lmod_$id", "") ?: ""
    fun setIcsLastModified(id: String, v: String) = p.edit().putString("lmod_$id", v).apply()

    fun icsContentHash(id: String): String      = p.getString("hash_$id", "") ?: ""
    fun setIcsContentHash(id: String, v: String) = p.edit().putString("hash_$id", v).apply()

    fun icsLastFetchMs(id: String): Long        = p.getLong("fetch_ms_$id", 0L)
    fun setIcsLastFetchMs(id: String, v: Long)  = p.edit().putLong("fetch_ms_$id", v).apply()

    // "OK" on success, or an error message.
    fun icsLastFetchStatus(id: String): String  = p.getString("fetch_ok_$id", "") ?: ""
    fun setIcsLastFetchStatus(id: String, v: String) = p.edit().putString("fetch_ok_$id", v).apply()

    // Number of events for today from this source (-1 = not yet known).
    fun icsLastFetchEventCount(id: String): Int = p.getInt("event_count_$id", -1)
    fun setIcsLastFetchEventCount(id: String, v: Int) = p.edit().putInt("event_count_$id", v).apply()

    // User-visible name for this source (auto-populated from X-WR-CALNAME on first fetch).
    fun icsSourceName(id: String): String = p.getString("name_$id", "") ?: ""
    fun setIcsSourceName(id: String, v: String) = p.edit().putString("name_$id", v).apply()

    fun clearSourceCache(id: String) {
        p.edit()
            .remove("etag_$id").remove("lmod_$id").remove("hash_$id")
            .remove("fetch_ms_$id").remove("fetch_ok_$id").remove("event_count_$id")
            .remove("name_$id")
            .apply()
    }
}
