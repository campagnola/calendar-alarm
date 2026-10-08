package com.example.calendaralarm

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.zone.ZoneRules

data class CalendarEvent(
    val uid: String,
    val summary: String,
    val start: Instant,
    val end: Instant,
    val isAllDay: Boolean,
    val location: String = "",
    val meetingUrl: String? = null,
    val sourceId: String = "",
    val organizer: String = "",
    val attendees: List<String> = emptyList(),
    val description: String = "",
    val isSeries: Boolean = false
) {
    /** Wall-clock start/end in the device's current time zone. */
    val localStart: LocalDateTime get() = LocalDateTime.ofInstant(start, ZoneId.systemDefault())
    val localEnd: LocalDateTime get() = LocalDateTime.ofInstant(end, ZoneId.systemDefault())

    fun toJson(): String {
        val obj = org.json.JSONObject().apply {
            put("uid", uid)
            put("summary", summary)
            put("start", start.toString())
            put("end", end.toString())
            put("isAllDay", isAllDay)
            put("location", location)
            put("description", description)
            meetingUrl?.let { put("meetingUrl", it) }
            put("sourceId", sourceId)
            put("organizer", organizer)
            put("isSeries", isSeries)
            val arr = org.json.JSONArray()
            attendees.forEach { arr.put(it) }
            put("attendees", arr)
        }
        return obj.toString()
    }

    companion object {
        fun fromJson(s: String): CalendarEvent {
            val obj = org.json.JSONObject(s)
            val attendeesArr = obj.optJSONArray("attendees") ?: org.json.JSONArray()
            return CalendarEvent(
                uid         = obj.getString("uid"),
                summary     = obj.getString("summary"),
                start       = Instant.parse(obj.getString("start")),
                end         = Instant.parse(obj.getString("end")),
                isAllDay    = obj.getBoolean("isAllDay"),
                location    = obj.optString("location", ""),
                description = obj.optString("description", ""),
                meetingUrl  = obj.optString("meetingUrl", "").takeIf { it.isNotEmpty() },
                sourceId    = obj.optString("sourceId", ""),
                organizer   = obj.optString("organizer", ""),
                attendees   = (0 until attendeesArr.length()).map { attendeesArr.getString(it) },
                isSeries    = obj.optBoolean("isSeries", false)
            )
        }
    }
}

object IcsParser {
    internal val dtFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun calendarName(icsText: String): String? {
        for (line in unfold(icsText)) {
            if (line.startsWith("X-WR-CALNAME:"))
                return unescape(line.removePrefix("X-WR-CALNAME:").trim())
            if (line == "BEGIN:VEVENT") break
        }
        return null
    }

    /** The events of a feed, and a description of each part of it that could not be read. */
    data class ParsedFeed(val events: List<CalendarEvent>, val problems: List<String>)

    /**
     * Returns the events overlapping [windowStart, windowEnd). Every date is resolved to an
     * instant here, against the VTIMEZONE definitions in the same [icsText]. An event that
     * cannot be read is left out and reported in [ParsedFeed.problems].
     */
    fun parse(icsText: String, windowStart: Instant, windowEnd: Instant,
              sourceId: String = ""): ParsedFeed {
        val lines = unfold(icsText)
        val problems = mutableListOf<String>()
        val zones = IcsTimeZone.parseAll(lines, problems)
        val rawEvents = mutableListOf<RawEvent>()
        var cur: RawEvent? = null
        fun reportBad(raw: RawEvent, e: Exception) =
            problems.add("Could not read event '${raw.summary ?: raw.uid}': $e")

        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> cur = RawEvent()
                line == "END:VEVENT" -> {
                    cur?.let { raw -> raw.error?.let { reportBad(raw, it) } ?: rawEvents.add(raw) }
                    cur = null
                }
                cur != null && cur.error == null -> try {
                    val colon = valueSeparator(line)
                    if (colon < 0) continue
                    val key = line.substring(0, colon)
                    val value = line.substring(colon + 1)
                    when (key.substringBefore(';').uppercase()) {
                        "UID"           -> cur!!.uid = value.trim()
                        "SUMMARY"       -> cur!!.summary = unescape(value)
                        "DTSTART"       -> cur!!.dtStart = parseDt(key, value, zones)
                        "DTEND"         -> cur!!.dtEnd = parseDt(key, value, zones)
                        "DURATION"      -> cur!!.duration = parseDuration(value)
                        "RRULE"         -> cur!!.rrule = value
                        "RECURRENCE-ID" -> cur!!.recurrenceId = parseDt(key, value, zones).instant
                        "EXDATE"        -> value.split(',').forEach { v ->
                                               cur!!.exDates.add(parseDt(key, v, zones).instant) }
                        "LOCATION"      -> cur!!.location = unescape(value)
                        "DESCRIPTION"   -> cur!!.description = unescape(value)
                        "ORGANIZER"     -> cur!!.organizer = value.removePrefix("mailto:").trim()
                        "ATTENDEE"      -> cur!!.attendees.add(value.removePrefix("mailto:").trim())
                    }
                } catch (e: Exception) {
                    cur.error = e
                }
            }
        }

        // Separate master events from per-instance overrides
        val masters = rawEvents.filter { it.recurrenceId == null }
        val overrides = rawEvents.filter { it.recurrenceId != null }.groupBy { it.uid ?: "" }

        val result = mutableListOf<CalendarEvent>()
        for (raw in masters) try {
            val uid = raw.uid ?: continue
            val dtStart = raw.dtStart ?: continue
            val start = dtStart.instant
            val duration = Duration.between(
                start,
                raw.dtEnd?.instant ?: start.plus(raw.duration ?: Duration.ofHours(1))
            )
            val instanceOverrideTimes = overrides[uid]?.mapNotNull { it.recurrenceId }?.toSet() ?: emptySet()
            val excludeTimes = raw.exDates + instanceOverrideTimes

            val rrule = raw.rrule
            if (rrule == null) {
                if (start !in instanceOverrideTimes) {
                    val end = start.plus(duration)
                    if (start < windowEnd && end > windowStart)
                        result.add(CalendarEvent(uid, raw.summary ?: "(No title)", start, end, dtStart.isDate,
                            raw.location, extractMeetingUrl(raw.location, raw.description),
                            sourceId, raw.organizer, raw.attendees.toList(), raw.description))
                }
            } else {
                for (occ in expandRrule(rrule, dtStart, excludeTimes, windowStart, windowEnd))
                    result.add(CalendarEvent(uid, raw.summary ?: "(No title)", occ, occ.plus(duration), dtStart.isDate,
                        raw.location, extractMeetingUrl(raw.location, raw.description),
                        sourceId, raw.organizer, raw.attendees.toList(), raw.description, isSeries = true))
            }

            // Include modified instances that fall in the window
            for (ov in overrides[uid] ?: emptyList()) {
                val ovDtStart = ov.dtStart ?: continue
                val ovStart = ovDtStart.instant
                val ovEnd = ov.dtEnd?.instant ?: ovStart.plus(ov.duration ?: duration)
                val ovDesc = ov.description.ifEmpty { raw.description }
                if (ovStart < windowEnd && ovEnd > windowStart)
                    result.add(CalendarEvent(uid, ov.summary ?: raw.summary ?: "(No title)", ovStart, ovEnd, ovDtStart.isDate,
                        ov.location.ifEmpty { raw.location },
                        extractMeetingUrl(ov.location.ifEmpty { raw.location }, ovDesc),
                        sourceId,
                        ov.organizer.ifEmpty { raw.organizer },
                        ov.attendees.ifEmpty { raw.attendees }.toList(), ovDesc, isSeries = rrule != null))
            }
        } catch (e: Exception) {
            reportBad(raw, e)
        }

        // Orphan overrides: RECURRENCE-ID instances whose master is absent from the file.
        // Outlook sometimes exports only the modified instance (e.g. a rescheduled occurrence
        // of a series whose root DTSTART predates the ICS export window). Treat them as
        // standalone events.
        val masterUids = masters.mapNotNull { it.uid }.toSet()
        for ((uid, ovList) in overrides) {
            if (uid in masterUids) continue
            for (ov in ovList) {
                val ovDtStart = ov.dtStart ?: continue
                val ovStart = ovDtStart.instant
                val ovEnd = ov.dtEnd?.instant ?: ovStart.plus(ov.duration ?: Duration.ofHours(1))
                if (ovStart < windowEnd && ovEnd > windowStart)
                    result.add(CalendarEvent(uid, ov.summary ?: "(No title)", ovStart, ovEnd, ovDtStart.isDate,
                        ov.location, extractMeetingUrl(ov.location, ov.description),
                        sourceId, ov.organizer, ov.attendees.toList(), ov.description))
            }
        }

        return ParsedFeed(result, problems)
    }

    // ── RRULE expansion ──────────────────────────────────────────────────────

    /**
     * Expands in wall-clock time of the event's own zone (so BYDAY and DST follow the
     * organizer's calendar), then converts each occurrence to an instant.
     */
    private fun expandRrule(
        rrule: String,
        first: IcsTime,
        excludeTimes: Set<Instant>,
        windowStartInstant: Instant,
        windowEndInstant: Instant
    ): List<Instant> {
        val rules       = first.rules
        val dtStart     = first.local
        val windowStart = rules.toLocal(windowStartInstant)
        val windowEnd   = rules.toLocal(windowEndInstant)
        val params = rruleParams(rrule)
        val freq      = params["FREQ"]?.uppercase() ?: return emptyList()
        val interval  = params["INTERVAL"]?.toLongOrNull() ?: 1L
        val hasCount  = params.containsKey("COUNT")
        val maxCount  = params["COUNT"]?.toIntOrNull() ?: Int.MAX_VALUE
        // A UTC UNTIL is an instant; a floating one is wall time in the event's zone.
        val ruleUntil = params["UNTIL"]?.let { u ->
            val until = parseDt("", u, emptyMap())
            if (u.endsWith('Z')) rules.toLocal(until.instant) else until.local
        }
        val effectiveEnd = if (ruleUntil != null) minOf(windowEnd, ruleUntil) else windowEnd

        val byDayEntries  = params["BYDAY"]?.split(',')?.map { parseByDayEntry(it) } ?: emptyList()
        val positionalDay = byDayEntries.filter { it.position != null }
        val plainDay      = byDayEntries.filter { it.position == null }
        val byMonthDay    = params["BYMONTHDAY"]?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        val byMonth       = params["BYMONTH"]?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList()

        val result = mutableListOf<Instant>()
        var generatedCount = 0

        fun emit(dt: LocalDateTime) {
            if (dt >= dtStart && dt <= effectiveEnd && generatedCount < maxCount) {
                generatedCount++
                val instant = rules.toInstant(dt)
                if (instant !in excludeTimes && dt >= windowStart) result.add(instant)
            }
        }

        when (freq) {
            "DAILY" -> {
                var cur = dtStart
                val skip = if (!hasCount && cur < windowStart) ChronoUnit.DAYS.between(cur, windowStart) / interval else 0L
                if (skip > 1) cur = cur.plusDays((skip - 1) * interval)
                while (cur <= effectiveEnd && generatedCount < maxCount) { emit(cur); cur = cur.plusDays(interval) }
            }

            "WEEKLY" -> {
                val targetDows = (if (plainDay.isEmpty()) listOf(dtStart.dayOfWeek)
                                  else plainDay.map { it.dayOfWeek }).toSet()
                var anchor = dtStart
                val skip = if (!hasCount && anchor < windowStart) ChronoUnit.WEEKS.between(anchor, windowStart) / interval else 0L
                if (skip > 1) anchor = anchor.plusWeeks((skip - 1) * interval)

                var safety = 0
                while (anchor <= effectiveEnd && generatedCount < maxCount && safety++ < 3000) {
                    for (i in 0..6) {
                        val c = anchor.plusDays(i.toLong())
                        if (c.dayOfWeek in targetDows) emit(c)
                    }
                    anchor = anchor.plusWeeks(interval)
                }
            }

            "MONTHLY" -> {
                val time = dtStart.toLocalTime()
                var curMonth = dtStart.withDayOfMonth(1).toLocalDate()
                val skip = if (!hasCount && curMonth.atStartOfDay() < windowStart)
                    ChronoUnit.MONTHS.between(curMonth, windowStart.toLocalDate()) / interval else 0L
                if (skip > 1) curMonth = curMonth.plusMonths((skip - 1) * interval)

                var safety = 0
                while (curMonth.atStartOfDay() <= effectiveEnd && generatedCount < maxCount && safety++ < 500) {
                    for (d in monthCandidates(curMonth.year, curMonth.month, dtStart.dayOfMonth,
                                              positionalDay, plainDay, byMonthDay))
                        emit(d.atTime(time))
                    curMonth = curMonth.plusMonths(interval)
                }
            }

            "YEARLY" -> {
                val time = dtStart.toLocalTime()
                var year  = dtStart.year
                val skip  = if (!hasCount && year < windowStart.year) (windowStart.year - year).toLong() / interval else 0L
                if (skip > 1) year += ((skip - 1) * interval).toInt()

                var safety = 0
                while (year <= effectiveEnd.year + 1 && generatedCount < maxCount && safety++ < 200) {
                    val targetMonths = if (byMonth.isEmpty()) listOf(dtStart.monthValue) else byMonth
                    for (monthNum in targetMonths) {
                        val month = Month.of(monthNum)
                        for (d in monthCandidates(year, month, dtStart.dayOfMonth,
                                                  positionalDay, plainDay, byMonthDay))
                            emit(d.atTime(time))
                    }
                    year += interval.toInt()
                }
            }
        }
        return result
    }

    /** Returns the candidate LocalDates within a given year/month based on BYDAY / BYMONTHDAY rules. */
    private fun monthCandidates(
        year: Int, month: Month, fallbackDay: Int,
        positionalDay: List<ByDayEntry>, plainDay: List<ByDayEntry>, byMonthDay: List<Int>
    ): List<LocalDate> {
        val first = LocalDate.of(year, month, 1)
        val daysInMonth = first.plusMonths(1).minusDays(1).dayOfMonth
        return when {
            positionalDay.isNotEmpty() ->
                positionalDay.mapNotNull { e -> nthWeekdayOfMonth(year, month, e.dayOfWeek, e.position!!) }
            plainDay.isNotEmpty() -> {
                val targetDows = plainDay.map { it.dayOfWeek }.toSet()
                (0 until daysInMonth).map { first.plusDays(it.toLong()) }.filter { it.dayOfWeek in targetDows }
            }
            byMonthDay.isNotEmpty() ->
                byMonthDay.mapNotNull { day ->
                    val actual = if (day < 0) daysInMonth + day + 1 else day
                    if (actual in 1..daysInMonth) LocalDate.of(year, month, actual) else null
                }
            else ->
                listOf(LocalDate.of(year, month, minOf(fallbackDay, daysInMonth)))
        }
    }

    private data class ByDayEntry(val dayOfWeek: DayOfWeek, val position: Int?)

    internal fun rruleParams(rrule: String): Map<String, String> = rrule.split(';').associate {
        val eq = it.indexOf('='); if (eq < 0) it to "" else it.substring(0, eq) to it.substring(eq + 1)
    }

    private fun parseByDayEntry(s: String): ByDayEntry {
        val t = s.trim()
        return ByDayEntry(parseDayOfWeek(t.takeLast(2)), t.dropLast(2).ifEmpty { null }?.toInt())
    }

    internal fun parseDayOfWeek(s: String): DayOfWeek = when (s.uppercase()) {
        "MO" -> DayOfWeek.MONDAY;   "TU" -> DayOfWeek.TUESDAY; "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY; "FR" -> DayOfWeek.FRIDAY
        "SA" -> DayOfWeek.SATURDAY; "SU" -> DayOfWeek.SUNDAY
        else -> throw IllegalArgumentException("Bad day of week '$s'")
    }

    private fun nthWeekdayOfMonth(year: Int, month: Month, dow: DayOfWeek, n: Int): LocalDate? = runCatching {
        val result = if (n > 0)
            LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow))
        else
            LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(dow)).plusWeeks((n + 1).toLong())
        if (result.month == month) result else null
    }.getOrNull()

    // ── ICS format helpers ───────────────────────────────────────────────────

    private data class RawEvent(
        var uid: String? = null,
        var summary: String? = null,
        var dtStart: IcsTime? = null,
        var dtEnd: IcsTime? = null,
        var duration: Duration? = null,
        var rrule: String? = null,
        var recurrenceId: Instant? = null,
        var location: String = "",
        var description: String = "",
        var organizer: String = "",
        val attendees: MutableList<String> = mutableListOf(),
        val exDates: MutableSet<Instant> = mutableSetOf(),
        var error: Exception? = null
    )

    /** A DATE or DATE-TIME value: wall-clock [local] time in the zone described by [rules]. */
    private data class IcsTime(val local: LocalDateTime, val rules: ZoneRules, val isDate: Boolean) {
        val instant: Instant get() = rules.toInstant(local)
    }

    /** Index of the ':' separating a content line's name/params from its value, skipping quoted params. */
    internal fun valueSeparator(line: String): Int {
        var quoted = false
        for ((i, c) in line.withIndex()) {
            if (c == '"') quoted = !quoted
            else if (c == ':' && !quoted) return i
        }
        return -1
    }

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder()
        for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((line.startsWith(' ') || line.startsWith('\t')) && sb.isNotEmpty())
                sb.append(line.drop(1))
            else { if (sb.isNotEmpty()) out.add(sb.toString()); sb.clear(); sb.append(line) }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private val tzidRegex = Regex("TZID=(\"[^\"]*\"|[^;:]+)", RegexOption.IGNORE_CASE)

    /** A TZID is resolved from the feed's own VTIMEZONEs first, then as an IANA zone; unknown TZIDs throw. */
    private fun parseDt(key: String, value: String, zones: Map<String, ZoneRules>): IcsTime {
        val v = value.trim()
        val params = key.split(';').drop(1)
        return when {
            // All-day dates are floating: pinned to the device zone each time a feed is read.
            params.any { it.equals("VALUE=DATE", ignoreCase = true) } || (v.length == 8 && !v.contains('T')) ->
                IcsTime(LocalDate.parse(v, dateFmt).atStartOfDay(), ZoneId.systemDefault().rules, isDate = true)
            v.endsWith('Z') ->
                IcsTime(LocalDateTime.parse(v.dropLast(1), dtFmt), ZoneOffset.UTC.rules, isDate = false)
            else -> {
                val tzid = tzidRegex.find(key)?.groupValues?.get(1)?.trim('"')
                val rules = if (tzid == null) ZoneId.systemDefault().rules  // floating time
                            else zones[tzid] ?: ZoneId.of(tzid).rules
                IcsTime(LocalDateTime.parse(v.take(15), dtFmt), rules, isDate = false)
            }
        }
    }

    private fun parseDuration(value: String): Duration {
        var d = Duration.ZERO
        val s = value.removePrefix("P"); val tIdx = s.indexOf('T')
        val date = if (tIdx >= 0) s.substring(0, tIdx) else s
        val time = if (tIdx >= 0) s.substring(tIdx + 1) else ""
        Regex("(\\d+)W").find(date)?.groupValues?.get(1)?.toLongOrNull()?.let { d = d.plusDays(it * 7) }
        Regex("(\\d+)D").find(date)?.groupValues?.get(1)?.toLongOrNull()?.let { d = d.plusDays(it) }
        Regex("(\\d+)H").find(time)?.groupValues?.get(1)?.toLongOrNull()?.let { d = d.plusHours(it) }
        Regex("(\\d+)M").find(time)?.groupValues?.get(1)?.toLongOrNull()?.let { d = d.plusMinutes(it) }
        return d
    }

    private val meetingUrlRegex = Regex(
        """https://[^\s<>"]*(?:teams\.microsoft\.com|zoom\.us)[^\s<>"]*""",
        RegexOption.IGNORE_CASE
    )

    fun extractMeetingUrl(location: String, description: String): String? =
        meetingUrlRegex.find(location)?.value ?: meetingUrlRegex.find(description)?.value

    private fun unescape(s: String) = s
        .replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}
