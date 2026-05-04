package com.example.calendaralarm

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class CalendarEvent(
    val uid: String,
    val summary: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val isAllDay: Boolean
)

object IcsParser {
    private val dtFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun parse(icsText: String, windowStart: LocalDateTime, windowEnd: LocalDateTime): List<CalendarEvent> {
        val rawEvents = mutableListOf<RawEvent>()
        var cur: RawEvent? = null

        for (line in unfold(icsText)) {
            when {
                line == "BEGIN:VEVENT" -> cur = RawEvent()
                line == "END:VEVENT" -> { cur?.let { rawEvents.add(it) }; cur = null }
                cur != null -> {
                    val colon = line.indexOf(':')
                    if (colon < 0) continue
                    val key = line.substring(0, colon)
                    val value = line.substring(colon + 1)
                    when (key.substringBefore(';').uppercase()) {
                        "UID"           -> cur!!.uid = value.trim()
                        "SUMMARY"       -> cur!!.summary = unescape(value)
                        "DTSTART"       -> parseDt(key, value)?.let { (dt, allDay) ->
                                               cur!!.dtStart = dt; cur!!.isAllDay = allDay }
                        "DTEND"         -> parseDt(key, value)?.let { cur!!.dtEnd = it.first }
                        "DURATION"      -> cur!!.duration = parseDuration(value)
                        "RRULE"         -> cur!!.rrule = value
                        "RECURRENCE-ID" -> parseDt(key, value)?.let { cur!!.recurrenceId = it.first }
                        "EXDATE"        -> value.split(',').forEach { v ->
                                               parseDt(key, v.trim())?.let { cur!!.exDates.add(it.first) } }
                    }
                }
            }
        }

        // Separate master events from per-instance overrides
        val masters = rawEvents.filter { it.recurrenceId == null }
        val overrides = rawEvents.filter { it.recurrenceId != null }.groupBy { it.uid ?: "" }

        val result = mutableListOf<CalendarEvent>()
        for (raw in masters) {
            val uid = raw.uid ?: continue
            val dtStart = raw.dtStart ?: continue
            val duration = Duration.between(
                dtStart,
                raw.dtEnd ?: dtStart.plus(raw.duration ?: Duration.ofHours(1))
            )
            val instanceOverrideTimes = overrides[uid]?.mapNotNull { it.recurrenceId }?.toSet() ?: emptySet()
            val excludeTimes = raw.exDates + instanceOverrideTimes

            val rrule = raw.rrule
            if (rrule == null) {
                if (dtStart !in instanceOverrideTimes) {
                    val dtEnd = dtStart.plus(duration)
                    if (dtStart < windowEnd && dtEnd > windowStart)
                        result.add(CalendarEvent(uid, raw.summary ?: "(No title)", dtStart, dtEnd, raw.isAllDay))
                }
            } else {
                for (occ in expandRrule(rrule, dtStart, excludeTimes, windowStart, windowEnd))
                    result.add(CalendarEvent(uid, raw.summary ?: "(No title)", occ, occ.plus(duration), raw.isAllDay))
            }

            // Include modified instances that fall in the window
            for (ov in overrides[uid] ?: emptyList()) {
                val ovStart = ov.dtStart ?: continue
                val ovDuration = Duration.between(ovStart, ov.dtEnd ?: ovStart.plus(ov.duration ?: duration))
                val ovEnd = ovStart.plus(ovDuration)
                if (ovStart < windowEnd && ovEnd > windowStart)
                    result.add(CalendarEvent(uid, ov.summary ?: raw.summary ?: "(No title)", ovStart, ovEnd, ov.isAllDay))
            }
        }

        // Orphan overrides: RECURRENCE-ID instances whose master is absent from the file.
        // Outlook sometimes exports only the modified instance (e.g. a rescheduled occurrence
        // of a series whose root DTSTART predates the ICS export window). Treat them as
        // standalone events.
        val masterUids = masters.mapNotNull { it.uid }.toSet()
        for ((uid, ovList) in overrides) {
            if (uid in masterUids) continue
            for (ov in ovList) {
                val ovStart = ov.dtStart ?: continue
                val ovEnd = ov.dtEnd ?: ovStart.plus(ov.duration ?: Duration.ofHours(1))
                if (ovStart < windowEnd && ovEnd > windowStart)
                    result.add(CalendarEvent(uid, ov.summary ?: "(No title)", ovStart, ovEnd, ov.isAllDay))
            }
        }

        return result
    }

    // ── RRULE expansion ──────────────────────────────────────────────────────

    private fun expandRrule(
        rrule: String,
        dtStart: LocalDateTime,
        excludeTimes: Set<LocalDateTime>,
        windowStart: LocalDateTime,
        windowEnd: LocalDateTime
    ): List<LocalDateTime> {
        val params = rrule.split(';').associate {
            val eq = it.indexOf('='); if (eq < 0) it to "" else it.substring(0, eq) to it.substring(eq + 1)
        }
        val freq      = params["FREQ"]?.uppercase() ?: return emptyList()
        val interval  = params["INTERVAL"]?.toLongOrNull() ?: 1L
        val maxCount  = params["COUNT"]?.toIntOrNull() ?: Int.MAX_VALUE
        val ruleUntil = params["UNTIL"]?.let { parseDt("", it.trimEnd('Z'))?.first }
        val effectiveEnd = if (ruleUntil != null) minOf(windowEnd, ruleUntil) else windowEnd

        val byDayEntries  = params["BYDAY"]?.split(',')?.mapNotNull { parseByDayEntry(it) } ?: emptyList()
        val positionalDay = byDayEntries.filter { it.position != null }
        val plainDay      = byDayEntries.filter { it.position == null }
        val byMonthDay    = params["BYMONTHDAY"]?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        val byMonth       = params["BYMONTH"]?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList()

        val result = mutableListOf<LocalDateTime>()

        fun emit(dt: LocalDateTime) {
            if (dt >= dtStart && dt <= effectiveEnd && dt !in excludeTimes && result.size < maxCount)
                if (dt >= windowStart) result.add(dt)
        }

        when (freq) {
            "DAILY" -> {
                var cur = dtStart
                val skip = if (cur < windowStart) ChronoUnit.DAYS.between(cur, windowStart) / interval else 0L
                if (skip > 1) cur = cur.plusDays((skip - 1) * interval)
                while (cur <= effectiveEnd && result.size < maxCount) { emit(cur); cur = cur.plusDays(interval) }
            }

            "WEEKLY" -> {
                val targetDows = (if (plainDay.isEmpty()) listOf(dtStart.dayOfWeek)
                                  else plainDay.map { it.dayOfWeek }).toSet()
                var anchor = dtStart
                val skip = if (anchor < windowStart) ChronoUnit.WEEKS.between(anchor, windowStart) / interval else 0L
                if (skip > 1) anchor = anchor.plusWeeks((skip - 1) * interval)

                var safety = 0
                while (anchor <= effectiveEnd && result.size < maxCount && safety++ < 3000) {
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
                val skip = if (curMonth.atStartOfDay() < windowStart)
                    ChronoUnit.MONTHS.between(curMonth, windowStart.toLocalDate()) / interval else 0L
                if (skip > 1) curMonth = curMonth.plusMonths((skip - 1) * interval)

                var safety = 0
                while (curMonth.atStartOfDay() <= effectiveEnd && result.size < maxCount && safety++ < 500) {
                    for (d in monthCandidates(curMonth.year, curMonth.month, dtStart.dayOfMonth,
                                              positionalDay, plainDay, byMonthDay))
                        emit(d.atTime(time))
                    curMonth = curMonth.plusMonths(interval)
                }
            }

            "YEARLY" -> {
                val time = dtStart.toLocalTime()
                var year  = dtStart.year
                val skip  = if (year < windowStart.year) (windowStart.year - year).toLong() / interval else 0L
                if (skip > 1) year += ((skip - 1) * interval).toInt()

                var safety = 0
                while (year <= effectiveEnd.year + 1 && result.size < maxCount && safety++ < 200) {
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

    private fun parseByDayEntry(s: String): ByDayEntry? {
        val t = s.trim(); if (t.length < 2) return null
        val dow = when (t.takeLast(2).uppercase()) {
            "MO" -> DayOfWeek.MONDAY;  "TU" -> DayOfWeek.TUESDAY;  "WE" -> DayOfWeek.WEDNESDAY
            "TH" -> DayOfWeek.THURSDAY; "FR" -> DayOfWeek.FRIDAY
            "SA" -> DayOfWeek.SATURDAY; "SU" -> DayOfWeek.SUNDAY
            else -> return null
        }
        return ByDayEntry(dow, t.dropLast(2).toIntOrNull())
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
        var dtStart: LocalDateTime? = null,
        var dtEnd: LocalDateTime? = null,
        var duration: Duration? = null,
        var rrule: String? = null,
        var recurrenceId: LocalDateTime? = null,
        var isAllDay: Boolean = false,
        val exDates: MutableSet<LocalDateTime> = mutableSetOf()
    )

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

    private fun parseDt(key: String, value: String): Pair<LocalDateTime, Boolean>? = runCatching {
        val v = value.trim()
        when {
            key.contains("VALUE=DATE", ignoreCase = true) || (v.length == 8 && !v.contains('T')) ->
                Pair(LocalDate.parse(v, dateFmt).atStartOfDay(), true)
            v.endsWith('Z') ->
                Pair(LocalDateTime.parse(v.dropLast(1), dtFmt)
                    .atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(), false)
            else ->
                Pair(LocalDateTime.parse(v.take(15), dtFmt), false)
        }
    }.getOrNull()

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

    private fun unescape(s: String) = s
        .replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}
