package com.example.calendaralarm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone

class AlarmPlannerTest {

    private lateinit var savedTz: TimeZone

    // Pin timezone to UTC so LocalDateTime ↔ epoch-ms conversions are stable.
    @Before fun setUtc() { savedTz = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After  fun restoreTz() { TimeZone.setDefault(savedTz) }

    // Dates: "today" is the 18th; events may land on the 18th (tonight) or 19th (tomorrow).
    private val today    = LocalDate.of(2026, 6, 18)
    private val tomorrow = today.plusDays(1)

    // "now" = 20:00 tonight — before quiet hours start at 22:00.
    private val nowMs = utcMs(today, 20, 0)

    // Quiet hours defaults: 22:00–07:00 (same as Prefs defaults).
    private val quietStart = 22 * 60  // 1320 minutes since midnight
    private val quietEnd   =  7 * 60  // 420 minutes since midnight

    private fun utcMs(d: LocalDate, h: Int, m: Int = 0): Long =
        d.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun event(date: LocalDate, hour: Int, min: Int = 0, uid: String = "uid") =
        CalendarEvent(
            uid = uid, summary = "Test", isAllDay = false,
            start = date.atTime(hour, min).toInstant(ZoneOffset.UTC),
            end   = date.atTime(hour, min).plusHours(1).toInstant(ZoneOffset.UTC)
        )

    private fun plan(
        events: List<CalendarEvent>,
        flags: Map<CalendarEvent, FilterEngine.EventFlags> = emptyMap(),
        advanceMinutes: Int = 5,
        quietHoursAlarmEnabled: Boolean = true
    ) = planAlarms(events, flags, advanceMinutes, quietHoursAlarmEnabled, quietStart, quietEnd, nowMs)

    // ── Test 1 ────────────────────────────────────────────────────────────────
    // A standard event that does not fall in quiet hours fires at the normal
    // (advance-adjusted) trigger time with no special handling.
    @Test fun `normal event outside quiet hours fires at advance time`() {
        val ev = event(tomorrow, 10)
        val result = plan(listOf(ev))
        assertEquals(1, result.size)
        with(result[0]) {
            assertEquals(utcMs(tomorrow, 9, 55), triggerMs)
            assertEquals(false, silent)
            assertEquals(false, isQuietHoursAlarm)
        }
    }

    // ── Test 2 ────────────────────────────────────────────────────────────────
    // A standard event whose start time falls within quiet hours is moved to the
    // beginning of quiet hours (the default EARLY mode when quietHoursAlarmEnabled).
    @Test fun `quiet-hours event is moved to start of quiet hours`() {
        val ev = event(today, 23)   // 23:00 tonight — inside quiet hours 22:00–07:00
        val result = plan(listOf(ev))
        assertEquals(1, result.size)
        with(result[0]) {
            assertEquals(utcMs(today, 22, 0), triggerMs)   // tonight at 22:00
            assertEquals(false, silent)
            assertEquals(true, isQuietHoursAlarm)
        }
    }

    // ── Test 3 ────────────────────────────────────────────────────────────────
    // A quiet-hours event whose per-event preference is set to ALARM mode fires at
    // its normal (advance-adjusted) trigger time, ignoring quiet hours entirely.
    @Test fun `quiet-hours event with ALARM mode fires at normal trigger`() {
        val ev = event(today, 23)   // 23:00 — inside quiet hours
        val flags = mapOf(ev to FilterEngine.EventFlags(
            quietHoursMode = FilterAction.QuietHoursMode.ALARM
        ))
        val result = plan(listOf(ev), flags)
        assertEquals(1, result.size)
        with(result[0]) {
            assertEquals(utcMs(today, 22, 55), triggerMs)  // 23:00 − 5 min
            assertEquals(false, silent)
            assertEquals(false, isQuietHoursAlarm)
        }
    }

    // ── Test 4 ────────────────────────────────────────────────────────────────
    // An event that starts just after quiet hours end (07:30) but whose advance
    // time (90 min) pushes the computed trigger into quiet hours (06:00) is also
    // moved to the beginning of quiet hours (22:00 the night before).
    @Test fun `advance pushes non-quiet-hours trigger into quiet hours`() {
        val ev = event(tomorrow, 7, 30)  // 07:30 — outside quiet hours (end is 07:00)
        val result = plan(listOf(ev), advanceMinutes = 90)
        // trigger would be 06:00 (inside quiet hours) → rescheduled to 22:00 tonight
        assertEquals(1, result.size)
        with(result[0]) {
            assertEquals(utcMs(today, 22, 0), triggerMs)
            assertEquals(false, silent)
            assertEquals(true, isQuietHoursAlarm)
        }
    }

    // ── Test 6 ────────────────────────────────────────────────────────────────
    // An event whose trigger has already passed but which has not ended yet is
    // scheduled immediately (now + 2s) rather than dropped.
    @Test fun `ongoing event whose trigger has passed fires at now plus 2s`() {
        val event9am = CalendarEvent(
            uid = "standup", summary = "Morning Standup",
            start = tomorrow.atTime(9, 0).toInstant(ZoneOffset.UTC),
            end   = tomorrow.atTime(11, 0).toInstant(ZoneOffset.UTC),
            isAllDay = false
        )
        val lateNowMs = utcMs(tomorrow, 10, 12)
        val result = planAlarms(listOf(event9am), emptyMap(), 5, false, quietStart, quietEnd, lateNowMs)
        assertEquals(1, result.size)
        assertEquals(lateNowMs + 2_000L, result[0].triggerMs)
    }

    // ── Test 5 ────────────────────────────────────────────────────────────────
    // An ICS event with DTSTART;TZID=America/Los_Angeles at 04:00 (PDT, UTC-7)
    // represents 11:00 UTC. On a UTC device the parsed start should be 11:00 so
    // the alarm fires at 10:55 (outside quiet hours), not at 04:00 UTC which
    // falls inside quiet hours and would push the alarm to the previous evening.
    @Test fun `TZID event is converted to device local time before alarm planning`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:tz-test@example.com
            DTSTART;TZID=America/Los_Angeles:20260619T040000
            DTEND;TZID=America/Los_Angeles:20260619T050000
            SUMMARY:Early LA meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val winStart = LocalDateTime.of(2026, 6, 19, 0, 0).toInstant(ZoneOffset.UTC)
        val winEnd   = LocalDateTime.of(2026, 6, 20, 0, 0).toInstant(ZoneOffset.UTC)
        val events = IcsParser.parse(ics, winStart, winEnd).events
        assertEquals(1, events.size)

        val ev = events[0]
        // 04:00 PDT (UTC-7) should arrive as 11:00 on a UTC device.
        assertEquals("start hour", 11, ev.localStart.hour)

        val result = planAlarms(listOf(ev), emptyMap(), 5, true, quietStart, quietEnd, nowMs)
        assertEquals(1, result.size)
        with(result[0]) {
            assertEquals("trigger ms", utcMs(tomorrow, 10, 55), triggerMs)
            assertEquals("not a quiet-hours alarm", false, isQuietHoursAlarm)
        }
    }

    @Test fun `an event's alarm choice overrides its series, which overrides filters`() {
        val ev = event(tomorrow, 9).copy(isSeries = true)
        val muteAll = EventFilter(actions = mutableListOf(FilterAction.AlarmAction(false)))
        fun enabled(vararg prefs: EventPreference) =
            FilterEngine.effectiveFlags(listOf(ev), listOf(muteAll), prefs.toList()).getValue(ev).alarmEnabled

        val seriesOn = EventPreference.of(ev, EventPreference.Scope.SERIES, true)
        val eventOff = EventPreference.of(ev, EventPreference.Scope.EVENT, false)
        assertEquals(false, enabled())
        assertEquals(true, enabled(seriesOn))
        assertEquals(false, enabled(seriesOn, eventOff))
        assertEquals(EventPreference.Scope.EVENT to false,
                     FilterEngine.alarmOverride(ev, listOf(seriesOn, eventOff).associateBy { it.key }))
    }
}
