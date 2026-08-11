package com.example.calendaralarm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.LooperMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AlarmSimulatorScenarioTest {

    private lateinit var savedTz: TimeZone
    private lateinit var sim: AlarmSimulator
    private val context get() = RuntimeEnvironment.getApplication()

    private val june17 = LocalDate.of(2026, 6, 17)
    private val june18 = LocalDate.of(2026, 6, 18)
    private val june19 = LocalDate.of(2026, 6, 19)
    private val june22 = LocalDate.of(2026, 6, 22)  // Monday

    private fun utcMs(date: LocalDate, h: Int, m: Int = 0) =
        date.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before fun setUp() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        sim = AlarmSimulator()
    }

    @After fun tearDown() {
        TimeZone.setDefault(savedTz)
        sim.tearDown()
    }

    // ── Test 1: Rescheduled after dismiss ─────────────────────────────────────────
    // If an event is rescheduled to a new time after the alarm was dismissed,
    // the alarm fires again because DTSTART changes → alarm key changes.

    @Test fun `alarm fires again when event is rescheduled to a later time after being dismissed`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:meeting@test.example
            DTSTART:20260618T090000Z
            DTEND:20260618T100000Z
            SUMMARY:Team Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val rescheduledIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:meeting@test.example
            DTSTART:20260618T100000Z
            DTEND:20260618T110000Z
            SUMMARY:Team Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        sim.setIcsContent(originalIcs)
        sim.setUp(utcMs(june18, 7, 0))
        sim.boot()

        // Original alarm fires at 08:55 (5 min before 09:00 start)
        sim.runUntil(utcMs(june18, 9, 5))
        assertEquals("original alarm fired once", 1, sim.firedAlarms().size)
        assertEquals("original alarm is for the team meeting", "meeting@test.example", sim.firedAlarms()[0].uid)

        // Calendar reschedules the event to 10:00 AM — same UID, new start time.
        // The alarm key encodes start and end ms, so raisedAlarmKeys no longer matches.
        sim.setIcsContent(rescheduledIcs)
        Prefs(context).lastAlarmScheduleTs = 0L
        sim.runUntil(utcMs(june18, 10, 5))

        assertEquals("second alarm fired for the rescheduled event", 2, sim.firedAlarms().size)
        val second = sim.firedAlarms()[1]
        assertEquals("rescheduled alarm is for the same UID", "meeting@test.example", second.uid)
        assertTrue(
            "rescheduled alarm fires at or after 09:55 (5 min before new 10:00 start)",
            second.firedAtMs >= utcMs(june18, 9, 55)
        )
    }

    // ── Test 2: Quiet hours ───────────────────────────────────────────────────────
    // Events that start during quiet hours (22:00–07:00) receive their alarm
    // at the preceding start of the quiet period, not at the normal advance time.

    @Test fun `event starting during quiet hours receives alarm at quiet hours start`() {
        // 22:30 is inside quiet hours 22:00–07:00.
        // quietHoursStartFor(22:30) = 22:00 same day.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:late-meeting@test.example
            DTSTART:20260618T223000Z
            DTEND:20260618T233000Z
            SUMMARY:Late Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        sim.setIcsContent(ics)
        sim.setUp(utcMs(june18, 18, 0))
        sim.boot()
        sim.runUntil(utcMs(june18, 22, 5))

        assertEquals("one quiet-hours alarm fired", 1, sim.firedAlarms().size)
        val alarm = sim.firedAlarms()[0]
        assertEquals("alarm is for the late meeting", "late-meeting@test.example", alarm.uid)
        assertFalse("quiet-hours early alarm is not silent", alarm.silent)
        assertEquals(
            "alarm fired exactly at quiet hours start (22:00)",
            utcMs(june18, 22, 0), alarm.firedAtMs
        )
    }

    // ── Test 3: Recurring event from a year ago ───────────────────────────────────
    // A weekly series that began a year ago still produces a timely alarm
    // for the current week's occurrence.

    @Test fun `recurring weekly event that started a year ago still raises alarm`() {
        // Series started 2025-06-16 (Monday). 2026-06-22 is also a Monday (53 weeks later).
        // No COUNT/UNTIL so the parser expands as far as needed (up to the 3000-iteration cap).
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:weekly-standup@test.example
            DTSTART:20250616T090000Z
            DTEND:20250616T100000Z
            SUMMARY:Weekly Standup
            RRULE:FREQ=WEEKLY;BYDAY=MO
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        sim.setIcsContent(ics)
        sim.setUp(utcMs(june22, 7, 0))
        sim.boot()
        sim.runUntil(utcMs(june22, 9, 5))

        assertEquals("one alarm fired for the recurring event", 1, sim.firedAlarms().size)
        val alarm = sim.firedAlarms()[0]
        assertEquals("alarm is for the weekly standup", "weekly-standup@test.example", alarm.uid)
        assertTrue(
            "alarm fired close to 08:55 (5 min before 09:00)",
            alarm.firedAtMs in utcMs(june22, 8, 55)..utcMs(june22, 9, 1)
        )
    }

    // ── Test 4: Event in a non-UTC timezone ───────────────────────────────────────
    // An event defined in America/New_York fires its alarm at the correct UTC time.

    @Test fun `event defined in America New York timezone fires alarm at correct UTC time`() {
        // 09:00 America/New_York (EDT = UTC-4) = 13:00 UTC.
        // Alarm trigger: 13:00 - 5 min = 12:55 UTC.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:ny-meeting@test.example
            DTSTART;TZID=America/New_York:20260619T090000
            DTEND;TZID=America/New_York:20260619T100000
            SUMMARY:New York Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        sim.setIcsContent(ics)
        sim.setUp(utcMs(june19, 11, 0))  // boot at 11:00 UTC; event (13:00 UTC) is in the 25-hour window
        sim.boot()
        sim.runUntil(utcMs(june19, 13, 5))

        assertEquals("one alarm fired for the NY meeting", 1, sim.firedAlarms().size)
        val alarm = sim.firedAlarms()[0]
        assertEquals("alarm is for the NY meeting", "ny-meeting@test.example", alarm.uid)
        assertEquals(
            "alarm fired at 12:55 UTC (= 08:55 AM EDT, 5 min before 09:00 AM EDT)",
            utcMs(june19, 12, 55), alarm.firedAtMs
        )
    }

    // ── Test 5: Quiet hours config reset ─────────────────────────────────────────
    // Changing quiet hours alone does NOT re-fire a dismissed alarm;
    // only clearing raisedAlarmKeys (and forcing a reschedule) does.

    @Test fun `resetting quiet hours alone does not re-fire a dismissed alarm but clearing raisedAlarmKeys does`() {
        // Event at 06:30 Jun 18. Default quiet hours are 22:00–07:00 (EARLY mode).
        // 06:30 falls inside quiet hours → alarm is scheduled at 22:00 Jun 17
        // (the preceding start of the quiet period that contains 06:30 AM).
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:early-bird@test.example
            DTSTART:20260618T063000Z
            DTEND:20260618T073000Z
            SUMMARY:Early Bird Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        sim.setIcsContent(ics)
        sim.setUp(utcMs(june17, 18, 0))
        sim.boot()

        // ── Phase 1: quiet-hours alarm fires at 22:00 Jun 17 ─────────────────────
        sim.runUntil(utcMs(june17, 22, 5))
        assertEquals("phase 1: quiet alarm fired", 1, sim.firedAlarms().size)
        assertEquals("early-bird@test.example", sim.firedAlarms()[0].uid)
        assertEquals(
            "phase 1: alarm fired at 22:00 Jun 17 (preceding quiet hours start)",
            utcMs(june17, 22, 0), sim.firedAlarms()[0].firedAtMs
        )

        // ── Phase 2: narrow quiet hours so 06:30 is now outside them ─────────────
        // With quietHoursEnd = 06:00, quiet hours become 22:00–06:00.
        // 06:30 AM is no longer in quiet hours, so a fresh alarm would be scheduled
        // at 06:25 AM (5 min advance). But raisedAlarmKeys still contains the key
        // from the dismissed quiet-hours alarm → planAlarms skips it.
        Prefs(context).quietHoursEndMinutes = 6 * 60
        Prefs(context).lastAlarmScheduleTs = 0L
        sim.runUntil(utcMs(june18, 6, 25))  // advance past where the new alarm would trigger
        assertEquals(
            "phase 2: alarm does not re-fire despite quiet hours change — raisedAlarmKeys blocks it",
            1, sim.firedAlarms().size
        )

        // ── Phase 3: clear raisedAlarmKeys and force a reschedule ────────────────
        // Now planAlarms sees the event as unraised and outside quiet hours →
        // schedules it. The trigger (06:25) is in the past → fires late.
        Prefs(context).raisedAlarmKeys = emptySet()
        Prefs(context).lastAlarmScheduleTs = 0L
        sim.runUntil(utcMs(june18, 6, 45))
        assertEquals("phase 3: alarm fires again after clearing raisedAlarmKeys", 2, sim.firedAlarms().size)
        val refired = sim.firedAlarms()[1]
        assertEquals("re-fired alarm is for the same event", "early-bird@test.example", refired.uid)
        assertTrue(
            "re-fired alarm is at or after 06:25 (the original trigger time)",
            refired.firedAtMs >= utcMs(june18, 6, 25)
        )
        assertTrue(
            "re-fired alarm is before 06:45 (confirmed late, not missing)",
            refired.firedAtMs <= utcMs(june18, 6, 45)
        )
    }
}
