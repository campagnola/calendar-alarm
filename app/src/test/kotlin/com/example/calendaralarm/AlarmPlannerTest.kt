package com.example.calendaralarm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
            start = date.atTime(hour, min),
            end   = date.atTime(hour, min).plusHours(1)
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
    // Reproduces: alarm fires at 10:12 AM for a 9:00 AM event on the calendar for months.
    //
    // Two compounding issues:
    //
    // Issue A — MainActivity window anchored to midnight:
    //   windowEnd = visDayStart.plusHours(25)   (= tomorrow 01:00 AM when showing today)
    //   Events starting after 01:00 AM tomorrow are excluded from the parse window.
    //   A 9:00 AM event tomorrow is therefore not scheduled when the app is opened today
    //   before 8 PM.
    //
    // Issue B — PollReceiver skip when ICS is unchanged:
    //   After each scheduleAll call, lastAlarmScheduleTs = maxCacheTs (ICS file timestamp).
    //   On every subsequent poll, maxCacheTs <= lastAlarmScheduleTs fires → skip.
    //   scheduleAll is never called again until the ICS changes, regardless of how much
    //   time has passed or how many upcoming events are now within the 25h window.
    //
    // Combined effect: if the user opened the app yesterday (Issue A missed tomorrow's 9 AM
    // event), all today's PollReceiver polls skip (Issue B), and the event gets no alarm.
    // When the user opens the app at 10:12 AM today, loadSchedule calls scheduleAll.
    // The event hasn't ended → it is included; trigger (8:55 AM) is in the past → late alarm.
    @Test fun `missed alarm fires late when ICS unchanged and window excluded the event`() {
        // Event: tomorrow 9:00–11:00 AM (still ongoing when the late reschedule happens at 10:12)
        val event9am = CalendarEvent(
            uid = "standup", summary = "Morning Standup",
            start = tomorrow.atTime(9, 0),
            end   = tomorrow.atTime(11, 0),
            isAllDay = false
        )

        // ── Issue A: MainActivity window misses the event ────────────────────────────
        // User opens the app today (visDay = today, visDayStart = today 00:00).
        // windowEnd = visDayStart + 25h = tomorrow 01:00 AM.
        val mainActivityWindowEnd = today.atTime(0, 0).plusHours(25)  // tomorrow 01:00 AM
        assertFalse(
            "Issue A: tomorrow's 9 AM event is outside MainActivity's window (ends tomorrow 01:00)",
            event9am.start.isBefore(mainActivityWindowEnd)
        )

        // ── Issue B: PollReceiver skip prevents later rescheduling ───────────────────
        // The last time PollReceiver called scheduleAll, it saved lastAlarmScheduleTs = cacheTs.
        // While the ICS is unchanged, maxCacheTs == cacheTs on every subsequent poll.
        val cacheTs = nowMs - 30L * 60 * 60_000  // ICS last changed 30h ago
        val lastAlarmScheduleTs = cacheTs
        val maxCacheTs = cacheTs  // ICS still unchanged
        assertTrue(
            "Issue B: skip fires (maxCacheTs <= lastAlarmScheduleTs) even though event " +
            "has now entered the 25h window — scheduleAll is never called",
            maxCacheTs <= lastAlarmScheduleTs
        )

        // ── Correct behavior if planAlarms were called on time ───────────────────────
        // At nowMs (today 20:00), tomorrow's 9 AM event IS within [now, now+25h].
        // planAlarms would schedule it correctly if it were called.
        val pollWindowEnd = LocalDateTime.ofEpochSecond(nowMs / 1000, 0, ZoneOffset.UTC).plusHours(25)
        assertTrue("Event IS in PollReceiver's 25h window right now",
            event9am.start.isBefore(pollWindowEnd))

        val onTimeResult = planAlarms(
            listOf(event9am), emptyMap(), 5, false, quietStart, quietEnd, nowMs
        )
        assertEquals("planAlarms schedules the event if called at nowMs", 1, onTimeResult.size)
        assertEquals("On-time trigger is 8:55 AM tomorrow",
            utcMs(tomorrow, 8, 55), onTimeResult[0].triggerMs)

        // ── What actually happens: late alarm at 10:12 AM ────────────────────────────
        // The first scheduleAll call that includes the event is at tomorrow 10:12 AM
        // (user opens the app). By then the trigger (8:55 AM) is 77 minutes in the past.
        val lateNowMs = utcMs(tomorrow, 10, 12)
        val lateResult = planAlarms(
            listOf(event9am), emptyMap(), 5, false, quietStart, quietEnd, lateNowMs
        )
        assertEquals("Event fires as a late alarm", 1, lateResult.size)
        // BUG CONFIRMED: alarm fires 77 minutes late instead of at the intended 8:55 AM.
        assertNotEquals("BUG: alarm trigger is 10:12 AM+2s instead of the intended 8:55 AM",
            utcMs(tomorrow, 8, 55), lateResult[0].triggerMs)
        assertEquals("Late trigger is nowMs+2s",
            lateNowMs + 2_000L, lateResult[0].triggerMs)
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

        val winStart = LocalDateTime.of(2026, 6, 19, 0, 0)
        val winEnd   = LocalDateTime.of(2026, 6, 20, 0, 0)
        val events = IcsParser.parse(ics, winStart, winEnd)
        assertEquals(1, events.size)

        val ev = events[0]
        // 04:00 PDT (UTC-7) should arrive as 11:00 on a UTC device.
        assertEquals("start hour", 11, ev.start.hour)

        val result = planAlarms(listOf(ev), emptyMap(), 5, true, quietStart, quietEnd, nowMs)
        assertEquals(1, result.size)
        with(result[0]) {
            assertEquals("trigger ms", utcMs(tomorrow, 10, 55), triggerMs)
            assertEquals("not a quiet-hours alarm", false, isQuietHoursAlarm)
        }
    }
}
