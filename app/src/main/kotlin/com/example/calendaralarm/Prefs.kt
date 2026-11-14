package com.example.calendaralarm

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

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

    var alarmSound: AlarmSound
        get() {
            val d = AlarmSound.DEFAULT
            return AlarmSound(
                uri         = p.getString("sound_uri", d.uri),
                delayMs     = p.getLong("sound_delay_ms", d.delayMs),
                startVolume = p.getInt("sound_start_vol", d.startVolume),
                maxVolume   = p.getInt("sound_max_vol", d.maxVolume),
                fadeMs      = p.getLong("sound_fade_ms", d.fadeMs)
            )
        }
        set(v) = p.edit()
            .putString("sound_uri", v.uri)
            .putLong("sound_delay_ms", v.delayMs)
            .putInt("sound_start_vol", v.startVolume)
            .putInt("sound_max_vol", v.maxVolume)
            .putLong("sound_fade_ms", v.fadeMs)
            .apply()

    var lastPollTime: Long
        get() = p.getLong("last_poll", 0L)
        set(v) = p.edit().putLong("last_poll", v).apply()

    // ── Alarm state from before AlarmStore (read once, by the migration) ──────

    /** AlarmKeys of raised alarms and request codes of registered alarms, or null if none are stored. */
    fun legacyAlarmState(): Pair<Set<String>, Set<Int>>? {
        if (LEGACY_ALARM_KEYS.none(p::contains)) return null
        return p.getStringSet("raised_alarm_keys", emptySet())!!.toSet() to
               p.getStringSet("active_alarm_codes", emptySet())!!.map(String::toInt).toSet()
    }

    fun clearLegacyAlarmState() {
        val edit = p.edit()
        LEGACY_ALARM_KEYS.forEach(edit::remove)
        check(edit.commit()) { "could not clear legacy alarm state" }
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

    fun feedStatus(id: String): FeedStatus =
        p.getString("feed_status_$id", null)?.let { FeedStatus.fromJson(JSONObject(it)) } ?: FeedStatus()
    fun setFeedStatus(id: String, v: FeedStatus) =
        p.edit().putString("feed_status_$id", v.toJson().toString()).apply()

    // Number of events for today from this source (-1 = not yet known).
    fun icsLastFetchEventCount(id: String): Int = p.getInt("event_count_$id", -1)
    fun setIcsLastFetchEventCount(id: String, v: Int) = p.edit().putInt("event_count_$id", v).apply()

    // User-visible name for this source (auto-populated from X-WR-CALNAME on first fetch).
    fun icsSourceName(id: String): String = p.getString("name_$id", "") ?: ""
    fun icsDisplayName(url: String): String = icsSourceName(urlId(url)).ifEmpty { url }
    fun setIcsSourceName(id: String, v: String) = p.edit().putString("name_$id", v).apply()

    // ── Event filters ─────────────────────────────────────────────────────────

    var eventFilters: List<EventFilter>
        get() {
            val ids = p.getString("filter_ids", null)?.split('\n')?.filter { it.isNotEmpty() }
                ?: return emptyList()
            return ids.map { id -> readJson("filter_data_$id", EventFilter::fromJson) }
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

    fun getFilter(filterId: String): EventFilter = readJson("filter_data_$filterId", EventFilter::fromJson)

    // ── Per-event preferences ────────────────────────────────────────────────

    // Writers hold eventPreferenceLock: each rewrites the whole list and would otherwise lose concurrent changes.
    var eventPreferences: List<EventPreference>
        get() {
            val ids = p.getString("event_pref_ids", null)?.split('\n')?.filter { it.isNotEmpty() }
                ?: return emptyList()
            return ids.map { key -> readJson("event_pref_$key", EventPreference::fromJson) }
        }
        private set(v) {
            val edit = p.edit()
            val oldIds = p.getString("event_pref_ids", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
            val newIds = v.map { it.key }
            edit.putString("event_pref_ids", newIds.joinToString("\n"))
            v.forEach { edit.putString("event_pref_${it.key}", it.toJson().toString()) }
            oldIds.filterNot { it in newIds }.forEach { edit.remove("event_pref_$it") }
            edit.apply()
        }

    /** Applies [change] to [event]'s preference for [scope], creating it if absent. */
    fun updateEventPreference(
        event: CalendarEvent, scope: EventPreference.Scope, change: (EventPreference) -> EventPreference
    ) = synchronized(eventPreferenceLock) {
        val stored = eventPreferences.associateBy { it.key }.toMutableMap()
        val key = EventPreference.key(event, scope)
        stored[key] = change(stored[key] ?: EventPreference(key, scope))
            .copy(lastSeenEndMs = event.end.toEpochMilli())
        eventPreferences = stored.values.sortedBy { it.key }
    }

    fun updateEventPreferenceLastSeen(events: List<CalendarEvent>) = synchronized(eventPreferenceLock) {
        val stored = eventPreferences.associateBy { it.key }.toMutableMap()
        var changed = false
        for (event in events) {
            val endMs = event.end.toEpochMilli()
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

    fun pruneStaleEventPreferences(now: Instant) = synchronized(eventPreferenceLock) {
        val cutoffMs = now.minus(Duration.ofDays(7)).toEpochMilli()
        val stored = eventPreferences
        val kept = stored.filter { it.lastSeenEndMs == 0L || it.lastSeenEndMs >= cutoffMs }
        if (kept.size != stored.size) eventPreferences = kept
    }

    /** Why the stored filters or event preferences could not be read at the last attempt; null if they could. */
    var settingsProblem: String?
        get() = p.getString("settings_problem", null)
        set(v) = p.edit().putString("settings_problem", v).apply()

    /** Parses the JSON stored under [key], which must exist. */
    private fun <T> readJson(key: String, parse: (JSONObject) -> T): T {
        val json = p.getString(key, null) ?: throw CorruptSettingException(key, "missing")
        try {
            return parse(JSONObject(json))
        } catch (e: Exception) {
            throw CorruptSettingException(key, e.toString(), e)
        }
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
            .remove("feed_status_$id").remove("event_count_$id")
            .remove("name_$id")
            .apply()
    }

    private companion object {
        val LEGACY_ALARM_KEYS = listOf("raised_alarm_keys", "active_alarm_codes", "active_alarm_json")
        val eventPreferenceLock = Any()
    }
}

/** A stored filter or event preference that cannot be read back. */
class CorruptSettingException(key: String, reason: String, cause: Throwable? = null) :
    IllegalStateException("Stored setting '$key' is unreadable: $reason", cause)
