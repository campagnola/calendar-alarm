package com.example.calendaralarm

import java.time.Instant
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneOffsetTransitionRule.TimeDefinition
import java.time.zone.ZoneRules

/**
 * Builds [ZoneRules] from an iCalendar VTIMEZONE block, so feeds that use non-IANA TZIDs
 * (e.g. Outlook's "Mountain Standard Time") are interpreted exactly as the feed defines them.
 *
 * Supports the shapes calendar servers emit: one fixed offset, or yearly transitions given
 * by `FREQ=YEARLY;BYMONTH=m;BYDAY=nDD` / `BYMONTHDAY=d`. Anything else throws.
 */
internal object IcsTimeZone {
    private data class Observance(
        val isStandard: Boolean,
        val start: LocalDateTime,
        val offsetFrom: ZoneOffset,
        val offsetTo: ZoneOffset,
        val rrule: String?
    )

    /**
     * Rules for every VTIMEZONE in a feed's unfolded [lines], keyed by TZID. A block that cannot
     * be read is reported to [problems]; events using its TZID then fail to resolve.
     */
    fun parseAll(lines: List<String>, problems: MutableList<String>): Map<String, ZoneRules> {
        val zones = mutableMapOf<String, ZoneRules>()
        var block: MutableList<String>? = null
        for (line in lines) {
            when {
                line == "BEGIN:VTIMEZONE" -> block = mutableListOf()
                line == "END:VTIMEZONE" -> {
                    try { zones += parse(block!!) }
                    catch (e: Exception) { problems.add("Could not read time zone: $e") }
                    block = null
                }
                block != null -> block.add(line)
            }
        }
        return zones
    }

    /** [lines] are the unfolded lines strictly between BEGIN:VTIMEZONE and END:VTIMEZONE. */
    private fun parse(lines: List<String>): Pair<String, ZoneRules> {
        var tzid: String? = null
        val observances = mutableListOf<Observance>()
        var props: MutableMap<String, String>? = null
        for (line in lines) {
            when (line) {
                "BEGIN:STANDARD", "BEGIN:DAYLIGHT" -> props = mutableMapOf()
                "END:STANDARD", "END:DAYLIGHT" -> {
                    val p = props!!
                    observances.add(Observance(
                        isStandard = line == "END:STANDARD",
                        start      = LocalDateTime.parse(p.getValue("DTSTART"), IcsParser.dtFmt),
                        offsetFrom = ZoneOffset.of(p.getValue("TZOFFSETFROM")),
                        offsetTo   = ZoneOffset.of(p.getValue("TZOFFSETTO")),
                        rrule      = p["RRULE"]
                    ))
                    props = null
                }
                else -> {
                    val colon = IcsParser.valueSeparator(line)
                    val name = line.substring(0, colon).substringBefore(';').uppercase()
                    val value = line.substring(colon + 1).trim()
                    if (props != null) props[name] = value
                    else if (name == "TZID") tzid = value
                }
            }
        }
        require(tzid != null) { "VTIMEZONE without TZID" }
        require(observances.isNotEmpty()) { "VTIMEZONE '$tzid' has no STANDARD/DAYLIGHT block" }
        return tzid to toRules(tzid, observances)
    }

    private fun toRules(tzid: String, observances: List<Observance>): ZoneRules {
        val offsets = observances.map { it.offsetTo }.toSet()
        if (offsets.size == 1) return ZoneRules.of(offsets.single())

        require(observances.all { it.rrule != null }) {
            "VTIMEZONE '$tzid': observances without RRULE are not supported"
        }
        val standardOffset = observances.first { it.isStandard }.offsetTo
        val rules = observances
            .map { transitionRule(tzid, it, standardOffset) }
            .sortedWith(compareBy({ it.month }, { it.dayOfMonthIndicator }))
        // ZoneRules only applies its yearly rules after the last explicit transition,
        // so seed it with the first rule's transition in the earliest observance year.
        val seed = rules.first().createTransition(observances.minOf { it.start.year })
        return ZoneRules.of(standardOffset, seed.offsetBefore, emptyList(), listOf(seed), rules)
    }

    private fun transitionRule(tzid: String, obs: Observance, standardOffset: ZoneOffset): ZoneOffsetTransitionRule {
        val params = IcsParser.rruleParams(obs.rrule!!)
        val unsupported = params.keys - setOf("FREQ", "INTERVAL", "BYMONTH", "BYDAY", "BYMONTHDAY")
        require(params["FREQ"] == "YEARLY" && (params["INTERVAL"] ?: "1") == "1" && unsupported.isEmpty()) {
            "VTIMEZONE '$tzid': unsupported RRULE ${obs.rrule}"
        }
        val month = Month.of(params.getValue("BYMONTH").toInt())
        val byDay = params["BYDAY"]
        val (dayIndicator, dayOfWeek) = if (byDay != null) {
            val dow = IcsParser.parseDayOfWeek(byDay.takeLast(2))
            // ZoneOffsetTransitionRule means "dow on or after day N" (or "on or before" for negative N).
            // Microsoft writes the last week as 5.
            val n = byDay.dropLast(2).toInt()
            when {
                n in 1..4 -> (n - 1) * 7 + 1 to dow
                n == 5 || n == -1 -> -1 to dow
                n < -1 -> -1 + (n + 1) * 7 to dow
                else -> throw IllegalArgumentException("VTIMEZONE '$tzid': unsupported BYDAY $byDay")
            }
        } else {
            params.getValue("BYMONTHDAY").toInt() to null
        }
        return ZoneOffsetTransitionRule.of(
            month, dayIndicator, dayOfWeek, obs.start.toLocalTime(), false,
            TimeDefinition.WALL, standardOffset, obs.offsetFrom, obs.offsetTo
        )
    }
}

/** Wall-clock [local] → instant; a time skipped by a DST gap is shifted forward, as ZonedDateTime does. */
internal fun ZoneRules.toInstant(local: LocalDateTime): Instant =
    local.toInstant(getValidOffsets(local).firstOrNull() ?: getTransition(local).offsetBefore)

internal fun ZoneRules.toLocal(instant: Instant): LocalDateTime =
    LocalDateTime.ofInstant(instant, getOffset(instant))
