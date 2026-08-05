package com.example.calendaralarm

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId

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

    // Minutes since midnight after which the main view switches to tomorrow (default 20:00 = 1200).
    var showTomorrowAtMinutes: Int
        get() = p.getInt("show_tomorrow_min", 20 * 60)
        set(v) = p.edit().putInt("show_tomorrow_min", v).apply()

    // Quiet hours: start/end as minutes since midnight (default 22:00–07:00).
    var quietHoursStartMinutes: Int
        get() = p.getInt("quiet_start_min", 22 * 60)
        set(v) = p.edit().putInt("quiet_start_min", v).apply()

    var quietHoursEndMinutes: Int
        get() = p.getInt("quiet_end_min", 7 * 60)
        set(v) = p.edit().putInt("quiet_end_min", v).apply()

    // Whether to fire a consolidated notification at the start of quiet hours.
    var quietHoursAlarmEnabled: Boolean
        get() = p.getBoolean("quiet_alarm_enabled", true)
        set(v) = p.edit().putBoolean("quiet_alarm_enabled", v).apply()

    var lastPollTime: Long
        get() = p.getLong("last_poll", 0L)
        set(v) = p.edit().putLong("last_poll", v).apply()

    var lastAlarmScheduleTs: Long
        get() = p.getLong("last_alarm_schedule_ts", 0L)
        set(v) = p.edit().putLong("last_alarm_schedule_ts", v).apply()

    // Non-null while AlarmService is running; used to redirect MainActivity to AlarmActivity.
    var activeAlarmJson: String?
        get() = p.getString("active_alarm_json", null)
        set(value) {
            if (value != null) p.edit().putString("active_alarm_json", value).apply()
            else p.edit().remove("active_alarm_json").apply()
        }

    var activeAlarmCodes: Set<Int>
        get() = (p.getStringSet("active_alarm_codes", emptySet()) ?: emptySet())
                    .mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = p.edit().putStringSet("active_alarm_codes", v.map { it.toString() }.toSet()).apply()

    // Keys of alarms that have already been raised, formatted as "${uid}_${startMs}".
    var raisedAlarmKeys: Set<String>
        get() = HashSet(p.getStringSet("raised_alarm_keys", emptySet()) ?: emptySet())
        set(v) = p.edit().putStringSet("raised_alarm_keys", v).apply()

    fun markAlarmRaised(key: String) {
        val current = raisedAlarmKeys.toMutableSet()
        current.add(key)
        p.edit().putStringSet("raised_alarm_keys", current).apply()
    }

    fun pruneRaisedAlarmKeys(nowMs: Long = System.currentTimeMillis()) {
        val cutoffMs = nowMs - 2 * 24 * 60 * 60 * 1000L
        val pruned = raisedAlarmKeys.filter { key ->
            // Key format: "${uid}_${startMs}_${endMs}". Keep until the event end is >2 days past.
            val endMs = key.substringAfterLast('_').toLongOrNull() ?: return@filter false
            endMs >= cutoffMs
        }.toSet()
        if (pruned.size != raisedAlarmKeys.size) raisedAlarmKeys = pruned
    }

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

    // ── Event filters ─────────────────────────────────────────────────────────

    var eventFilters: List<EventFilter>
        get() {
            val ids = p.getString("filter_ids", null)?.split('\n')?.filter { it.isNotEmpty() }
                ?: return emptyList()
            return ids.mapNotNull { id ->
                p.getString("filter_data_$id", null)?.let { json ->
                    runCatching { EventFilter.fromJson(JSONObject(json)) }.getOrNull()
                }
            }
        }
        set(v) {
            val edit   = p.edit()
            val oldIds = p.getString("filter_ids", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
            val newIds = v.map { it.id }
            edit.putString("filter_ids", newIds.joinToString("\n"))
            v.forEach { edit.putString("filter_data_${it.id}", it.toJson().toString()) }
            oldIds.filterNot { it in newIds }.forEach { edit.remove("filter_data_$it") }
            edit.apply()
        }

    fun saveFilter(filter: EventFilter) {
        val ids = p.getString("filter_ids", null)?.split('\n')?.filter { it.isNotEmpty() }
            ?.toMutableList() ?: mutableListOf()
        if (filter.id !in ids) ids.add(filter.id)
        p.edit()
            .putString("filter_ids", ids.joinToString("\n"))
            .putString("filter_data_${filter.id}", filter.toJson().toString())
            .apply()
    }

    fun deleteFilter(filterId: String) {
        val ids = p.getString("filter_ids", null)?.split('\n')?.filter { it.isNotEmpty() }
            ?.toMutableList() ?: mutableListOf()
        ids.remove(filterId)
        p.edit()
            .putString("filter_ids", ids.joinToString("\n"))
            .remove("filter_data_$filterId")
            .remove("filter_mc_$filterId")
            .apply()
    }

    // Number of today's events matching this filter (-1 = not yet computed).
    fun filterMatchCount(filterId: String): Int = p.getInt("filter_mc_$filterId", -1)
    fun setFilterMatchCount(filterId: String, v: Int) = p.edit().putInt("filter_mc_$filterId", v).apply()

    fun getFilter(filterId: String): EventFilter? =
        p.getString("filter_data_$filterId", null)?.let { json ->
            runCatching { EventFilter.fromJson(JSONObject(json)) }.getOrNull()
        }

    // ── Per-event preferences ────────────────────────────────────────────────

    var eventPreferences: List<EventPreference>
        get() {
            val ids = p.getString("event_pref_ids", null)?.split('\n')?.filter { it.isNotEmpty() }
                ?: return emptyList()
            return ids.mapNotNull { key ->
                p.getString("event_pref_$key", null)?.let { json ->
                    runCatching { EventPreference.fromJson(JSONObject(json)) }.getOrNull()
                }
            }
        }
        set(v) {
            val edit = p.edit()
            val oldIds = p.getString("event_pref_ids", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
            val newIds = v.map { it.key }
            edit.putString("event_pref_ids", newIds.joinToString("\n"))
            v.forEach { edit.putString("event_pref_${it.key}", it.toJson().toString()) }
            oldIds.filterNot { it in newIds }.forEach { edit.remove("event_pref_$it") }
            edit.apply()
        }

    fun saveEventPreference(pref: EventPreference) {
        val prefs = eventPreferences.associateBy { it.key }.toMutableMap()
        prefs[pref.key] = pref
        eventPreferences = prefs.values.sortedBy { it.key }
        lastAlarmScheduleTs = 0L
    }

    fun updateEventPreferenceLastSeen(events: List<CalendarEvent>) {
        val stored = eventPreferences.associateBy { it.key }.toMutableMap()
        var changed = false
        for (event in events) {
            val endMs = event.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val eventKey = EventPreference.eventKey(event)
            stored[eventKey]?.let {
                if (endMs > it.lastSeenEndMs) {
                    stored[eventKey] = it.copy(lastSeenEndMs = endMs)
                    changed = true
                }
            }
            if (event.isSeries) {
                val seriesKey = EventPreference.seriesKey(event)
                stored[seriesKey]?.let {
                    if (endMs > it.lastSeenEndMs) {
                        stored[seriesKey] = it.copy(lastSeenEndMs = endMs)
                        changed = true
                    }
                }
            }
        }
        if (changed) eventPreferences = stored.values.sortedBy { it.key }
    }

    fun pruneStaleEventPreferences(now: LocalDateTime = LocalDateTime.now()) {
        val cutoffMs = now.minusDays(7).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val kept = eventPreferences.filter { it.lastSeenEndMs == 0L || it.lastSeenEndMs >= cutoffMs }
        if (kept.size != eventPreferences.size) eventPreferences = kept
    }

    // ── Local device calendar source ───────────────────────────────────────────

    var localCalendarEnabled: Boolean
        get() = p.getBoolean("local_cal_enabled", false)
        set(v) = p.edit().putBoolean("local_cal_enabled", v).apply()

    // Comma-joined set of CalendarContract calendar IDs the user has selected.
    var localCalendarIds: Set<Long>
        get() = (p.getString("local_cal_ids", "") ?: "")
                    .split(',').filter { it.isNotEmpty() }
                    .mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = p.edit().putString("local_cal_ids", v.joinToString(",")).apply()

    fun localCalendarEventCount(): Int = p.getInt("local_event_count", -1)
    fun setLocalCalendarEventCount(v: Int) = p.edit().putInt("local_event_count", v).apply()

    // Display name for a specific device calendar (keyed by its CalendarContract ID).
    fun localCalendarName(calId: Long): String = p.getString("local_cal_name_$calId", "") ?: ""
    fun setLocalCalendarName(calId: Long, name: String) = p.edit().putString("local_cal_name_$calId", name).apply()

    fun clearSourceCache(id: String) {
        p.edit()
            .remove("etag_$id").remove("lmod_$id").remove("hash_$id")
            .remove("fetch_ms_$id").remove("fetch_ok_$id").remove("event_count_$id")
            .remove("name_$id")
            .apply()
    }
}
